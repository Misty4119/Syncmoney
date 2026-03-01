package noietime.syncmoney.sync;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.JedisPubSub;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * [SYNC-PUBSUB-001] Redis Pub/Sub subscriber for cross-server balance synchronization.
 * Listens on dedicated thread and updates local cache/memory on received messages.
 *
 * [AsyncScheduler] Runs on dedicated thread, updates memory directly.
 * Switches to EntityScheduler for player UI updates if needed.
 */
public final class PubsubSubscriber {

    private static final String CHANNEL = "syncmoney:balance:update";
    private static final long INITIAL_RETRY_DELAY_MS = 1000; // Start with 1 second
    private static final long MAX_RETRY_DELAY_MS = 30000; // Cap at 30 seconds

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final CacheManager cacheManager;
    private final EconomyFacade economyFacade;
    private final DebounceManager debounceManager;
    private final RedisManager redisManager;
    private final boolean redisRequired;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread subscriberThread;

    public PubsubSubscriber(Plugin plugin, SyncmoneyConfig config,
                          CacheManager cacheManager, EconomyFacade economyFacade,
                          DebounceManager debounceManager, RedisManager redisManager,
                          boolean redisRequired) {
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.economyFacade = economyFacade;
        this.debounceManager = debounceManager;
        this.redisManager = redisManager;
        this.redisRequired = redisRequired;
    }

