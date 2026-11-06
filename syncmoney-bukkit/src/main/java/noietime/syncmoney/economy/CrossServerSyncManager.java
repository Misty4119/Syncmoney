package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.protocol.RedisChannels;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.CacheManager.CachedBalance;
import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.sync.PubSubMessage;
import noietime.syncmoney.sync.MessageKeys;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.breaker.DiscordWebhookNotifier;
import noietime.syncmoney.uuid.OnlinePlayerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * [SYNC-ECO-030] Cross-server synchronization manager using Redis Pub/Sub.
 * Publishes balance updates to other servers and handles incoming messages.
 */
public class CrossServerSyncManager {

    private static final String CMI_CHANNEL = RedisChannels.CMI_BALANCE_UPDATE;
    private static final long VERSION_CHECK_INTERVAL_MS = 5 * 60 * 1000;
    private static final long MS_PER_TICK = 50L;
    private static final long VERSION_CHECK_INTERVAL_TICKS = VERSION_CHECK_INTERVAL_MS / MS_PER_TICK;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private volatile DiscordWebhookNotifier discordWebhookNotifier;
    private volatile ScheduledTask versionCheckTask;
    private volatile boolean stopped;
    private final Object versionCheckLifecycleGate = new Object();
    private boolean versionCheckRunning;

