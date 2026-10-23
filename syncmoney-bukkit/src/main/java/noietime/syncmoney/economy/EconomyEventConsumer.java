package noietime.syncmoney.economy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import noietime.syncmoney.protocol.RedisChannels;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import noietime.syncmoney.storage.recovery.LegacyOverflowParser;
import noietime.syncmoney.storage.recovery.RecoverySpool;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
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

    private static final String PUBSUB_CHANNEL = RedisChannels.BALANCE_UPDATE;
    private static final int MAX_RETRY_COUNT = 3;
    private static final long SQL_WRITE_ACK_TIMEOUT_MILLIS = 8_000L;

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
    private final ConcurrentMap<String, RecoveryCheckpoint> durableRecovery = new ConcurrentHashMap<>();
    private volatile ExecutorService executor;
    private volatile Thread workerThread;
    private volatile boolean recoveryBlocked;
    private volatile boolean shutdownCheckpointRequested;
    private final OverflowLogInterface overflowLog;
    private final noietime.syncmoney.storage.db.DatabaseManager databaseManager;
    private final RecoverySpool recoverySpool;
    private final long shutdownDeadlineMillis;
    private final ObjectMapper recoveryMapper = new ObjectMapper();

    private enum RecoveryStage {
        ATTEMPTING,
        RETRYABLE,
        CONFIRMED,
        RECONCILE,
        RECONCILE_CONFIRMED,
        AMBIGUOUS
    }

    private record RecoveryCheckpoint(int version, RecoveryStage stage, EconomyEvent event,
            EconomyEvent partner, ReplicationReceipt receipt, int retryCount, long firstFailureTime) { }

    private record FailedEvent(EconomyEvent event, EconomyEvent partner, int retryCount, long firstFailureTime,
            ReplicationReceipt confirmedReceipt, boolean quarantined, boolean reconciliationOnly, boolean recovered) {
        private FailedEvent(EconomyEvent event, int retryCount, long firstFailureTime,
                ReplicationReceipt confirmedReceipt) {
            this(event, null, retryCount, firstFailureTime, confirmedReceipt, false, false, false);
        }

        private FailedEvent(EconomyEvent event, EconomyEvent partner, int retryCount,
                long firstFailureTime, ReplicationReceipt confirmedReceipt) {
            this(event, partner, retryCount, firstFailureTime, confirmedReceipt, false, false, false);
        }

        private FailedEvent(EconomyEvent event, EconomyEvent partner, int retryCount,
                long firstFailureTime, ReplicationReceipt confirmedReceipt,
                boolean quarantined, boolean reconciliationOnly) {
            this(event, partner, retryCount, firstFailureTime, confirmedReceipt,
                    quarantined, reconciliationOnly, false);
        }

        private FailedEvent(EconomyEvent event, int retryCount, long firstFailureTime,
                ReplicationReceipt confirmedReceipt, boolean quarantined) {
            this(event, null, retryCount, firstFailureTime, confirmedReceipt, quarantined, false, false);
        }

        private FailedEvent withConfirmedReceipt(ReplicationReceipt receipt) {
            return new FailedEvent(event, partner, retryCount, firstFailureTime, receipt, false, false, recovered);
        }

        private FailedEvent withReconciliationReceipt(ReplicationReceipt receipt) {
            return new FailedEvent(event, partner, retryCount, firstFailureTime, receipt, false, true, recovered);
        }

        private FailedEvent withRecoveryState(int retryCount, ReplicationReceipt receipt,
                boolean quarantined, boolean reconciliationOnly, boolean recovered) {
            return new FailedEvent(event, partner, retryCount, firstFailureTime, receipt,
                    quarantined, reconciliationOnly, recovered);
        }

        private FailedEvent asRecovered() {
            return new FailedEvent(event, partner, retryCount, firstFailureTime,
                    confirmedReceipt, quarantined, reconciliationOnly, true);
        }

        private FailedEvent nextAttempt() {
            return new FailedEvent(event, partner, Math.min(retryCount + 1, MAX_RETRY_COUNT), firstFailureTime,
                    confirmedReceipt, quarantined, reconciliationOnly, recovered);
        }

        private FailedEvent asQuarantined() {
            return new FailedEvent(event, partner, retryCount, firstFailureTime,
                    confirmedReceipt, true, reconciliationOnly, recovered);
        }
    }

    private record TransferWriteResult(ReplicationReceipt receipt, boolean applied) { }

    private record AuthorityAccount(BigDecimal balance, long version) { }

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
        this(plugin, config, queue, cacheManager, redisManager, dbWriteQueue, auditLogger, hybridAuditManager,
                nameResolver, baltopManager, shadowSyncTask, overflowLog, databaseManager, null, 10_000L);
    }

    EconomyEventConsumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cacheManager,
            RedisManager redisManager, DbWriteQueue dbWriteQueue,
            AuditLogger auditLogger, HybridAuditManager hybridAuditManager,
            NameResolver nameResolver,
            BaltopManager baltopManager,
            ShadowSyncTask shadowSyncTask,
            OverflowLogInterface overflowLog,
            noietime.syncmoney.storage.db.DatabaseManager databaseManager,
            RecoverySpool recoverySpool) {
        this(plugin, config, queue, cacheManager, redisManager, dbWriteQueue, auditLogger, hybridAuditManager,
                nameResolver, baltopManager, shadowSyncTask, overflowLog, databaseManager, recoverySpool, 10_000L);
    }

    EconomyEventConsumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cacheManager,
            RedisManager redisManager, DbWriteQueue dbWriteQueue,
            AuditLogger auditLogger, HybridAuditManager hybridAuditManager,
            NameResolver nameResolver,
            BaltopManager baltopManager,
            ShadowSyncTask shadowSyncTask,
            OverflowLogInterface overflowLog,
            noietime.syncmoney.storage.db.DatabaseManager databaseManager,
            RecoverySpool recoverySpool, long shutdownDeadlineMillis) {
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
        this.recoverySpool = recoverySpool;
        this.shutdownDeadlineMillis = Math.max(1L, shutdownDeadlineMillis);
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
            t.setDaemon(false);
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
            if (Thread.currentThread() == workerThread) {
                shutdownCheckpointRequested = true;
                logShutdownDrainStatus();
                return;
            }
            try {
                if (!exec.awaitTermination(shutdownDeadlineMillis, TimeUnit.MILLISECONDS)) {
                    plugin.getLogger().severe("CRITICAL HEALTH: EconomyEventConsumer drain timed out; "
                            + "checkpointing accepted writes before releasing queue ownership");
                    queue.cancelUncommittedReservations();
                    shutdownCheckpointRequested = true;
                    exec.shutdownNow();
                    if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                        plugin.getLogger().severe("CRITICAL HEALTH: EconomyEventConsumer recovery handoff is still "
                                + "pending; its non-daemon worker will retain ownership and retry the spool");
                    }
                }
            } catch (InterruptedException e) {
                queue.cancelUncommittedReservations();
                shutdownCheckpointRequested = true;
                exec.shutdownNow();
                Thread.currentThread().interrupt();
                plugin.getLogger().severe("CRITICAL HEALTH: interrupted while awaiting EconomyEventConsumer drain; "
                        + "recovery handoff will continue on its worker");
            }
        }
        logShutdownDrainStatus();
    }

    private void logShutdownDrainStatus() {
        int queued = queue.size();
        int outstanding = queue.snapshotOutstandingEvents().size();
        int retries = failedEvents.size();
        int recoveryPending = durableRecovery.size();
        ExecutorService exec = executor;
        boolean workerTerminated = exec == null || exec.isTerminated();
        boolean drained = queued == 0 && outstanding == 0 && retries == 0 && recoveryPending == 0
                && workerTerminated && !recoveryBlocked;
        plugin.getLogger().info("T21_DRAIN owner=economy status=" + (drained ? "drained" : "pending")
                + " queued=" + queued + " outstanding=" + outstanding + " retry=" + retries
                + " workerTerminated=" + workerTerminated + " recoveryPending=" + recoveryPending
                + " recoveryBlocked=" + recoveryBlocked);
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
                if (recoverySpool != null && checkpoint(key, failed, recoveryStage(failed))) {
                    retireFailedEvent(failed, true);
                    failedEvents.remove(key, failed);
                } else if (recoverySpool == null && !hasOutstandingOwnership(failed)) {
                    // Legacy/unit-test construction without the durable spool may only retire an
                    // ambiguous write after an external confirmed reconciliation released it.
                    failedEvents.remove(key, failed);
                }
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
                if (checkpoint(key, failed, recoveryStage(failed))) {
                    handleCriticalFailure(failed);
                    if (recoverySpool != null) {
                        retireFailedEvent(failed, true);
                        failedEvents.remove(key, failed);
                    } else {
                        retainWithoutSpool(key, failed);
                    }
                } else {
                    FailedEvent next = failed.nextAttempt();
                    failedEvents.replace(key, failed, next);
                }
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
                    if (!checkpoint(key, retryState, RecoveryStage.ATTEMPTING)) {
                        FailedEvent nextAttempt = retryState.nextAttempt();
                        failedEvents.replace(key, retryState, nextAttempt);
                        continue;
                    }
                    receipt = processRedisWrite(failed.event());
                    if (receipt == null) {
                        FailedEvent nextAttempt = failed.nextAttempt();
                        checkpoint(key, nextAttempt, RecoveryStage.RETRYABLE);
                        failedEvents.replace(key, retryState, nextAttempt);
                        logRetryFailure(failed, nextAttempt, BASE_DELAY_MS, MAX_DELAY_MS);
                        continue;
                    }

                    // Keep the exact authority receipt before any downstream operation can throw.
                    retryState = failed.withConfirmedReceipt(receipt);
                    if (!failedEvents.replace(key, failed, retryState)) {
                        continue;
                    }
                    if (!checkpoint(key, retryState, RecoveryStage.CONFIRMED)) {
                        FailedEvent nextAttempt = retryState.nextAttempt();
                        failedEvents.replace(key, retryState, nextAttempt);
                        continue;
                    }
                } else if (!checkpoint(key, retryState, recoveryStage(retryState))) {
                    FailedEvent nextAttempt = retryState.nextAttempt();
                    failedEvents.replace(key, retryState, nextAttempt);
                    continue;
                }

                processEventSteps(failed.event(), receipt);
                ConfirmedAccount account = receipt.accountFor(failed.event().playerUuid());
                if (retryState.recovered()) {
                    queue.reconcileRecovered(failed.event(), account.balance(), account.version(), false);
                } else {
                    queue.complete(failed.event(), account.balance(), account.version());
                }
                enqueueShadowSyncEvent(failed.event(), receipt);
                acknowledgeRecovery(key);
                failedEvents.remove(key, retryState);
                plugin.getLogger().fine("Retry successful for event: " + failed.event().requestId());
            } catch (RedisMutationException e) {
                if (retryState.confirmedReceipt() == null
                        && e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                    FailedEvent rejected = retryState.withReconciliationReceipt(null);
                    checkpoint(key, rejected, RecoveryStage.RECONCILE);
                    if (failedEvents.replace(key, retryState, rejected)) {
                        processRejectedEventReconciliation(key, rejected, now + BASE_DELAY_MS,
                                BASE_DELAY_MS, MAX_DELAY_MS, true);
                    }
                } else if (retryState.confirmedReceipt() == null
                        && e.executionState() == RedisMutationException.ExecutionState.SENT_UNKNOWN) {
                    FailedEvent quarantined = retryState.asQuarantined();
                    queue.markAccountUnavailable(failed.event().playerUuid());
                    checkpoint(key, quarantined, RecoveryStage.AMBIGUOUS);
                    if (!failedEvents.replace(key, retryState, quarantined)) {
                        failedEvents.putIfAbsent(key, quarantined);
                    }
                    plugin.getLogger().severe("CRITICAL HEALTH: quarantined accepted economy event after Redis "
                            + e.executionState() + "; automatic retry is disabled for request "
                            + failed.event().requestId());
                    continue;
                }
                FailedEvent nextAttempt = retryState.nextAttempt();
                checkpoint(key, nextAttempt, nextAttempt.confirmedReceipt() == null
                        ? RecoveryStage.RETRYABLE : recoveryStage(nextAttempt));
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(failed, nextAttempt, BASE_DELAY_MS, MAX_DELAY_MS);
            } catch (Exception e) {
                FailedEvent nextAttempt = retryState.nextAttempt();
                if (nextAttempt.confirmedReceipt() == null) {
                    nextAttempt = nextAttempt.asQuarantined();
                    queue.markAccountUnavailable(nextAttempt.event().playerUuid());
                    checkpoint(key, nextAttempt, RecoveryStage.AMBIGUOUS);
                } else {
                    checkpoint(key, nextAttempt, recoveryStage(nextAttempt));
                }
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(failed, nextAttempt, BASE_DELAY_MS, MAX_DELAY_MS);
            }
        }
    }

    private void retireFailedEvent(FailedEvent failed, boolean keepUnavailable) {
        EconomyEvent event = failed.event();
        EconomyEvent partner = failed.partner();
        ReplicationReceipt receipt = failed.confirmedReceipt();
        if (partner != null) {
            if (failed.recovered()) {
                if (receipt != null) {
                    completeRecoveredTransferPair(event, partner, receipt, failed.reconciliationOnly());
                }
            } else if (receipt != null) {
                if (failed.reconciliationOnly()) {
                    AuthorityAccount firstAuthority = authorityAccount(receipt, event.playerUuid());
                    AuthorityAccount secondAuthority = authorityAccount(receipt, partner.playerUuid());
                    queue.completeRejectedPair(event, firstAuthority.balance(), firstAuthority.version(), partner,
                            secondAuthority.balance(), secondAuthority.version());
                } else {
                    queue.completePair(event, receipt.accountFor(event.playerUuid()).balance(),
                            receipt.accountFor(event.playerUuid()).version(), partner,
                            receipt.accountFor(partner.playerUuid()).balance(),
                            receipt.accountFor(partner.playerUuid()).version());
                }
            } else {
                queue.quarantine(event);
                queue.quarantine(partner);
            }
            if (keepUnavailable) {
                queue.markTransferUnavailable(TransferRequestIds.operationId(event),
                        event.playerUuid(), partner.playerUuid());
            }
            return;
        }
        if (isTransferType(event)) {
            queue.quarantine(event);
            if (keepUnavailable) {
                queue.markTransferUnavailable(TransferRequestIds.operationId(event), event.playerUuid(), null);
            }
            return;
        }
        if (failed.recovered()) {
            if (receipt != null) {
                AuthorityAccount account = authorityAccount(receipt, event.playerUuid());
                queue.reconcileRecovered(event, account.balance(), account.version(), failed.reconciliationOnly());
                if (!failed.reconciliationOnly()) enqueueShadowSyncEvent(event, receipt);
            }
        } else if (receipt != null) {
            AuthorityAccount account = authorityAccount(receipt, event.playerUuid());
            if (failed.reconciliationOnly()) queue.completeRejected(event, account.balance(), account.version());
            else if (account.balance() != null) {
                queue.complete(event, account.balance(), account.version());
                enqueueShadowSyncEvent(event, receipt);
            }
        } else {
            queue.quarantine(event);
        }
        if (keepUnavailable) queue.markAccountUnavailable(event.playerUuid());
    }

    private void processRejectedEventReconciliation(String key, FailedEvent failed, long now,
            long baseDelayMillis, long maxDelayMillis, boolean initialAttempt) {
        long retryDelay = Math.min(baseDelayMillis * (1L << Math.min(failed.retryCount(), 30)), maxDelayMillis);
        if (!initialAttempt && now - failed.firstFailureTime() < retryDelay) return;

        FailedEvent retryState = failed;
        try {
            ReplicationReceipt receipt = retryState.confirmedReceipt();
            if (retryState.recovered()) {
                if (!checkpoint(key, retryState.event(), null, null, retryState.retryCount(),
                        retryState.firstFailureTime(), RecoveryStage.RECONCILE)) return;
                receipt = readRejectedAuthoritySnapshot(retryState.event());
                retryState = retryState.withReconciliationReceipt(receipt);
                if (!failedEvents.replace(key, failed, retryState)) return;
                if (!checkpoint(key, retryState, RecoveryStage.RECONCILE_CONFIRMED)) return;
            } else if (receipt == null) {
                if (!checkpoint(key, retryState, RecoveryStage.RECONCILE)) return;
                receipt = readRejectedAuthoritySnapshot(failed.event());
                retryState = failed.withReconciliationReceipt(receipt);
                if (!failedEvents.replace(key, failed, retryState)) return;
                if (!checkpoint(key, retryState, RecoveryStage.RECONCILE_CONFIRMED)) return;
            } else if (!checkpoint(key, retryState, RecoveryStage.RECONCILE_CONFIRMED)) {
                return;
            }

            persistRejectedSnapshot(retryState.event(), receipt);
            AuthorityAccount account = authorityAccount(receipt, retryState.event().playerUuid());
            if (retryState.recovered()) {
                queue.reconcileRecovered(retryState.event(), account.balance(), account.version(), true);
            } else if (!queue.completeRejected(retryState.event(), account.balance(), account.version())) {
                queue.reconcileRecovered(retryState.event(), account.balance(), account.version(), true);
            }
            acknowledgeRecovery(key);
            failedEvents.remove(key, retryState);
            plugin.getLogger().fine("Reconciled Redis-rejected economy event without replay: "
                    + retryState.event().requestId());
        } catch (Exception e) {
            FailedEvent nextAttempt = retryState.nextAttempt();
            checkpoint(key, nextAttempt, recoveryStage(nextAttempt));
            if (!failedEvents.replace(key, retryState, nextAttempt)) {
                failedEvents.putIfAbsent(key, nextAttempt);
            }
            logRetryFailure(retryState, nextAttempt, baseDelayMillis, maxDelayMillis);
        }
    }

    private ReplicationReceipt readRejectedAuthoritySnapshot(EconomyEvent event) {
        List<ConfirmedAccount> accounts = cacheManager.readAuthorityBalanceSnapshotStrict(event.playerUuid())
                .map(snapshot -> List.of(new ConfirmedAccount(
                        event.playerUuid(), snapshot.balance(), snapshot.version())))
                .orElseGet(List::of);
        return new ReplicationReceipt("reconcile:" + operationId(event), accounts);
    }

    private void persistRejectedSnapshot(EconomyEvent event, ReplicationReceipt receipt) {
        if (!config.isDbEnabled()) return;
        ConfirmedAccount account = receipt.findAccount(event.playerUuid()).orElse(null);
        if (account == null) return;
        String serverName = config.getServerName() != null ? config.getServerName() : "unknown";
        java.time.Instant timestamp = java.time.Instant.now();
        DbWriteQueue.DbWriteTask task = new DbWriteQueue.DbWriteTask(receipt.operationId(), account,
                nameResolver.getNameCachedOnly(account.playerUuid()), serverName, timestamp);
        CompletableFuture<Boolean> completion = dbWriteQueue == null ? null : dbWriteQueue.offerAndTrack(task);
        if (completion != null) {
            if (awaitSqlPersistence(completion, receipt.operationId())) return;
            throw new IllegalStateException("Rejected authority snapshot SQL receipt was not confirmed");
        }
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
            if (checkpoint(key, failed, recoveryStage(failed))) {
                quarantineTransfer(key, failed, "transfer downstream work did not reach a confirmed receipt");
            }
            return;
        }
        long retryDelay = Math.min(baseDelayMillis * (1L << Math.min(failed.retryCount(), 30)), maxDelayMillis);
        if (now - failed.firstFailureTime() < retryDelay) return;

        FailedEvent retryState = failed;
        try {
            if (retryState.recovered() && retryState.reconciliationOnly()) {
                // Recovery entries are replayed independently, so a later accepted write may
                // already have advanced Redis after this rejection snapshot was checkpointed.
                // Re-read before applying stored absence/presence to memory or acknowledging it.
                if (!checkpoint(key, retryState.event(), retryState.partner(), null, retryState.retryCount(),
                        retryState.firstFailureTime(), RecoveryStage.RECONCILE)) return;
                retryState = failed.withReconciliationReceipt(
                        readRejectedTransferAuthorityPair(failed.event(), failed.partner()));
                if (!failedEvents.replace(key, failed, retryState)) return;
                if (!checkpoint(key, retryState, RecoveryStage.RECONCILE_CONFIRMED)) return;
            } else if (retryState.confirmedReceipt() == null) {
                if (retryState.reconciliationOnly()) {
                    if (!checkpoint(key, retryState, RecoveryStage.RECONCILE)) return;
                    retryState = failed.withReconciliationReceipt(
                            readRejectedTransferAuthorityPair(failed.event(), failed.partner()));
                    if (!failedEvents.replace(key, failed, retryState)) return;
                    if (!checkpoint(key, retryState, RecoveryStage.RECONCILE_CONFIRMED)) return;
                } else {
                    if (!checkpoint(key, retryState, RecoveryStage.ATTEMPTING)) return;
                    TransferWriteResult write = executeTransferWrite(failed.event(), failed.partner());
                    if (write.applied()) {
                        retryState = failed.withConfirmedReceipt(write.receipt());
                        if (!failedEvents.replace(key, failed, retryState)) return;
                        if (!checkpoint(key, retryState, RecoveryStage.CONFIRMED)) return;
                    } else {
                        ReplicationReceipt receipt = write.receipt() != null
                                ? write.receipt()
                                : readRejectedTransferAuthorityPair(failed.event(), failed.partner());
                        retryState = failed.withReconciliationReceipt(receipt);
                        if (!failedEvents.replace(key, failed, retryState)) return;
                        if (!checkpoint(key, retryState, RecoveryStage.RECONCILE_CONFIRMED)) return;
                    }
                }
            } else if (!checkpoint(key, retryState, recoveryStage(retryState))) {
                return;
            }

            if (retryState.reconciliationOnly()) {
                persistTransferPair(retryState.event(), retryState.partner(), retryState.confirmedReceipt(), true);
            } else {
                processTransferEventSteps(retryState.event(), retryState.partner(), retryState.confirmedReceipt());
            }
            if (retryState.recovered()) {
                completeRecoveredTransferPair(retryState.event(), retryState.partner(),
                        retryState.confirmedReceipt(), retryState.reconciliationOnly());
            } else if (!completeTransferPair(retryState.event(), retryState.partner(),
                    retryState.confirmedReceipt(), retryState.reconciliationOnly())) {
                completeRecoveredTransferPair(retryState.event(), retryState.partner(),
                        retryState.confirmedReceipt(), retryState.reconciliationOnly());
            }
            acknowledgeRecovery(key);
            failedEvents.remove(key, retryState);
            plugin.getLogger().fine("Retry successful for transfer: "
                    + TransferRequestIds.operationId(retryState.event()));
        } catch (RedisMutationException e) {
            if (retryState.confirmedReceipt() == null
                    && e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                FailedEvent rejected = retryState.withReconciliationReceipt(null);
                checkpoint(key, rejected, RecoveryStage.RECONCILE);
                if (failedEvents.replace(key, retryState, rejected)) {
                    processFailedTransfer(key, rejected, now + baseDelayMillis,
                            baseDelayMillis, maxDelayMillis);
                }
            } else if (retryState.confirmedReceipt() == null
                    && e.executionState() == RedisMutationException.ExecutionState.SENT_UNKNOWN) {
                quarantineTransfer(key, retryState, "transfer authority outcome is SENT_UNKNOWN");
            } else {
                FailedEvent nextAttempt = retryState.nextAttempt();
                checkpoint(key, nextAttempt, recoveryStage(nextAttempt));
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(retryState, nextAttempt, baseDelayMillis, maxDelayMillis);
            }
        } catch (Exception e) {
            if (retryState.confirmedReceipt() == null && !retryState.reconciliationOnly()) {
                quarantineTransfer(key, retryState,
                        "transfer authority reconciliation failed: " + e.getMessage());
            } else {
                FailedEvent nextAttempt = retryState.nextAttempt();
                checkpoint(key, nextAttempt, recoveryStage(nextAttempt));
                failedEvents.replace(key, retryState, nextAttempt);
                logRetryFailure(retryState, nextAttempt, baseDelayMillis, maxDelayMillis);
            }
        }
    }

    private void quarantineTransfer(String key, FailedEvent failed, String reason) {
        FailedEvent quarantined = failed.asQuarantined();
        RecoveryStage stage = recoveryStage(quarantined);
        if (!checkpoint(key, quarantined, stage)) {
            queue.markTransferUnavailable(key, failed.event().playerUuid(),
                    failed.partner() == null ? null : failed.partner().playerUuid());
            failedEvents.putIfAbsent(key, quarantined);
            return;
        }
        queue.markTransferUnavailable(key, failed.event().playerUuid(),
                failed.partner() == null ? null : failed.partner().playerUuid());
        if (!failedEvents.replace(key, failed, quarantined) && failedEvents.putIfAbsent(key, quarantined) != null) {
            return;
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
                        : authorityAccount(receipt, failed.event().type() == EconomyEvent.EventType.TRANSFER_OUT
                                ? failed.event().playerUuid() : failed.partner().playerUuid()).balance(),
                receipt == null || failed.partner() == null ? null
                        : authorityAccount(receipt, failed.event().type() == EconomyEvent.EventType.TRANSFER_IN
                                ? failed.event().playerUuid() : failed.partner().playerUuid()).balance());
        if (recoverySpool != null) {
            retireFailedEvent(quarantined, true);
            failedEvents.remove(key, quarantined);
        }
        publishTransferPostEvents(failed.event(), failed.partner(), receipt, resolution);
        plugin.getLogger().severe("CRITICAL HEALTH: quarantined accepted transfer pair "
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

    private boolean restoreDurableRecovery() {
        if (recoverySpool == null) return true;
        final List<RecoverySpool.Entry> entries;
        try {
            entries = recoverySpool.pending();
        } catch (Exception failure) {
            recoveryBlocked = true;
            queue.closeAdmission();
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "CRITICAL HEALTH: recovery spool could not be validated; economy writes are closed and the "
                            + "original recovery-v1.jsonl was preserved; 1.3.2 rollback remains blocked until "
                            + "the recovery evidence is reconciled", failure);
            return false;
        }

        for (RecoverySpool.Entry entry : entries) {
            final RecoveryCheckpoint checkpoint;
            try {
                checkpoint = recoveryMapper.readValue(entry.payload(), RecoveryCheckpoint.class);
            } catch (Exception malformed) {
                return blockRecovery("recovery payload is invalid for " + entry.id(), malformed);
            }
            if (!validCheckpoint(entry.id(), checkpoint)) {
                return blockRecovery("recovery payload does not match its operation id " + entry.id(), null);
            }
            durableRecovery.put(entry.id(), checkpoint);
            markRecoveryUnavailable(entry.id(), checkpoint.event(), checkpoint.partner());

            if (checkpoint.stage() == RecoveryStage.ATTEMPTING
                    || checkpoint.stage() == RecoveryStage.AMBIGUOUS) {
                plugin.getLogger().severe("CRITICAL HEALTH: retained ambiguous economy operation " + entry.id()
                        + " without replay; account(s) remain unavailable for operator reconciliation");
                continue;
            }

            boolean reconciliationOnly = checkpoint.stage() == RecoveryStage.RECONCILE
                    || checkpoint.stage() == RecoveryStage.RECONCILE_CONFIRMED;
            failedEvents.putIfAbsent(entry.id(), new FailedEvent(checkpoint.event(), checkpoint.partner(),
                    checkpoint.retryCount(), checkpoint.firstFailureTime(), checkpoint.receipt(),
                    false, reconciliationOnly, true));
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().severe("CRITICAL HEALTH: loaded " + entries.size()
                    + " pending economy recovery checkpoint(s); confirmed operations will resume downstream only; "
                    + "1.3.2 rollback remains blocked until every pending checkpoint is acknowledged or reconciled");
        }
        return true;
    }

    private boolean inspectLegacyOverflow() {
        if (overflowLog == null) return true;
        final List<String> legacyRecords;
        try {
            legacyRecords = overflowLog.readWithoutClear();
        } catch (Exception failure) {
            return blockRecovery("legacy overflow records could not be read without clearing", failure);
        }

        int ambiguous = 0;
        int invalid = 0;
        for (String line : legacyRecords) {
            LegacyOverflowParser.Result parsed = LegacyOverflowParser.parse(line);
            if (parsed.classification() == LegacyOverflowParser.Classification.AMBIGUOUS) {
                UUID uuid = UUID.fromString(parsed.playerUuid());
                queue.markAccountUnavailable(uuid);
                ambiguous++;
            } else {
                invalid++;
            }
        }
        if (ambiguous > 0) {
            plugin.getLogger().severe("CRITICAL HEALTH: retained " + ambiguous
                    + " ambiguous legacy overflow record(s) read-only; affected account(s) are unavailable and "
                    + "1.3.2 rollback remains blocked until manual reconciliation");
        }
        if (invalid > 0) {
            return blockRecovery(invalid + " malformed legacy overflow record(s) have no provable account identity", null);
        }
        return true;
    }

    private boolean blockRecovery(String reason, Throwable failure) {
        recoveryBlocked = true;
        queue.closeAdmission();
        if (failure == null) {
            plugin.getLogger().severe("CRITICAL HEALTH: " + reason
                    + "; economy writes are closed and recovery evidence was preserved; "
                    + "1.3.2 rollback remains blocked until the evidence is reconciled");
        } else {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "CRITICAL HEALTH: " + reason + "; economy writes are closed and recovery evidence was preserved; "
                            + "1.3.2 rollback remains blocked until the evidence is reconciled", failure);
        }
        return false;
    }

    private boolean validCheckpoint(String id, RecoveryCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.version() != 1 || checkpoint.stage() == null
                || checkpoint.event() == null || checkpoint.retryCount() < 0
                || checkpoint.firstFailureTime() < 0
                || !id.equals(recoveryKey(checkpoint.event(), checkpoint.partner()))) return false;
        if (checkpoint.stage() == RecoveryStage.CONFIRMED
                || checkpoint.stage() == RecoveryStage.RECONCILE_CONFIRMED) {
            return checkpoint.receipt() != null;
        }
        if (checkpoint.stage() == RecoveryStage.AMBIGUOUS) return true;
        if (checkpoint.receipt() != null) return false;
        if (checkpoint.partner() == null) {
            return !isTransferType(checkpoint.event())
                    || checkpoint.stage() == RecoveryStage.ATTEMPTING
                    || checkpoint.stage() == RecoveryStage.AMBIGUOUS;
        }
        return isTransferType(checkpoint.event()) && TransferRequestIds.arePair(checkpoint.event(), checkpoint.partner());
    }

    private String recoveryKey(EconomyEvent event, EconomyEvent partner) {
        return isTransferType(event) ? failedTransferKey(event) : failedKey(event);
    }

    private void markRecoveryUnavailable(String id, EconomyEvent event, EconomyEvent partner) {
        if (isTransferType(event)) {
            queue.markTransferUnavailable(id, event.playerUuid(), partner == null ? null : partner.playerUuid());
        } else {
            queue.markAccountUnavailable(event.playerUuid());
        }
    }

    private boolean checkpoint(String id, FailedEvent failed, RecoveryStage stage) {
        return checkpoint(id, failed.event(), failed.partner(), failed.confirmedReceipt(),
                failed.retryCount(), failed.firstFailureTime(), stage);
    }

    private boolean checkpoint(String id, EconomyEvent event, EconomyEvent partner,
            ReplicationReceipt receipt, int retryCount, long firstFailureTime, RecoveryStage stage) {
        if (recoverySpool == null) return true;
        RecoveryCheckpoint record = new RecoveryCheckpoint(1, stage, event, partner, receipt,
                retryCount, firstFailureTime);
        boolean interrupted = Thread.interrupted();
        try {
            recoverySpool.append(id, recoveryMapper.writeValueAsString(record));
            durableRecovery.put(id, record);
            return true;
        } catch (Exception failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "CRITICAL HEALTH: could not durably checkpoint economy operation " + id
                            + "; accepted ownership remains held", failure);
            return false;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void acknowledgeRecovery(String id) throws java.io.IOException {
        if (recoverySpool == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            recoverySpool.acknowledge(id);
            durableRecovery.remove(id);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static RecoveryStage recoveryStage(FailedEvent failed) {
        if (failed.confirmedReceipt() != null) {
            return failed.reconciliationOnly() ? RecoveryStage.RECONCILE_CONFIRMED : RecoveryStage.CONFIRMED;
        }
        if (failed.reconciliationOnly()) return RecoveryStage.RECONCILE;
        if (failed.quarantined()) return RecoveryStage.AMBIGUOUS;
        return RecoveryStage.RETRYABLE;
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


        if (overflowLog != null && recoverySpool == null) {
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

        if (!restoreDurableRecovery() || !inspectLegacyOverflow()) return;

        while (running || !queue.isEmpty() || queue.hasOutstandingWrites() || !failedEvents.isEmpty()) {
            if (shutdownCheckpointRequested) {
                if (checkpointOutstandingForShutdown()) break;
            }
            if (recoveryBlocked) {
                try {
                    TimeUnit.MILLISECONDS.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.interrupted();
                }
                continue;
            }
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
                if (shutdownCheckpointRequested) {
                    Thread.interrupted();
                    if (checkpointOutstandingForShutdown()) break;
                    continue;
                }
                if (recoveryBlocked) {
                    Thread.interrupted();
                    continue;
                }
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
     * Handoff accepted writes after the graceful drain deadline. Each recovery record is forced
     * before its queue ownership is released; failed spool writes keep the worker and ownership
     * alive so the handoff can be retried while storage dependencies remain available.
     */
    private boolean checkpointOutstandingForShutdown() {
        queue.cancelUncommittedReservations();
        if (recoverySpool == null) {
            List<EconomyEvent> outstanding = queue.snapshotOutstandingEvents();
            if (outstanding.isEmpty()) {
                for (Map.Entry<String, FailedEvent> entry : failedEvents.entrySet()) {
                    if (!hasOutstandingOwnership(entry.getValue())) {
                        failedEvents.remove(entry.getKey(), entry.getValue());
                    }
                }
                return failedEvents.isEmpty();
            }
            for (EconomyEvent event : outstanding) {
                markRecoveryUnavailable(recoveryKey(event, null), event, null);
            }
            for (FailedEvent failed : failedEvents.values()) {
                markRecoveryUnavailable(failedKeyFor(failed), failed.event(), failed.partner());
            }
            recoveryBlocked = true;
            shutdownCheckpointRequested = false;
            plugin.getLogger().severe("CRITICAL HEALTH: accepted economy writes remain owned because the durable "
                    + "recovery spool is unavailable; the consumer worker will remain alive");
            return false;
        }

        // Clear the interrupt used to break an in-flight wait. A failed durable handoff must
        // continue retrying instead of exiting with accepted state only in memory.
        Thread.interrupted();
        while (true) {
            Map<String, List<EconomyEvent>> outstandingByKey = new LinkedHashMap<>();
            for (EconomyEvent event : queue.snapshotOutstandingEvents()) {
                outstandingByKey.computeIfAbsent(recoveryKey(event, null), ignored -> new ArrayList<>()).add(event);
            }

            Map<String, FailedEvent> failures = new LinkedHashMap<>(failedEvents);
            // A broken transfer pair can have consumed an unrelated second queue item. Keep that
            // item in the same ambiguous checkpoint; never let it reappear as an independent retry.
            for (Map.Entry<String, FailedEvent> entry : failures.entrySet()) {
                FailedEvent failed = entry.getValue();
                if (failed.partner() != null && isTransferType(failed.event())
                        && !TransferRequestIds.arePair(failed.event(), failed.partner())) {
                    String partnerKey = recoveryKey(failed.partner(), null);
                    List<EconomyEvent> partnerEvents = outstandingByKey.get(partnerKey);
                    if (partnerEvents != null) partnerEvents.remove(failed.partner());
                    List<EconomyEvent> operationEvents = outstandingByKey.computeIfAbsent(
                            entry.getKey(), ignored -> new ArrayList<>());
                    if (!operationEvents.contains(failed.event())) operationEvents.add(failed.event());
                    if (!operationEvents.contains(failed.partner())) operationEvents.add(failed.partner());
                }
            }

            Set<String> operationKeys = new LinkedHashSet<>();
            outstandingByKey.forEach((key, events) -> {
                if (!events.isEmpty()) operationKeys.add(key);
            });
            operationKeys.addAll(failures.keySet());

            boolean allCheckpointed = true;
            for (String key : operationKeys) {
                List<EconomyEvent> events = outstandingByKey.getOrDefault(key, List.of());
                FailedEvent failed = failures.get(key);
                RecoveryCheckpoint previous = durableRecovery.get(key);
                RecoveryCheckpoint checkpoint = shutdownCheckpoint(key, events, failed, previous);
                if (checkpoint == null) {
                    allCheckpointed = false;
                    plugin.getLogger().severe("CRITICAL HEALTH: cannot represent accepted recovery operation "
                            + key + "; queue ownership remains held for operator recovery");
                    continue;
                }

                markRecoveryUnavailable(key, checkpoint.event(), checkpoint.partner());
                if (!checkpoint(key, checkpoint.event(), checkpoint.partner(), checkpoint.receipt(),
                        checkpoint.retryCount(), checkpoint.firstFailureTime(), checkpoint.stage())) {
                    allCheckpointed = false;
                    continue;
                }

                LinkedHashSet<EconomyEvent> releaseAfterCheckpoint = new LinkedHashSet<>(events);
                if (failed != null) {
                    releaseAfterCheckpoint.add(failed.event());
                    if (failed.partner() != null) releaseAfterCheckpoint.add(failed.partner());
                }
                if (previous != null) {
                    releaseAfterCheckpoint.add(previous.event());
                    if (previous.partner() != null) releaseAfterCheckpoint.add(previous.partner());
                }
                releaseAfterCheckpoint.forEach(queue::quarantine);
            }

            if (allCheckpointed && queue.snapshotOutstandingEvents().isEmpty()) {
                plugin.getLogger().info("Accepted economy writes were handed off to recovery-v1.jsonl after "
                        + "the shutdown drain deadline");
                return true;
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException interrupted) {
                Thread.interrupted();
            }
        }
    }

    private RecoveryCheckpoint shutdownCheckpoint(String key, List<EconomyEvent> outstanding,
            FailedEvent failed, RecoveryCheckpoint previous) {
        List<EconomyEvent> transferLegs = new ArrayList<>();
        if (failed != null && isTransferType(failed.event())) {
            addDistinctEvent(transferLegs, failed.event());
            addDistinctEvent(transferLegs, failed.partner());
        }
        if (previous != null && isTransferType(previous.event())) {
            addDistinctEvent(transferLegs, previous.event());
            addDistinctEvent(transferLegs, previous.partner());
        }
        for (EconomyEvent event : outstanding) {
            if (isTransferType(event)) addDistinctEvent(transferLegs, event);
        }
        if (!transferLegs.isEmpty()) {
            EconomyEvent out = null;
            EconomyEvent in = null;
            for (int firstIndex = 0; firstIndex < transferLegs.size() && out == null; firstIndex++) {
                for (int secondIndex = firstIndex + 1; secondIndex < transferLegs.size(); secondIndex++) {
                    EconomyEvent first = transferLegs.get(firstIndex);
                    EconomyEvent second = transferLegs.get(secondIndex);
                    if (TransferRequestIds.arePair(first, second)) {
                        out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : second;
                        in = out == first ? second : first;
                        break;
                    }
                }
            }
            if (out == null) return null;

            ReplicationReceipt receipt = failed != null && failed.confirmedReceipt() != null
                    ? failed.confirmedReceipt() : previous == null ? null : previous.receipt();
            RecoveryStage stage;
            if (receipt != null) {
                stage = failed != null && failed.confirmedReceipt() != null
                        ? recoveryStage(failed) : previous.stage();
            } else if (failed != null && (failed.quarantined() || failed.reconciliationOnly())) {
                stage = recoveryStage(failed);
            } else if (previous != null && (previous.stage() == RecoveryStage.ATTEMPTING
                    || previous.stage() == RecoveryStage.AMBIGUOUS
                    || previous.stage() == RecoveryStage.RECONCILE)) {
                stage = previous.stage();
            } else {
                stage = RecoveryStage.RETRYABLE;
            }
            int retryCount = failed != null ? failed.retryCount() : previous == null ? 0 : previous.retryCount();
            long firstFailureTime = failed != null ? failed.firstFailureTime()
                    : previous == null ? System.currentTimeMillis() : previous.firstFailureTime();
            return new RecoveryCheckpoint(1, stage, out, in, receipt, retryCount, firstFailureTime);
        }

        if (failed != null) {
            RecoveryStage stage = recoveryStage(failed);
            if (isTransferType(failed.event()) && failed.partner() != null
                    && !TransferRequestIds.arePair(failed.event(), failed.partner())) {
                stage = RecoveryStage.AMBIGUOUS;
            }
            return new RecoveryCheckpoint(1, stage, failed.event(), failed.partner(),
                    failed.confirmedReceipt(), failed.retryCount(), failed.firstFailureTime());
        }
        if (previous != null) return previous;
        if (outstanding.isEmpty()) return null;

        EconomyEvent event = outstanding.get(0);
        if (!isTransferType(event)) {
            return new RecoveryCheckpoint(1, RecoveryStage.RETRYABLE, event, null, null, 0,
                    System.currentTimeMillis());
        }
        for (int index = 1; index < outstanding.size(); index++) {
            EconomyEvent candidate = outstanding.get(index);
            if (TransferRequestIds.arePair(event, candidate)) {
                return new RecoveryCheckpoint(1, RecoveryStage.RETRYABLE, event, candidate, null, 0,
                        System.currentTimeMillis());
            }
        }
        EconomyEvent partner = outstanding.size() == 2 ? outstanding.get(1) : null;
        return new RecoveryCheckpoint(1, RecoveryStage.AMBIGUOUS, event, partner, null, 0,
                System.currentTimeMillis());
    }

    private static void addDistinctEvent(List<EconomyEvent> events, EconomyEvent candidate) {
        if (candidate != null && !events.contains(candidate)) events.add(candidate);
    }

    private static String failedKeyFor(FailedEvent failed) {
        return isTransferType(failed.event()) ? failedTransferKey(failed.event()) : failedKey(failed.event());
    }

    private boolean hasOutstandingOwnership(FailedEvent failed) {
        List<EconomyEvent> outstanding = queue.snapshotOutstandingEvents();
        return outstanding.contains(failed.event())
                || failed.partner() != null && outstanding.contains(failed.partner());
    }

    private void retainWithoutSpool(String key, FailedEvent failed) {
        FailedEvent quarantined = failed.asQuarantined();
        if (isTransferType(failed.event())) {
            queue.markTransferUnavailable(key, failed.event().playerUuid(),
                    failed.partner() == null ? null : failed.partner().playerUuid());
        } else {
            queue.markAccountUnavailable(failed.event().playerUuid());
        }
        if (!failedEvents.replace(key, failed, quarantined)) {
            failedEvents.putIfAbsent(key, quarantined);
        }
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
        String key = failedKey(event);
        ReplicationReceipt confirmedReceipt = null;
        long firstFailureTime = System.currentTimeMillis();
        FailedEvent initial = new FailedEvent(event, 0, firstFailureTime, null);
        if (!checkpoint(key, initial, RecoveryStage.ATTEMPTING)) {
            failedEvents.putIfAbsent(key, initial);
            return;
        }
        try {
            ReplicationReceipt receipt = processRedisWrite(event);

            if (receipt == null) {
                plugin.getLogger().warning("Redis write failed for accepted event " + event.requestId()
                        + "; retaining it for retry after authority recovery");
                FailedEvent retryable = initial.nextAttempt();
                checkpoint(key, retryable, RecoveryStage.RETRYABLE);
                failedEvents.putIfAbsent(key, retryable);
                return;
            }

            confirmedReceipt = receipt;
            FailedEvent confirmed = initial.withConfirmedReceipt(receipt);
            if (!checkpoint(key, confirmed, RecoveryStage.CONFIRMED)) {
                failedEvents.putIfAbsent(key, confirmed);
                return;
            }
            processEventSteps(event, receipt);
            ConfirmedAccount account = receipt.accountFor(event.playerUuid());
            queue.complete(event, account.balance(), account.version());
            enqueueShadowSyncEvent(event, receipt);
            acknowledgeRecovery(key);

        } catch (RedisMutationException e) {
            if (confirmedReceipt == null && e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                queue.markAccountUnavailable(event.playerUuid());
                FailedEvent rejected = new FailedEvent(event, null, 0, firstFailureTime, null, false, true);
                checkpoint(key, rejected, RecoveryStage.RECONCILE);
                FailedEvent existing = failedEvents.putIfAbsent(key, rejected);
                processRejectedEventReconciliation(key, existing == null ? rejected : existing,
                        System.currentTimeMillis(), 100L, 30000L, true);
                return;
            }
            if (confirmedReceipt == null
                    && e.executionState() == RedisMutationException.ExecutionState.SENT_UNKNOWN) {
                FailedEvent ambiguous = new FailedEvent(event, 0, firstFailureTime, null, true);
                queue.markAccountUnavailable(event.playerUuid());
                checkpoint(key, ambiguous, RecoveryStage.AMBIGUOUS);
                failedEvents.putIfAbsent(key, ambiguous);
                plugin.getLogger().severe("CRITICAL HEALTH: quarantined accepted economy event after Redis "
                        + e.executionState() + "; automatic retry is disabled for request " + event.requestId());
                return;
            }
            FailedEvent retryable = confirmedReceipt == null
                    ? initial.nextAttempt() : initial.withConfirmedReceipt(confirmedReceipt).nextAttempt();
            if (confirmedReceipt == null) checkpoint(key, retryable, RecoveryStage.RETRYABLE);
            else checkpoint(key, retryable, RecoveryStage.CONFIRMED);
            failedEvents.putIfAbsent(key, retryable);
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to process economy event: " + e.getMessage(), e);
        } catch (Exception e) {
            if (confirmedReceipt == null) {
                FailedEvent ambiguous = new FailedEvent(event, 0, firstFailureTime, null, true);
                queue.markAccountUnavailable(event.playerUuid());
                checkpoint(key, ambiguous, RecoveryStage.AMBIGUOUS);
                failedEvents.putIfAbsent(key, ambiguous);
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "CRITICAL HEALTH: untyped failure before an authority receipt; quarantined "
                                + "accepted operation " + event.requestId(), e);
                return;
            }
            FailedEvent confirmed = initial.withConfirmedReceipt(confirmedReceipt).nextAttempt();
            checkpoint(key, confirmed, RecoveryStage.CONFIRMED);
            failedEvents.putIfAbsent(key, confirmed);
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to process confirmed economy event downstream: " + event.requestId(), e);
        }
    }

    private void processTransferEvent(EconomyEvent first) {
        long firstFailureTime = System.currentTimeMillis();
        EconomyEvent second;
        try {
            second = queue.poll(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            if (deferInterruptedTransfer(first, firstFailureTime)) return;
            Thread.currentThread().interrupt();
            second = null;
        }
        if (!TransferRequestIds.arePair(first, second)) {
            String orphanKey = failedTransferKey(first);
            FailedEvent orphan = new FailedEvent(first, second, 0, firstFailureTime,
                    null, true, false);
            failedEvents.putIfAbsent(orphanKey, orphan);
            quarantineTransfer(orphanKey, orphan, "accepted transfer queue legs could not be paired");
            publishTransferPostEvents(first, second, null, TransferResolution.UNKNOWN);
            queue.completeTransferObservation(TransferRequestIds.operationId(first),
                    EconomyWriteQueue.TransferOutcome.UNKNOWN, null, null);
            plugin.getLogger().severe("CRITICAL HEALTH: accepted transfer did not have its matching queue leg; "
                    + "both accounts are unavailable: "
                    + TransferRequestIds.operationId(first));
            return;
        }

        EconomyEvent out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : second;
        EconomyEvent in = out == first ? second : first;
        String key = failedTransferKey(out);
        FailedEvent initial = new FailedEvent(out, in, 0, firstFailureTime, null);
        if (!checkpoint(key, initial, RecoveryStage.ATTEMPTING)) {
            failedEvents.putIfAbsent(key, initial);
            return;
        }
        try {
            TransferWriteResult write = executeTransferWrite(out, in);
            if (write.applied()) {
                FailedEvent confirmed = initial.withConfirmedReceipt(write.receipt());
                if (!checkpoint(key, confirmed, RecoveryStage.CONFIRMED)) {
                    failedEvents.putIfAbsent(key, confirmed);
                    return;
                }
                try {
                    processTransferEventSteps(out, in, write.receipt());
                    if (!completeTransferPair(out, in, write.receipt(), false)) {
                        queue.reconcileRecoveredPair(out, write.receipt().accountFor(out.playerUuid()).balance(),
                                write.receipt().accountFor(out.playerUuid()).version(), in,
                                write.receipt().accountFor(in.playerUuid()).balance(),
                                write.receipt().accountFor(in.playerUuid()).version(), false);
                    }
                    acknowledgeRecovery(key);
                } catch (Exception e) {
                    FailedEvent retry = confirmed.nextAttempt();
                    checkpoint(key, retry, RecoveryStage.CONFIRMED);
                    failedEvents.putIfAbsent(key, retry);
                    plugin.getLogger().severe("Confirmed transfer pair retained for downstream retry: "
                            + TransferRequestIds.operationId(out) + " - " + e.getMessage());
                }
            } else {
                markTransferUnavailable(out, in);
                FailedEvent rejected = new FailedEvent(out, in, 0, firstFailureTime,
                        write.receipt(), false, true);
                if (!checkpoint(key, rejected, write.receipt() == null
                        ? RecoveryStage.RECONCILE : RecoveryStage.RECONCILE_CONFIRMED)) {
                    failedEvents.putIfAbsent(key, rejected);
                    return;
                }
                FailedEvent existing = failedEvents.putIfAbsent(key, rejected);
                processFailedTransfer(key, existing == null ? rejected : existing,
                        System.currentTimeMillis() + 100L, 100L, 30000L);
            }
        } catch (RedisMutationException e) {
            if (e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                markTransferUnavailable(out, in);
                FailedEvent rejected = new FailedEvent(out, in, 0, firstFailureTime,
                        null, false, true);
                checkpoint(key, rejected, RecoveryStage.RECONCILE);
                FailedEvent existing = failedEvents.putIfAbsent(key, rejected);
                processFailedTransfer(key, existing == null ? rejected : existing,
                        System.currentTimeMillis() + 100L, 100L, 30000L);
            } else if (e.executionState() == RedisMutationException.ExecutionState.NOT_SENT) {
                FailedEvent retryable = initial.nextAttempt();
                checkpoint(key, retryable, RecoveryStage.RETRYABLE);
                failedEvents.putIfAbsent(key, retryable);
                plugin.getLogger().warning("Transfer pair was not sent to Redis; retaining both accepted legs for retry: "
                        + TransferRequestIds.operationId(out));
            } else {
                failedEvents.putIfAbsent(key, initial);
                quarantineTransfer(key, initial,
                        "transfer authority outcome is " + e.executionState());
            }
        } catch (Exception e) {
            failedEvents.putIfAbsent(key, initial);
            quarantineTransfer(key, initial,
                    "transfer authority outcome is unknown: " + e.getMessage());
        }
    }

    /**
     * An interrupted pair poll happens before any authority call. Keep both accepted legs
     * together for retry or shutdown handoff instead of treating the first leg as an orphan.
     */
    private boolean deferInterruptedTransfer(EconomyEvent first, long firstFailureTime) {
        String key = failedTransferKey(first);
        for (EconomyEvent candidate : queue.snapshotOutstandingEvents()) {
            if (candidate.equals(first) || !TransferRequestIds.arePair(first, candidate)) continue;
            EconomyEvent out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : candidate;
            EconomyEvent in = out == first ? candidate : first;
            failedEvents.putIfAbsent(key, new FailedEvent(out, in, 0, firstFailureTime, null));
            plugin.getLogger().warning("Transfer pair poll was interrupted before Redis execution; retained both "
                    + "accepted legs for safe retry: " + TransferRequestIds.operationId(out));
            return true;
        }

        FailedEvent incomplete = new FailedEvent(first, null, 0, firstFailureTime, null, true, false);
        failedEvents.putIfAbsent(key, incomplete);
        queue.markTransferUnavailable(key, first.playerUuid(), null);
        queue.closeAdmission();
        recoveryBlocked = true;
        plugin.getLogger().severe("CRITICAL HEALTH: transfer pair poll was interrupted and its accepted partner "
                + "could not be identified; retaining ownership and closing economy admission for "
                + TransferRequestIds.operationId(first));
        return true;
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

    private ReplicationReceipt readRejectedTransferAuthorityPair(EconomyEvent out, EconomyEvent in) {
        CacheManager.TransferResult snapshot = cacheManager.readRejectedTransferSnapshotStrict(
                out.playerUuid(), in.playerUuid());
        if (snapshot == null
                || !validAuthorityAccount(snapshot.fromAccountPresent,
                        snapshot.fromNewBalance, snapshot.fromNewVersion)
                || !validAuthorityAccount(snapshot.toAccountPresent,
                        snapshot.toNewBalance, snapshot.toNewVersion)) {
            throw new IllegalStateException("Redis returned an invalid rejected transfer authority snapshot");
        }
        List<ConfirmedAccount> accounts = new ArrayList<>(2);
        if (snapshot.fromAccountPresent) {
            accounts.add(new ConfirmedAccount(out.playerUuid(), snapshot.fromNewBalance, snapshot.fromNewVersion));
        }
        if (snapshot.toAccountPresent) {
            accounts.add(new ConfirmedAccount(in.playerUuid(), snapshot.toNewBalance, snapshot.toNewVersion));
        }
        // An omitted UUID is a confirmed absent account; the transfer events retain both UUIDs
        // in the existing recovery-v1 receipt shape.
        return new ReplicationReceipt(TransferRequestIds.operationId(out), accounts);
    }

    private static boolean validAuthorityAccount(boolean present, BigDecimal balance, long version) {
        return present ? balance != null && version >= 0L : balance == null && version == -1L;
    }

    private static AuthorityAccount authorityAccount(ReplicationReceipt receipt, UUID uuid) {
        return receipt.findAccount(uuid)
                .map(account -> new AuthorityAccount(account.balance(), account.version()))
                .orElseGet(() -> new AuthorityAccount(null, -1L));
    }

    private void completeRecoveredTransferPair(EconomyEvent first, EconomyEvent second,
            ReplicationReceipt receipt, boolean rejected) {
        AuthorityAccount firstAuthority = authorityAccount(receipt, first.playerUuid());
        AuthorityAccount secondAuthority = authorityAccount(receipt, second.playerUuid());
        queue.reconcileRecoveredPair(first, firstAuthority.balance(), firstAuthority.version(),
                second, secondAuthority.balance(), secondAuthority.version(), rejected);
        if (!rejected) {
            enqueueShadowSyncEvent(first, receipt);
            enqueueShadowSyncEvent(second, receipt);
        }
    }

    private static ReplicationReceipt transferReceipt(EconomyEvent out, EconomyEvent in,
            CacheManager.TransferResult result) {
        return new ReplicationReceipt(TransferRequestIds.operationId(out), List.of(
                new ConfirmedAccount(out.playerUuid(), result.fromNewBalance, result.fromNewVersion),
                new ConfirmedAccount(in.playerUuid(), result.toNewBalance, result.toNewVersion)));
    }

    private boolean completeTransferPair(EconomyEvent out, EconomyEvent in, ReplicationReceipt receipt,
            boolean rejected) {
        AuthorityAccount sender = authorityAccount(receipt, out.playerUuid());
        AuthorityAccount receiver = authorityAccount(receipt, in.playerUuid());
        if (!rejected && (sender.balance() == null || receiver.balance() == null)) return false;
        boolean completed = rejected
                ? queue.completeRejectedPair(out, sender.balance(), sender.version(),
                        in, receiver.balance(), receiver.version())
                : queue.completePair(out, sender.balance(), sender.version(),
                        in, receiver.balance(), receiver.version());
        if (!completed) {
            return false;
        }
        if (!rejected) {
            enqueueShadowSyncEvent(out, receipt);
            enqueueShadowSyncEvent(in, receipt);
        }
        if (rejected) {
            plugin.getLogger().warning("Redis rejected accepted transfer pair "
                    + TransferRequestIds.operationId(out) + " (" + out.playerUuid() + " -> "
                    + in.playerUuid() + ", " + out.delta().abs().toPlainString()
                    + "); authority balances are " + formatAuthorityBalance(sender.balance())
                    + " and " + formatAuthorityBalance(receiver.balance()));
            publishTransferPostEvents(out, in, receipt, TransferResolution.REJECTED);
        } else {
            publishTransferPostEvents(out, in, receipt, TransferResolution.CONFIRMED);
        }
        return true;
    }

    private static String formatAuthorityBalance(BigDecimal balance) {
        return balance == null ? "absent" : balance.toPlainString();
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
        String senderName = nameResolver == null ? null : nameResolver.getNameCachedOnly(out.playerUuid());
        boolean outStepsComplete = processEventSteps(out, receipt, false);
        boolean inStepsComplete = processEventSteps(in, receipt, false, senderName);
        persistTransferPair(out, in, receipt, false);
        if (!outStepsComplete || !inStepsComplete) {
            throw new IllegalStateException("Required downstream work failed for confirmed transfer "
                    + receipt.operationId());
        }
    }

    private void persistTransferPair(EconomyEvent out, EconomyEvent in, ReplicationReceipt receipt,
            boolean rejected) {
        if (!config.isDbEnabled()) return;
        DbWriteQueue.DbWriteTask task = transferDbWriteTask(out, in, receipt);
        if (task == null) return;
        CompletableFuture<Boolean> completion = dbWriteQueue == null ? null : dbWriteQueue.offerAndTrack(task);
        if (completion != null) {
            if (awaitSqlPersistence(completion, receipt.operationId())) return;
            throw new IllegalStateException("Confirmed transfer pair SQL receipt was not confirmed: "
                    + receipt.operationId());
        }

        plugin.getLogger().severe("DbWriteQueue rejected confirmed transfer pair; attempting one SQL transaction: "
                + receipt.operationId());
        if (databaseManager == null) {
            throw new IllegalStateException("Confirmed transfer pair has no SQL persistence owner");
        }
        List<DbWriteQueue.DbWriteTask> stale = databaseManager.batchInsertOrUpdatePlayersWithStale(List.of(task));
        if (stale.isEmpty()) return;

        ReplicationReceipt latest = rejected
                ? readRejectedTransferAuthorityPair(out, in) : readTransferAuthorityPair(out, in);
        DbWriteQueue.DbWriteTask refreshed = transferDbWriteTask(out, in, latest);
        if (refreshed == null) {
            throw new IllegalStateException("Redis transfer authority is absent while SQL has a stale account row");
        }
        if (!databaseManager.batchInsertOrUpdatePlayersWithStale(List.of(refreshed)).isEmpty()) {
            throw new IllegalStateException("Latest authority transfer pair is still stale in SQL");
        }
    }

    private DbWriteQueue.DbWriteTask transferDbWriteTask(EconomyEvent out, EconomyEvent in,
            ReplicationReceipt receipt) {
        String serverName = config.getServerName() != null ? config.getServerName() : "unknown";
        java.time.Instant timestamp = java.time.Instant.now();
        List<DbWriteQueue.AccountWrite> writes = new ArrayList<>(2);
        receipt.findAccount(out.playerUuid()).ifPresent(account -> writes.add(
                new DbWriteQueue.AccountWrite(account, nameResolver.getNameCachedOnly(account.playerUuid()),
                        serverName, timestamp)));
        receipt.findAccount(in.playerUuid()).ifPresent(account -> writes.add(
                new DbWriteQueue.AccountWrite(account, nameResolver.getNameCachedOnly(account.playerUuid()),
                        serverName, timestamp)));
        return writes.isEmpty() ? null : new DbWriteQueue.DbWriteTask(receipt.operationId(), writes);
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
        if (!processEventSteps(event, receipt, true)) {
            throw new IllegalStateException("Required downstream work failed for confirmed operation "
                    + receipt.operationId());
        }
    }

    private boolean processEventSteps(EconomyEvent event, ReplicationReceipt receipt, boolean persistSql) {
        return processEventSteps(event, receipt, persistSql, null);
    }

    private boolean processEventSteps(EconomyEvent event, ReplicationReceipt receipt, boolean persistSql,
            String sourcePlayerName) {
        ConfirmedAccount account = receipt.accountFor(event.playerUuid());
        BigDecimal newBalance = account.balance();
        boolean requiredStepsComplete = true;

        if (config.isPubsubEnabled()) {
            requiredStepsComplete &= publishUpdate(event, account, sourcePlayerName);
        }

        if (persistSql && config.isDbEnabled()) {
            requiredStepsComplete &= enqueueDbWrite(event, receipt);
        }

        if (hybridAuditManager != null && hybridAuditManager.isEnabled()
                && event.source() != EconomyEvent.EventSource.TEST) {
            requiredStepsComplete &= recordAuditLog(event, newBalance);
        } else if (auditLogger != null && auditLogger.isEnabled()
                && event.source() != EconomyEvent.EventSource.TEST) {
            requiredStepsComplete &= recordAuditLog(event, newBalance);
        }

        if (baltopManager != null && event.source() != EconomyEvent.EventSource.TEST) {
            updateBaltop(event.playerUuid(), newBalance.doubleValue());
        }

        return requiredStepsComplete;
    }

    /**
     * Enqueue a confirmed receipt only after the queue's completion handler has reconciled
     * optimistic memory to the authority pair. ShadowSyncTask also revalidates the pair when
     * admitting and flushing it, so a later write safely supersedes this receipt.
     */
    private void enqueueShadowSyncEvent(EconomyEvent event, ReplicationReceipt receipt) {
        if (shadowSyncTask == null || !config.shadowSync().isShadowSyncEnabled()
                || event.source() == EconomyEvent.EventSource.TEST) {
            return;
        }
        ConfirmedAccount account = receipt.findAccount(event.playerUuid()).orElse(null);
        if (account == null) return;
        String playerName = nameResolver == null ? null : nameResolver.getNameCachedOnly(event.playerUuid());
        if (playerName != null) {
            shadowSyncTask.enqueueSyncEvent(
                    event.playerUuid(), playerName, account.balance(), account.version());
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
    private boolean publishUpdate(EconomyEvent event, ConfirmedAccount account) {
        return publishUpdate(event, account, null);
    }

    private boolean publishUpdate(EconomyEvent event, ConfirmedAccount account, String sourcePlayerName) {
        String serverName = config.getServerName();
        PubSubMessage message = createPubSubMessage(event, account, serverName, sourcePlayerName);

        try (var jedis = redisManager.getResource()) {
            jedis.publish(PUBSUB_CHANNEL, message.toJson());
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish Pub/Sub: " + e.getMessage());
            return false;
        }
    }

    static PubSubMessage createPubSubMessage(EconomyEvent event, ConfirmedAccount account,
            String serverName) {
        return createPubSubMessage(event, account, serverName, null);
    }

    static PubSubMessage createPubSubMessage(EconomyEvent event, ConfirmedAccount account,
            String serverName, String sourcePlayerName) {
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
                sourcePlayerName);
    }

    /** Enqueue the authority receipt without reading cache state again. */
    private boolean enqueueDbWrite(EconomyEvent event, ReplicationReceipt receipt) {
        ConfirmedAccount account = receipt.accountFor(event.playerUuid());
        String serverName = config.getServerName();
        String playerName = nameResolver.getNameCachedOnly(event.playerUuid());

        var task = new DbWriteQueue.DbWriteTask(receipt.operationId(), account, playerName,
                serverName != null ? serverName : "unknown", java.time.Instant.now());

        CompletableFuture<Boolean> completion = dbWriteQueue == null ? null : dbWriteQueue.offerAndTrack(task);
        if (completion != null) return awaitSqlPersistence(completion, receipt.operationId());

        plugin.getLogger().severe("CRITICAL: DbWriteQueue rejected confirmed write, attempting direct fallback for "
                + event.playerUuid());
        if (tryDirectDbWrite(account, playerName, serverName)) return true;

        plugin.getLogger().severe("CRITICAL: DbWriteQueue and direct DB fallback failed for " + event.playerUuid()
                + "; preserving the confirmed recovery checkpoint");
        // The spool already owns this operation's confirmed receipt. A duplicate legacy line
        // would be read as permanently ambiguous on the next startup and block the account
        // after the confirmed checkpoint has recovered successfully.
        if (recoverySpool == null) {
            logEventToOverflowLog(event);
        }
        return false;
    }

    /** Queue admission is not a persistence receipt; retain the recovery checkpoint until the writer confirms SQL. */
    private boolean awaitSqlPersistence(CompletableFuture<Boolean> completion, String operationId) {
        try {
            boolean persisted = Boolean.TRUE.equals(completion.get(
                    SQL_WRITE_ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            if (!persisted) {
                plugin.getLogger().severe("CRITICAL: SQL writer exhausted or rejected confirmed operation "
                        + operationId + "; its recovery checkpoint remains pending");
            }
            return persisted;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("SQL persistence wait was interrupted for confirmed operation "
                    + operationId + "; retaining its recovery checkpoint");
            return false;
        } catch (ExecutionException | TimeoutException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "SQL persistence receipt was not confirmed for operation " + operationId
                            + "; retaining its recovery checkpoint", failure);
            return false;
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
    private boolean recordAuditLog(EconomyEvent event, BigDecimal newBalance) {
        try {
            String playerName = nameResolver.getNameCachedOnly(event.playerUuid());
            if (playerName == null) {
                playerName = "Unknown";
            }
            String serverName = config.getServerName() != null ? config.getServerName() : "unknown";


            AuditRecord record = confirmedAuditRecord(event, playerName, serverName, newBalance);


            if (hybridAuditManager != null && hybridAuditManager.isEnabled()) {
                hybridAuditManager.persistConfirmedRecord(record);
            } 

            else if (auditLogger != null && auditLogger.isEnabled()) {
                auditLogger.persistConfirmedRecord(record);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to record audit log: " + e.getMessage());
            return false;
        }
    }

    private static AuditRecord confirmedAuditRecord(EconomyEvent event, String playerName,
            String serverName, BigDecimal balanceAfter) {
        AuditRecord record = AuditRecord.fromEconomyEvent(event, playerName, serverName, balanceAfter, 1, 0);
        String id = record.id();
        if (id == null || id.isBlank() || id.length() > 36) {
            String identity = operationId(event) + ":" + event.type() + ":" + event.playerUuid();
            id = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        }
        return new AuditRecord(id, record.timestamp(), record.sequence(), record.type(), record.playerUuid(),
                record.playerName(), record.amount(), record.balanceAfter(), record.source(), record.server(),
                record.targetUuid(), record.targetName(), record.reason(), record.mergedCount());
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
