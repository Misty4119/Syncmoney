package noietime.syncmoney.listener;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.sync.CMIDebounceManager;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CMI economy change listener — cross-server sync only.
 *
 * <p>Aligned with {@code reference/CMIEconomySync}: CMI is the local authority; this class
 * debounces {@code CMIUserBalanceChangeEvent}, reads the final CMI balance, and publishes
 * the absolute value to Redis. Inbound writes are handled by {@code CMIPubsubHandler}.
 *
 * <p>No polling while CMI events are available (polling duplicates publishes during rapid
 * {@code /cmi pay} spam). No mirror-pull during gameplay (only version-aware reconcile on join).
 *
 * [MainThread] CMI events fire on the main thread; Redis I/O runs on the async scheduler.
 */
public final class CMIEconomyListener implements Listener {

    private final Plugin plugin;
    private final CMIEconomyHandler cmiHandler;
    private final SyncmoneyConfig config;
    private final CMIDebounceManager debounceManager;
    private final boolean cmiAvailable;

    private ScheduledTask pollingTask;
    private final ConcurrentHashMap<UUID, BigDecimal> lastKnownCMIBalance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> outboundSuppressUntilMs = new ConcurrentHashMap<>();
    private static final long OUTBOUND_SUPPRESS_MS = 750L;

    private static Class<?> cmiEventClass = null;
    private static Method getUserMethod = null;
    private static Method getFromMethod = null;
    private static Method getToMethod = null;

    public CMIEconomyListener(Plugin plugin, CMIEconomyHandler cmiHandler, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.cmiHandler = cmiHandler;
        this.config = config;
        this.debounceManager = new CMIDebounceManager(plugin, config.cmi().getCMIDebounceTicks());
        this.cmiAvailable = initCMIReflection();

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
        if (cmiHandler == null || pollingTask != null) {
            return;
        }
        long intervalMs = Math.max(1000L, config.cmi().getCMIDetectIntervalMs());
        pollingTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                publishDebounced(player.getUniqueId(), "CMI-POLLING");
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        plugin.getLogger().fine("CMI polling fallback started (interval: " + intervalMs + "ms)");
    }

    private void handleCMIEvent(Event event) {
        if (cmiHandler == null) {
            return;
        }
        try {
            double fromBalance = (double) getFromMethod.invoke(event);
            double toBalance = (double) getToMethod.invoke(event);
            if (Double.compare(fromBalance, toBalance) == 0) {
                return;
            }

            Object cmiUser = getUserMethod.invoke(event);
            UUID uuid = getUUIDFromCMIUser(cmiUser);
            if (uuid == null || isOutboundSuppressed(uuid)) {
                return;
            }

            final String sourceName = "CMI";
            debounceManager.scheduleDebounced(uuid, () -> publishDebounced(uuid, sourceName));

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI Event] " + getNameFromCMIUser(cmiUser)
                        + " " + fromBalance + " -> " + toBalance);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing CMI event: " + e.getMessage());
        }
    }

    private void publishDebounced(UUID uuid, String sourceName) {
        if (isOutboundSuppressed(uuid)) {
            return;
        }
        BigDecimal absolute = cmiHandler.getCMILocalBalance(uuid);
        if (absolute == null) {
            return;
        }

        BigDecimal previous = lastKnownCMIBalance.get(uuid);
        if (previous != null && previous.compareTo(absolute) == 0) {
            return;
        }

        BigDecimal diff = previous != null ? absolute.subtract(previous) : BigDecimal.ZERO;
        boolean isDeposit = diff.compareTo(BigDecimal.ZERO) >= 0;
        lastKnownCMIBalance.put(uuid, absolute);

        plugin.getServer().getAsyncScheduler().runNow(plugin, task ->
                cmiHandler.syncAbsoluteBalance(uuid, absolute, diff, isDeposit, sourceName));
    }

    public void suppressOutbound(UUID uuid, long durationMs) {
        if (uuid != null && durationMs > 0) {
            outboundSuppressUntilMs.put(uuid, System.currentTimeMillis() + durationMs);
        }
    }

    public boolean isOutboundSuppressed(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long until = outboundSuppressUntilMs.get(uuid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            outboundSuppressUntilMs.remove(uuid, until);
            return false;
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (cmiHandler == null) {
            return;
        }
        final UUID uuid = event.getPlayer().getUniqueId();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            cmiHandler.reconcileOnJoin(uuid);
            BigDecimal local = cmiHandler.getCMILocalBalance(uuid);
            if (local != null) {
                lastKnownCMIBalance.put(uuid, local);
            }
        });
    }

    public void notifyInboundApplied(UUID uuid, BigDecimal absoluteBalance) {
        suppressOutbound(uuid, OUTBOUND_SUPPRESS_MS);
        lastKnownCMIBalance.put(uuid, absoluteBalance);
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
        if (debounceManager != null) {
            debounceManager.cancelAll();
        }
        if (pollingTask != null) {
            pollingTask.cancel();
        }
    }
}