    public CrossServerSyncManager(Syncmoney plugin, SyncmoneyConfig config,
                                RedisManager redisManager,
                                EconomyFacade economyFacade,
                                CacheManager cacheManager) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.economyFacade = economyFacade;
        this.cacheManager = cacheManager;
    }

    /**
     * Set Discord webhook notifier for cross-server notifications.
     */
    public void setDiscordWebhookNotifier(DiscordWebhookNotifier discordWebhookNotifier) {
        this.discordWebhookNotifier = discordWebhookNotifier;
    }

    /** Notify the originating server locally; shared Pub/Sub is emitted after the Redis receipt. */
    public void notifyLocalBalanceChange(UUID uuid, String eventType, double amount, String sourcePlugin) {
        String currentServer = config.getServerName();

        if (config.crossServer().isCrossServerNotificationsEnabled()) {
            notifyLocalPlayer(uuid, currentServer, eventType, amount, sourcePlugin);
        }
    }

    /**
     * Legacy publisher retained for integrations that do not yet pass an authority version.
     * It publishes only when one current cached balance/version snapshot matches the supplied balance.
     */
    public void publishAndNotify(UUID uuid, BigDecimal newBalance,
                                String eventType, double amount, String sourcePlugin, String sourcePlayerName) {
        CacheManager.CachedBalance snapshot = cacheManager == null || uuid == null
                ? null
                : cacheManager.getCachedBalanceSnapshot(uuid).orElse(null);
        if (snapshot != null && newBalance != null && snapshot.balance().compareTo(newBalance) == 0) {
            publishAndNotify(uuid, newBalance, snapshot.version(), eventType, amount,
                    sourcePlugin, sourcePlayerName);
            return;
        }

        notifyLocalBalanceChange(uuid, eventType, amount, sourcePlugin);
    }

    /**
     * Publish an authority-confirmed balance/version pair and notify the originating server.
     * The caller must pass the version returned with the balance by the same mutation.
     */
    public void publishAndNotify(UUID uuid, BigDecimal newBalance, long version,
                                String eventType, double amount, String sourcePlugin, String sourcePlayerName) {
        if (uuid == null || newBalance == null) {
            return;
        }
        String currentServer = config.getServerName();
        String channel = eventType != null && eventType.startsWith("CMI_") ? CMI_CHANNEL
                : RedisChannels.BALANCE_UPDATE;
        PubSubMessage message = new PubSubMessage(
                uuid.toString(), newBalance, version, currentServer,
                UUID.randomUUID().toString(), System.currentTimeMillis(), eventType, amount,
                sourcePlugin, sourcePlayerName);
        try (var jedis = redisManager.getResource()) {
            jedis.publish(channel, message.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish to Pub/Sub: " + e.getMessage());
        }
        if (config.crossServer().isCrossServerNotificationsEnabled()) {
            notifyLocalPlayer(uuid, currentServer, eventType, amount, sourcePlugin);
        }
    }

    public void publishCMIUpdate(UUID uuid, BigDecimal newBalance, long version,
                                 String eventType, double amount, String sourcePlugin) {
        publishCMIUpdate(uuid, newBalance, version, eventType, amount, sourcePlugin, null);
    }

    public void publishCMIUpdate(UUID uuid, BigDecimal newBalance, long version,
                                 String eventType, double amount, String sourcePlugin,
                                 String sourcePlayerName) {
        String currentServer = config.getServerName();

        PubSubMessage message = new PubSubMessage(
            uuid.toString(),
            newBalance,
            version,
            currentServer,
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            eventType,
            amount,
            sourcePlugin,
            sourcePlayerName
        );

        try (var jedis = redisManager.getResource()) {
            jedis.publish(CMI_CHANNEL, message.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish CMI update to Pub/Sub: " + e.getMessage());
        }

    }

    /**
     * [SYNC-ECO-032] Process incoming cross-server balance update message.
     * Validates version before applying to prevent stale updates.
     */
    public void handleIncomingMessage(String channel, String messageJson) {
        try {
            PubSubMessage msg = PubSubMessage.fromJson(messageJson);
            if (msg == null) return;

            if (msg.getSourceServer() != null &&
                msg.getSourceServer().equals(config.getServerName())) {
                return;
            }

            UUID uuid = msg.getUuidAsUUID();

            long currentVersion = cacheManager.getVersion(uuid);
            if (msg.getVersion() <= currentVersion) {
                return;
            }

            if (economyFacade != null) {
                economyFacade.updateMemoryState(uuid, msg.getBalanceAsBigDecimal(), msg.getVersion());
            }

            if (cacheManager != null) {
                cacheManager.updateMemoryCache(uuid, msg.getBalanceAsBigDecimal(), msg.getVersion());
            }

            if (config.crossServer().isCrossServerNotificationsEnabled()) {
                notifyLocalPlayer(uuid, msg.getSourceServer(),
                    msg.getEventType(), msg.getAmount(), msg.getSourcePlugin());
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process cross-server message: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-033] Notify local player.
     * 
     * Fix: Now properly routes messages based on eventType, matching PubsubSubscriber logic.
     * Previously only checked amount > 0, which caused VAULT_DEPOSIT from third-party plugins
     * to be incorrectly routed to admin.money-received instead of cross-server.money-received.
     */
    private void notifyLocalPlayer(UUID uuid, String sourceServer,
                                String eventType, double amount, String sourcePlugin) {
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player == null) return;

        String notifyType = config.crossServer().getCrossServerNotifyType().toLowerCase();

        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            switch (notifyType) {
                case "sse":
                    notifyPlayerViaSse(player, sourceServer, eventType, amount, sourcePlugin);
                    break;
                case "discord":
                    notifyPlayerViaDiscord(uuid, sourceServer, eventType, amount, sourcePlugin);
                    break;
                case "both":
                    notifyPlayerViaSse(player, sourceServer, eventType, amount, sourcePlugin);
                    notifyPlayerViaDiscord(uuid, sourceServer, eventType, amount, sourcePlugin);
                    break;
                default:
                    notifyPlayerViaSse(player, sourceServer, eventType, amount, sourcePlugin);
                    break;
            }
        }, null);
    }

    /**
     * Notify player via in-game SSE (chat message + action bar).
     * 
     * Now uses eventType to determine message key, matching PubsubSubscriber.notifyPlayerIfOnline logic.
     */
    private void notifyPlayerViaSse(Player player, String sourceServer, String eventType, double amount, String sourcePlugin) {
        String messageKey = notificationMessageKey(eventType, amount);

        String message = plugin.getMessage(messageKey);
        if (message != null) {
            message = message.replace("{server}", sourceServer);
            message = message.replace("{amount}",
                FormatUtil.formatCurrency(Math.abs(amount)));

            if (sourcePlugin != null && !sourcePlugin.equals("Unknown")) {
                String prefix = plugin.getMessage("cross-server.from-plugin");
                if (prefix != null) {
                    message = prefix.replace("{plugin}", sourcePlugin) + message;
                }
            }

            MessageHelper.sendMessage(player, message);
        }

        if (config.crossServer().showCrossServerActionbar()) {
            String actionbar = plugin.getMessage("cross-server.balance-synced");
            if (actionbar != null) {
                actionbar = actionbar.replace("{server}", sourceServer);
                MessageHelper.sendActionBar(player, actionbar,
                    config.crossServer().getActionbarDuration());
            }
        }
    }

    static String notificationMessageKey(String eventType, double amount) {
        String messageKey = MessageKeys.forEventType(eventType);
        if (messageKey != null) {
            return messageKey;
        }
        return amount > 0 ? "cross-server.money-received" : "cross-server.money-spent";
    }

    /**
     * Notify player via Discord webhook.
     * 
     * Now uses eventType to determine direction, matching the SSE notification logic.
     */
    private void notifyPlayerViaDiscord(UUID uuid, String sourceServer, String eventType, double amount, String sourcePlugin) {
        if (discordWebhookNotifier == null) {
            return;
        }

        String playerName = getPlayerNameFromUuid(uuid);
        String direction = determineDirection(eventType, amount);
        String formattedAmount = FormatUtil.formatCurrency(Math.abs(amount));

        String description = String.format(
            "**Player:** %s\n**Direction:** %s\n**Amount:** %s\n**Source Server:** %s\n**Source Plugin:** %s",
            playerName,
            direction,
            formattedAmount,
            sourceServer,
            sourcePlugin != null ? sourcePlugin : "Unknown"
        );

        discordWebhookNotifier.sendHighValueTransactionEvent(
            uuid,
            java.math.BigDecimal.valueOf(Math.abs(amount)),
            direction + "_cross_server"
        );
    }

    /**
     * Determines the direction (received/spent) based on eventType and amount.
     * Matches the logic used in notifyPlayerViaSse.
     */
    private String determineDirection(String eventType, double amount) {
        
        if ("VAULT_DEPOSIT".equals(eventType) || "DEPOSIT".equals(eventType) || "ADMIN_GIVE".equals(eventType)) {
            return "received";
        } else if ("VAULT_WITHDRAW".equals(eventType) || "WITHDRAW".equals(eventType) || "ADMIN_TAKE".equals(eventType)) {
            return "spent";
        } else if ("TRANSFER_IN".equals(eventType)) {
            return "received";
        }
        
        return amount > 0 ? "received" : "spent";
    }

    /**
     * Get player name from UUID with fallback.
     */
    private String getPlayerNameFromUuid(UUID uuid) {
        OnlinePlayerRegistry registry = plugin.getOnlinePlayerRegistry();
        String localName = registry == null ? null : registry.getLocalOnlinePlayerName(uuid);
        if (localName != null && !localName.isBlank()) return localName;
        return uuid.toString();
    }

    /**
     * [SYNC-ECO-034] Shutdown cross-server sync manager.
     */
    public void shutdown() {
        ScheduledTask scheduled;
        synchronized (versionCheckLifecycleGate) {
            stopped = true;
            scheduled = versionCheckTask;
            versionCheckTask = null;
        }
        if (scheduled != null) {
            scheduled.cancel();
        }
        awaitVersionCheck();
    }

    /**
     * [SYNC-ECO-035] Periodic version check to ensure cross-server data consistency.
     * Runs every 5 minutes ({@link #VERSION_CHECK_INTERVAL_TICKS} = 6000 ticks) to sync
     * any missed Pub/Sub messages. Should be called during plugin startup.
     *
     * <p>Note: {@code GlobalRegionScheduler.runAtFixedRate} takes its delay/period in ticks,
     * so the interval is supplied in ticks (not milliseconds) to actually run every 5 minutes.
     */
    public void startPeriodicVersionCheck() {
        if (config.isCMIMode()) {
            plugin.getLogger().fine("Cross-server version repair skipped: CMI mode uses its dedicated Redis mirror.");
            return;
        }
        synchronized (versionCheckLifecycleGate) {
            if (stopped || versionCheckTask != null) {
                return;
            }
        }
        ScheduledTask scheduled = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (beginVersionCheck()) {
                boolean handedOff = false;
                try {
                    OnlinePlayerRegistry onlinePlayerRegistry = plugin.getOnlinePlayerRegistry();
                    Set<UUID> onlineUuids = onlinePlayerRegistry == null
                            ? Set.of()
                            : onlinePlayerRegistry.getLocalOnlinePlayerIds();
                    if (stopped) {
                        return;
                    }
                    AsyncScheduler asyncScheduler = plugin.getServer().getAsyncScheduler();
                    ScheduledTask asyncTask = asyncScheduler.runNow(plugin, asyncTaskHandle -> {
                        try {
                            if (!stopped) {
                                performVersionCheck(onlineUuids);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Cross-server version check failed: " + e.getMessage());
                        } finally {
                            finishVersionCheck();
                        }
                    });
                    if (asyncTask != null) {
                        handedOff = true;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to schedule cross-server version check: " + e.getMessage());
                } finally {
                    if (!handedOff) {
                        finishVersionCheck();
                    }
                }
            } else if (stopped) {
                task.cancel();
            }
        }, VERSION_CHECK_INTERVAL_TICKS, VERSION_CHECK_INTERVAL_TICKS);

        boolean cancelScheduled;
        synchronized (versionCheckLifecycleGate) {
            cancelScheduled = stopped || versionCheckTask != null;
            if (!cancelScheduled) {
                versionCheckTask = scheduled;
            }
        }
        if (cancelScheduled && scheduled != null) {
            scheduled.cancel();
        }
    }

    /**
     * [SYNC-ECO-036] Performs version check for all online players using batch getAllVersions.
     */
    private void performVersionCheck(Set<UUID> onlineUuids) {
        if (redisManager == null || cacheManager == null || economyFacade == null) {
            return;
        }

        for (UUID uuid : onlineUuids) {
            if (stopped) return;
            try {
                EconomyState localState = economyFacade.getMemoryState(uuid);
                Optional<CachedBalance> redisSnapshot = cacheManager.readAuthorityBalanceSnapshotStrict(uuid);
                if (stopped || redisSnapshot.isEmpty()) {
                    continue;
                }

                CachedBalance authority = redisSnapshot.get();
                if (localState != null && authority.version() <= localState.version()) {
                    continue;
                }

                if (economyFacade.updateMemoryState(uuid, authority.balance(), authority.version())) {
                    long oldVersion = localState == null ? 0L : localState.version();
                    plugin.getLogger().fine("Version sync: Updated " + uuid + " from v" + oldVersion
                            + " to v" + authority.version());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Cross-server version sync failed for " + uuid + ": " + e.getMessage());
            }
        }
    }

    private boolean beginVersionCheck() {
        synchronized (versionCheckLifecycleGate) {
            if (stopped || versionCheckRunning) {
                return false;
            }
            versionCheckRunning = true;
            return true;
        }
    }

    private void finishVersionCheck() {
        synchronized (versionCheckLifecycleGate) {
            versionCheckRunning = false;
            versionCheckLifecycleGate.notifyAll();
        }
    }

    private void awaitVersionCheck() {
        boolean interrupted = false;
        synchronized (versionCheckLifecycleGate) {
            while (versionCheckRunning) {
                try {
                    versionCheckLifecycleGate.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
