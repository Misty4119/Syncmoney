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
import noietime.syncmoney.protocol.RedisOperationFingerprint;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

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
    void startupRecoveryOpensSharedWriteAdmissionAfterEmptySpoolValidation() {
        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        queue.closeAdmission();
        EconomyEventConsumer consumer = consumer(plugin(), syncConfig(), queue, mock(CacheManager.class),
                mock(DbWriteQueue.class), mock(NameResolver.class), new RecoverySpool(temp.resolve("empty-startup.jsonl")));
        consumer.stop();

        consumer.run();

        assertTrue(queue.offer(event(UUID.randomUUID(), "after-startup-recovery")),
                "shared writes should be admitted only after both recovery scans succeed");
    }

    @Test
    void startupRecoveryFailureKeepsSharedWriteAdmissionClosed() throws Exception {
        Path journal = temp.resolve("corrupt-startup.jsonl");
        java.nio.file.Files.writeString(journal, "not-a-recovery-frame\n");
        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        queue.closeAdmission();
        EconomyEventConsumer consumer = consumer(plugin(), syncConfig(), queue, mock(CacheManager.class),
                mock(DbWriteQueue.class), mock(NameResolver.class), new RecoverySpool(journal));
        consumer.stop();

        consumer.run();

        assertFalse(queue.offer(event(UUID.randomUUID(), "blocked-after-startup-recovery")),
                "invalid recovery evidence must not reopen shared write admission");
    }

    @Test
    void paySpoolAdmissionFailureRejectsTransferBeforeEitherBalanceChanges() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal senderBalance = new BigDecimal("100.00");
        BigDecimal receiverBalance = new BigDecimal("5.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        long now = System.currentTimeMillis();
        memory.put(sender, new AccountState(senderBalance, 7L, now));
        memory.put(receiver, new AccountState(receiverBalance, 3L, now));

        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(sender)).thenReturn("sender");
        when(facade.resolvePlayerName(receiver)).thenReturn("receiver");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, redis), null, memory, new AtomicLong(), false);

        Path invalidJournal = temp.resolve("recovery-journal-is-a-directory");
        java.nio.file.Files.createDirectory(invalidJournal);
        EconomyEventConsumer consumer = consumer(plugin, config, queue, mock(CacheManager.class),
                mock(DbWriteQueue.class), mock(NameResolver.class), new RecoverySpool(invalidJournal));
        queue.setTransferAdmissionHandler(consumer::checkpointPayTransferAdmission);

        BigDecimal result = writer.transfer(sender, receiver, new BigDecimal("10.00"),
                EconomyEvent.EventSource.PLAYER_TRANSFER);

        assertAll(
                () -> assertTrue(result.signum() < 0, "failed durable admission must reject /pay"),
                () -> assertEquals(senderBalance, memory.get(sender).balance()),
                () -> assertEquals(7L, memory.get(sender).version()),
                () -> assertEquals(receiverBalance, memory.get(receiver).balance()),
                () -> assertEquals(3L, memory.get(receiver).version()),
                () -> assertTrue(queue.isEmpty(), "neither transfer leg may enter the queue"),
                () -> assertFalse(queue.hasAcceptedWrite(sender)),
                () -> assertFalse(queue.hasAcceptedWrite(receiver)),
                () -> assertTrue(consumer.isRecoveryBlocked(), "spool failure must fail closed")
        );
    }

    @Test
    void restartedAmbiguousPayPairIsVisibleAndRequiresItsExactReceiptForReconciliation() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), new BigDecimal("90.00"), 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.PLAYER_TRANSFER,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, new BigDecimal("15.00"), 4L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.PLAYER_TRANSFER,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        Path journal = temp.resolve("ambiguous-pay-pair.jsonl");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager beforeRestartCache = mock(CacheManager.class);
        when(beforeRestartCache.atomicTransfer(sender, receiver, amount, operationId))
                .thenThrow(new RedisMutationException(RedisMutationException.ExecutionState.SENT_UNKNOWN,
                        "synthetic lost response", null));
        EconomyWriteQueue beforeRestartQueue = new EconomyWriteQueue(10);
        MemoryStateManager beforeRestartMemory = new MemoryStateManager(plugin, beforeRestartQueue);
        beforeRestartMemory.put(sender, new AccountState(new BigDecimal("100.00"), 7L, now));
        beforeRestartMemory.put(receiver, new AccountState(new BigDecimal("5.00"), 3L, now));
        EconomyWriteQueue.Reservation senderReservation = beforeRestartQueue.tryReserve(sender);
        EconomyWriteQueue.Reservation receiverReservation = beforeRestartQueue.tryReserve(receiver);
        assertNotNull(senderReservation);
        assertNotNull(receiverReservation);
        assertTrue(beforeRestartQueue.commitPair(senderReservation, out, receiverReservation, in, null));
        RecoverySpool beforeRestartSpool = new RecoverySpool(journal);
        EconomyEventConsumer beforeRestart = consumer(plugin, config, beforeRestartQueue, beforeRestartCache,
                mock(DbWriteQueue.class), mock(NameResolver.class), beforeRestartSpool);

        assertEquals(out, beforeRestartQueue.poll(0, TimeUnit.MILLISECONDS),
                "the consumer's transfer method expects the worker to have polled the first leg");
        invokeProcessTransferEvent(beforeRestart, out);

        assertEquals("AMBIGUOUS", stage(beforeRestartSpool.pending().get(0)));
        verify(beforeRestartCache, times(1)).atomicTransfer(sender, receiver, amount, operationId);

        EconomyWriteQueue recoveredQueue = new EconomyWriteQueue(10);
        MemoryStateManager recoveredMemory = new MemoryStateManager(plugin, recoveredQueue);
        recoveredMemory.put(sender, new AccountState(new BigDecimal("100.00"), 7L, now));
        recoveredMemory.put(receiver, new AccountState(new BigDecimal("5.00"), 3L, now));
        CacheManager recoveredCache = mock(CacheManager.class);
        String fingerprint = RedisOperationFingerprint.transfer(sender, receiver, amount);
        CacheManager.OperationReceipt appliedReceipt = new CacheManager.OperationReceipt(operationId, fingerprint,
                CacheManager.OperationStatus.APPLIED, null, List.of(
                        new CacheManager.ReceiptAccount(sender, new BigDecimal("90.00"), 8L, true),
                        new CacheManager.ReceiptAccount(receiver, new BigDecimal("15.00"), 4L, true)));
        when(recoveredCache.readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver))))
                .thenReturn(Optional.empty(), Optional.of(appliedReceipt));
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        when(dbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        RecoverySpool recoveredSpool = new RecoverySpool(journal);
        EconomyEventConsumer recovered = consumer(plugin, config, recoveredQueue, recoveredCache,
                dbQueue, mock(NameResolver.class), recoveredSpool);
        recovered.start();
        try {
            await(() -> recovered.inspectRecovery(operationId) != null, 2, TimeUnit.SECONDS,
                    "startup must load the paired ambiguous checkpoint before exposing recovery");

            EconomyEventConsumer.RecoveryView listed = recovered.recoveryViews().stream()
                    .filter(view -> view.operationId().equals(operationId)).findFirst().orElseThrow();
            assertAll(
                    () -> assertEquals(sender, listed.playerUuid()),
                    () -> assertEquals(receiver, listed.partnerUuid()),
                    () -> assertEquals("AMBIGUOUS", listed.stage()),
                    () -> assertEquals(listed, recovered.inspectRecovery(operationId)),
                    () -> assertTrue(recoveredMemory.isUnavailable(sender)),
                    () -> assertTrue(recoveredMemory.isUnavailable(receiver))
            );
            verify(recoveredCache, never()).atomicTransfer(any(), any(), any(), anyString());
            assertTrue(recovered.reconcileRecovery(operationId).contains("no exact receipt"));
            assertTrue(recoveredMemory.isUnavailable(sender), "missing evidence must retain sender quarantine");
            assertTrue(recoveredMemory.isUnavailable(receiver), "missing evidence must retain receiver quarantine");
            assertEquals("AMBIGUOUS", stage(new RecoverySpool(journal).pending().get(0)));

            String exactReconciliation = recovered.reconcileRecovery(operationId);
            assertTrue(exactReconciliation.contains("Exact Redis receipt"), exactReconciliation);
            await(() -> pending(new RecoverySpool(journal)).isEmpty(), 2, TimeUnit.SECONDS,
                    "only exact receipt reconciliation may acknowledge the pair checkpoint");
            assertFalse(recoveredMemory.isUnavailable(sender));
            assertFalse(recoveredMemory.isUnavailable(receiver));
            assertEquals(new BigDecimal("90.00"), recoveredMemory.get(sender).balance());
            assertEquals(new BigDecimal("15.00"), recoveredMemory.get(receiver).balance());
            verify(recoveredCache, times(2)).readOperationReceipt(eq(operationId), eq(fingerprint),
                    eq(List.of(sender, receiver)));
            verify(recoveredCache, never()).atomicTransfer(any(), any(), any(), anyString());
        } finally {
            recovered.stop();
            recovered.shutdown();
        }
    }

    @Test
    void missingRedisAccountWithNewerStrictSqlRowIsQuarantinedWithoutMutationOrAcknowledgement() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent stale = new EconomyEvent(uuid, new BigDecimal("5.00"), new BigDecimal("5.00"), 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "stale-event-missing-redis", System.currentTimeMillis());
        Path journal = temp.resolve("stale-sql-authority.jsonl");
        CacheManager cache = mock(CacheManager.class);
        when(cache.readAuthorityBalanceSnapshotStrict(uuid)).thenReturn(Optional.empty());
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isConnected()).thenReturn(true);
        when(database.getPlayerStrict(uuid)).thenReturn(Optional.of(new DatabaseManager.PlayerRecord(
                uuid, "synthetic", new BigDecimal("900.00"), 41L, "sql-server", Instant.now())));
        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        List<UUID> quarantined = new java.util.concurrent.CopyOnWriteArrayList<>();
        queue.setQuarantinedAccountHandler(quarantined::add);
        assertTrue(queue.offer(stale));
        RecoverySpool spool = new RecoverySpool(journal);
        Plugin plugin = plugin();
        EconomyEventConsumer consumer = consumer(plugin, syncConfig(), queue, cache,
                mock(RedisManager.class), mock(DbWriteQueue.class), null, database,
                mock(NameResolver.class), null, spool, null);

        invokeProcessEvent(consumer, stale);

        assertEquals(1, spool.pending().size());
        assertEquals("AMBIGUOUS", stage(spool.pending().get(0)));
        assertTrue(queue.hasAcceptedWrite(uuid), "the unacknowledged event retains accepted-write ownership");
        assertEquals(List.of(uuid), quarantined);
        verify(database).getPlayerStrict(uuid);
        verify(cache, never()).atomicAddBalanceReceipt(any(), any(), anyString());
        verify(cache, never()).atomicSetBalanceReceipt(any(), any(), anyString());
    }

    @Test
    void missingRedisAccountWithStrictSqlReadFailureIsQuarantinedWithoutMutation() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, new BigDecimal("5.00"), new BigDecimal("5.00"), 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "sql-read-failure-missing-redis", System.currentTimeMillis());
        CacheManager cache = mock(CacheManager.class);
        when(cache.readAuthorityBalanceSnapshotStrict(uuid)).thenReturn(Optional.empty());
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isConnected()).thenReturn(true);
        when(database.getPlayerStrict(uuid)).thenThrow(new SQLException("synthetic read failure"));
        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        List<UUID> quarantined = new java.util.concurrent.CopyOnWriteArrayList<>();
        queue.setQuarantinedAccountHandler(quarantined::add);
        assertTrue(queue.offer(event));
        RecoverySpool spool = new RecoverySpool(temp.resolve("sql-read-failure.jsonl"));
        EconomyEventConsumer consumer = consumer(plugin(), syncConfig(), queue, cache,
                mock(RedisManager.class), mock(DbWriteQueue.class), null, database,
                mock(NameResolver.class), null, spool, null);

        invokeProcessEvent(consumer, event);

        assertEquals("AMBIGUOUS", stage(spool.pending().get(0)));
        assertTrue(queue.hasAcceptedWrite(uuid));
        assertEquals(List.of(uuid), quarantined);
        verify(cache, never()).atomicAddBalanceReceipt(any(), any(), anyString());
    }

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
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId()))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("110.00"), 9L));
        when(firstDbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(recoveryDbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(cache.acknowledgeOperationReceipt(anyString())).thenAnswer(invocation -> {
            assertTrue(new RecoverySpool(journal).pending().isEmpty(),
                    "Redis receipts must be retired only after the local recovery ACK is durable");
            return true;
        });

        EconomyEventConsumer beforeRestart = consumer(plugin, config, new EconomyWriteQueue(10), cache,
                firstDbQueue, names, spool);
        invokeProcessEvent(beforeRestart, event);

        List<RecoverySpool.Entry> afterFailure = spool.pending();
        assertEquals(1, afterFailure.size());
        assertEquals("CONFIRMED", stage(afterFailure.get(0)));
        assertTrue(afterFailure.get(0).payload().contains("110.00"));
        verify(cache, never()).acknowledgeOperationReceipt(anyString());

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

        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId());
        verify(cache, times(1)).acknowledgeOperationReceipt(event.requestId());
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
        Logger logger = Logger.getLogger("EconomyEventConsumerRecoveryTest");
        logger.setLevel(Level.ALL);
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        CacheManager cache = mock(CacheManager.class);
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        NameResolver names = mock(NameResolver.class);
        when(dbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId())).thenThrow(new RedisMutationException(
                RedisMutationException.ExecutionState.SENT_UNKNOWN, "response was lost", null));
        when(cache.atomicAddBalanceReceipt(uuid, new BigDecimal("5.00"), "ambiguous-followup-write"))
                .thenReturn(new ConfirmedAccount(uuid, new BigDecimal("115.00"), 11L));

        EconomyEventConsumer beforeRestart = consumer(plugin, config, new EconomyWriteQueue(10), cache,
                dbQueue, names, spool);
        invokeProcessEvent(beforeRestart, event);
        assertEquals("AMBIGUOUS", stage(spool.pending().get(0)));

        EconomyWriteQueue recoveredQueue = new EconomyWriteQueue(10);
        MemoryStateManager recoveredMemory = new MemoryStateManager(plugin, recoveredQueue);
        recoveredMemory.put(uuid, new AccountState(new BigDecimal("110.00"), 10L, System.currentTimeMillis()));
        EconomyEvent queuedBeforeAmbiguousRecovery = new EconomyEvent(uuid, new BigDecimal("5.00"),
                new BigDecimal("115.00"), 11L, EconomyEvent.EventType.DEPOSIT,
                EconomyEvent.EventSource.TEST, "ambiguous-followup-write", System.currentTimeMillis());
        EconomyWriteQueue.Reservation reservation = recoveredQueue.tryReserve(uuid);
        assertNotNull(reservation);
        assertTrue(reservation.commit(queuedBeforeAmbiguousRecovery));

        EconomyEventConsumer afterRestart = consumer(plugin, config, recoveredQueue, cache,
                dbQueue, names, new RecoverySpool(journal));
        afterRestart.start();
        try {
            await(() -> recoveredMemory.get(uuid).version() == 11L, 2, TimeUnit.SECONDS,
                    "the already accepted same-account write should finish after recovery is loaded");
            assertTrue(recoveredMemory.isUnavailable(uuid),
                    "a later confirmed write must not clear an unresolved durable ambiguous operation");
        } finally {
            afterRestart.stop();
            afterRestart.shutdown();
            logger.removeHandler(capture);
        }

        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId());
        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, new BigDecimal("5.00"),
                "ambiguous-followup-write");
        assertEquals(1, pending(new RecoverySpool(journal)).size(),
                "ambiguous evidence must remain pending for operator reconciliation");
        assertTrue(messages.stream().anyMatch(message -> message.equals(
                "T21_DRAIN owner=economy status=pending queued=0 outstanding=0 retry=0 "
                        + "workerTerminated=true recoveryPending=1 recoveryBlocked=false")),
                "unresolved durable recovery must not be reported as drained");
    }

    @Test
    void missingLuaScriptShaIsRetryableWithoutQuarantiningTheAccount() throws Exception {
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = event(uuid, "missing-lua-sha-not-sent");
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyEventConsumerRecoveryTest"));
        when(plugin.getResource(anyString())).thenAnswer(ignored -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis startupResource = mock(Jedis.class);
        Jedis mutationResource = mock(Jedis.class);
        when(startupResource.scriptLoad(anyString())).thenThrow(new JedisConnectionException("script load unavailable"));
        when(mutationResource.scriptLoad(anyString())).thenThrow(new JedisConnectionException("script load unavailable"));
        when(redis.getResource()).thenReturn(startupResource, mutationResource);
        CacheManager cache = new CacheManager(plugin, redis, true);
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        List<UUID> unavailable = new CopyOnWriteArrayList<>();
        List<UUID> quarantined = new CopyOnWriteArrayList<>();
        queue.setUnavailableAccountHandler(unavailable::add);
        queue.setQuarantinedAccountHandler(quarantined::add);
        RecoverySpool spool = new RecoverySpool(temp.resolve("missing-lua-sha.jsonl"));
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);

        invokeProcessEvent(consumer(plugin, config(), queue, cache, dbQueue,
                mock(NameResolver.class), spool), event);

        assertEquals("RETRYABLE", stage(spool.pending().get(0)),
                "a missing script SHA means no Redis balance command was sent");
        assertTrue(unavailable.isEmpty(), "pre-send script loading failure must not make the account unavailable");
        assertTrue(quarantined.isEmpty(), "pre-send script loading failure must not quarantine the account");
        verify(redis, times(2)).getResource();
        verify(mutationResource, never()).evalsha(anyString(), org.mockito.ArgumentMatchers.anyInt(), any(String[].class));
    }

    @Test
    void recoveredRejectedTransferUsesExactReceiptBeforeReconcilingNewerAcceptedAccount() throws Exception {
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
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        String fingerprint = RedisOperationFingerprint.transfer(sender, receiver, new BigDecimal("70.00"));
        when(cache.readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver))))
                .thenReturn(Optional.of(new CacheManager.OperationReceipt(operationId, fingerprint,
                        CacheManager.OperationStatus.REJECTED, "INSUFFICIENT_FUNDS", List.of(
                                new CacheManager.ReceiptAccount(sender, new BigDecimal("20.00"), 8L, true),
                                new CacheManager.ReceiptAccount(receiver, null, -1L, false)))));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, new AccountState(new BigDecimal("20.00"), 8L, now));
        memory.put(receiver, new AccountState(new BigDecimal("5.00"), 1L, now + 1L));
        EconomyEvent laterAccepted = new EconomyEvent(receiver, BigDecimal.ONE, new BigDecimal("6.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "later-accepted-account", now + 2L);
        assertTrue(queue.offer(laterAccepted));
        when(cache.atomicAddBalanceReceipt(receiver, BigDecimal.ONE, laterAccepted.requestId()))
                .thenReturn(new ConfirmedAccount(receiver, new BigDecimal("6.00"), 2L));
        queue.markTransferUnavailable(operationId, sender, receiver);
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        when(dbQueue.offerAndTrack(any())).thenReturn(CompletableFuture.completedFuture(true));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cache,
                dbQueue, mock(NameResolver.class), null);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        var constructor = failedEventType.getDeclaredConstructor(EconomyEvent.class, EconomyEvent.class,
                int.class, long.class, ReplicationReceipt.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object failed = constructor.newInstance(out, in, 0, now - 5_000L, null,
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

        verify(cache).readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver)));
        assertEquals(new BigDecimal("20.00"), memory.get(sender).balance());
        assertNull(memory.get(receiver),
                "a pending accepted write cannot expose its unconfirmed projection after the rejected receipt records absence");
        assertTrue(memory.isUnavailable(receiver),
                "a newer local projection remains quarantined until its own accepted receipt is processed");
        assertTrue(failedEvents.isEmpty(), "the exact rejected operation receipt completes its own reconciliation");

        invokeProcessEvent(consumer, laterAccepted);
        assertEquals(new BigDecimal("6.00"), memory.get(receiver).balance());
        assertEquals(2L, memory.get(receiver).version());
        assertFalse(memory.isUnavailable(receiver), "the later accepted receipt clears the account quarantine");
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
        RecoverySpool spool = new RecoverySpool(temp.resolve("rejected-transfer-refresh.jsonl"));
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        String fingerprint = RedisOperationFingerprint.transfer(sender, receiver, new BigDecimal("70.00"));
        CacheManager.OperationReceipt exactReceipt = new CacheManager.OperationReceipt(operationId, fingerprint,
                CacheManager.OperationStatus.REJECTED, "INVALID_AMOUNT", List.of());
        when(cache.readRejectedTransferSnapshotStrict(sender, receiver)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("20.00"), new BigDecimal("5.00"), 8L, 1L));
        when(cache.readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver))))
                .thenAnswer(invocation -> {
            JsonNode checkpoint = new ObjectMapper().readTree(pending(spool).get(0).payload());
            assertEquals("RECONCILE", checkpoint.path("stage").asText());
            assertTrue(checkpoint.path("receipt").isNull(),
                    "a refresh-in-progress checkpoint must satisfy the recovery validator");
            return Optional.of(exactReceipt);
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
        Object failed = constructor.newInstance(out, in, 0, now - 5_000L, null,
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
        verify(cache).readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver)));
        verify(cache).readRejectedTransferSnapshotStrict(sender, receiver);
        assertEquals(new BigDecimal("5.00"), memory.get(receiver).balance());
        assertFalse(memory.isUnavailable(receiver));
    }

    @Test
    void rejectedTransferReceiptWithoutPairStaysQuarantinedWhenStrictSnapshotFails() throws Exception {
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
        RecoverySpool spool = new RecoverySpool(temp.resolve("rejected-transfer-snapshot-failed.jsonl"));
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        String fingerprint = RedisOperationFingerprint.transfer(sender, receiver, new BigDecimal("70.00"));
        when(cache.readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver))))
                .thenReturn(Optional.of(new CacheManager.OperationReceipt(operationId, fingerprint,
                        CacheManager.OperationStatus.REJECTED, "INVALID_AMOUNT", List.of())));
        when(cache.readRejectedTransferSnapshotStrict(sender, receiver))
                .thenThrow(new IllegalStateException("Redis authority pair is malformed"));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, new AccountState(new BigDecimal("20.00"), 8L, now));
        memory.put(receiver, new AccountState(new BigDecimal("5.00"), 1L, now));
        queue.markTransferUnavailable(operationId, sender, receiver);
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cache, mock(DbWriteQueue.class),
                mock(NameResolver.class), spool);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        var constructor = failedEventType.getDeclaredConstructor(EconomyEvent.class, EconomyEvent.class,
                int.class, long.class, ReplicationReceipt.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object failed = constructor.newInstance(out, in, 0, now - 5_000L, null,
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

        verify(cache).readOperationReceipt(eq(operationId), eq(fingerprint), eq(List.of(sender, receiver)));
        verify(cache).readRejectedTransferSnapshotStrict(sender, receiver);
        assertEquals(1, pending(spool).size(), "an unreadable authority pair must retain the durable checkpoint");
        assertTrue(memory.isUnavailable(sender));
        assertTrue(memory.isUnavailable(receiver));
        assertTrue(failedEvents.containsKey(operationId));
    }

    @Test
    void legacyRejectedCheckpointReceiptIsNotTrustedWithoutExactRedisReceipt() throws Exception {
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
        ReplicationReceipt unverifiedSnapshot = new ReplicationReceipt(operationId, List.of(
                new ConfirmedAccount(sender, new BigDecimal("20.00"), 8L),
                new ConfirmedAccount(receiver, new BigDecimal("70.00"), 1L)));
        RecoverySpool spool = new RecoverySpool(temp.resolve("legacy-rejected-snapshot.jsonl"));
        ObjectMapper mapper = new ObjectMapper();
        String legacyCheckpoint = mapper.writeValueAsString(java.util.Map.of(
                "version", 1,
                "stage", "RECONCILE_CONFIRMED",
                "event", out,
                "partner", in,
                "receipt", unverifiedSnapshot,
                "retryCount", 0,
                "firstFailureTime", now - 5_000L));
        spool.append(operationId, legacyCheckpoint);

        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        when(cache.readOperationReceipt(eq(operationId), any(), eq(List.of(sender, receiver))))
                .thenReturn(java.util.Optional.empty());
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        List<UUID> unavailable = new CopyOnWriteArrayList<>();
        queue.setUnavailableAccountHandler(unavailable::add);
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cache,
                mock(DbWriteQueue.class), mock(NameResolver.class), spool);

        consumer.start();
        try {
            Thread.sleep(300L);

            assertEquals(1, new RecoverySpool(temp.resolve("legacy-rejected-snapshot.jsonl")).pending().size(),
                    "an unverified legacy snapshot must remain pending when Redis has no exact receipt");
            assertTrue(unavailable.contains(sender));
            assertTrue(unavailable.contains(receiver));
            verify(cache, org.mockito.Mockito.atLeastOnce()).readOperationReceipt(
                    eq(operationId), any(), eq(List.of(sender, receiver)));
            verify(cache, never()).atomicTransfer(any(), any(), any());
        } finally {
            consumer.stop();
            consumer.shutdown();
        }
    }

    @Test
    void recoveredRejectedAccountUsesExactReceiptInsteadOfCurrentBalanceSnapshot() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EconomyEvent rejected = new EconomyEvent(uuid, new BigDecimal("70.00"), new BigDecimal("70.00"), 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "rejected-first-deposit", now);
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        String fingerprint = RedisOperationFingerprint.add(uuid, new BigDecimal("70.00"));
        when(cache.readOperationReceipt(eq(rejected.requestId()), eq(fingerprint), eq(List.of(uuid))))
                .thenReturn(Optional.of(new CacheManager.OperationReceipt(rejected.requestId(), fingerprint,
                        CacheManager.OperationStatus.REJECTED, "ACCOUNT_APPEARED", List.of(
                                new CacheManager.ReceiptAccount(uuid, new BigDecimal("5.00"), 1L, true)))));
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
        Object failed = constructor.newInstance(rejected, null, 0, now - 5_000L, null,
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

        verify(cache).readOperationReceipt(eq(rejected.requestId()), eq(fingerprint), eq(List.of(uuid)));
        verify(cache, never()).readAuthorityBalanceSnapshotStrict(uuid);
        assertEquals(new BigDecimal("5.00"), memory.get(uuid).balance());
        assertFalse(memory.isUnavailable(uuid), "fresh authority should restore the later accepted account");
        assertTrue(failedEvents.isEmpty(), "the rejection checkpoint should be acknowledged after refresh");
    }

    @Test
    void rejectedSingleAccountReceiptWithoutSnapshotRefreshesOnlyAfterExactReceipt() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EconomyEvent rejected = new EconomyEvent(uuid, new BigDecimal("70.00"), new BigDecimal("70.00"), 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "rejected-empty-account-receipt", now);
        Plugin plugin = plugin();
        SyncmoneyConfig config = config();
        when(config.isDbEnabled()).thenReturn(false);
        CacheManager cache = mock(CacheManager.class);
        String fingerprint = RedisOperationFingerprint.add(uuid, new BigDecimal("70.00"));
        when(cache.readOperationReceipt(eq(rejected.requestId()), eq(fingerprint), eq(List.of(uuid))))
                .thenReturn(Optional.of(new CacheManager.OperationReceipt(rejected.requestId(), fingerprint,
                        CacheManager.OperationStatus.REJECTED, "INVALID_AMOUNT", List.of())));
        when(cache.readAuthorityBalanceSnapshotStrict(uuid)).thenReturn(Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("5.00"), 1L, now + 1L)));
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(uuid, new AccountState(new BigDecimal("70.00"), 1L, now));
        memory.markUnavailable(uuid);
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cache, mock(DbWriteQueue.class),
                mock(NameResolver.class), null);

        Class<?> failedEventType = Class.forName(EconomyEventConsumer.class.getName() + "$FailedEvent");
        var constructor = failedEventType.getDeclaredConstructor(EconomyEvent.class, EconomyEvent.class,
                int.class, long.class, ReplicationReceipt.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object failed = constructor.newInstance(rejected, null, 0, now - 5_000L, null,
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

        verify(cache).readOperationReceipt(eq(rejected.requestId()), eq(fingerprint), eq(List.of(uuid)));
        verify(cache).readAuthorityBalanceSnapshotStrict(uuid);
        assertEquals(new BigDecimal("5.00"), memory.get(uuid).balance());
        assertFalse(memory.isUnavailable(uuid));
        assertTrue(failedEvents.isEmpty(), "the exact rejected receipt and fresh snapshot should release quarantine");
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
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId()))
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
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId()))
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
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId()))
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
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId()))
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
        when(cache.atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId()))
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
        verify(cache, times(1)).atomicAddBalanceReceipt(uuid, BigDecimal.TEN, event.requestId());
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

    private static SyncmoneyConfig syncConfig() {
        SyncmoneyConfig config = config();
        when(config.isSyncMode()).thenReturn(true);
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
