package noietime.syncmoney.sync;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.protocol.RedisChannels;
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
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * [SYNC-PUBSUB-001] Redis Pub/Sub subscriber for cross-server balance synchronization.
 * Listens on dedicated thread and updates local cache/memory on received messages.
 *
 * [AsyncScheduler] Runs on dedicated thread, updates memory directly.
 * Switches to EntityScheduler for player UI updates if needed.
 */
public final class PubsubSubscriber {

    private static final String CHANNEL = RedisChannels.BALANCE_UPDATE;
    private static final String CMI_CHANNEL = RedisChannels.CMI_BALANCE_UPDATE;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final CacheManager cacheManager;
    private final EconomyFacade economyFacade;
    private final DebounceManager debounceManager;
    private final RedisManager redisManager;
    private final boolean redisRequired;
    private final Supplier<redis.clients.jedis.Jedis> jedisFactory;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object lifecycleGate = new Object();
    private final Object inboundApplyGate = new Object();
    private volatile Thread subscriberThread;
    private volatile redis.clients.jedis.Jedis activeJedis;
    private volatile CMIPubsubHandler cmiPubsubHandler;

    public PubsubSubscriber(Plugin plugin, SyncmoneyConfig config,
                          CacheManager cacheManager, EconomyFacade economyFacade,
                          DebounceManager debounceManager, RedisManager redisManager,
                          boolean redisRequired) {
        this(plugin, config, cacheManager, economyFacade, debounceManager, redisManager,
                redisRequired, () -> new redis.clients.jedis.Jedis(
                        config.redis().getRedisHost(), config.redis().getRedisPort(), 5000, 5000));
    }

    PubsubSubscriber(Plugin plugin, SyncmoneyConfig config,
                     CacheManager cacheManager, EconomyFacade economyFacade,
                     DebounceManager debounceManager, RedisManager redisManager,
                     boolean redisRequired, Supplier<redis.clients.jedis.Jedis> jedisFactory) {
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.economyFacade = economyFacade;
        this.debounceManager = debounceManager;
        this.redisManager = redisManager;
        this.redisRequired = redisRequired;
        this.jedisFactory = jedisFactory;
    }

    /**
     * Start subscription.
     * Should be called from AsyncScheduler or separate thread.
     */
    public void startSubscription() {
        synchronized (lifecycleGate) {
            if (stopped.get()) {
                return;
            }
        }
        if (!config.isPubsubEnabled()) {
            plugin.getLogger().fine("Pub/Sub is disabled in config.");
            return;
        }

        if (!redisRequired) {
            plugin.getLogger().fine("Pub/Sub disabled because Redis is not required for this economy mode.");
            return;
        }

        if (redisManager == null) {
            plugin.getLogger().severe("Pub/Sub cannot start because Redis is required but RedisManager is unavailable.");
            return;
        }

        String serverName = config.getServerName();
        if (serverName == null || serverName.isBlank()) {
            plugin.getLogger().severe("server-name not configured. Pub/Sub disabled.");
            return;
        }

        synchronized (lifecycleGate) {
            if (stopped.get()) {
                return;
            }
            if (!running.compareAndSet(false, true)) {
                plugin.getLogger().fine("Pub/Sub subscription already running.");
                return;
            }

            subscriberThread = new Thread(this::runSubscription, "Syncmoney-PubSub");
            subscriberThread.setDaemon(true);
            subscriberThread.start();
        }

        plugin.getLogger().fine("Pub/Sub subscription started on channel: " + CHANNEL);
    }

