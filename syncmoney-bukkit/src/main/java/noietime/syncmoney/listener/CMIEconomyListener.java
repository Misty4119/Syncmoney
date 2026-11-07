package noietime.syncmoney.listener;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.sync.CMIDebounceManager;
import noietime.syncmoney.util.NumericUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CMI economy change listener — cross-server sync only.
 *
 * <p>Aligned with {@code reference/CMIEconomySync}: CMI is the local authority; this class
 * debounces {@code CMIUserBalanceChangeEvent} and forwards its captured balance delta to Redis,
 * which applies it to the latest shared mirror. Event-driven writes do not require the account
 * holder to be online on this server.
 * Inbound writes are handled by {@code CMIPubsubHandler}.
 *
 * <p>No polling while CMI events are available (polling duplicates publishes during rapid
 * {@code /cmi pay} spam). No mirror-pull during gameplay (only version-aware reconcile on join).
 *
 * [MainThread] CMI events fire on the main thread; outbound Redis I/O runs on an owned async worker.
 */
public final class CMIEconomyListener implements Listener {

    private final Plugin plugin;
    private final CMIEconomyHandler cmiHandler;
    private final SyncmoneyConfig config;
    private final CMIDebounceManager debounceManager;
    private final boolean cmiAvailable;

    private ScheduledTask pollingTask;
    private static final int MAX_BALANCE_OBSERVATIONS = 10_000;
    private final LinkedHashMap<UUID, BigDecimal> lastKnownCMIBalance = new LinkedHashMap<>(16, 0.75f, true);
    private final CMIInboundEchoTracker inboundEchoTracker = new CMIInboundEchoTracker();
    private final ConcurrentHashMap<UUID, PendingCmiChange> pendingCmiChanges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> failedOutboundSyncAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> outboundRetryTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService outboundExecutor;
    private volatile boolean stopped;
    private volatile boolean closing;
    private final Object outboundLifecycleGate = new Object();
    private int inFlightOutboundCallbacks;
    private static final int MAX_OUTBOUND_SYNC_ATTEMPTS = 3;

    private static Class<?> cmiEventClass = null;
    private static Method getUserMethod = null;
    private static Method getFromMethod = null;
    private static Method getToMethod = null;

    private record PendingCmiChange(BigDecimal localBalance, BigDecimal delta,
                                    String sourceName, String operationId) {
    }

