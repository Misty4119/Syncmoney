package noietime.syncmoney.economy;
import noietime.syncmoney.core.state.AccountState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.AuditRecord;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.recovery.RecoverySpool;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.*;

class EconomyEventConsumerRecoveryTest {
    @TempDir
    Path temp;

    @Test
    void confirmedReceiptSurvivesRestartAndRetriesDownstreamWithoutRepeatingRedis() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "confirmed-retry-1");
        Path journal = temp.resolve("recovery-v1.jsonl");
        RecoverySpool spool = new RecoverySpool(journal);
        Plugin plugin = mock(Plugin.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        SyncmoneyConfig config = config();
        CacheManager cache = mock(CacheManager.class);
        DbWriteQueue firstDbQueue = mock(DbWriteQueue.class);
        DbWriteQueue recoveryDbQueue = mock(DbWriteQueue.class);
        NameResolver names = mock(NameResolver.class);
        when(names.getNameCachedOnly(uuid)).thenReturn("account");
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        when(firstDbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(recoveryDbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(CompletableFuture.completedFuture(true));

        EconomyEventConsumer beforeRestart = consumer(plugin, config, new EconomyWriteQueue(10), cache,
                firstDbQueue, names, spool);
        invokeProcessEvent(beforeRestart, event);

        List<RecoverySpool.Entry> afterFailure = spool.pending();
        assertEquals(1, afterFailure.size());
        assertEquals("CONFIRMED", stage(afterFailure.get(0)));
        assertTrue(afterFailure.get(0).payload().contains("110.00"));

        EconomyWriteQueue recoveredQueue = new EconomyWriteQueue(10);
        AtomicInteger memoryReconciliations = new AtomicInteger();
        recoveredQueue.setWriteCompletionHandler(new EconomyWriteQueue.WriteCompletionHandler() {
            @Override
            public void onComplete(EconomyEvent recovered, BigDecimal balance, long version) {
                assertEquals(uuid, recovered.playerUuid());
                assertEquals(new BigDecimal("110.00"), balance);
                assertEquals(9L, version);
                memoryReconciliations.incrementAndGet();
            }

            @Override
            public void withAccountsLocked(UUID firstAccount, UUID secondAccount, Runnable completion) {
                completion.run();
            }
        });
        EconomyEventConsumer afterRestart = consumer(plugin, config, recoveredQueue, cache,
                recoveryDbQueue, names, new RecoverySpool(journal));

        afterRestart.start();
        try {
            await(() -> pending(new RecoverySpool(journal)).isEmpty(), 2, TimeUnit.SECONDS,
                    "recovered downstream work should be acknowledged after retry");
        } finally {
            afterRestart.stop();
            afterRestart.shutdown();
        }

        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, BigDecimal.TEN);
        verify(recoveryDbQueue, times(1)).offerAndTrack(any(DbWriteQueue.DbWriteTask.class));
        assertEquals(1, memoryReconciliations.get(),
                "a recovered receipt must reconcile memory even though its old queue owner is gone");
        verify(logger, atLeastOnce()).severe(contains("1.3.2 rollback remains blocked"));
    }

    @Test
    void attemptingRecordIsQuarantinedAcrossRestartWithoutRedisReplay() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "ambiguous-attempt-1");
        Path journal = temp.resolve("recovery-v1.jsonl");
        RecoverySpool spool = new RecoverySpool(journal);
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        CacheManager cache = mock(CacheManager.class);
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        NameResolver names = mock(NameResolver.class);
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN)).thenThrow(new RedisMutationException(
                RedisMutationException.ExecutionState.SENT_UNKNOWN, "response was lost", null));

        EconomyEventConsumer beforeRestart = consumer(plugin, config, new EconomyWriteQueue(10), cache,
                dbQueue, names, spool);
        invokeProcessEvent(beforeRestart, event);
        assertEquals("AMBIGUOUS", stage(spool.pending().get(0)));

        CopyOnWriteArrayList<UUID> unavailable = new CopyOnWriteArrayList<>();
        EconomyWriteQueue recoveredQueue = new EconomyWriteQueue(10);
        recoveredQueue.setUnavailableAccountHandler(unavailable::add);
        EconomyEventConsumer afterRestart = consumer(plugin, config, recoveredQueue, cache,
                dbQueue, names, new RecoverySpool(journal));
        afterRestart.start();
        try {
            await(() -> unavailable.contains(uuid), 2, TimeUnit.SECONDS,
                    "ambiguous recovered accounts must remain unavailable");
        } finally {
            afterRestart.stop();
            afterRestart.shutdown();
        }

        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, BigDecimal.TEN);
        assertEquals(1, pending(new RecoverySpool(journal)).size(),
                "ambiguous evidence must remain pending for operator reconciliation");
    }

    @Test
    void recoveredRejectedTransferRefreshesStoredAbsenceBeforeReconcilingNewerAcceptedAccount() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, new BigDecimal("-70.00"), BigDecimal.ZERO, 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, new BigDecimal("70.00"), new BigDecimal("70.00"), 1L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        ReplicationReceipt oldAbsentReceipt = new ReplicationReceipt(operationId, List.of());

        Plugin plugin = plugin();
        CacheManager cache = mock(CacheManager.class);
        when(cache.readRejectedTransferSnapshotStrict(sender, receiver)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("20.00"), new BigDecimal("5.00"),
                        8L, 1L, true, true));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, new AccountState(new BigDecimal("20.00"), 8L, now));
        // This is a later accepted write restored first after restart; it may share the
        // absent receiver's initial authority version while having a different balance.
        memory.put(receiver, new AccountState(new BigDecimal("5.00"), 1L, now + 1L));
        queue.markTransferUnavailable(operationId, sender, receiver);
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        when(dbQueue.offerAndTrack(any())).thenReturn(CompletableFuture.completedFuture(true));
        EconomyEventConsumer consumer = consumer(plugin, config(), queue, cache,
                dbQueue, mock(NameResolver.class), null);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        var constructor = failedEventType.getDeclaredConstructor(EconomyEvent.class, EconomyEvent.class,
                int.class, long.class, ReplicationReceipt.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object failed = constructor.newInstance(out, in, 0, now - 5_000L, oldAbsentReceipt,
                false, true, true);
        Field failedEventsField = EconomyEventConsumer.class.getDeclaredField("failedEvents");
        failedEventsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var failedEvents = (java.util.concurrent.ConcurrentMap<String, Object>) failedEventsField.get(consumer);
        String key = operationId;
        failedEvents.put(key, failed);

        Method process = EconomyEventConsumer.class.getDeclaredMethod("processFailedTransfer",
                String.class, failedEventType, long.class, long.class, long.class);
        process.setAccessible(true);
        process.invoke(consumer, key, failed, System.currentTimeMillis(), 1_000L, 30_000L);

        assertAll(
                () -> verify(cache).readRejectedTransferSnapshotStrict(sender, receiver),
                () -> assertEquals(new BigDecimal("20.00"), memory.get(sender).balance()),
                () -> assertEquals(new BigDecimal("5.00"), memory.get(receiver).balance(),
                        "the stored absence must not erase a later confirmed account snapshot"),
                () -> assertFalse(memory.isUnavailable(sender)),
                () -> assertFalse(memory.isUnavailable(receiver)),
                () -> assertTrue(failedEvents.isEmpty(), "successful fresh reconciliation acknowledges the old checkpoint")
        );
    }

    @Test
    void recoveredRejectedTransferPersistsReceiptFreeCheckpointBeforeRefreshingAuthority() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, new BigDecimal("-70.00"), BigDecimal.ZERO, 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, new BigDecimal("70.00"), new BigDecimal("70.00"), 1L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        ReplicationReceipt storedReceipt = new ReplicationReceipt(operationId, List.of(
                new ConfirmedAccount(sender, new BigDecimal("20.00"), 8L),
                new ConfirmedAccount(receiver, new BigDecimal("70.00"), 1L)));
        RecoverySpool spool = new RecoverySpool(temp.resolve("rejected-transfer-refresh.jsonl"));
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        when(cache.readRejectedTransferSnapshotStrict(sender, receiver)).thenAnswer(invocation -> {
            JsonNode checkpoint = new ObjectMapper().readTree(pending(spool).get(0).payload());
            assertEquals("RECONCILE", checkpoint.path("stage").asText());
            assertTrue(checkpoint.path("receipt").isNull(),
                    "a refresh-in-progress checkpoint must satisfy the recovery validator");
            return new CacheManager.TransferResult(new BigDecimal("20.00"), new BigDecimal("5.00"),
                    8L, 1L, true, true);
        });
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, new AccountState(new BigDecimal("20.00"), 8L, now));
        memory.put(receiver, new AccountState(new BigDecimal("5.00"), 1L, now + 1L));
        queue.markTransferUnavailable(operationId, sender, receiver);
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cache, mock(DbWriteQueue.class),
                mock(NameResolver.class), spool);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        var constructor = failedEventType.getDeclaredConstructor(EconomyEvent.class, EconomyEvent.class,
                int.class, long.class, ReplicationReceipt.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object failed = constructor.newInstance(out, in, 0, now - 5_000L, storedReceipt,
                false, true, true);
        Field failedEventsField = EconomyEventConsumer.class.getDeclaredField("failedEvents");
        failedEventsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var failedEvents = (java.util.concurrent.ConcurrentMap<String, Object>) failedEventsField.get(consumer);
        failedEvents.put(operationId, failed);

        Method process = EconomyEventConsumer.class.getDeclaredMethod("processFailedTransfer",
                String.class, failedEventType, long.class, long.class, long.class);
        process.setAccessible(true);
        process.invoke(consumer, operationId, failed, System.currentTimeMillis(), 1_000L, 30_000L);

        assertTrue(pending(spool).isEmpty(), "the refreshed authority receipt should complete reconciliation");
        verify(cache).readRejectedTransferSnapshotStrict(sender, receiver);
        assertEquals(new BigDecimal("5.00"), memory.get(receiver).balance());
        assertFalse(memory.isUnavailable(receiver));
    }

    @Test
    void recoveredRejectedAccountRefreshesAnOlderAbsentAuthorityReceipt() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EconomyEvent rejected = new EconomyEvent(uuid, new BigDecimal("70.00"), new BigDecimal("70.00"), 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "rejected-first-deposit", now);
        ReplicationReceipt oldAbsentReceipt = new ReplicationReceipt(rejected.requestId(), List.of());

        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        when(cache.readAuthorityBalanceSnapshotStrict(uuid)).thenReturn(java.util.Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("5.00"), 1L, now + 1L)));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(uuid, new AccountState(new BigDecimal("5.00"), 1L, now + 1L));
        memory.markUnavailable(uuid);
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cache, mock(DbWriteQueue.class),
                mock(NameResolver.class), null);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        var constructor = failedEventType.getDeclaredConstructor(EconomyEvent.class, EconomyEvent.class,
                int.class, long.class, ReplicationReceipt.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object failed = constructor.newInstance(rejected, null, 0, now - 5_000L, oldAbsentReceipt,
                false, true, true);
        Field failedEventsField = EconomyEventConsumer.class.getDeclaredField("failedEvents");
        failedEventsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var failedEvents = (java.util.concurrent.ConcurrentMap<String, Object>) failedEventsField.get(consumer);
        failedEvents.put(rejected.requestId(), failed);

        Method process = EconomyEventConsumer.class.getDeclaredMethod("processRejectedEventReconciliation",
                String.class, failedEventType, long.class, long.class, long.class, boolean.class);
        process.setAccessible(true);
        process.invoke(consumer, rejected.requestId(), failed, System.currentTimeMillis(),
                1_000L, 30_000L, false);

        verify(cache).readAuthorityBalanceSnapshotStrict(uuid);
        assertEquals(new BigDecimal("5.00"), memory.get(uuid).balance());
        assertFalse(memory.isUnavailable(uuid), "fresh authority should restore the later accepted account");
        assertTrue(failedEvents.isEmpty(), "the rejection checkpoint should be acknowledged after refresh");
    }

    @Test
    void confirmedCheckpointRemainsPendingWhenDbQueueAndDirectFallbackFailAndSkipsLegacyOverflow() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "sql-fallback-failed-1");
        RecoverySpool spool = new RecoverySpool(temp.resolve("sql-fallback-failed.jsonl"));
        OverflowLogInterface legacyOverflowLog = mock(OverflowLogInterface.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        assertTrue(queue.offer(event));
        CacheManager cache = mock(CacheManager.class);
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        when(dbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(null);
        SyncmoneyConfig config = config();
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(true);

        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin(), config, queue, cache,
                mock(RedisManager.class), dbQueue, null, null, mock(NameResolver.class), null, null,
                legacyOverflowLog, null, spool);
        invokeProcessEvent(consumer, event);

        List<RecoverySpool.Entry> pending = spool.pending();
        assertEquals(1, pending.size(), "the checkpoint must remain until SQL persistence succeeds");
        assertEquals("CONFIRMED", stage(pending.get(0)),
                "a rejected SQL queue offer and failed direct write must retain the exact confirmed receipt");
        assertTrue(queue.hasAcceptedWrite(uuid), "the accepted write must remain owned until SQL persistence succeeds");
        verify(legacyOverflowLog, never()).log(event);
    }

    @Test
    void confirmedCheckpointWaitsForTheSqlWriterReceipt() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "sql-receipt-wait-1");
        RecoverySpool spool = new RecoverySpool(temp.resolve("sql-receipt-wait.jsonl"));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        assertTrue(queue.offer(event));
        CacheManager cache = mock(CacheManager.class);
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        CompletableFuture<Boolean> sqlCompletion = new CompletableFuture<>();
        CountDownLatch sqlTaskAdmitted = new CountDownLatch(1);
        when(dbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            sqlTaskAdmitted.countDown();
            return sqlCompletion;
        });
        SyncmoneyConfig config = config();
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(true);

        EconomyEventConsumer consumer = consumer(plugin(), config, queue, cache, mock(RedisManager.class),
                dbQueue, null, null, mock(NameResolver.class), null, spool, null);
        Thread worker = new Thread(() -> {
            try {
                invokeProcessEvent(consumer, event);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }, "economy-recovery-sql-receipt-test");
        worker.start();

        assertTrue(sqlTaskAdmitted.await(2, TimeUnit.SECONDS), "the exact SQL receipt should be admitted");
        assertTrue(worker.isAlive(), "the consumer must not acknowledge on queue admission alone");
        assertEquals("CONFIRMED", stage(spool.pending().get(0)));
        assertTrue(queue.hasAcceptedWrite(uuid), "queue ownership remains until SQL persistence is confirmed");

        sqlCompletion.complete(false);
        worker.join(2_000L);

        assertFalse(worker.isAlive(), "the failed SQL receipt should release the consumer to retry/handoff");
        assertEquals("CONFIRMED", stage(spool.pending().get(0)));
        assertTrue(queue.hasAcceptedWrite(uuid), "a terminal SQL failure must retain accepted ownership");
        verify(dbQueue, times(1)).offerAndTrack(any(DbWriteQueue.DbWriteTask.class));
        verify(dbQueue, never()).offer(any(DbWriteQueue.DbWriteTask.class));
    }

    @Test
    void confirmedCheckpointRemainsPendingWhenPubSubPublishFails() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "pubsub-failed-1");
        RecoverySpool spool = new RecoverySpool(temp.resolve("pubsub-failed.jsonl"));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        assertTrue(queue.offer(event));
        CacheManager cache = mock(CacheManager.class);
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        RedisManager redis = mock(RedisManager.class);
        when(redis.getResource()).thenThrow(new IllegalStateException("publish unavailable"));
        SyncmoneyConfig config = config();
        when(config.isPubsubEnabled()).thenReturn(true);
        when(config.isDbEnabled()).thenReturn(false);

        EconomyEventConsumer consumer = consumer(plugin(), config, queue, cache, redis,
                null, null, null, mock(NameResolver.class), null, spool, null);
        invokeProcessEvent(consumer, event);

        List<RecoverySpool.Entry> pending = spool.pending();
        assertEquals(1, pending.size(), "the checkpoint must remain until Pub/Sub succeeds");
        assertEquals("CONFIRMED", stage(pending.get(0)),
                "a failed Pub/Sub publish must not acknowledge the confirmed recovery checkpoint");
        assertTrue(queue.hasAcceptedWrite(uuid), "the accepted write must remain owned until publish succeeds");
    }

    @Test
    void confirmedCheckpointRemainsPendingWhenAuditWriteFails() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "transfer:" + UUID.randomUUID() + ":out",
                EconomyEvent.EventSource.ADMIN_GIVE);
        RecoverySpool spool = new RecoverySpool(temp.resolve("audit-failed.jsonl"));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        assertTrue(queue.offer(event));
        CacheManager cache = mock(CacheManager.class);
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        AuditLogger auditLogger = mock(AuditLogger.class);
        when(auditLogger.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("audit persistence unavailable")).when(auditLogger)
                .persistConfirmedRecord(any(AuditRecord.class));
        SyncmoneyConfig config = config();
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(false);

        EconomyEventConsumer consumer = consumer(plugin(), config, queue, cache, mock(RedisManager.class),
                null, auditLogger, null, mock(NameResolver.class), null, spool, null);
        invokeProcessEvent(consumer, event);

        List<RecoverySpool.Entry> pending = spool.pending();
        assertEquals(1, pending.size(), "the checkpoint must remain until audit persistence succeeds");
        assertEquals("CONFIRMED", stage(pending.get(0)),
                "a failed audit write must not acknowledge the confirmed recovery checkpoint");
        assertTrue(queue.hasAcceptedWrite(uuid), "the accepted write must remain owned until audit succeeds");
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLogger).persistConfirmedRecord(record.capture());
        assertTrue(record.getValue().id().length() <= 36,
                "the recovery audit id must fit the existing SQL VARCHAR(36) contract");
    }

    @Test
    void recoverySpoolTerminalFailureDoesNotCreatePermanentLegacyOverflowRecord() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "terminal-recovery-1");
        RecoverySpool spool = new RecoverySpool(temp.resolve("terminal-recovery.jsonl"));
        OverflowLogInterface overflowLog = mock(OverflowLogInterface.class);
        Plugin plugin = plugin();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayer(uuid)).thenReturn(null);
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config(), new EconomyWriteQueue(10),
                mock(CacheManager.class), mock(RedisManager.class), null, null, null,
                mock(NameResolver.class), null, null, overflowLog,
                mock(DatabaseManager.class), spool);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        java.lang.reflect.Constructor<?> failedEventConstructor = failedEventType.getDeclaredConstructor(
                EconomyEvent.class, EconomyEvent.class, int.class, long.class, ReplicationReceipt.class,
                boolean.class, boolean.class, boolean.class);
        failedEventConstructor.setAccessible(true);
        Field retryCountField = EconomyEventConsumer.class.getDeclaredField("MAX_RETRY_COUNT");
        retryCountField.setAccessible(true);
        int maxRetryCount = retryCountField.getInt(null);
        Object failed = failedEventConstructor.newInstance(event, null, maxRetryCount,
                System.currentTimeMillis(), null, false, false, false);
        Method handleCriticalFailure = EconomyEventConsumer.class.getDeclaredMethod("handleCriticalFailure",
                failedEventType);
        handleCriticalFailure.setAccessible(true);

        handleCriticalFailure.invoke(consumer, failed);

        verify(overflowLog, never()).log(event);
    }

    @Test
    void interruptedTransferPairPollHandsOffBothAcceptedLegsBeforeReleasingEither() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, new BigDecimal("-10.00"), new BigDecimal("90.00"), 9L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, new BigDecimal("10.00"), new BigDecimal("110.00"), 7L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        EconomyWriteQueue.Reservation senderReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation receiverReservation = queue.tryReserve(receiver);
        assertNotNull(senderReservation);
        assertNotNull(receiverReservation);
        assertTrue(queue.commitPair(senderReservation, out, receiverReservation, in, null));

        Set<UUID> unavailable = ConcurrentHashMap.newKeySet();
        queue.setTransferAvailabilityHandler(new EconomyWriteQueue.TransferAvailabilityHandler() {
            @Override
            public void markUnavailable(String ignored, UUID firstAccount, UUID secondAccount) {
                if (firstAccount != null) unavailable.add(firstAccount);
                if (secondAccount != null) unavailable.add(secondAccount);
            }

            @Override
            public void resolve(String ignored, UUID firstAccount, UUID secondAccount) {
                if (firstAccount != null) unavailable.remove(firstAccount);
                if (secondAccount != null) unavailable.remove(secondAccount);
            }
        });

        EconomyEvent first = queue.poll(1, TimeUnit.SECONDS);
        assertEquals(out, first);
        Path journal = temp.resolve("transfer-shutdown.jsonl");
        EconomyEventConsumer consumer = consumer(plugin(), config(), queue, mock(CacheManager.class),
                null, mock(NameResolver.class), new RecoverySpool(journal));
        setBoolean(consumer, "shutdownCheckpointRequested", true);
        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyEventConsumerRecoveryTest"));
            SyncmoneyEventBus.init(plugin);
        }

        Thread.currentThread().interrupt();
        try {
            invokeProcessTransferEvent(consumer, first);
        } finally {
            Thread.interrupted();
        }

        assertTrue(queue.hasAcceptedWrite(sender), "shutdown interruption must retain the first leg until pair handoff");
        assertTrue(queue.hasAcceptedWrite(receiver), "shutdown interruption must retain the second leg until pair handoff");
        assertTrue(invokeShutdownCheckpoint(consumer), "both legs should be durably handed off together");

        List<RecoverySpool.Entry> pending = new RecoverySpool(journal).pending();
        assertEquals(1, pending.size());
        JsonNode checkpoint = new ObjectMapper().readTree(pending.get(0).payload());
        assertEquals("RETRYABLE", checkpoint.path("stage").asText(),
                "the interrupt occurs before any Redis transfer attempt, so both accepted legs are safely not sent");
        assertEquals(out.requestId(), checkpoint.path("event").path("requestId").asText());
        assertEquals(in.requestId(), checkpoint.path("partner").path("requestId").asText());
        assertFalse(queue.hasAcceptedWrite(sender));
        assertFalse(queue.hasAcceptedWrite(receiver));
        assertTrue(unavailable.contains(sender));
        assertTrue(unavailable.contains(receiver));
    }

    @Test
    void shutdownTimeoutCheckpointsConfirmedWorkBeforeReleasingQueueOwnership() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "shutdown-confirmed-1");
        Path journal = temp.resolve("recovery-v1.jsonl");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        CacheManager cache = mock(CacheManager.class);
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        CountDownLatch downstreamStarted = new CountDownLatch(1);
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        when(dbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            downstreamStarted.countDown();
            return new CompletableFuture<Boolean>();
        });
        assertTrue(queue.offer(event));

        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cache,
                mock(RedisManager.class), dbQueue, null, null, mock(NameResolver.class),
                null, null, null, null, new RecoverySpool(journal), 10L);
        consumer.start();
        assertTrue(downstreamStarted.await(2, TimeUnit.SECONDS), "event should reach downstream persistence");

        consumer.shutdown();

        List<RecoverySpool.Entry> pending = new RecoverySpool(journal).pending();
        assertFalse(queue.hasAcceptedWrite(uuid),
                "accepted ownership may be released only after the timed-out work is durably handed off; pending="
                        + pending.size() + ", outstanding=" + queue.snapshotOutstandingEvents().size());
        assertEquals(1, pending.size(), "shutdown must preserve a pending receipt for next startup");
        assertEquals("CONFIRMED", stage(pending.get(0)),
                "a confirmed authority write must resume downstream work without repeating Redis");
        assertTrue(pending.get(0).payload().contains("110.00"));
        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, BigDecimal.TEN);
    }

    private static EconomyEventConsumer consumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cache, DbWriteQueue dbQueue,
            NameResolver names, RecoverySpool spool) {
        return new EconomyEventConsumer(plugin, config, queue, cache, mock(RedisManager.class),
                dbQueue, null, null, names, null, null, null, null, spool);
    }

    private static EconomyEventConsumer consumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cache, RedisManager redis, DbWriteQueue dbQueue,
            AuditLogger auditLogger, DatabaseManager databaseManager, NameResolver names,
            noietime.syncmoney.baltop.BaltopManager baltopManager, RecoverySpool spool,
            noietime.syncmoney.shadow.ShadowSyncTask shadowSyncTask) {
        return new EconomyEventConsumer(plugin, config, queue, cache, redis, dbQueue,
                auditLogger, null, names, baltopManager, shadowSyncTask, null, databaseManager, spool);
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyEventConsumerRecoveryTest"));
        return plugin;
    }

    private static SyncmoneyConfig config() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(true);
        when(config.getServerName()).thenReturn("test-server");
        return config;
    }

    private static EconomyEvent event(UUID uuid, String requestId) {
        return event(uuid, requestId, EconomyEvent.EventSource.TEST);
    }

    private static EconomyEvent event(UUID uuid, String requestId, EconomyEvent.EventSource source) {
        return new EconomyEvent(uuid, BigDecimal.TEN, new BigDecimal("110.00"), 9L,
                EconomyEvent.EventType.DEPOSIT, source, requestId,
                System.currentTimeMillis());
    }

    private static void invokeProcessEvent(EconomyEventConsumer consumer, EconomyEvent event) throws Exception {
        Method method = EconomyEventConsumer.class.getDeclaredMethod("processEvent", EconomyEvent.class);
        method.setAccessible(true);
        method.invoke(consumer, event);
    }

    private static void invokeProcessTransferEvent(EconomyEventConsumer consumer, EconomyEvent event) throws Exception {
        Method method = EconomyEventConsumer.class.getDeclaredMethod("processTransferEvent", EconomyEvent.class);
        method.setAccessible(true);
        method.invoke(consumer, event);
    }

    private static boolean invokeShutdownCheckpoint(EconomyEventConsumer consumer) throws Exception {
        Method method = EconomyEventConsumer.class.getDeclaredMethod("checkpointOutstandingForShutdown");
        method.setAccessible(true);
        return (boolean) method.invoke(consumer);
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static String stage(RecoverySpool.Entry entry) throws Exception {
        JsonNode node = new ObjectMapper().readTree(entry.payload());
        return node.path("stage").asText();
    }

    private static List<RecoverySpool.Entry> pending(RecoverySpool spool) {
        try {
            return spool.pending();
        } catch (Exception failure) {
            throw new AssertionError("recovery spool could not be read", failure);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition,
            long timeout, TimeUnit unit, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }
}