    /**
     * Subscription main loop.
     * Note: subscribe() is a blocking call, must manually manage connection lifecycle.
     * Note: Uses dedicated connection here, not connection pool, to avoid long-term occupation of pool resources.
     */
    private void runSubscription() {
        plugin.getLogger().fine("Starting Pub/Sub subscription thread...");

        long retryDelayMs = INITIAL_RETRY_DELAY_MS;

        while (running.get()) {
            redis.clients.jedis.Jedis jedis = null;
            try {

            jedis = jedisFactory.get();

            synchronized (lifecycleGate) {
                if (stopped.get() || !running.get()) {
                    break;
                }
                activeJedis = jedis;
            }

            String redisPassword = config.redis().getRedisPassword();
            if (redisPassword != null && !redisPassword.isEmpty()) {
                jedis.auth(redisPassword);
            }

            jedis.select(config.redis().getRedisDatabase());

                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (CHANNEL.equals(channel)) {
                            handleMessage(message);
                        } else if (CMI_CHANNEL.equals(channel)) {
                            handleCMIMessage(message);
                        }
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

                jedis.subscribe(pubSub, CHANNEL, CMI_CHANNEL);

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
                    retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS);
                }
            } finally {
                if (jedis != null) {
                    try {
                        jedis.close();
                    } catch (Exception ignored) {
                    }
                    synchronized (lifecycleGate) {
                        if (activeJedis == jedis) {
                            activeJedis = null;
                        }
                    }
                }
        }
        }

        plugin.getLogger().fine("Pub/Sub subscription thread ended.");
    }

    /**
     * Stop subscription.
     */
    public void stopSubscription() {
        Thread threadToStop;
        redis.clients.jedis.Jedis connectionToClose;
        synchronized (lifecycleGate) {
            stopped.set(true);
            running.set(false);
            threadToStop = subscriberThread;
            connectionToClose = activeJedis;
        }

        if (connectionToClose != null) {
            try {
                connectionToClose.close();
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "Failed to close the active Pub/Sub connection", e);
            }
        }

        synchronized (inboundApplyGate) {
            // Fence any inbound balance apply that passed the initial running check.
        }

        boolean interrupted = false;
        if (threadToStop != null) {
            threadToStop.interrupt();
            while (threadToStop.isAlive()) {
                try {
                    threadToStop.join(10_000);
                    if (threadToStop.isAlive()) {
                        plugin.getLogger().severe("Pub/Sub callback is still active after 10 seconds; "
                                + "waiting before storage can be closed");
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    threadToStop.interrupt();
                }
            }
        }

        CMIPubsubHandler handler = cmiPubsubHandler;
        if (handler != null) {
            handler.shutdown();
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        plugin.getLogger().fine("Pub/Sub subscription stopped.");
    }

    public void setCmiPubsubHandler(CMIPubsubHandler cmiPubsubHandler) {
        this.cmiPubsubHandler = cmiPubsubHandler;
    }

    private void handleCMIMessage(String message) {
        if (stopped.get() || !running.get()) {
            return;
        }
        CMIPubsubHandler handler = this.cmiPubsubHandler;
        if (handler == null) {
            return;
        }
        try {
            PubSubMessage msg = PubSubMessage.fromCmiJson(message);
            if (msg == null) {
                plugin.getLogger().warning("Failed to parse CMI Pub/Sub message: " + message);
                return;
            }
            handler.process(msg);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing CMI Pub/Sub message: " + e.getMessage(), e);
        }
    }

    /**
     * Handle received message.
     *
     * [AsyncScheduler] This method runs on Pub/Sub subscriber thread.
     */
    private void handleMessage(String message) {
        if (stopped.get() || !running.get()) {
            return;
        }
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
            synchronized (inboundApplyGate) {
                if (stopped.get() || !running.get()) {
                    return;
                }
                boolean updated = economyFacade.updateMemoryState(uuid, balance, msg.getVersion());
                if (!updated) {
                    if (isDebugEnabled()) {
                        plugin.getLogger().fine("Pub/Sub: memory state not updated for " + uuid);
                    }
                    return;
                }
                cacheManager.updateMemoryCache(uuid, balance, msg.getVersion());
            }

            if (stopped.get() || !running.get()) {
                if (isDebugEnabled()) {
                    plugin.getLogger().fine("Pub/Sub: stopped before online notification for " + uuid);
                }
                return;
            }

            if (msg.getSourceServer() != null && !msg.getSourceServer().equals(config.getServerName())) {
                notifyPlayerIfOnline(uuid, balance, msg.getEventType(), msg.getAmount(), msg.getSourcePlayerName(), msg.getSourceServer());
            }

            plugin.getLogger().fine("Pub/Sub: processed update for " + uuid +
                " v" + msg.getVersion() + " balance " + FormatUtil.formatCurrency(balance));

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing Pub/Sub message: " + e.getMessage(), e);
        }
    }

    /**
     * If player is online, notify them of balance change (cross-server)
     * Sends notification message to target player
     *
     * [EntityScheduler] This method switches to player's EntityScheduler for execution.
     */
    private void notifyPlayerIfOnline(UUID uuid, BigDecimal newBalance, String eventType, double amount, String sourcePlayerName, String sourceServer) {
        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getScheduler().run(plugin, (task) -> {
            if (stopped.get() || !running.get()) {
                return;
            }
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String messageKey = MessageKeys.forEventType(eventType);

                if (messageKey != null) {
                    String message = syncMoneyPlugin.getMessage(messageKey);
                    if (message != null) {
                        message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                        if (amount != 0) {
                            message = message.replace("{amount}", FormatUtil.formatCurrency(Math.abs(amount)));
                        }
                        if (sourceServer != null) {
                            message = message.replace("{server}", sourceServer);
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
        return config.isDebug();
    }

    /**
     * Check if running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Legacy balance publisher retained for integrations that do not pass a version.
     * It publishes only when the supplied balance matches one current cached pair.
     */
    public void publishBalanceChange(UUID uuid, BigDecimal newBalance) {
        publishLegacyTransferEvent(uuid, newBalance, null, 0, null);
    }

    /**
     * Legacy transfer publisher retained for binary/reflection compatibility.
     * It publishes only when the supplied balance matches one current cached pair.
     */
    public void publishTransferEvent(UUID uuid, BigDecimal newBalance, String eventType,
                                     double amount, String sourcePlayerName) {
        publishLegacyTransferEvent(uuid, newBalance, eventType, amount, sourcePlayerName);
    }

    private void publishLegacyTransferEvent(UUID uuid, BigDecimal newBalance, String eventType,
                                            double amount, String sourcePlayerName) {
        if (!running.get() || uuid == null || newBalance == null || cacheManager == null) {
            return;
        }
        CacheManager.CachedBalance snapshot = cacheManager.getCachedBalanceSnapshot(uuid).orElse(null);
        if (snapshot == null || snapshot.balance().compareTo(newBalance) != 0) {
            return;
        }
        publishTransferEvent(uuid, newBalance, snapshot.version(), eventType, amount, sourcePlayerName);
    }

    /**
     * Publish an authority-confirmed transfer snapshot to cross-server channel.
     * The balance and version must come from the same atomic mutation result.
     *
     * @param uuid Player UUID
     * @param newBalance New balance after transfer
     * @param version Version paired with {@code newBalance} by the authority mutation
     * @param eventType Event type (TRANSFER_IN or TRANSFER_OUT)
     * @param amount Transfer amount
     * @param sourcePlayerName Name of the player who initiated the transfer (for TRANSFER_IN)
     */
    public void publishTransferEvent(UUID uuid, BigDecimal newBalance, long version,
                                     String eventType, double amount, String sourcePlayerName) {
        if (!running.get() || uuid == null || newBalance == null) {
            return;
        }
        try {
            PubSubMessage message = new PubSubMessage(
                    uuid.toString(),
                    newBalance,
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
