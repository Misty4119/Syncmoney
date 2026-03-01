package noietime.syncmoney.economy;

import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.PubSubMessage;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [SYNC-EVENT-001] Single consumer for economic events, driven by Folia AsyncScheduler.
 * Processes events in sequence:
 * 1. Redis atomic write
 * 2. Pub/Sub publish
 * 3. DB queue enqueue
 *
 * [AsyncScheduler] This thread is driven by Folia AsyncScheduler.
 */
public final class EconomyEventConsumer implements Runnable {

    private static final String PUBSUB_CHANNEL = "syncmoney:balance:update";
    private static final int MAX_RETRY_COUNT = 3;

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final EconomyWriteQueue queue;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final DbWriteQueue dbWriteQueue;
    private final AuditLogger auditLogger;
    private final NameResolver nameResolver;
    private final BaltopManager baltopManager;
    private final ShadowSyncTask shadowSyncTask;
    private volatile boolean running = true;


    private final ConcurrentLinkedQueue<FailedEvent> failedEventsQueue;


    private record FailedEvent(EconomyEvent event, int retryCount, long firstFailureTime) {}

    public EconomyEventConsumer(Plugin plugin, SyncmoneyConfig config,
                               EconomyWriteQueue queue, CacheManager cacheManager,
                               RedisManager redisManager, DbWriteQueue dbWriteQueue,
                               AuditLogger auditLogger, NameResolver nameResolver,
                               BaltopManager baltopManager,
                               ShadowSyncTask shadowSyncTask) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.dbWriteQueue = dbWriteQueue;
        this.auditLogger = auditLogger;
        this.nameResolver = nameResolver;
        this.baltopManager = baltopManager;
        this.shadowSyncTask = shadowSyncTask;
        this.failedEventsQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Stop the consumer (graceful shutdown).
     */
    public void stop() {
        running = false;
        plugin.getLogger().info("EconomyEventConsumer shutting down...");
    }

