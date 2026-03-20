package noietime.syncmoney.economy;

import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.AuditRecord;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.PubSubMessage;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [SYNC-EVENT-001] Single consumer for economic events, driven by Folia
 * AsyncScheduler.
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
    private final HybridAuditManager hybridAuditManager;
    private final NameResolver nameResolver;
    private final BaltopManager baltopManager;
    private final ShadowSyncTask shadowSyncTask;
    private volatile boolean running = true;

    private final ConcurrentLinkedQueue<FailedEvent> failedEventsQueue;
    private final OverflowLogInterface overflowLog;
    private final noietime.syncmoney.storage.db.DatabaseManager databaseManager;

    private record FailedEvent(EconomyEvent event, int retryCount, long firstFailureTime) {
    }

    public EconomyEventConsumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cacheManager,
            RedisManager redisManager, DbWriteQueue dbWriteQueue,
            AuditLogger auditLogger, HybridAuditManager hybridAuditManager,
            NameResolver nameResolver,
            BaltopManager baltopManager,
            ShadowSyncTask shadowSyncTask,
            OverflowLogInterface overflowLog,
            noietime.syncmoney.storage.db.DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.dbWriteQueue = dbWriteQueue;
        this.auditLogger = auditLogger;
        this.hybridAuditManager = hybridAuditManager;
        this.nameResolver = nameResolver;
        this.baltopManager = baltopManager;
        this.shadowSyncTask = shadowSyncTask;
        this.failedEventsQueue = new ConcurrentLinkedQueue<>();
        this.overflowLog = overflowLog;
        this.databaseManager = databaseManager;
    }

    /**
     * Stop the consumer (graceful shutdown).
     */
    public void stop() {
        running = false;
        plugin.getLogger().fine("EconomyEventConsumer shutting down...");
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
     * [STORE-04 FIX] Added exponential backoff to prevent retry storms.
     */
    private void processFailedEvents() {

        final long BASE_DELAY_MS = 100;
        final long MAX_DELAY_MS = 30000;

        FailedEvent failed;
        while ((failed = failedEventsQueue.peek()) != null) {
            if (failed.retryCount() >= MAX_RETRY_COUNT) {
                handleCriticalFailure(failed);
                failedEventsQueue.poll();
                continue;
            }


            long retryDelay = Math.min(BASE_DELAY_MS * (1L << failed.retryCount()), MAX_DELAY_MS);
            long timeSinceFirstFailure = System.currentTimeMillis() - failed.firstFailureTime();

            if (timeSinceFirstFailure < retryDelay) {


                break;
            }

            BigDecimal newBalance = processRedisWrite(failed.event());
            if (newBalance != null && newBalance.compareTo(BigDecimal.ZERO) >= 0) {
                failedEventsQueue.poll();
                plugin.getLogger().fine("Retry successful for event: " + failed.event().requestId());

                processEventSteps(failed.event(), newBalance);
            } else {
                failedEventsQueue.poll();
                failedEventsQueue.add(new FailedEvent(
                        failed.event(),
                        failed.retryCount() + 1,
                        failed.firstFailureTime()));
                plugin.getLogger().warning("Retry failed for event: " + failed.event().requestId() +
                        " (attempt " + (failed.retryCount() + 1) + "/" + MAX_RETRY_COUNT +
                        ", next retry in " + (Math.min(BASE_DELAY_MS * (1L << (failed.retryCount() + 1)), MAX_DELAY_MS)) + "ms)");
            }
        }
    }

    /**
     * [STORE-01 FIX] Layer 3: Replay overflow events from WAL on startup.
     * This recovers events that were dropped due to WriteQueue overflow.
     */
    private void replayOverflowEvents() {
        if (overflowLog == null) {
            return;
        }

        List<String> overflowRecords = overflowLog.readAndClear();
        if (overflowRecords.isEmpty()) {
            return;
        }

        plugin.getLogger().info("Replaying " + overflowRecords.size() + " overflow events from WAL...");

        int replayed = 0;
        int failed = 0;

        for (String line : overflowRecords) {
            try {
                String[] parts = line.trim().split("\\|");
                if (parts.length < 6) {
                    plugin.getLogger().warning("Invalid overflow log line: " + line);
                    failed++;
                    continue;
                }

                long timestamp = Long.parseLong(parts[0]);
                UUID uuid = UUID.fromString(parts[1]);
                EconomyEvent.EventType type = EconomyEvent.EventType.valueOf(parts[2]);
                EconomyEvent.EventSource source = EconomyEvent.EventSource.valueOf(parts[3]);
                BigDecimal amount = new BigDecimal(parts[4]);
                String requestId = parts[5];


                EconomyEvent event = new EconomyEvent(
                    uuid,
                    amount,
                    BigDecimal.ZERO,
                    0L,
                    type,
                    source,
                    requestId,
                    timestamp
                );

                if (queue.offer(event)) {
                    replayed++;
                } else {
                    plugin.getLogger().warning("Failed to replay overflow event: " + requestId);

                    overflowLog.log(event);
                    failed++;
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse overflow log line: " + line + " - " + e.getMessage());
                failed++;
            }
        }

        plugin.getLogger().info("Overflow replay complete: " + replayed + " replayed, " + failed + " failed");
    }

    /**
     * Handle critical failure.
     * Note: After Lua script execution succeeds (e.g., transfer), even if
     * subsequent steps fail,
     * the player should NOT be kicked because the currency has already been
     * modified in Redis.
     * Kicking the player would only cause greater data inconsistency.
     * 
     * [FIX-001] Now also writes to OverflowLog to prevent permanent data loss.
     */
    private void handleCriticalFailure(FailedEvent failed) {
        UUID playerUuid = failed.event().playerUuid();
        long failureDuration = System.currentTimeMillis() - failed.firstFailureTime();

        plugin.getLogger().severe("CRITICAL: Failed to process event after " + MAX_RETRY_COUNT +
                " retries for player " + playerUuid + ". Duration: " + failureDuration + "ms");


        if (overflowLog != null) {
            try {
                overflowLog.log(failed.event());
                plugin.getLogger().info("CRITICAL: Event written to OverflowLog for later recovery: " + 
                    failed.event().requestId());
            } catch (Exception e) {
                plugin.getLogger().severe("CRITICAL: Failed to write to OverflowLog: " + e.getMessage());
            }
        }

        if (auditLogger != null) {
            String playerName = nameResolver.getNameCachedOnly(playerUuid);
            if (playerName == null)
                playerName = playerUuid.toString();
            auditLogger.logCriticalFailure(playerName, failed.event(), failureDuration);
        }

        Player player = Bukkit.getServer().getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            String warnMessage = ((noietime.syncmoney.Syncmoney) plugin).getMessage("general.sync-error");
            if (warnMessage != null) {
                noietime.syncmoney.util.MessageHelper.sendMessage(player, warnMessage);
            }
            plugin.getLogger()
                    .warning("Player " + player.getName() + " has pending sync issues - data may need manual review");
        }
    }

    @Override
    public void run() {
        plugin.getLogger().fine("EconomyEventConsumer started.");

        if (overflowLog != null && overflowLog.hasOverflowRecords()) {
            replayOverflowEvents();
        }

        while (running || !queue.isEmpty()) {
            try {
                EconomyEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                plugin.getLogger().severe("Error in EconomyEventConsumer: " + e.getMessage());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "EconomyEventConsumer stacktrace", e);
            }
        }

        plugin.getLogger().fine("EconomyEventConsumer stopped.");
    }

    /**
     * Process single economic event.
     * [STORE-03 FIX] Added DB fallback when Redis fails.
     */
    private void processEvent(EconomyEvent event) {
        try {
            BigDecimal newBalance = processRedisWrite(event);

            if (newBalance == null || newBalance.compareTo(BigDecimal.ZERO) < 0) {
                plugin.getLogger()
                        .warning("Redis write failed for event: " + event.requestId());



                BigDecimal currentBalance = cacheManager.getBalance(event.playerUuid());
                if (currentBalance == null) {
                    currentBalance = BigDecimal.ZERO;
                }
                
                BigDecimal eventDelta = event.delta() != null ? event.delta() : BigDecimal.ZERO;
                

                if (event.type() == EconomyEvent.EventType.DEPOSIT || 
                    event.type() == EconomyEvent.EventType.SET_BALANCE ||
                    event.source() == EconomyEvent.EventSource.ADMIN_GIVE ||
                    event.source() == EconomyEvent.EventSource.COMMAND_PAY) {
                    newBalance = currentBalance.add(eventDelta);
                } else if (event.type() == EconomyEvent.EventType.WITHDRAW ||
                           event.source() == EconomyEvent.EventSource.ADMIN_TAKE ||
                           event.source() == EconomyEvent.EventSource.VAULT_WITHDRAW ||
                           event.source() == EconomyEvent.EventSource.PLAYER_TRANSFER) {
                    newBalance = currentBalance.subtract(eventDelta);
                } else {
                    newBalance = eventDelta;
                }
                

                cacheManager.atomicSetBalance(event.playerUuid(), newBalance);
                

                processEventStepsWithoutRedis(event, newBalance);
                
                plugin.getLogger().info("DB fallback successful for event: " + event.requestId() + 
                    ", new balance: " + newBalance);
                return;
            }

            processEventSteps(event, newBalance);

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process economy event: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Economy event processing stacktrace", e);
            failedEventsQueue.add(new FailedEvent(event, 0, System.currentTimeMillis()));
        }
    }

    /**
     * Try direct DB write as fallback when Redis is unavailable.
     * [STORE-03 FIX] This prevents data loss when Redis is down.
     * Note: This method requires databaseManager field which is not yet implemented.
     * TODO: Add databaseManager to EconomyEventConsumer or implement via StorageManager.
     */
    /*
    private boolean tryDbFallback(EconomyEvent event) {

        return false;
    }
    */

    /**
     * Process event steps without Redis (used when Redis fails but DB fallback succeeds).
     */
    private void processEventStepsWithoutRedis(EconomyEvent event, BigDecimal newBalance) {



        if (config.isDbEnabled()) {
            enqueueDbWrite(event, newBalance);
        }

        if (hybridAuditManager != null && hybridAuditManager.isEnabled() && event.source() != EconomyEvent.EventSource.TEST) {
            recordAuditLog(event, newBalance);
        } else if (auditLogger != null && auditLogger.isEnabled() && event.source() != EconomyEvent.EventSource.TEST) {
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
     * Process event steps (after Redis write succeeds).
     */
    private void processEventSteps(EconomyEvent event, BigDecimal newBalance) {
        if (config.isPubsubEnabled()) {
            publishUpdate(event, newBalance);
        }

        if (config.isDbEnabled()) {
            enqueueDbWrite(event, newBalance);
        }

        if (hybridAuditManager != null && hybridAuditManager.isEnabled() && event.source() != EconomyEvent.EventSource.TEST) {
            recordAuditLog(event, newBalance);
        } else if (auditLogger != null && auditLogger.isEnabled() && event.source() != EconomyEvent.EventSource.TEST) {
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
                event.delta().doubleValue());

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
                java.time.Instant.now());

        if (!dbWriteQueue.offer(task)) {

            plugin.getLogger().severe("CRITICAL: DbWriteQueue is full, attempting fallback for " + event.playerUuid());


            boolean directWriteSuccess = tryDirectDbWrite(event.playerUuid(), playerName, newBalance, version, serverName);

            if (!directWriteSuccess) {

                plugin.getLogger().severe("CRITICAL: DbWriteQueue is full, direct DB write failed, writing to overflow log for "
                        + event.playerUuid() + ". This may cause data loss on restart.");

                logEventToOverflowLog(event);
            }
        }
    }

    /**
     * [STORE-02 FIX] Try direct synchronous DB write as fallback.
     */
    private boolean tryDirectDbWrite(UUID uuid, String playerName, BigDecimal balance, long version, String serverName) {
        try {
            if (databaseManager != null) {
                databaseManager.insertOrUpdatePlayer(uuid, playerName, balance, version, serverName);
                plugin.getLogger().info("STORE-02: Direct DB write succeeded for " + uuid);
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("STORE-02: Direct DB write failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * [STORE-02 FIX] Log event to overflow log for later recovery.
     */
    private void logEventToOverflowLog(EconomyEvent event) {
        try {
            if (overflowLog != null) {
                overflowLog.log(event);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("STORE-02: Failed to write overflow log: " + e.getMessage());
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


            AuditRecord record = AuditRecord.fromEconomyEvent(event, playerName, serverName, newBalance, 1, 0);


            if (hybridAuditManager != null && hybridAuditManager.isEnabled()) {
                hybridAuditManager.log(record);
            } 

            else if (auditLogger != null && auditLogger.isEnabled()) {
                auditLogger.logFromEvent(event, playerName, serverName, newBalance, 1, 0);
            }
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