    public CMIEconomyListener(Plugin plugin, CMIEconomyHandler cmiHandler, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.cmiHandler = cmiHandler;
        this.config = config;
        this.debounceManager = new CMIDebounceManager(plugin, config.cmi().getCMIDebounceTicks());
        this.cmiAvailable = initCMIReflection();
        this.outboundExecutor = cmiHandler == null ? null : Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "syncmoney-cmi-outbound");
            thread.setDaemon(true);
            return thread;
        });

        if (cmiAvailable) {
            registerCMIEvent();
            plugin.getLogger().fine("CMI event listener initialized");
        } else {
            plugin.getLogger().warning("CMI API not detected — using polling fallback for CMI mode");
            startPollingFallback();
        }
    }

    private void registerCMIEvent() {
        if (!cmiAvailable || cmiEventClass == null) {
            return;
        }
        try {
            Class<? extends Event> eventClass = cmiEventClass.asSubclass(Event.class);
            EventExecutor executor = (listener, event) -> {
                if (cmiEventClass.isInstance(event)) {
                    handleCMIEvent(event);
                }
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, executor, plugin, true);
            plugin.getLogger().fine("CMI event registered: " + cmiEventClass.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register CMI event, enabling polling fallback: " + e.getMessage());
            startPollingFallback();
        }
    }

    private boolean initCMIReflection() {
        try {
            cmiEventClass = Class.forName("com.Zrips.CMI.events.CMIUserBalanceChangeEvent");
            getUserMethod = cmiEventClass.getMethod("getUser");
            getFromMethod = cmiEventClass.getMethod("getFrom");
            getToMethod = cmiEventClass.getMethod("getTo");
            return true;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().fine("CMI event class not found: " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("CMI event methods not found: " + e.getMessage());
            return false;
        }
    }


    private void startPollingFallback() {
        if (stopped || cmiHandler == null || pollingTask != null) {
            return;
        }
        long intervalMs = Math.max(1000L, config.cmi().getCMIDetectIntervalMs());
        pollingTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin,
                    player -> publishDebounced(player.getUniqueId(), "CMI-POLLING"));
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        plugin.getLogger().fine("CMI polling fallback started (interval: " + intervalMs + "ms)");
    }

    private void handleCMIEvent(Event event) {
        if (stopped || closing || cmiHandler == null) {
            return;
        }
        try {
            double fromBalance = (double) getFromMethod.invoke(event);
            double toBalance = (double) getToMethod.invoke(event);
            if (Double.compare(fromBalance, toBalance) == 0) {
                return;
            }

            // This is a post-change notification. Invalidate a live snapshot before applying
            // echo suppression, which only controls outbound publishing.
            MigrationLock.recordCmiAuthorityMutation();

            Object cmiUser = getUserMethod.invoke(event);
            UUID uuid = getUUIDFromCMIUser(cmiUser);
            if (uuid == null) {
                return;
            }
            cmiHandler.invalidateCachedBalanceForApi(uuid);
            if (inboundEchoTracker.consume(uuid, fromBalance, toBalance)) {
                return;
            }

            if (!Double.isFinite(fromBalance) || !Double.isFinite(toBalance)) {
                plugin.getLogger().warning("Ignored CMI balance event with a non-finite balance for " + uuid);
                return;
            }
            BigDecimal previous = NumericUtil.normalize(fromBalance);
            BigDecimal absolute = NumericUtil.normalize(toBalance);
            BigDecimal diff = absolute.subtract(previous);
            final String sourceName = "CMI";
            PendingCmiChange next = new PendingCmiChange(
                    absolute, diff, sourceName, UUID.randomUUID().toString());
            synchronized (outboundLifecycleGate) {
                if (stopped || closing) {
                    return;
                }
                pendingCmiChanges.merge(uuid, next, (pending, latest) -> new PendingCmiChange(
                        latest.localBalance(), pending.delta().add(latest.delta()),
                        latest.sourceName(), pending.operationId()));
                debounceManager.scheduleDebounced(uuid, () -> flushPendingCmiChange(uuid));
            }

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI Event] " + getNameFromCMIUser(cmiUser)
                        + " " + fromBalance + " -> " + toBalance);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing CMI event: " + e.getMessage());
        }
    }

    private void publishDebounced(UUID uuid, String sourceName) {
        if (stopped || closing) return;
        noietime.syncmoney.util.PlayerLookupUtil.runForPlayer(plugin, uuid,
                player -> {
                    if (!stopped && !closing) publishOnPlayerScheduler(uuid, sourceName);
                });
    }

    private void publishOnPlayerScheduler(UUID uuid, String sourceName) {
        if (stopped || closing || cmiHandler == null) {
            return;
        }
        BigDecimal absolute = cmiHandler.getCMILocalBalance(uuid);
        if (absolute == null) {
            return;
        }
        BigDecimal previous = getLastKnownCMIBalance(uuid);
        if (MigrationLock.isLocked()) {
            // Polling is only an observer. A changed or unknown current balance cannot be
            // silently accepted into a snapshot whose baseline may predate that observation.
            if (previous == null || previous.compareTo(absolute) != 0) {
                MigrationLock.recordCmiAuthorityMutation();
            }
            debounceManager.scheduleDebounced(uuid, () -> publishDebounced(uuid, sourceName));
            return;
        }
        if (previous != null && previous.compareTo(absolute) == 0) {
            return;
        }

        BigDecimal diff = previous != null ? absolute.subtract(previous) : BigDecimal.ZERO;
        publishObservedChange(uuid, absolute, diff, sourceName);
    }

    /** Flushes the coalesced event delta, including changes for users offline on this server. */
    private void flushPendingCmiChange(UUID uuid) {
        PendingCmiChange change;
        synchronized (outboundLifecycleGate) {
            change = pendingCmiChanges.remove(uuid);
            if (change == null || stopped) {
                return;
            }
            inFlightOutboundCallbacks++;
        }
        executeOutboundCallback(uuid, change.localBalance(), change.delta(),
                change.sourceName(), change.operationId());
    }

    private void publishObservedChange(UUID uuid, BigDecimal absolute, BigDecimal diff,
                                       String sourceName) {
        publishBalanceChange(uuid, absolute, diff, sourceName, UUID.randomUUID().toString(), false);
    }

    private void publishBalanceChange(UUID uuid, BigDecimal absolute, BigDecimal diff,
                                      String sourceName, String operationId, boolean allowWhileClosing) {
        if (cmiHandler == null || !reserveOutboundCallback(allowWhileClosing)) {
            return;
        }
        boolean isDeposit = diff.compareTo(BigDecimal.ZERO) >= 0;
        executeOutboundCallback(uuid, absolute, diff, isDeposit, sourceName, operationId);
    }

    private void executeOutboundCallback(UUID uuid, BigDecimal absolute, BigDecimal diff,
                                         String sourceName, String operationId) {
        executeOutboundCallback(uuid, absolute, diff, diff.compareTo(BigDecimal.ZERO) >= 0,
                sourceName, operationId);
    }

    private void executeOutboundCallback(UUID uuid, BigDecimal absolute, BigDecimal diff,
                                         boolean isDeposit, String sourceName, String operationId) {
        if (outboundExecutor == null) {
            finishOutboundCallback();
            return;
        }
        try {
            outboundExecutor.execute(() -> {
                try {
                    if (stopped) return;
                    if (cmiHandler.trySyncAbsoluteBalance(uuid, absolute, diff, isDeposit,
                            sourceName, operationId)) {
                        rememberLastKnownCMIBalance(uuid, absolute);
                        failedOutboundSyncAttempts.remove(operationId);
                    } else {
                        int attempts = failedOutboundSyncAttempts.merge(operationId, 1, Integer::sum);
                        // While running normally, retain the legacy migration-fence retry
                        // behavior. During shutdown, keep the drain bounded if an owner failed
                        // to release the global fence; do not hold the server's disable path
                        // forever for a write that the fence will continue to reject.
                        if ((!closing && MigrationLock.isLocked()) || attempts < MAX_OUTBOUND_SYNC_ATTEMPTS) {
                            scheduleOutboundRetry(uuid, absolute, diff, sourceName, operationId);
                        } else {
                            failedOutboundSyncAttempts.remove(operationId, attempts);
                            String lockSuffix = closing && MigrationLock.isLocked()
                                    ? " while the migration lock remained active; it was not mirrored before shutdown"
                                    : "";
                            plugin.getLogger().warning("CMI balance mirror retries exhausted for " + uuid + lockSuffix);
                        }
                    }
                } finally {
                    finishOutboundCallback();
                }
            });
        } catch (RuntimeException exception) {
            finishOutboundCallback();
            failedOutboundSyncAttempts.remove(operationId);
            plugin.getLogger().warning("Could not schedule CMI balance mirror write for " + uuid
                    + ": " + exception.getMessage());
        }
    }

    private void scheduleOutboundRetry(UUID uuid, BigDecimal absolute, BigDecimal diff,
                                      String sourceName, String operationId) {
        if (outboundExecutor == null || !reserveOutboundCallback(true)) {
            return;
        }
        AtomicReference<ScheduledFuture<?>> scheduledRef = new AtomicReference<>();
        try {
            ScheduledFuture<?> scheduled = outboundExecutor.schedule(() -> {
                ScheduledFuture<?> self = scheduledRef.get();
                if (self != null) {
                    outboundRetryTasks.remove(operationId, self);
                }
                try {
                    publishBalanceChange(uuid, absolute, diff, sourceName, operationId, true);
                } finally {
                    finishOutboundCallback();
                }
            }, Math.max(50L, config.cmi().getCMIDebounceTicks() * 50L), TimeUnit.MILLISECONDS);
            scheduledRef.set(scheduled);
            ScheduledFuture<?> previous = outboundRetryTasks.put(operationId, scheduled);
            if (previous != null) {
                previous.cancel(false);
            }
        } catch (RuntimeException exception) {
            finishOutboundCallback();
            failedOutboundSyncAttempts.remove(operationId);
            plugin.getLogger().warning("Could not schedule CMI balance retry for " + uuid
                    + ": " + exception.getMessage());
        }
    }

    public Runnable expectInboundBalanceWrite(UUID uuid, double fromBalance, double toBalance) {
        if (stopped || closing) {
            return () -> { };
        }
        return inboundEchoTracker.expect(uuid, fromBalance, toBalance);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (stopped || closing || cmiHandler == null) {
            return;
        }
        final UUID uuid = event.getPlayer().getUniqueId();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (stopped) return;
            cmiHandler.reconcileOnJoin(uuid);
            noietime.syncmoney.util.PlayerLookupUtil.runForPlayer(plugin, uuid, player -> {
                if (stopped) return;
                BigDecimal local = cmiHandler.getCMILocalBalance(uuid);
                if (local != null) rememberLastKnownCMIBalance(uuid, local);
            });
        });
    }

    public void notifyInboundApplied(UUID uuid, BigDecimal absoluteBalance) {
        if (stopped || closing) return;
        rememberLastKnownCMIBalance(uuid, absoluteBalance);
    }

    private UUID getUUIDFromCMIUser(Object cmiUser) {
        try {
            Method getUniqueIdMethod = cmiUser.getClass().getMethod("getUniqueId");
            return (UUID) getUniqueIdMethod.invoke(cmiUser);
        } catch (Exception e) {
            return null;
        }
    }

    private String getNameFromCMIUser(Object cmiUser) {
        try {
            Method getNameMethod = cmiUser.getClass().getMethod("getName");
            return (String) getNameMethod.invoke(cmiUser);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public void shutdown() {
        boolean interrupted = false;
        List<Map.Entry<UUID, PendingCmiChange>> pending = new ArrayList<>();
        synchronized (outboundLifecycleGate) {
            if (stopped) return;
            if (closing) {
                while (!stopped) {
                    try {
                        outboundLifecycleGate.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return;
            }
            closing = true;
            for (Map.Entry<UUID, PendingCmiChange> entry : pendingCmiChanges.entrySet()) {
                PendingCmiChange change = pendingCmiChanges.remove(entry.getKey());
                if (change != null) {
                    pending.add(Map.entry(entry.getKey(), change));
                    // Count before dispatch so shutdown cannot miss work that has not reached
                    // the executor yet.
                    inFlightOutboundCallbacks++;
                }
            }
        }
        if (cmiHandler != null) {
            cmiHandler.stopJoinReconciliation();
        }
        if (debounceManager != null) {
            debounceManager.cancelAll();
        }
        for (Map.Entry<UUID, PendingCmiChange> entry : pending) {
            PendingCmiChange change = entry.getValue();
            executeOutboundCallback(entry.getKey(), change.localBalance(), change.delta(),
                    change.sourceName(), change.operationId());
        }
        synchronized (outboundLifecycleGate) {
            while (inFlightOutboundCallbacks > 0) {
                try {
                    outboundLifecycleGate.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            stopped = true;
            outboundLifecycleGate.notifyAll();
        }
        if (outboundExecutor != null) {
            outboundExecutor.shutdown();
        }
        outboundRetryTasks.values().forEach(task -> task.cancel(false));
        outboundRetryTasks.clear();
        if (pollingTask != null) {
            pollingTask.cancel();
        }
        failedOutboundSyncAttempts.clear();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean reserveOutboundCallback(boolean allowWhileClosing) {
        synchronized (outboundLifecycleGate) {
            if (stopped || (closing && !allowWhileClosing)) {
                return false;
            }
            inFlightOutboundCallbacks++;
            return true;
        }
    }

    private void finishOutboundCallback() {
        synchronized (outboundLifecycleGate) {
            inFlightOutboundCallbacks--;
            outboundLifecycleGate.notifyAll();
        }
    }

    private BigDecimal getLastKnownCMIBalance(UUID uuid) {
        synchronized (lastKnownCMIBalance) {
            return lastKnownCMIBalance.get(uuid);
        }
    }

    private void rememberLastKnownCMIBalance(UUID uuid, BigDecimal balance) {
        if (uuid == null || balance == null) return;
        synchronized (lastKnownCMIBalance) {
            lastKnownCMIBalance.put(uuid, balance);
            if (lastKnownCMIBalance.size() > MAX_BALANCE_OBSERVATIONS) {
                UUID oldest = lastKnownCMIBalance.keySet().iterator().next();
                lastKnownCMIBalance.remove(oldest);
            }
        }
    }
}