    /**
     * Check if consumer is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Process pending events (driven by Folia AsyncScheduler).
     * Processes all available events in the queue on each invocation.
     *
     * [AsyncScheduler] This method is called periodically by Folia AsyncScheduler.
     */
    public void processPending() {
        processFailedEvents();

        while (running || !queue.isEmpty()) {
            try {
                EconomyEvent event = queue.poll();
                if (event != null) {
                    processEvent(event);
                } else {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Process failed event retries.
     */
    private void processFailedEvents() {
        FailedEvent failed;
        while ((failed = failedEventsQueue.peek()) != null) {
            if (failed.retryCount() >= MAX_RETRY_COUNT) {
                handleCriticalFailure(failed);
                failedEventsQueue.poll();
                continue;
            }

            BigDecimal newBalance = processRedisWrite(failed.event());
            if (newBalance != null && newBalance.compareTo(BigDecimal.ZERO) >= 0) {
                failedEventsQueue.poll();
                plugin.getLogger().info("Retry successful for event: " + failed.event().requestId());

                processEventSteps(failed.event(), newBalance);
            } else {
                failedEventsQueue.poll();
                failedEventsQueue.add(new FailedEvent(
                    failed.event(),
                    failed.retryCount() + 1,
                    failed.firstFailureTime()
                ));
                plugin.getLogger().warning("Retry failed for event: " + failed.event().requestId() +
                    " (attempt " + (failed.retryCount() + 1) + "/" + MAX_RETRY_COUNT + ")");
            }
        }
    }

    /**
     * Handle critical failure.
     * Note: After Lua script execution succeeds (e.g., transfer), even if subsequent steps fail,
     * the player should NOT be kicked because the currency has already been modified in Redis.
     * Kicking the player would only cause greater data inconsistency.
     */
    private void handleCriticalFailure(FailedEvent failed) {
        UUID playerUuid = failed.event().playerUuid();
        long failureDuration = System.currentTimeMillis() - failed.firstFailureTime();

        plugin.getLogger().severe("CRITICAL: Failed to process event after " + MAX_RETRY_COUNT +
            " retries for player " + playerUuid + ". Duration: " + failureDuration + "ms");

        if (auditLogger != null) {
            String playerName = nameResolver.getNameCachedOnly(playerUuid);
            if (playerName == null) playerName = playerUuid.toString();
            auditLogger.logCriticalFailure(playerName, failed.event(), failureDuration);
        }

        Player player = Bukkit.getServer().getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            String warnMessage = ((noietime.syncmoney.Syncmoney) plugin).getMessage("general.sync-error");
            if (warnMessage != null) {
                noietime.syncmoney.util.MessageHelper.sendMessage(player, warnMessage);
            }
            plugin.getLogger().warning("Player " + player.getName() + " has pending sync issues - data may need manual review");
        }
    }

    @Override
    public void run() {
        plugin.getLogger().info("EconomyEventConsumer started.");

        while (running || !queue.isEmpty()) {
            try {
                EconomyEvent event = queue.poll();

                if (event != null) {
                    processEvent(event);
                } else {
                    // Use poll with timeout instead of Thread.sleep to avoid busy-waiting
                    queue.poll(10, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                plugin.getLogger().severe("Error in EconomyEventConsumer: " + e.getMessage());
                e.printStackTrace();
            }
        }

        plugin.getLogger().info("EconomyEventConsumer stopped.");
    }

    /**
     * Process single economic event.
     */
    private void processEvent(EconomyEvent event) {
        try {
            BigDecimal newBalance = processRedisWrite(event);

            if (newBalance == null || newBalance.compareTo(BigDecimal.ZERO) < 0) {
                // Redis write failed, adding to retry queue
                plugin.getLogger().warning("Redis write failed for event: " + event.requestId() + ", adding to retry queue");
                failedEventsQueue.add(new FailedEvent(event, 0, System.currentTimeMillis()));
                return;
            }

            processEventSteps(event, newBalance);

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process economy event: " + e.getMessage());
            e.printStackTrace();
            failedEventsQueue.add(new FailedEvent(event, 0, System.currentTimeMillis()));
        }
    }

    /**
     * Process event steps (after Redis write succeeds).
     */
    private void processEventSteps(EconomyEvent event, BigDecimal newBalance) {
        if (config.isPubsubEnabled()) {
            publishUpdate(event, newBalance);
        }

        if (config.isDbEnabled()) {
            enqueueDbWrite(event, newBalance);
        }

        if (auditLogger != null && auditLogger.isEnabled() && event.source() != EconomyEvent.EventSource.TEST) {
            recordAuditLog(event, newBalance);
        }

        if (baltopManager != null && event.source() != EconomyEvent.EventSource.TEST) {
            updateBaltop(event.playerUuid(), newBalance.doubleValue());
        }

        if (shadowSyncTask != null && config.isShadowSyncEnabled() && event.source() != EconomyEvent.EventSource.TEST) {
            String playerName = nameResolver.getNameCachedOnly(event.playerUuid());
            if (playerName != null) {
                shadowSyncTask.enqueueSyncEvent(event.playerUuid(), playerName, newBalance);
            }
        }
    }

    /**
     * Execute Redis atomic write.
     */
    private BigDecimal processRedisWrite(EconomyEvent event) {
        BigDecimal newBalance;

        switch (event.type()) {
            case DEPOSIT, WITHDRAW -> {
                newBalance = cacheManager.atomicAddBalance(event.playerUuid(), event.delta());
            }
            case SET_BALANCE -> {
                long version = cacheManager.atomicSetBalance(event.playerUuid(), event.balanceAfter());
                newBalance = version > 0 ? event.balanceAfter() : null;
            }
            case TRANSFER_IN -> {
                newBalance = cacheManager.getBalance(event.playerUuid());
            }
            case TRANSFER_OUT -> {
                newBalance = cacheManager.getBalance(event.playerUuid());
            }
            default -> newBalance = null;
        }

        return newBalance;
    }

    /**
     * Publish Pub/Sub message.
     */
    private void publishUpdate(EconomyEvent event, BigDecimal newBalance) {
        long version = cacheManager.getVersion(event.playerUuid());
        String serverName = config.getServerName();
        String messageId = event.requestId();
        long timestamp = event.timestamp();

        PubSubMessage message = new PubSubMessage(
                event.playerUuid().toString(),
                newBalance.doubleValue(),
                version,
                serverName != null ? serverName : "unknown",
                messageId,
                timestamp,
                event.type().name(),
                event.delta().doubleValue()
        );

        try (var jedis = redisManager.getResource()) {
            jedis.publish(PUBSUB_CHANNEL, message.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish Pub/Sub: " + e.getMessage());
        }
    }

    /**
     * Enqueue DB write operation.
     */
    private void enqueueDbWrite(EconomyEvent event, BigDecimal newBalance) {
        long version = cacheManager.getVersion(event.playerUuid());
        String serverName = config.getServerName();

        String playerName = nameResolver.getNameCachedOnly(event.playerUuid());

        var task = new DbWriteQueue.DbWriteTask(
                event.playerUuid(),
                playerName,
                newBalance.doubleValue(),
                version,
                serverName != null ? serverName : "unknown",
                java.time.Instant.now()
        );

        if (!dbWriteQueue.offer(task)) {
            plugin.getLogger().warning("DbWriteQueue is full, write deferred for " + event.playerUuid());
        }
    }

    /**
     * Record audit log.
     */
    private void recordAuditLog(EconomyEvent event, BigDecimal newBalance) {
        try {
            String playerName = nameResolver.getNameCachedOnly(event.playerUuid());
            if (playerName == null) {
                playerName = "Unknown";
            }
            String serverName = config.getServerName() != null ? config.getServerName() : "unknown";

            auditLogger.logFromEvent(event, playerName, serverName);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to record audit log: " + e.getMessage());
        }
    }

    /**
     * Update baltop rankings.
     */
    private void updateBaltop(UUID uuid, double newBalance) {
        try {
            baltopManager.updatePlayerRank(uuid, newBalance);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update baltop: " + e.getMessage());
        }
    }
}
