package noietime.syncmoney.sync;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import redis.clients.jedis.Transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CMIPubsubHandler {

    private static final int MAX_VERSION_ENTRIES = 10_000;
    private static final long VERSION_TTL_MS = 10 * 60 * 1000L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final CMIApi cmiApi;
    private final noietime.syncmoney.economy.CMIEconomyHandler cmiHandler;
    private final String redisPrefix;

    private final Map<UUID, VersionEntry> lastUpdateVersion = new ConcurrentHashMap<>();
    private volatile long lastCleanupTime = 0L;

    private final Set<String> seenNotificationIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_SEEN_NOTIFICATION_IDS = 20_000;

    private final Map<UUID, Long> notificationCooldownUntilMs = new ConcurrentHashMap<>();
    private static final long NOTIFICATION_COOLDOWN_MS = 1_500L;

    private record VersionEntry(long version, long timestamp) {}

    public CMIPubsubHandler(Syncmoney plugin, SyncmoneyConfig config, RedisManager redisManager,
                            CMIApi cmiApi, noietime.syncmoney.economy.CMIEconomyHandler cmiHandler) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.cmiApi = cmiApi;
        this.cmiHandler = cmiHandler;
        this.redisPrefix = config.cmi().getCMIRedisPrefix();
        if (!cmiApi.isAvailable()) {
            plugin.getLogger().fine("CMI inbound handler: CMI API unavailable, Redis mirror only.");
        }
    }

    public void process(PubSubMessage msg) {
        if (msg == null) {
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
                        + " v" + incoming + " from " + src + " (last applied v" + last + ", server=" + config.getServerName() + ")");
            }
            if (!CMIVersioning.isNewer(incoming, last)) {
                if (config.isDebug()) {
                    plugin.getLogger().fine("[CMI Inbound] Rejected stale version for " + uuid
                            + " (current=" + last + " incoming=" + incoming + ")");
                }
                return;
            }

            BigDecimal balance = NumericUtil.normalize(msg.getBalanceAsBigDecimal());
            updateRedisMirror(uuid, balance, incoming);
            lastUpdateVersion.put(uuid, new VersionEntry(incoming, System.currentTimeMillis()));
            maybeCleanup();

            if (cmiApi.isAvailable()) {
                final BigDecimal targetBalance = balance;
                final long appliedVersion = incoming;
                Runnable apply = () -> {
                    try {
                        Double beforeObj = cmiApi.getBalance(uuid);
                        double before = beforeObj != null ? beforeObj.doubleValue() : targetBalance.doubleValue();
                        if (Double.compare(before, targetBalance.doubleValue()) == 0) {
                            cmiHandler.notifyInboundApplied(uuid, targetBalance, appliedVersion);
                            return;
                        }
                        cmiHandler.suppressOutbound(uuid, 750L);
                        cmiApi.setBalance(uuid, targetBalance.doubleValue());
                        cmiHandler.notifyInboundApplied(uuid, targetBalance, appliedVersion);
                        notifyPlayerIfBalanceChanged(uuid, msg, before, targetBalance.doubleValue());
                        if (config.isDebug()) {
                            plugin.getLogger().fine("[CMI Inbound] CMI write " + uuid
                                    + " = " + targetBalance + " (before=" + before + ")");
                        }
                    } catch (Throwable th) {
                        plugin.getLogger().warning("[CMI Inbound] CMI write threw for " + uuid
                                + ": " + th.getMessage());
                    }
                };
                try {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> apply.run());
                } catch (Throwable schedEx) {
                    if (config.isDebug()) {
                        plugin.getLogger().fine("[CMI Inbound] GlobalRegionScheduler.run failed for "
                                + uuid + " (" + schedEx.getMessage() + "), CMI write skipped this tick");
                    }
                }
            } else {
                plugin.getLogger().fine("[CMI Inbound] CMI API unavailable on this server; "
                        + "Redis mirror updated for " + uuid + " but in-game CMI will only sync on next join.");
                cmiHandler.notifyInboundApplied(uuid, balance, incoming);
            }

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI Inbound] Applied " + uuid + " = " + balance
                        + " v" + incoming + " from " + src);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process CMI inbound update: " + e.getMessage());
        }
    }

    private long currentVersion(UUID uuid) {
        VersionEntry entry = lastUpdateVersion.get(uuid);
        return entry != null ? entry.version() : 0L;
    }

    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if (lastUpdateVersion.size() <= MAX_VERSION_ENTRIES && now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;

        lastUpdateVersion.entrySet().removeIf(e -> now - e.getValue().timestamp() > VERSION_TTL_MS);

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

    private void updateRedisMirror(UUID uuid, BigDecimal balance, long version) {
        if (redisManager == null || redisManager.isDegraded()) {
            return;
        }
        try (var jedis = redisManager.getResource()) {
            Transaction tx = jedis.multi();
            tx.set(redisPrefix + ":" + uuid, balance.toPlainString());
            tx.set(redisPrefix + ":" + uuid + ":version", String.valueOf(version));
            tx.exec();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update CMI Redis mirror: " + e.getMessage());
        }
    }

    /**
     * Notify the online player once per inbound message, using the actual CMI balance delta rather
     * than {@code msg.amount} (which could be wrong on counterparty/verify publishes).
     */
    private void notifyPlayerIfBalanceChanged(UUID uuid, PubSubMessage msg, double before, double after) {
        if (!config.crossServer().isCrossServerNotificationsEnabled()) {
            return;
        }
        double delta = after - before;
        if (Math.abs(delta) < 0.01) {
            return;
        }
        long now = System.currentTimeMillis();
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
        if (player == null || !player.isOnline()) {
            return;
        }
        String sourceServer = msg.getSourceServer();
        String key = delta > 0 ? "cross-server.money-received" : "cross-server.money-spent";

        player.getScheduler().run(plugin, t -> {
            String message = plugin.getMessage(key);
            if (message != null) {
                message = message.replace("{amount}", FormatUtil.formatCurrency(Math.abs(delta)));
                message = message.replace("{server}", sourceServer != null ? sourceServer : "");
                MessageHelper.sendMessage(player, message);
            }
        }, null);
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
