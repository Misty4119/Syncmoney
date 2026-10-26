package noietime.syncmoney.sync;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class CMIPubsubHandler {

    private static final int MAX_VERSION_ENTRIES = 10_000;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final CMIApi cmiApi;
    private final noietime.syncmoney.economy.CMIEconomyHandler cmiHandler;
    private final CMIRedisMirror redisMirror;
    private final Clock clock;

    private final Map<UUID, VersionEntry> lastUpdateVersion = new ConcurrentHashMap<>();
    private volatile long lastCleanupTime = 0L;

    private final Set<String> seenNotificationIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_SEEN_NOTIFICATION_IDS = 20_000;

    private final Map<UUID, Long> notificationCooldownUntilMs = new ConcurrentHashMap<>();
    private final Set<PendingEntityApply> pendingEntityApplies = ConcurrentHashMap.newKeySet();
    private final Object lifecycleGate = new Object();
    private volatile boolean stopped;
    private static final long NOTIFICATION_COOLDOWN_MS = 1_500L;

    // A failed application may accept the same version again without reopening older versions.
    private record VersionEntry(long version, long timestamp, boolean retryable) {}

    public CMIPubsubHandler(Syncmoney plugin, SyncmoneyConfig config, RedisManager redisManager,
                            CMIApi cmiApi, noietime.syncmoney.economy.CMIEconomyHandler cmiHandler) {
        this(plugin, config, redisManager, cmiApi, cmiHandler, Clock.systemUTC());
    }

    CMIPubsubHandler(Syncmoney plugin, SyncmoneyConfig config, RedisManager redisManager,
                     CMIApi cmiApi, noietime.syncmoney.economy.CMIEconomyHandler cmiHandler,
                     Clock clock) {
        this.plugin = plugin;
        this.config = config;
        this.cmiApi = cmiApi;
        this.cmiHandler = cmiHandler;
        this.redisMirror = new CMIRedisMirror(redisManager, config.cmi().getCMIRedisPrefix());
        this.clock = clock;
        if (!cmiApi.isAvailable()) {
            plugin.getLogger().fine("CMI inbound handler: CMI API unavailable, Redis mirror only.");
        }
    }

    public void process(PubSubMessage msg) {
        if (stopped || msg == null) {
            return;
        }
        try {
            String src = msg.getSourceServer();
            if (src != null && src.equals(config.getServerName())) {
                return;
            }

            UUID uuid = msg.getUuidAsUUID();
            if (uuid == null) {
                return;
            }

            long incoming = msg.getVersion();
            long last = currentVersion(uuid);
            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI Inbound] Received " + uuid + " balance=" + msg.getBalanceAsBigDecimal()
                        + " v" + incoming + " from " + src + " (last inbound v" + last + ", server=" + config.getServerName() + ")");
            }
            VersionEntry currentEntry = lastUpdateVersion.get(uuid);
            boolean retryingFailedVersion = currentEntry != null
                    && incoming == currentEntry.version() && currentEntry.retryable();
            if (!CMIVersioning.isNewer(incoming, last) && !retryingFailedVersion) {
                if (config.isDebug()) {
                    plugin.getLogger().fine("[CMI Inbound] Rejected stale version for " + uuid
                            + " (current=" + last + " incoming=" + incoming + ")");
                }
                return;
            }

            BigDecimal balance = NumericUtil.normalize(msg.getBalanceAsBigDecimal());
            MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
            if (writeLease == null) {
                return;
            }
            boolean leaseTransferred = false;
            try {
                CMIRedisMirror.WriteResult mirrorWrite = updateRedisMirror(uuid, balance, incoming);
                if (mirrorWrite != CMIRedisMirror.WriteResult.ACCEPTED) {
                    if (config.isDebug()) {
                        plugin.getLogger().fine("[CMI Inbound] Redis mirror rejected " + uuid
                                + " v" + incoming + " (" + mirrorWrite + "); no CMI mutation scheduled");
                    }
                    return;
                }
                if (!claimInboundVersion(uuid, incoming)) {
                    return;
                }
                maybeCleanup();

                if (cmiApi.isAvailable()) {
                    final BigDecimal targetBalance = balance;
                    final long appliedVersion = incoming;
                    Player player = plugin.getServer().getPlayer(uuid);
                    if (player != null) {
                        PendingEntityApply pending = registerPendingEntityApply(writeLease);
                        if (pending != null) {
                            try {
                                ScheduledTask scheduled = player.getScheduler().run(plugin, task -> {
                                    if (!pending.begin()) {
                                        return;
                                    }
                                    try {
                                        if (stopped || currentVersion(uuid) != appliedVersion) {
                                            return;
                                        }
                                        if (!player.isOnline()) {
                                            markInboundVersionRetryable(uuid, appliedVersion);
                                            return;
                                        }
                                        Double beforeObj = cmiApi.getBalance(uuid);
                                        if (!pending.beginApply(uuid, appliedVersion)) return;
                                        if (beforeObj == null) {
                                            cmiHandler.invalidateCachedBalanceForApi(uuid);
                                        } else {
                                            cmiHandler.recordApiAuthorityBalance(
                                                    uuid, NumericUtil.normalize(beforeObj));
                                        }
                                        if (beforeObj != null
                                                && Double.compare(beforeObj, targetBalance.doubleValue()) == 0) {
                                            cmiHandler.recordApiAuthorityBalance(uuid, targetBalance);
                                            cmiHandler.notifyInboundApplied(uuid, targetBalance, appliedVersion);
                                            return;
                                        }
                                        cmiHandler.suppressOutbound(uuid, 750L);
                                        cmiHandler.invalidateCachedBalanceForApi(uuid);
                                        boolean applied = cmiApi.setBalance(uuid, targetBalance.doubleValue());
                                        if (applied) {
                                            cmiHandler.recordApiAuthorityBalance(uuid, targetBalance);
                                        } else {
                                            Double afterObj = cmiApi.getBalance(uuid);
                                            applied = afterObj != null
                                                    && Double.compare(afterObj, targetBalance.doubleValue()) == 0;
                                            if (applied) {
                                                cmiHandler.recordApiAuthorityBalance(uuid, targetBalance);
                                            }
                                        }
                                        if (applied) {
                                            cmiHandler.notifyInboundApplied(uuid, targetBalance, appliedVersion);
                                            if (beforeObj != null) {
                                                notifyPlayerIfBalanceChanged(uuid, msg, beforeObj,
                                                        targetBalance.doubleValue());
                                            }
                                        } else {
                                            cmiHandler.invalidateCachedBalanceForApi(uuid);
                                            markInboundVersionRetryable(uuid, appliedVersion);
                                        }
                                        if (config.isDebug()) {
                                            plugin.getLogger().fine("[CMI Inbound] CMI write " + uuid
                                                    + " = " + targetBalance + " (before=" + beforeObj + ")");
                                        }
                                    } catch (Throwable th) {
                                        markInboundVersionRetryable(uuid, appliedVersion);
                                        plugin.getLogger().warning("[CMI Inbound] CMI write threw for " + uuid
                                                + ": " + th.getMessage());
                                    } finally {
                                        pending.finish();
                                    }
                                }, pending::cancelBeforeStart);
                                pending.setTask(scheduled);
                                leaseTransferred = scheduled != null;
                                if (!leaseTransferred) {
                                    pending.finish();
                                    markInboundVersionRetryable(uuid, appliedVersion);
                                }
                            } catch (Throwable schedEx) {
                                pending.finish();
                                markInboundVersionRetryable(uuid, appliedVersion);
                                if (config.isDebug()) {
                                    plugin.getLogger().fine("[CMI Inbound] EntityScheduler.run failed for "
                                            + uuid + " (" + schedEx.getMessage() + "), CMI write skipped this tick");
                                }
                            }
                        }
                    } else if (!stopped) {
                        markInboundVersionRetryable(uuid, appliedVersion);
                    }
                } else if (!stopped) {
                        plugin.getLogger().fine("[CMI Inbound] CMI API unavailable on this server; "
                                + "Redis mirror updated for " + uuid + " but in-game CMI will only sync on next join.");
                        cmiHandler.invalidateCachedBalanceForApi(uuid);
                        markInboundVersionRetryable(uuid, incoming);
                    }
            } finally {
                if (!leaseTransferred) {
                    writeLease.close();
                }
            }

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI Inbound] Processed mirror " + uuid + " = " + balance
                        + " v" + incoming + " from " + src);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process CMI inbound update: " + e.getMessage());
        }
    }

    /**
     * Stops new inbound work and cancels owner callbacks that have not started. An owner callback
     * that wins {@link PendingEntityApply#beginApply(UUID, long)} before shutdown is already
     * admitted and may finish without making shutdown wait on another entity region.
     */
    public void shutdown() {
        List<PendingEntityApply> pending;
        synchronized (lifecycleGate) {
            if (stopped) {
                return;
            }
            stopped = true;
            pending = List.copyOf(pendingEntityApplies);
        }
        for (PendingEntityApply apply : pending) {
            apply.cancelBeforeStart();
        }
    }

    private PendingEntityApply registerPendingEntityApply(MigrationLock.WriteLease writeLease) {
        synchronized (lifecycleGate) {
            if (stopped) {
                return null;
            }
            PendingEntityApply pending = new PendingEntityApply(writeLease);
            pendingEntityApplies.add(pending);
            return pending;
        }
    }

    private final class PendingEntityApply {
        private final MigrationLock.WriteLease writeLease;
        private final AtomicReference<PendingEntityState> state =
                new AtomicReference<>(PendingEntityState.PENDING);
        private volatile ScheduledTask task;

        private PendingEntityApply(MigrationLock.WriteLease writeLease) {
            this.writeLease = writeLease;
        }

        private void setTask(ScheduledTask task) {
            this.task = task;
            if (stopped && task != null && !cancelBeforeStart()
                    && state.get() == PendingEntityState.FINISHED) {
                task.cancel();
            }
        }

        private boolean begin() {
            return state.compareAndSet(PendingEntityState.PENDING, PendingEntityState.RUNNING);
        }

        private boolean beginApply(UUID uuid, long expectedVersion) {
            synchronized (lifecycleGate) {
                if (stopped || state.get() != PendingEntityState.RUNNING
                        || currentVersion(uuid) != expectedVersion) {
                    return false;
                }
                state.set(PendingEntityState.APPLYING);
                return true;
            }
        }

        private void finish() {
            if (state.getAndSet(PendingEntityState.FINISHED) == PendingEntityState.FINISHED) {
                return;
            }
            releaseLease();
        }

        private boolean cancelBeforeStart() {
            if (!state.compareAndSet(PendingEntityState.PENDING, PendingEntityState.FINISHED)) {
                return false;
            }
            releaseLease();
            ScheduledTask scheduled = task;
            if (scheduled != null) {
                scheduled.cancel();
            }
            return true;
        }

        private void releaseLease() {
            pendingEntityApplies.remove(this);
            writeLease.close();
        }
    }

    private enum PendingEntityState {
        PENDING,
        RUNNING,
        APPLYING,
        FINISHED
    }

    private long currentVersion(UUID uuid) {
        VersionEntry entry = lastUpdateVersion.get(uuid);
        return entry != null ? entry.version() : 0L;
    }

    private boolean claimInboundVersion(UUID uuid, long incoming) {
        synchronized (lifecycleGate) {
            if (stopped) {
                return false;
            }
            VersionEntry current = lastUpdateVersion.get(uuid);
            if (current != null) {
                boolean retryingFailedVersion = incoming == current.version() && current.retryable();
                if (!CMIVersioning.isNewer(incoming, current.version()) && !retryingFailedVersion) {
                    return false;
                }
            } else if (!CMIVersioning.isNewer(incoming, 0L)) {
                return false;
            }
            lastUpdateVersion.put(uuid, new VersionEntry(incoming, clock.millis(), false));
            return true;
        }
    }

    private void markInboundVersionRetryable(UUID uuid, long expectedVersion) {
        synchronized (lifecycleGate) {
            if (stopped) {
                return;
            }
            VersionEntry current = lastUpdateVersion.get(uuid);
            if (current != null && current.version() == expectedVersion) {
                lastUpdateVersion.put(uuid, new VersionEntry(current.version(), clock.millis(), true));
            }
        }
    }

    private void maybeCleanup() {
        long now = clock.millis();
        if (lastUpdateVersion.size() <= MAX_VERSION_ENTRIES && now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;

        // Age-based expiry can reopen the stale-message window if Redis mirror keys were evicted.
        // Retain freshness watermarks until capacity pressure requires bounded oldest-entry eviction.
        while (lastUpdateVersion.size() > MAX_VERSION_ENTRIES) {
            List<Map.Entry<UUID, VersionEntry>> entries = new ArrayList<>(lastUpdateVersion.entrySet());
            int overflow = entries.size() - MAX_VERSION_ENTRIES;
            if (overflow <= 0) {
                break;
            }
            entries.sort(Comparator.comparingLong(e -> e.getValue().timestamp()));
            for (int i = 0; i < overflow; i++) {
                lastUpdateVersion.remove(entries.get(i).getKey());
            }
        }
    }

    private CMIRedisMirror.WriteResult updateRedisMirror(UUID uuid, BigDecimal balance, long version) {
        return redisMirror.compareAndWrite(uuid, balance, version);
    }

    /**
     * Notify the online player once per inbound message, using the actual CMI balance delta rather
     * than {@code msg.amount} (which could be wrong on counterparty/verify publishes).
     */
    private void notifyPlayerIfBalanceChanged(UUID uuid, PubSubMessage msg, double before, double after) {
        if (stopped || !config.crossServer().isCrossServerNotificationsEnabled()) {
            return;
        }
        double delta = after - before;
        if (Math.abs(delta) < 0.01) {
            return;
        }
        long now = clock.millis();
        Long cooldownUntil = notificationCooldownUntilMs.get(uuid);
        if (cooldownUntil != null && now < cooldownUntil) {
            return;
        }
        notificationCooldownUntilMs.put(uuid, now + NOTIFICATION_COOLDOWN_MS);
        String messageId = msg.getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            if (!seenNotificationIds.add(messageId)) {
                return;
            }
            trimSeenNotifications();
        }
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player == null) return;
        String sourceServer = msg.getSourceServer();
        String key = delta > 0 ? "cross-server.money-received" : "cross-server.money-spent";

        synchronized (lifecycleGate) {
            if (stopped) {
                return;
            }
            player.getScheduler().run(plugin, t -> {
                if (!player.isOnline() || stopped) {
                    return;
                }
                String message = plugin.getMessage(key);
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(Math.abs(delta)));
                    message = message.replace("{server}", sourceServer != null ? sourceServer : "");
                    if (!stopped) {
                        MessageHelper.sendMessage(player, message);
                    }
                }
            }, null);
        }
    }

    private void trimSeenNotifications() {
        if (seenNotificationIds.size() <= MAX_SEEN_NOTIFICATION_IDS) {
            return;
        }
        int overflow = seenNotificationIds.size() - MAX_SEEN_NOTIFICATION_IDS;
        var iterator = seenNotificationIds.iterator();
        for (int i = 0; i < overflow && iterator.hasNext(); i++) {
            iterator.next();
            iterator.remove();
        }
    }
}
