package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.PubSubMessage;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * [SYNC-SYNC-001] Cross-server synchronization manager using Redis Pub/Sub.
 * Publishes balance updates to other servers and handles incoming messages.
 */
public class CrossServerSyncManager {

    private static final String SYNC_CHANNEL = "syncmoney:balance:update";
    private static final String CMI_CHANNEL = "syncmoney:cmi:balance:update";

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;

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
     * [SYNC-SYNC-002] Publish balance update to Redis Pub/Sub channel and notify local player.
     */
    public void publishAndNotify(UUID uuid, BigDecimal newBalance,
                                String eventType, double amount, String sourcePlugin) {
        String currentServer = config.getServerName();

        String channel = eventType != null && eventType.startsWith("CMI_") ? CMI_CHANNEL : SYNC_CHANNEL;

        long version = System.currentTimeMillis();
        PubSubMessage message = new PubSubMessage(
            uuid.toString(),
            newBalance.doubleValue(),
            version,
            currentServer,
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            eventType,
            amount,
            sourcePlugin
        );

        try (var jedis = redisManager.getResource()) {
            jedis.publish(channel, message.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish to Pub/Sub: " + e.getMessage());
        }

        if (config.isCrossServerNotificationsEnabled()) {
            notifyLocalPlayer(uuid, currentServer, eventType, amount, sourcePlugin);
        }
    }

    /**
     * [SYNC-SYNC-003] Process incoming cross-server balance update message.
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

            if (config.isCrossServerNotificationsEnabled()) {
                notifyLocalPlayer(uuid, msg.getSourceServer(),
                    msg.getEventType(), msg.getAmount(), msg.getSourcePlugin());
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process cross-server message: " + e.getMessage());
        }
    }

    /**
     * Notify local player.
     */
    private void notifyLocalPlayer(UUID uuid, String sourceServer,
                                String eventType, double amount, String sourcePlugin) {
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getScheduler().run(plugin, task -> {
            String messageKey;
            if (amount > 0) {
                messageKey = "cross-server.money-received";
            } else {
                messageKey = "cross-server.money-spent";
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

            if (config.showCrossServerActionbar()) {
                String actionbar = plugin.getMessage("cross-server.balance-synced");
                if (actionbar != null) {
                    actionbar = actionbar.replace("{server}", sourceServer);
                    MessageHelper.sendActionBar(player, actionbar,
                        config.getActionbarDuration());
                }
            }
        }, null);
    }

    /**
     * Shutdown cross-server sync manager.
     */
    public void shutdown() {
    }
}
