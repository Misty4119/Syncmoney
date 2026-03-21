package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.PubSubMessage;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.breaker.DiscordWebhookNotifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * [SYNC-ECO-030] Cross-server synchronization manager using Redis Pub/Sub.
 * Publishes balance updates to other servers and handles incoming messages.
 */
public class CrossServerSyncManager {

    private static final String SYNC_CHANNEL = "syncmoney:balance:update";
    private static final String CMI_CHANNEL = "syncmoney:cmi:balance:update";
    private static final long VERSION_CHECK_INTERVAL_MS = 5 * 60 * 1000;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private volatile DiscordWebhookNotifier discordWebhookNotifier;

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

    /**
     * [SYNC-ECO-031] Publish balance update to Redis Pub/Sub channel and notify local player.
     */
    public void publishAndNotify(UUID uuid, BigDecimal newBalance,
                                String eventType, double amount, String sourcePlugin, String sourcePlayerName) {
        String currentServer = config.getServerName();

        String channel = eventType != null && eventType.startsWith("CMI_") ? CMI_CHANNEL : SYNC_CHANNEL;

        long version = cacheManager.getVersion(uuid);
        PubSubMessage message = new PubSubMessage(
            uuid.toString(),
            newBalance.doubleValue(),
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
            jedis.publish(channel, message.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish to Pub/Sub: " + e.getMessage());
        }

        if (config.crossServer().isCrossServerNotificationsEnabled()) {
            notifyLocalPlayer(uuid, currentServer, eventType, amount, sourcePlugin);
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
        if (player == null || !player.isOnline()) {
            return;
        }

        String notifyType = config.crossServer().getCrossServerNotifyType().toLowerCase();

        player.getScheduler().run(plugin, task -> {
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
        String messageKey;
        
        
        
        if ("VAULT_DEPOSIT".equals(eventType)) {
            messageKey = "cross-server.money-received";
        } else if ("VAULT_WITHDRAW".equals(eventType)) {
            messageKey = "cross-server.money-spent";
        } else if ("DEPOSIT".equals(eventType) || "ADMIN_GIVE".equals(eventType)) {
            
            
            
            messageKey = "admin.money-received";
        } else if ("WITHDRAW".equals(eventType) || "ADMIN_TAKE".equals(eventType)) {
            messageKey = "admin.money-taken";
        } else if ("SET_BALANCE".equals(eventType)) {
            messageKey = "admin.balance-set-by-admin";
        } else if ("TRANSFER_IN".equals(eventType)) {
            messageKey = "pay.success-receiver";
        } else {
            
            
            messageKey = amount > 0 ? "cross-server.money-received" : "cross-server.money-spent";
        }

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
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            return player.getName();
        }
        return uuid.toString();
    }

    /**
     * [SYNC-ECO-034] Shutdown cross-server sync manager.
     */
    public void shutdown() {
    }

    /**
     * [SYNC-ECO-035] Periodic version check to ensure cross-server data consistency.
     * Runs every 5 minutes to sync any missed Pub/Sub messages.
     * Should be called during plugin startup.
     */
    public void startPeriodicVersionCheck() {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            performVersionCheck();
        }, VERSION_CHECK_INTERVAL_MS / 50, VERSION_CHECK_INTERVAL_MS / 50);
    }

    /**
     * [SYNC-ECO-036] Performs version check for all online players using batch getAllVersions.
     */
    private void performVersionCheck() {
        if (redisManager == null || cacheManager == null || economyFacade == null) {
            return;
        }

        try (var jedis = redisManager.getResource()) {
            Set<UUID> onlineUuids = plugin.getServer().getOnlinePlayers().stream()
                    .map(player -> player.getUniqueId())
                    .collect(java.util.stream.Collectors.toSet());
            Map<UUID, Long> redisVersions = cacheManager.getAllVersions(onlineUuids);

            for (UUID uuid : onlineUuids) {
                Long redisVersion = redisVersions.get(uuid);
                if (redisVersion == null) {

                    continue;
                }

                long localVersion = cacheManager.getVersion(uuid);
                if (redisVersion > localVersion) {
                    String balanceKey = "syncmoney:balance:" + uuid.toString();
                    String balanceStr = jedis.get(balanceKey);
                    if (balanceStr != null) {
                        BigDecimal redisBalance = new BigDecimal(balanceStr);
                        economyFacade.updateMemoryState(uuid, redisBalance, redisVersion);
                        cacheManager.updateMemoryCache(uuid, redisBalance, redisVersion);

                        plugin.getLogger().fine("Version sync: Updated " + uuid + " from v" + localVersion + " to v" + redisVersion);
                    }
                } else if (localVersion > redisVersion) {
                    BigDecimal localBalance = economyFacade.getBalance(uuid);

                    String balanceKey = "syncmoney:balance:" + uuid.toString();
                    String versionKey = "syncmoney:version:" + uuid.toString();
                    jedis.set(balanceKey, localBalance.toString());
                    jedis.set(versionKey, String.valueOf(localVersion));

                    plugin.getLogger().fine("Version sync: Pushed " + uuid + " v" + localVersion + " to Redis");
                }
            }
        } catch (Exception e) {
        }
    }
}