    /**
     * Start subscription.
     * Should be called from AsyncScheduler or separate thread.
     */
    public void startSubscription() {
        if (!config.isPubsubEnabled()) {
            plugin.getLogger().info("Pub/Sub is disabled in config.");
            return;
        }

        if (redisManager.isDegraded()) {
            plugin.getLogger().fine("Pub/Sub disabled: Redis not available (LOCAL mode).");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            plugin.getLogger().warning("Pub/Sub subscription already running.");
            return;
        }

        String serverName = config.getServerName();
        if (serverName == null || serverName.isBlank()) {
            plugin.getLogger().severe("server-name not configured. Pub/Sub disabled.");
            running.set(false);
            return;
        }

        subscriberThread = new Thread(this::runSubscription, "Syncmoney-PubSub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        plugin.getLogger().info("Pub/Sub subscription started on channel: " + CHANNEL);
    }

    /**
     * Subscription main loop.
     * Note: subscribe() is a blocking call, must manually manage connection lifecycle.
     * Note: Uses dedicated connection here, not connection pool, to avoid long-term occupation of pool resources.
     */
    private void runSubscription() {
        plugin.getLogger().info("Starting Pub/Sub subscription thread...");

        long retryDelayMs = INITIAL_RETRY_DELAY_MS;

        while (running.get()) {
            redis.clients.jedis.Jedis jedis = null;
            try {
                jedis = new redis.clients.jedis.Jedis(
                    config.getRedisHost(),
                    config.getRedisPort()
                );

                String redisPassword = config.getRedisPassword();
                if (redisPassword != null && !redisPassword.isEmpty()) {
                    jedis.auth(redisPassword);
                }

                jedis.select(config.getRedisDatabase());

                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (!CHANNEL.equals(channel)) return;
                        handleMessage(message);
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        plugin.getLogger().fine("Pub/Sub: subscribed to " + channel);
                    }

                    @Override
                    public void onUnsubscribe(String channel, int subscribedChannels) {
                        plugin.getLogger().fine("Pub/Sub: unsubscribed from " + channel);
                    }
                };

                jedis.subscribe(pubSub, CHANNEL);

                // Reset retry delay on successful connection
                retryDelayMs = INITIAL_RETRY_DELAY_MS;

            } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                if (running.get()) {
                    if (redisRequired) {
                        plugin.getLogger().log(Level.WARNING, "Pub/Sub connection error: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Exponential backoff: double the delay, cap at max
                    retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS);
                }
            } catch (Exception e) {
                if (running.get()) {
                    if (redisRequired) {
                        plugin.getLogger().log(Level.WARNING, "Pub/Sub error: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Exponential backoff: double the delay, cap at max
                    retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS);
                }
            } finally {
                if (jedis != null) {
                    try {
                        jedis.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        plugin.getLogger().info("Pub/Sub subscription thread ended.");
    }

    /**
     * Stop subscription.
     */
    public void stopSubscription() {
        running.set(false);

        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        plugin.getLogger().info("Pub/Sub subscription stopped.");
    }

    /**
     * Handle received message.
     *
     * [AsyncScheduler] This method runs on Pub/Sub subscriber thread.
     */
    private void handleMessage(String message) {
        try {
            PubSubMessage msg = PubSubMessage.fromJson(message);
            if (msg == null) {
                plugin.getLogger().warning("Failed to parse Pub/Sub message: " + message);
                return;
            }

            String currentServer = config.getServerName();
            String msgServer = msg.getSourceServer();
            if (currentServer != null && currentServer.equals(msgServer)) {
                if (isDebugEnabled()) {
                    plugin.getLogger().fine("Pub/Sub: ignored message from self (" + msg.getSourceServer() + ")");
                }
                return;
            }

            UUID uuid = msg.getUuidAsUUID();
            long currentVersion = cacheManager.getVersion(uuid);
            if (msg.getVersion() <= currentVersion) {
                if (isDebugEnabled()) {
                    plugin.getLogger().fine("Pub/Sub: rejected old version for " + uuid +
                        " (current=" + currentVersion + " new=" + msg.getVersion() + ")");
                }
                return;
            }

            if (!debounceManager.shouldProcess(uuid, msg.getVersion(),
                                               msg.getSourceServer(), msg.getMessageId())) {
                if (isDebugEnabled()) {
                    plugin.getLogger().fine("Pub/Sub: debounced duplicate for " + uuid);
                }
                return;
            }

            BigDecimal balance = msg.getBalanceAsBigDecimal();
            boolean updated = economyFacade.updateMemoryState(uuid, balance, msg.getVersion());
            if (!updated) {
                if (isDebugEnabled()) {
                    plugin.getLogger().fine("Pub/Sub: memory state not updated for " + uuid);
                }
                return;
            }

            cacheManager.updateMemoryCache(uuid, balance, msg.getVersion());

            if (msg.getSourceServer() != null && !msg.getSourceServer().equals(config.getServerName())) {
                notifyPlayerIfOnline(uuid, balance, msg.getEventType(), msg.getAmount(), msg.getSourcePlayerName());
            }

            plugin.getLogger().fine("Pub/Sub: processed update for " + uuid +
                " v" + msg.getVersion() + " balance " + FormatUtil.formatCurrency(balance));

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing Pub/Sub message: " + e.getMessage(), e);
        }
    }

    /**
     * If player is online, update their UI via EntityScheduler
     *
     * [EntityScheduler] This method switches to player's EntityScheduler for execution.
     */
    private void notifyPlayerIfOnline(UUID uuid, BigDecimal newBalance) {
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getScheduler().run(plugin, (task) -> {
        }, null);
    }

    /**
     * If player is online, notify them of balance change (cross-server)
     * Sends notification message to target player
     *
     * [EntityScheduler] This method switches to player's EntityScheduler for execution.
     */
    private void notifyPlayerIfOnline(UUID uuid, BigDecimal newBalance, String eventType, double amount, String sourcePlayerName) {
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getScheduler().run(plugin, (task) -> {
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String messageKey = null;

                if ("DEPOSIT".equals(eventType) || "ADMIN_GIVE".equals(eventType)) {
                    messageKey = "admin.money-received";
                } else if ("WITHDRAW".equals(eventType) || "ADMIN_TAKE".equals(eventType)) {
                    messageKey = "admin.money-taken";
                } else if ("SET_BALANCE".equals(eventType)) {
                    messageKey = "admin.balance-set-by-admin";
                } else if ("TRANSFER_IN".equals(eventType)) {
                    messageKey = "pay.success-receiver";
                }

                if (messageKey != null) {
                    String message = syncMoneyPlugin.getMessage(messageKey);
                    if (message != null) {
                        message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                        if (amount != 0) {
                            message = message.replace("{amount}", FormatUtil.formatCurrency(Math.abs(amount)));
                        }
                        if ("TRANSFER_IN".equals(eventType)) {
                            message = message.replace("{player}", sourcePlayerName != null ? sourcePlayerName : "Unknown");
                        }
                        MessageHelper.sendMessage(player, message);
                    }
                }
            }
        }, null);
    }

    private boolean isDebugEnabled() {
        return plugin.getConfig().getBoolean("debug", false);
    }

    /**
     * Check if running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Publish balance change to cross-server channel.
     * @param uuid Player UUID
     * @param newBalance New balance
     */
    public void publishBalanceChange(UUID uuid, BigDecimal newBalance) {
        publishTransferEvent(uuid, newBalance, null, 0, null);
    }

    /**
     * Publish transfer event to cross-server channel.
     * Used for /pay command to notify receiver on other server.
     *
     * @param uuid Player UUID
     * @param newBalance New balance after transfer
     * @param eventType Event type (TRANSFER_IN or TRANSFER_OUT)
     * @param amount Transfer amount
     * @param sourcePlayerName Name of the player who initiated the transfer (for TRANSFER_IN)
     */
    public void publishTransferEvent(UUID uuid, BigDecimal newBalance, String eventType, double amount, String sourcePlayerName) {
        if (!running.get()) {
            return;
        }
        try {
            long version = cacheManager.getVersion(uuid);
            PubSubMessage message = new PubSubMessage(
                    uuid.toString(),
                    newBalance.doubleValue(),
                    version,
                    config.getServerName(),
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    eventType,
                    amount,
                    "Syncmoney",
                    sourcePlayerName
            );
            String json = message.toJson();
            try (var jedis = redisManager.getResource()) {
                jedis.publish(CHANNEL, json);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish transfer event: " + e.getMessage());
        }
    }
}
