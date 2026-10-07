package noietime.syncmoney.economy;

import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.AuditRecord;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private final ConcurrentMap<String, FailedEvent> failedEvents;
    private volatile ExecutorService executor;
    private volatile Thread workerThread;
    private final OverflowLogInterface overflowLog;
    private final noietime.syncmoney.storage.db.DatabaseManager databaseManager;

    private record FailedEvent(EconomyEvent event, EconomyEvent partner, int retryCount, long firstFailureTime,
            ReplicationReceipt confirmedReceipt, boolean quarantined, boolean reconciliationOnly) {
        private FailedEvent(EconomyEvent event, int retryCount, long firstFailureTime,
                ReplicationReceipt confirmedReceipt) {
            this(event, null, retryCount, firstFailureTime, confirmedReceipt, false, false);
        }

        private FailedEvent(EconomyEvent event, EconomyEvent partner, int retryCount,
                long firstFailureTime, ReplicationReceipt confirmedReceipt) {
            this(event, partner, retryCount, firstFailureTime, confirmedReceipt, false, false);
        }

        private FailedEvent(EconomyEvent event, int retryCount, long firstFailureTime,
                ReplicationReceipt confirmedReceipt, boolean quarantined) {
            this(event, null, retryCount, firstFailureTime, confirmedReceipt, quarantined, false);
        }

        private FailedEvent withConfirmedReceipt(ReplicationReceipt receipt) {
            return new FailedEvent(event, partner, retryCount, firstFailureTime, receipt, false, false);
        }

        private FailedEvent withReconciliationReceipt(ReplicationReceipt receipt) {
            return new FailedEvent(event, partner, retryCount, firstFailureTime, receipt, false, true);
        }

        private FailedEvent nextAttempt() {
            return new FailedEvent(event, partner, Math.min(retryCount + 1, MAX_RETRY_COUNT), firstFailureTime,
                    confirmedReceipt, quarantined, reconciliationOnly);
        }

        private FailedEvent asQuarantined() {
            return new FailedEvent(event, partner, retryCount, firstFailureTime,
                    confirmedReceipt, true, reconciliationOnly);
        }
    }

    private record TransferWriteResult(ReplicationReceipt receipt, boolean applied) { }

    private enum TransferResolution {
        CONFIRMED,
        REJECTED,
        UNKNOWN,
        CONFIRMED_QUARANTINED
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
        this.failedEvents = new ConcurrentHashMap<>();
        this.overflowLog = overflowLog;
        this.databaseManager = databaseManager;
    }

    /**
     * Start the consumer on a dedicated single-thread executor.
     * Idempotent: subsequent calls are ignored once the consumer is running.
     */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Syncmoney-EventConsumer");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this);
    }

    /**
     * Signal the consumer to stop (cooperative, non-blocking).
     */
    public void stop() {
        running = false;
        plugin.getLogger().fine("EconomyEventConsumer shutting down...");
    }

    public void shutdown() {
        // Stop new producers before draining. Reservations accepted before closure may
        // still commit, so the worker remains alive until their ownership is released.
        queue.closeAdmission();
        running = false;
        plugin.getLogger().fine("EconomyEventConsumer shutting down...");

        ExecutorService exec = this.executor;
        if (exec != null) {
            // Let the single worker drain accepted writes before interrupting it.
            exec.shutdown();
            if (Thread.currentThread() == workerThread) return;
            try {
                if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe(
                            "EconomyEventConsumer drain timed out; interrupting worker with pending writes");
                    exec.shutdownNow();
                    exec.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
                plugin.getLogger().severe("Interrupted while awaiting EconomyEventConsumer executor termination");
            }
        }
    }

    /**
     * Check if consumer is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Process failed event retries.
     * [STORE-04 FIX] Added exponential backoff to prevent retry storms.
     */
    private void processFailedEvents() {
        if (failedEvents.isEmpty()) {
            return;
        }

        final long BASE_DELAY_MS = 100;
        final long MAX_DELAY_MS = 30000;
        final long now = System.currentTimeMillis();

        for (Map.Entry<String, FailedEvent> entry : failedEvents.entrySet()) {
            String key = entry.getKey();
            FailedEvent failed = entry.getValue();

            if (failed.quarantined()) {
                continue;
            }

            if (failed.partner() != null) {
                processFailedTransfer(key, failed, now, BASE_DELAY_MS, MAX_DELAY_MS);
                continue;
            }

            if (failed.reconciliationOnly()) {
                processRejectedEventReconciliation(key, failed, now, BASE_DELAY_MS, MAX_DELAY_MS, false);
                continue;
            }

            if (failed.retryCount() >= MAX_RETRY_COUNT) {
                handleCriticalFailure(failed);
                failedEvents.remove(key, failed);
                continue;
            }

            long retryDelay = Math.min(BASE_DELAY_MS * (1L << failed.retryCount()), MAX_DELAY_MS);
            long timeSinceFirstFailure = now - failed.firstFailureTime();

            if (timeSinceFirstFailure < retryDelay) {
                // Per-event backoff: skip this one for now, keep scanning the rest.
                continue;
            }

            FailedEvent retryState = failed;
            try {
                ReplicationReceipt receipt = retryState.confirmedReceipt();
                if (receipt == null) {
                    receipt = processRedisWrite(failed.event());
                    if (receipt == null) {
                        FailedEvent nextAttempt = failed.nextAttempt();
                        failedEvents.replace(key, failed, nextAttempt);
                        logRetryFailure(failed, nextAttempt, BASE_DELAY_MS, MAX_DELAY_MS);
                        continue;
                    }

                    // Keep the exact authority receipt before any downstream operation can throw.
                    retryState = failed.withConfirmedReceipt(receipt);
                    if (!failedEvents.replace(key, failed, retryState)) {
                        continue;
                    }
                }

                processEventSteps(failed.event(), receipt);
                ConfirmedAccount account = receipt.accountFor(failed.event().playerUuid());
                queue.complete(failed.event(), account.balance(), account.version());
                failedEvents.remove(key, retryState);
                plugin.getLogger().fine("Retry successful for event: " + failed.event().requestId());
            } catch (RedisMutationException e) {
                if (retryState.confirmedReceipt() == null && requiresQuarantine(e.executionState())) {
                    FailedEvent quarantined = retryState.asQuarantined();
                    queue.markAccountUnavailable(failed.event().playerUuid());
                    if (!failedEvents.replace(key, retryState, quarantined)) {
                        failedEvents.putIfAbsent(key, quarantined);
                    }
                    plugin.getLogger().severe("Quarantined accepted economy event after Redis "
                            + e.executionState() + "; automatic retry is disabled for request "
                            + failed.event().requestId());
                    continue;
                }
                FailedEvent nextAttempt = retryState.nextAttempt();
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(failed, nextAttempt, BASE_DELAY_MS, MAX_DELAY_MS);
            } catch (Exception e) {
                FailedEvent nextAttempt = retryState.nextAttempt();
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(failed, nextAttempt, BASE_DELAY_MS, MAX_DELAY_MS);
            }
        }
    }

    private void processRejectedEventReconciliation(String key, FailedEvent failed, long now,
            long baseDelayMillis, long maxDelayMillis, boolean initialAttempt) {
        long retryDelay = Math.min(baseDelayMillis * (1L << Math.min(failed.retryCount(), 30)), maxDelayMillis);
        if (!initialAttempt && now - failed.firstFailureTime() < retryDelay) return;

        FailedEvent retryState = failed;
        try {
            ReplicationReceipt receipt = retryState.confirmedReceipt();
            if (receipt == null) {
                receipt = readRejectedAuthoritySnapshot(failed.event());
                retryState = failed.withReconciliationReceipt(receipt);
                if (!failedEvents.replace(key, failed, retryState)) return;
            }

            persistRejectedSnapshot(retryState.event(), receipt);
            ConfirmedAccount account = receipt.accountFor(retryState.event().playerUuid());
            if (!queue.completeRejected(retryState.event(), account.balance(), account.version())) {
                throw new IllegalStateException("Rejected accepted write no longer has queue ownership: "
                        + retryState.event().requestId());
            }
            failedEvents.remove(key, retryState);
            plugin.getLogger().fine("Reconciled Redis-rejected economy event without replay: "
                    + retryState.event().requestId());
        } catch (Exception e) {
            FailedEvent nextAttempt = retryState.nextAttempt();
            if (!failedEvents.replace(key, retryState, nextAttempt)) {
                failedEvents.putIfAbsent(key, nextAttempt);
            }
            logRetryFailure(retryState, nextAttempt, baseDelayMillis, maxDelayMillis);
        }
    }

    private ReplicationReceipt readRejectedAuthoritySnapshot(EconomyEvent event) {
        CacheManager.CachedBalance snapshot = cacheManager.readAuthorityBalanceSnapshotStrict(event.playerUuid())
                .orElseThrow(() -> new IllegalStateException(
                        "Redis has no confirmed account snapshot for rejected write " + event.requestId()));
        ConfirmedAccount account = new ConfirmedAccount(event.playerUuid(), snapshot.balance(), snapshot.version());
        return new ReplicationReceipt("reconcile:" + operationId(event), List.of(account));
    }

    private void persistRejectedSnapshot(EconomyEvent event, ReplicationReceipt receipt) {
        if (!config.isDbEnabled()) return;
        ConfirmedAccount account = receipt.accountFor(event.playerUuid());
        String serverName = config.getServerName() != null ? config.getServerName() : "unknown";
        java.time.Instant timestamp = java.time.Instant.now();
        DbWriteQueue.DbWriteTask task = new DbWriteQueue.DbWriteTask(receipt.operationId(), account,
                nameResolver.getNameCachedOnly(account.playerUuid()), serverName, timestamp);
        if (dbWriteQueue != null && dbWriteQueue.offer(task)) return;
        if (databaseManager == null) {
            throw new IllegalStateException("Rejected authority snapshot has no SQL persistence owner");
        }

        List<DbWriteQueue.DbWriteTask> stale = databaseManager.batchInsertOrUpdatePlayersWithStale(List.of(task));
        for (int attempt = 0; !stale.isEmpty() && attempt < 2; attempt++) {
            ReplicationReceipt latest = readRejectedAuthoritySnapshot(event);
            ConfirmedAccount latestAccount = latest.accountFor(event.playerUuid());
            task = new DbWriteQueue.DbWriteTask(latest.operationId(), latestAccount,
                    nameResolver.getNameCachedOnly(latestAccount.playerUuid()), serverName, timestamp);
            stale = databaseManager.batchInsertOrUpdatePlayersWithStale(List.of(task));
        }
        if (!stale.isEmpty()) {
            throw new IllegalStateException("Latest rejected-write authority snapshot is still stale in SQL");
        }
    }

    private void processFailedTransfer(String key, FailedEvent failed, long now,
            long baseDelayMillis, long maxDelayMillis) {
        if (failed.retryCount() >= MAX_RETRY_COUNT && !failed.reconciliationOnly()) {
            quarantineTransfer(key, failed, "transfer downstream work did not reach a confirmed receipt");
            return;
        }
        long retryDelay = Math.min(baseDelayMillis * (1L << Math.min(failed.retryCount(), 30)), maxDelayMillis);
        if (now - failed.firstFailureTime() < retryDelay) return;

        FailedEvent retryState = failed;
        try {
            if (retryState.confirmedReceipt() == null) {
                if (retryState.reconciliationOnly()) {
                    retryState = failed.withReconciliationReceipt(
                            readTransferAuthorityPair(failed.event(), failed.partner()));
                } else {
                    TransferWriteResult write = executeTransferWrite(failed.event(), failed.partner());
                    if (write.applied()) {
                        retryState = failed.withConfirmedReceipt(write.receipt());
                    } else {
                        ReplicationReceipt receipt = write.receipt() != null
                                ? write.receipt()
                                : readTransferAuthorityPair(failed.event(), failed.partner());
                        retryState = failed.withReconciliationReceipt(receipt);
                    }
                }
                if (!failedEvents.replace(key, failed, retryState)) return;
            }

            if (retryState.reconciliationOnly()) {
                persistTransferPair(retryState.event(), retryState.partner(), retryState.confirmedReceipt());
            } else {
                processTransferEventSteps(retryState.event(), retryState.partner(), retryState.confirmedReceipt());
            }
            completeTransferPair(retryState.event(), retryState.partner(), retryState.confirmedReceipt(),
                    retryState.reconciliationOnly());
            failedEvents.remove(key, retryState);
            plugin.getLogger().fine("Retry successful for transfer: "
                    + TransferRequestIds.operationId(retryState.event()));
        } catch (RedisMutationException e) {
            if (retryState.reconciliationOnly()
                    || e.executionState() == RedisMutationException.ExecutionState.NOT_SENT
                            && retryState.confirmedReceipt() == null) {
                FailedEvent nextAttempt = retryState.nextAttempt();
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(retryState, nextAttempt, baseDelayMillis, maxDelayMillis);
            } else if (e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                FailedEvent rejected = retryState.withReconciliationReceipt(null);
                if (failedEvents.replace(key, retryState, rejected)) {
                    processFailedTransfer(key, rejected, now + baseDelayMillis,
                            baseDelayMillis, maxDelayMillis);
                }
            } else {
                quarantineTransfer(key, retryState,
                        "transfer authority outcome is " + e.executionState());
            }
        } catch (Exception e) {
            if (retryState.confirmedReceipt() == null && !retryState.reconciliationOnly()) {
                quarantineTransfer(key, retryState,
                        "transfer authority reconciliation failed: " + e.getMessage());
            } else {
                FailedEvent nextAttempt = retryState.nextAttempt();
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(retryState, nextAttempt, baseDelayMillis, maxDelayMillis);
            }
        }
    }

    private void quarantineTransfer(String key, FailedEvent failed, String reason) {
        markTransferUnavailable(failed.event(), failed.partner());
        FailedEvent quarantined = failed.asQuarantined();
        if (!failedEvents.replace(key, failed, quarantined)) {
            failedEvents.putIfAbsent(key, quarantined);
        }
        ReplicationReceipt receipt = failed.confirmedReceipt();
        TransferResolution resolution = failed.reconciliationOnly()
                ? TransferResolution.REJECTED
                : receipt != null ? TransferResolution.CONFIRMED_QUARANTINED : TransferResolution.UNKNOWN;
        queue.completeTransferObservation(TransferRequestIds.operationId(failed.event()),
                switch (resolution) {
                    case CONFIRMED -> EconomyWriteQueue.TransferOutcome.CONFIRMED;
                    case REJECTED -> EconomyWriteQueue.TransferOutcome.REJECTED;
                    case UNKNOWN -> EconomyWriteQueue.TransferOutcome.UNKNOWN;
                    case CONFIRMED_QUARANTINED -> EconomyWriteQueue.TransferOutcome.CONFIRMED_QUARANTINED;
                },
                receipt == null || failed.partner() == null ? null
                        : receipt.accountFor(failed.event().type() == EconomyEvent.EventType.TRANSFER_OUT
                                ? failed.event().playerUuid() : failed.partner().playerUuid()).balance(),
                receipt == null || failed.partner() == null ? null
                        : receipt.accountFor(failed.event().type() == EconomyEvent.EventType.TRANSFER_IN
                                ? failed.event().playerUuid() : failed.partner().playerUuid()).balance());
        publishTransferPostEvents(failed.event(), failed.partner(), receipt, resolution);
        plugin.getLogger().severe("Quarantined accepted transfer pair "
                + TransferRequestIds.operationId(failed.event()) + "; both accounts remain unavailable: " + reason);
    }

    private void logRetryFailure(FailedEvent failed, FailedEvent nextAttempt,
            long baseDelayMs, long maxDelayMs) {
        long nextRetryDelay = Math.min(baseDelayMs * (1L << nextAttempt.retryCount()), maxDelayMs);
        plugin.getLogger().warning("Retry failed for event: " + failed.event().requestId() +
                " (attempt " + nextAttempt.retryCount() + "/" + MAX_RETRY_COUNT +
                ", next retry in " + nextRetryDelay + "ms)");
    }

    private static String failedKey(EconomyEvent event) {
        String id = event.requestId();
        return id != null && !id.isBlank() ? id : operationId(event);
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

        noietime.syncmoney.util.PlayerLookupUtil.runForPlayer(plugin, playerUuid, player -> {
            if (player != null && player.isOnline()) {
                String warnMessage = ((noietime.syncmoney.Syncmoney) plugin).getMessage("general.sync-error");
                if (warnMessage != null) {
                    noietime.syncmoney.util.MessageHelper.sendMessage(player, warnMessage);
                }
                plugin.getLogger()
                        .warning("Player " + player.getName() + " has pending sync issues - data may need manual review");
            }
        });
    }

    @Override
    public void run() {
        this.workerThread = Thread.currentThread();
        plugin.getLogger().fine("EconomyEventConsumer started.");

        if (overflowLog != null && overflowLog.hasOverflowRecords()) {
            replayOverflowEvents();
        }

        while (running || !queue.isEmpty() || queue.hasOutstandingWrites()) {
            try {
                if (isRedisDegraded()) {
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }

                processFailedEvents();
                if (!failedEvents.isEmpty()) {
                    // Preserve the single consumer's FIFO order while earlier accepted work is unresolved.
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }

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

    private boolean isRedisDegraded() {
        return redisManager != null && redisManager.isDegraded();
    }

    /**
     * Process one accepted economic event. Redis remains authoritative for shared modes;
     * failed work is retained for retry rather than applied through a SQL fallback.
     */
    private void processEvent(EconomyEvent event) {
        if (isTransferType(event)) {
            processTransferEvent(event);
            return;
        }
        ReplicationReceipt confirmedReceipt = null;
        long firstFailureTime = System.currentTimeMillis();
        try {
            ReplicationReceipt receipt = processRedisWrite(event);

            if (receipt == null) {
                plugin.getLogger().warning("Redis write failed for accepted event " + event.requestId()
                        + "; retaining it for retry after authority recovery");
                failedEvents.putIfAbsent(failedKey(event),
                        new FailedEvent(event, 0, firstFailureTime, null));
                return;
            }

            confirmedReceipt = receipt;
            processEventSteps(event, receipt);
            ConfirmedAccount account = receipt.accountFor(event.playerUuid());
            queue.complete(event, account.balance(), account.version());

        } catch (RedisMutationException e) {
            if (confirmedReceipt == null && requiresQuarantine(e.executionState())) {
                queue.markAccountUnavailable(event.playerUuid());
                if (e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                    String key = failedKey(event);
                    FailedEvent rejected = new FailedEvent(event, null, 0, firstFailureTime,
                            null, false, true);
                    FailedEvent existing = failedEvents.putIfAbsent(key, rejected);
                    processRejectedEventReconciliation(key, existing == null ? rejected : existing,
                            System.currentTimeMillis(), 100L, 30000L, true);
                } else {
                    failedEvents.putIfAbsent(failedKey(event),
                            new FailedEvent(event, 0, firstFailureTime, null, true));
                    plugin.getLogger().severe("Quarantined accepted economy event after Redis "
                            + e.executionState() + "; automatic retry is disabled for request " + event.requestId());
                }
                return;
            }
            plugin.getLogger().severe("Failed to process economy event: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Economy event processing stacktrace", e);
            failedEvents.putIfAbsent(failedKey(event),
                    new FailedEvent(event, 0, firstFailureTime, confirmedReceipt));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process economy event: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Economy event processing stacktrace", e);
            failedEvents.putIfAbsent(failedKey(event),
                    new FailedEvent(event, 0, firstFailureTime, confirmedReceipt));
        }
    }

    private void processTransferEvent(EconomyEvent first) {
        long firstFailureTime = System.currentTimeMillis();
        EconomyEvent second;
        try {
            second = queue.poll(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            second = null;
        }
        if (!TransferRequestIds.arePair(first, second)) {
            FailedEvent orphan = new FailedEvent(first, second, 0, firstFailureTime,
                    null, true, false);
            markTransferUnavailable(first, second);
            failedEvents.putIfAbsent(failedTransferKey(first), orphan);
            publishTransferPostEvents(first, second, null, TransferResolution.UNKNOWN);
            queue.completeTransferObservation(TransferRequestIds.operationId(first),
                    EconomyWriteQueue.TransferOutcome.UNKNOWN, null, null);
            plugin.getLogger().severe("Accepted transfer did not have its matching queue leg; both accounts are unavailable: "
                    + TransferRequestIds.operationId(first));
            return;
        }

        EconomyEvent out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : second;
        EconomyEvent in = out == first ? second : first;
        String key = failedTransferKey(out);
        try {
            TransferWriteResult write = executeTransferWrite(out, in);
            if (write.applied()) {
                try {
                    processTransferEventSteps(out, in, write.receipt());
                    completeTransferPair(out, in, write.receipt(), false);
                } catch (Exception e) {
                    failedEvents.putIfAbsent(key, new FailedEvent(out, in, 0, firstFailureTime,
                            write.receipt(), false, false));
                    plugin.getLogger().severe("Confirmed transfer pair retained for downstream retry: "
                            + TransferRequestIds.operationId(out) + " - " + e.getMessage());
                }
            } else {
                markTransferUnavailable(out, in);
                FailedEvent rejected = new FailedEvent(out, in, 0, firstFailureTime,
                        write.receipt(), false, true);
                FailedEvent existing = failedEvents.putIfAbsent(key, rejected);
                processFailedTransfer(key, existing == null ? rejected : existing,
                        System.currentTimeMillis() + 100L, 100L, 30000L);
            }
        } catch (RedisMutationException e) {
            if (e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                markTransferUnavailable(out, in);
                FailedEvent rejected = new FailedEvent(out, in, 0, firstFailureTime,
                        null, false, true);
                FailedEvent existing = failedEvents.putIfAbsent(key, rejected);
                processFailedTransfer(key, existing == null ? rejected : existing,
                        System.currentTimeMillis() + 100L, 100L, 30000L);
            } else if (e.executionState() == RedisMutationException.ExecutionState.NOT_SENT) {
                failedEvents.putIfAbsent(key, new FailedEvent(out, in, 0, firstFailureTime, null));
                plugin.getLogger().warning("Transfer pair was not sent to Redis; retaining both accepted legs for retry: "
                        + TransferRequestIds.operationId(out));
            } else {
                quarantineTransfer(key, new FailedEvent(out, in, 0, firstFailureTime,
                        null, false, false), "transfer authority outcome is " + e.executionState());
            }
        } catch (Exception e) {
            quarantineTransfer(key, new FailedEvent(out, in, 0, firstFailureTime,
                    null, false, false), "transfer authority reconciliation failed: " + e.getMessage());
        }
    }

    private static boolean isTransferType(EconomyEvent event) {
        return event != null && (event.type() == EconomyEvent.EventType.TRANSFER_OUT
                || event.type() == EconomyEvent.EventType.TRANSFER_IN);
    }

    private static String failedTransferKey(EconomyEvent event) {
        String operationId = TransferRequestIds.operationId(event);
        return operationId == null ? "orphan-transfer:" + failedKey(event) : operationId;
    }

    private void markTransferUnavailable(EconomyEvent first, EconomyEvent second) {
        EconomyEvent primary = first != null ? first : second;
        String operationId = TransferRequestIds.operationId(primary);
        queue.markTransferUnavailable(operationId,
                first == null ? null : first.playerUuid(),
                second == null ? null : second.playerUuid());
    }

    private TransferWriteResult executeTransferWrite(EconomyEvent first, EconomyEvent second) {
        if (!TransferRequestIds.arePair(first, second)) {
            throw new IllegalArgumentException("Transfer processing requires a matching pair");
        }
        EconomyEvent out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : second;
        EconomyEvent in = out == first ? second : first;
        BigDecimal amount = out.delta().abs();
        CacheManager.TransferResult result;
        result = cacheManager.atomicTransfer(out.playerUuid(), in.playerUuid(), amount);
        if (result == null) {
            throw new RedisMutationException(RedisMutationException.ExecutionState.NOT_SENT,
                    "Redis transfer was not sent and returned no execution receipt", null);
        }
        if (result == CacheManager.TransferResult.insufficientFunds()) return new TransferWriteResult(null, false);
        return new TransferWriteResult(transferReceipt(out, in, result), true);
    }

    private ReplicationReceipt readTransferAuthorityPair(EconomyEvent out, EconomyEvent in) {
        CacheManager.TransferResult snapshot = cacheManager.readTransferSnapshotStrict(
                out.playerUuid(), in.playerUuid());
        return new ReplicationReceipt(TransferRequestIds.operationId(out), List.of(
                new ConfirmedAccount(out.playerUuid(), snapshot.fromNewBalance, snapshot.fromNewVersion),
                new ConfirmedAccount(in.playerUuid(), snapshot.toNewBalance, snapshot.toNewVersion)));
    }

    private static ReplicationReceipt transferReceipt(EconomyEvent out, EconomyEvent in,
            CacheManager.TransferResult result) {
        return new ReplicationReceipt(TransferRequestIds.operationId(out), List.of(
                new ConfirmedAccount(out.playerUuid(), result.fromNewBalance, result.fromNewVersion),
                new ConfirmedAccount(in.playerUuid(), result.toNewBalance, result.toNewVersion)));
    }

    private void completeTransferPair(EconomyEvent out, EconomyEvent in, ReplicationReceipt receipt,
            boolean rejected) {
        ConfirmedAccount sender = receipt.accountFor(out.playerUuid());
        ConfirmedAccount receiver = receipt.accountFor(in.playerUuid());
        boolean completed = rejected
                ? queue.completeRejectedPair(out, sender.balance(), sender.version(),
                        in, receiver.balance(), receiver.version())
                : queue.completePair(out, sender.balance(), sender.version(),
                        in, receiver.balance(), receiver.version());
        if (!completed) {
            throw new IllegalStateException("Accepted transfer pair could not be completed after authority reconciliation");
        }
        if (rejected) {
            plugin.getLogger().warning("Redis rejected accepted transfer pair "
                    + TransferRequestIds.operationId(out) + " (" + out.playerUuid() + " -> "
                    + in.playerUuid() + ", " + out.delta().abs().toPlainString()
                    + "); both accounts were reconciled to confirmed authority balances "
                    + sender.balance().toPlainString() + " and " + receiver.balance().toPlainString());
            publishTransferPostEvents(out, in, receipt, TransferResolution.REJECTED);
        } else {
            publishTransferPostEvents(out, in, receipt, TransferResolution.CONFIRMED);
        }
    }

    private void publishTransferPostEvents(EconomyEvent first, EconomyEvent second,
            ReplicationReceipt receipt, TransferResolution resolution) {
        if (!SyncmoneyEventBus.isInitialized() || first == null || !isTransferType(first)) return;
        EconomyEvent out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : second;
        EconomyEvent in = out == first ? second : first;
        if (out == null) return;

        BigDecimal amount = out.delta().abs();
        ConfirmedAccount sender = receipt == null ? null : receipt.accounts().stream()
                .filter(account -> account.playerUuid().equals(out.playerUuid())).findFirst().orElse(null);
        ConfirmedAccount receiver = receipt == null || in == null ? null : receipt.accounts().stream()
                .filter(account -> account.playerUuid().equals(in.playerUuid())).findFirst().orElse(null);
        BigDecimal senderBalance = sender == null ? out.balanceAfter() : sender.balance();
        BigDecimal receiverBalance = in == null ? null
                : receiver == null ? in.balanceAfter() : receiver.balance();
        boolean success = resolution == TransferResolution.CONFIRMED
                || resolution == TransferResolution.CONFIRMED_QUARANTINED;
        BigDecimal senderBefore = success ? senderBalance.add(amount) : senderBalance;
        BigDecimal receiverBefore = success && receiverBalance != null
                ? receiverBalance.subtract(amount) : receiverBalance;
        String error = switch (resolution) {
            case CONFIRMED -> null;
            case REJECTED -> "REJECTED";
            case UNKNOWN -> "SENT_UNKNOWN";
            case CONFIRMED_QUARANTINED -> "CONFIRMED_QUARANTINED";
        };
        String operationId = TransferRequestIds.operationId(out);
        String senderName = nameResolver == null ? null : nameResolver.getNameCachedOnly(out.playerUuid());
        String receiverName = in == null || nameResolver == null
                ? null : nameResolver.getNameCachedOnly(in.playerUuid());
        SyncmoneyEventBus eventBus = SyncmoneyEventBus.getInstance();
        eventBus.callEvent(new PostTransactionEvent(out.playerUuid(), senderName,
                AsyncPreTransactionEvent.TransactionType.WITHDRAW, amount,
                senderBefore, senderBalance, out.source().name(),
                in == null ? null : in.playerUuid(), receiverName, operationId, success, error));
        if (in != null) {
            eventBus.callEvent(new PostTransactionEvent(in.playerUuid(), receiverName,
                    AsyncPreTransactionEvent.TransactionType.DEPOSIT, amount,
                    receiverBefore, receiverBalance, in.source().name(),
                    out.playerUuid(), senderName, operationId, success, error));
        }
    }

    private void processTransferEventSteps(EconomyEvent out, EconomyEvent in, ReplicationReceipt receipt) {
        processEventSteps(out, receipt, false);
        processEventSteps(in, receipt, false);
        persistTransferPair(out, in, receipt);
    }

    private void persistTransferPair(EconomyEvent out, EconomyEvent in, ReplicationReceipt receipt) {
        if (!config.isDbEnabled()) return;
        ConfirmedAccount sender = receipt.accountFor(out.playerUuid());
        ConfirmedAccount receiver = receipt.accountFor(in.playerUuid());
        String serverName = config.getServerName() != null ? config.getServerName() : "unknown";
        java.time.Instant timestamp = java.time.Instant.now();
        DbWriteQueue.DbWriteTask task = new DbWriteQueue.DbWriteTask(receipt.operationId(), List.of(
                new DbWriteQueue.AccountWrite(sender, nameResolver.getNameCachedOnly(sender.playerUuid()),
                        serverName, timestamp),
                new DbWriteQueue.AccountWrite(receiver, nameResolver.getNameCachedOnly(receiver.playerUuid()),
                        serverName, timestamp)));
        if (dbWriteQueue != null && dbWriteQueue.offer(task)) return;

        plugin.getLogger().severe("DbWriteQueue rejected confirmed transfer pair; attempting one SQL transaction: "
                + receipt.operationId());
        if (databaseManager == null) {
            throw new IllegalStateException("Confirmed transfer pair has no SQL persistence owner");
        }
        List<DbWriteQueue.DbWriteTask> stale = databaseManager.batchInsertOrUpdatePlayersWithStale(List.of(task));
        if (stale.isEmpty()) return;

        ReplicationReceipt latest = readTransferAuthorityPair(out, in);
        ConfirmedAccount latestSender = latest.accountFor(out.playerUuid());
        ConfirmedAccount latestReceiver = latest.accountFor(in.playerUuid());
        DbWriteQueue.DbWriteTask refreshed = new DbWriteQueue.DbWriteTask(latest.operationId(), List.of(
                new DbWriteQueue.AccountWrite(latestSender, nameResolver.getNameCachedOnly(out.playerUuid()),
                        serverName, timestamp),
                new DbWriteQueue.AccountWrite(latestReceiver, nameResolver.getNameCachedOnly(in.playerUuid()),
                        serverName, timestamp)));
        if (!databaseManager.batchInsertOrUpdatePlayersWithStale(List.of(refreshed)).isEmpty()) {
            throw new IllegalStateException("Latest authority transfer pair is still stale in SQL");
        }
    }

    private static boolean requiresQuarantine(RedisMutationException.ExecutionState executionState) {
        return executionState == RedisMutationException.ExecutionState.REJECTED
                || executionState == RedisMutationException.ExecutionState.SENT_UNKNOWN;
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
     * Process event steps (after Redis write succeeds).
     */
    private void processEventSteps(EconomyEvent event, ReplicationReceipt receipt) {
        processEventSteps(event, receipt, true);
    }

    private void processEventSteps(EconomyEvent event, ReplicationReceipt receipt, boolean persistSql) {
        ConfirmedAccount account = receipt.accountFor(event.playerUuid());
        BigDecimal newBalance = account.balance();

        if (config.isPubsubEnabled()) {
            publishUpdate(event, account);
        }

        if (persistSql && config.isDbEnabled()) {
            enqueueDbWrite(event, receipt);
        }

        if (hybridAuditManager != null && hybridAuditManager.isEnabled()
                && event.source() != EconomyEvent.EventSource.TEST) {
            recordAuditLog(event, newBalance);
        } else if (auditLogger != null && auditLogger.isEnabled()
                && event.source() != EconomyEvent.EventSource.TEST) {
            recordAuditLog(event, newBalance);
        }

        if (baltopManager != null && event.source() != EconomyEvent.EventSource.TEST) {
            updateBaltop(event.playerUuid(), newBalance.doubleValue());
        }

        if (shadowSyncTask != null && config.shadowSync().isShadowSyncEnabled()
                && event.source() != EconomyEvent.EventSource.TEST) {
            String playerName = nameResolver.getNameCachedOnly(event.playerUuid());
            if (playerName != null) {
                shadowSyncTask.enqueueSyncEvent(event.playerUuid(), playerName, newBalance);
            }
        }
    }

    /** Execute Redis atomic write and preserve the pair returned by that same authority operation. */
    private ReplicationReceipt processRedisWrite(EconomyEvent event) {
        ConfirmedAccount account = switch (event.type()) {
            case DEPOSIT, WITHDRAW -> cacheManager.atomicAddBalanceReceipt(event.playerUuid(), event.delta());
            case SET_BALANCE -> cacheManager.atomicSetBalanceReceipt(event.playerUuid(), event.balanceAfter());
            case TRANSFER_IN, TRANSFER_OUT -> throw new IllegalStateException(
                    "Transfer legs must be processed as one authority pair");
            default -> null;
        };
        return account == null ? null : new ReplicationReceipt(operationId(event), List.of(account));
    }

    private static String operationId(EconomyEvent event) {
        String requestId = event.requestId();
        if (requestId != null && !requestId.isBlank()) return requestId;
        return "event:" + event.playerUuid() + ":" + event.timestamp() + ":" + event.type() + ":" + event.version();
    }

    /** Publish the exact balance/version pair confirmed by the authority write. */
    private void publishUpdate(EconomyEvent event, ConfirmedAccount account) {
        String serverName = config.getServerName();
        PubSubMessage message = createPubSubMessage(event, account, serverName);

        try (var jedis = redisManager.getResource()) {
            jedis.publish(PUBSUB_CHANNEL, message.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish Pub/Sub: " + e.getMessage());
        }
    }

    static PubSubMessage createPubSubMessage(EconomyEvent event, ConfirmedAccount account,
            String serverName) {
        String sourcePlugin = event.sourcePlugin() == null || event.sourcePlugin().isBlank()
                ? "Syncmoney"
                : event.sourcePlugin();
        return new PubSubMessage(
                event.playerUuid().toString(),
                account.balance(),
                account.version(),
                serverName != null ? serverName : "unknown",
                event.requestId(),
                event.timestamp(),
                event.type().name(),
                event.delta().doubleValue(),
                sourcePlugin,
                null);
    }

    /** Enqueue the authority receipt without reading cache state again. */
    private void enqueueDbWrite(EconomyEvent event, ReplicationReceipt receipt) {
        ConfirmedAccount account = receipt.accountFor(event.playerUuid());
        String serverName = config.getServerName();
        String playerName = nameResolver.getNameCachedOnly(event.playerUuid());

        var task = new DbWriteQueue.DbWriteTask(receipt.operationId(), account, playerName,
                serverName != null ? serverName : "unknown", java.time.Instant.now());

        if (!dbWriteQueue.offer(task)) {
            plugin.getLogger().severe("CRITICAL: DbWriteQueue is full, attempting fallback for " + event.playerUuid());
            boolean directWriteSuccess = tryDirectDbWrite(account, playerName, serverName);
            if (!directWriteSuccess) {
                plugin.getLogger().severe("CRITICAL: DbWriteQueue is full, direct DB write failed, "
                        + "writing to overflow log for " + event.playerUuid()
                        + ". This may cause data loss on restart.");
                logEventToOverflowLog(event);
            }
        }
    }

    /** Try a direct database write when the bounded queue rejects the confirmed snapshot. */
    private boolean tryDirectDbWrite(ConfirmedAccount account, String playerName, String serverName) {
        try {
            if (databaseManager != null) {
                return databaseManager.tryInsertOrUpdatePlayer(account.playerUuid(), playerName,
                        account.balance(), account.version(), serverName);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("STORE-02: Direct DB write failed: " + e.getMessage());
        }
        return false;
    }
    /**
     * Execute Redis atomic write.
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
