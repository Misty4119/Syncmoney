package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DbWriterConsumer;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EconomyEventConsumerTransferTest {

    @Test
    void sharedTransferUsesOneAtomicAuthorityMutationAndOneSqlReceiptPair() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(true);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("90.00"), new BigDecimal("10.00"), 8L, 4L));
        DbWriteQueue dbWriteQueue = new DbWriteQueue(10);
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        String operationId = TransferRequestIds.newOperationId();
        AtomicReference<EconomyWriteQueue.TransferCompletion> completion = new AtomicReference<>();
        assertTrue(queue.registerTransferCompletionListener(operationId, completion::set));
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), new BigDecimal("90.00"), 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, new BigDecimal("10.00"), 4L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("90.00", 8L));
        memory.put(receiver, state("10.00", 4L));
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        AtomicReference<DbWriteQueue.DbWriteTask> persistedPair = new AtomicReference<>();
        when(databaseManager.batchInsertOrUpdatePlayersWithStale(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<DbWriteQueue.DbWriteTask> tasks = invocation.getArgument(0);
            persistedPair.set(tasks.getFirst());
            return List.of();
        });
        DbWriterConsumer dbWriterConsumer = new DbWriterConsumer(plugin, dbWriteQueue, databaseManager);
        Thread dbWriterThread = new Thread(dbWriterConsumer::run, "transfer-test-db-writer");
        dbWriterThread.start();
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                dbWriteQueue, mock(NameResolver.class), databaseManager);

        try {
            consumer.start();
            consumer.shutdown();
        } finally {
            dbWriterConsumer.stop();
            dbWriterThread.join(3_000L);
        }

        assertAll(
                () -> verify(cacheManager).atomicTransfer(sender, receiver, amount),
                () -> verify(cacheManager, never()).atomicAddBalanceReceipt(any(), any()),
                () -> assertNotNull(persistedPair.get()),
                () -> assertEquals(2, persistedPair.get().accountCount(),
                        "both confirmed accounts must cross the SQL admission boundary as one task"),
                () -> assertEquals(new BigDecimal("90.00"),
                        persistedPair.get().accounts().get(0).account().balance()),
                () -> assertEquals(new BigDecimal("10.00"),
                        persistedPair.get().accounts().get(1).account().balance()),
                () -> assertFalse(queue.hasAcceptedWrite(sender)),
                () -> assertFalse(queue.hasAcceptedWrite(receiver)),
                () -> assertNotNull(completion.get()),
                () -> assertEquals(EconomyWriteQueue.TransferOutcome.CONFIRMED, completion.get().outcome()),
                () -> assertEquals(new BigDecimal("90.00"), completion.get().senderBalance()),
                () -> assertEquals(new BigDecimal("10.00"), completion.get().receiverBalance())
        );
    }

    @Test
    void definitiveAuthorityRejectionReconcilesBothAccountsEvenAtTheProvisionalVersion() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount))
                .thenReturn(CacheManager.TransferResult.insufficientFunds());
        when(cacheManager.readRejectedTransferSnapshotStrict(sender, receiver)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("20.00"), new BigDecimal("80.00"), 9L, 5L));
        EconomyWriteQueue queue = pairedQueue(sender, receiver, amount, new BigDecimal("30.00"),
                new BigDecimal("70.00"), 9L, 5L, plugin);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("30.00", 9L));
        memory.put(receiver, state("70.00", 5L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        consumer.shutdown();

        assertAll(
                () -> verify(cacheManager).atomicTransfer(sender, receiver, amount),
                () -> verify(cacheManager).readRejectedTransferSnapshotStrict(sender, receiver),
                () -> verify(cacheManager, never()).atomicAddBalanceReceipt(any(), any()),
                () -> assertEquals(new BigDecimal("20.00"), memory.get(sender).balance(),
                        "a confirmed equal-version authority snapshot replaces the rejected provisional sender leg"),
                () -> assertEquals(new BigDecimal("80.00"), memory.get(receiver).balance(),
                        "the receiver is reconciled from the same authority snapshot"),
                () -> assertFalse(queue.hasAcceptedWrite(sender)),
                () -> assertFalse(queue.hasAcceptedWrite(receiver))
        );
    }

    @Test
    void rejectedTransferKeepsBothAccountsUnavailableUntilAuthorityPairIsReconciled() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount))
                .thenReturn(CacheManager.TransferResult.insufficientFunds());
        CountDownLatch authorityReadStarted = new CountDownLatch(1);
        CountDownLatch releaseAuthorityRead = new CountDownLatch(1);
        when(cacheManager.readRejectedTransferSnapshotStrict(sender, receiver)).thenAnswer(invocation -> {
            authorityReadStarted.countDown();
            if (!releaseAuthorityRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release transfer authority read");
            }
            return new CacheManager.TransferResult(new BigDecimal("20.00"), new BigDecimal("80.00"), 9L, 5L);
        });
        EconomyWriteQueue queue = pairedQueue(sender, receiver, amount, new BigDecimal("30.00"),
                new BigDecimal("70.00"), 9L, 5L, plugin);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("30.00", 9L));
        memory.put(receiver, state("70.00", 5L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        try {
            assertTrue(authorityReadStarted.await(5, TimeUnit.SECONDS),
                    "the rejected pair must begin an authority snapshot reconciliation");
            assertAll(
                    () -> assertTrue(memory.isUnavailable(sender),
                            "the sender cannot accept another write against the rejected provisional balance"),
                    () -> assertTrue(memory.isUnavailable(receiver),
                            "the receiver cannot accept another write against the rejected provisional balance"),
                    () -> assertTrue(queue.hasAcceptedWrite(sender)),
                    () -> assertTrue(queue.hasAcceptedWrite(receiver))
            );
        } finally {
            releaseAuthorityRead.countDown();
            consumer.shutdown();
        }

        assertAll(
                () -> assertEquals(new BigDecimal("20.00"), memory.get(sender).balance()),
                () -> assertEquals(new BigDecimal("80.00"), memory.get(receiver).balance()),
                () -> assertFalse(memory.isUnavailable(sender)),
                () -> assertFalse(memory.isUnavailable(receiver))
        );
    }

    @Test
    void rejectedTransferToAbsentReceiverDoesNotStrandLaterAcceptedReceiverWrite() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount))
                .thenReturn(CacheManager.TransferResult.insufficientFunds());
        when(cacheManager.readTransferSnapshotStrict(sender, receiver)).thenThrow(
                new IllegalStateException("Redis transfer authority snapshot is missing an account balance"));
        when(cacheManager.readRejectedTransferSnapshotStrict(sender, receiver)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("20.00"), null, 8L, -1L, true, false));
        when(cacheManager.atomicAddBalanceReceipt(receiver, new BigDecimal("5.00")))
                .thenReturn(new noietime.syncmoney.core.port.ConfirmedAccount(
                        receiver, new BigDecimal("5.00"), 1L));

        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        long now = System.currentTimeMillis();
        String operationId = TransferRequestIds.newOperationId();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), BigDecimal.ZERO, 10L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, amount, 5L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));

        EconomyEvent laterDeposit = new EconomyEvent(receiver, new BigDecimal("5.00"),
                new BigDecimal("75.00"), 6L, EconomyEvent.EventType.DEPOSIT,
                EconomyEvent.EventSource.TEST, "receiver-later-deposit", now + 1L);
        EconomyWriteQueue.Reservation laterReservation = queue.tryReserve(receiver);
        assertNotNull(laterReservation);
        assertTrue(laterReservation.commit(laterDeposit));

        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("0.00", 10L));
        memory.put(receiver, state("75.00", 6L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.hasOutstandingWrites() && System.nanoTime() < deadline) Thread.sleep(10L);
        consumer.shutdown();

        assertAll(
                () -> assertFalse(queue.hasOutstandingWrites(),
                        "the confirmed no-mutation result must release both rejected legs and drain later accepted work"),
                () -> assertEquals(new BigDecimal("20.00"), memory.get(sender).balance()),
                () -> assertEquals(new BigDecimal("5.00"), memory.get(receiver).balance(),
                        "the accepted deposit must create the absent Redis account without retaining the rejected transfer credit"),
                () -> assertFalse(memory.isUnavailable(sender)),
                () -> assertFalse(memory.isUnavailable(receiver)),
                () -> verify(cacheManager).readRejectedTransferSnapshotStrict(sender, receiver),
                () -> verify(cacheManager, never()).readTransferSnapshotStrict(sender, receiver),
                () -> verify(cacheManager).atomicAddBalanceReceipt(receiver, new BigDecimal("5.00"))
        );
    }

    @Test
    void rejectedTransferCancelsUncommittedReceiverReservationBeforeRemovingAbsentProjection() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount))
                .thenReturn(CacheManager.TransferResult.insufficientFunds());
        when(cacheManager.readRejectedTransferSnapshotStrict(sender, receiver)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("20.00"), null, 8L, -1L, true, false));

        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        EconomyWriteQueue.Reservation senderReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation receiverReservation = queue.tryReserve(receiver);
        long now = System.currentTimeMillis();
        String operationId = TransferRequestIds.newOperationId();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), BigDecimal.ZERO, 10L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, amount, 1L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        assertNotNull(senderReservation);
        assertNotNull(receiverReservation);
        assertTrue(queue.commitPair(senderReservation, out, receiverReservation, in, null));

        EconomyWriteQueue.Reservation uncommitted = queue.tryReserve(receiver);
        assertNotNull(uncommitted, "model a producer paused after capacity reservation");
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("0.00", 10L));
        memory.put(receiver, state("70.00", 1L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queue.hasOutstandingWrites() && System.nanoTime() < deadline) Thread.sleep(10L);
        } finally {
            consumer.shutdown();
        }

        EconomyEvent lateCommit = new EconomyEvent(receiver, new BigDecimal("5.00"), new BigDecimal("75.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "uncommitted-receiver-deposit", now + 1L);
        assertAll(
                () -> assertFalse(uncommitted.commit(lateCommit),
                        "known rejection cancels uncommitted producers before clearing provisional state"),
                () -> assertFalse(queue.hasOutstandingWrites()),
                () -> assertNull(memory.get(receiver),
                        "confirmed absence must not leave the rejected transfer's synthetic receiver balance"),
                () -> assertFalse(memory.isUnavailable(receiver),
                        "canceling the pre-accept reservation must not strand the absent account"),
                () -> assertFalse(memory.isUnavailable(sender))
        );
    }

    @Test
    void authorityRejectionRevalidatesLaterPendingWriteAndUsesItsEqualVersionReceipt() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount))
                .thenReturn(CacheManager.TransferResult.insufficientFunds());
        when(cacheManager.readRejectedTransferSnapshotStrict(sender, receiver)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("20.00"), new BigDecimal("80.00"), 9L, 5L));
        when(cacheManager.atomicAddBalanceReceipt(sender, new BigDecimal("5.00")))
                .thenReturn(new noietime.syncmoney.core.port.ConfirmedAccount(
                        sender, new BigDecimal("25.00"), 10L));

        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        String operationId = TransferRequestIds.newOperationId();
        AtomicReference<EconomyWriteQueue.TransferCompletion> completion = new AtomicReference<>();
        assertTrue(queue.registerTransferCompletionListener(operationId, completion::set));
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), new BigDecimal("30.00"), 9L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, new BigDecimal("70.00"), 5L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));

        EconomyEvent laterDeposit = new EconomyEvent(sender, new BigDecimal("5.00"), new BigDecimal("35.00"), 10L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                UUID.randomUUID().toString(), now + 1);
        EconomyWriteQueue.Reservation laterReservation = queue.tryReserve(sender);
        assertNotNull(laterReservation);
        assertTrue(laterReservation.commit(laterDeposit));

        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("35.00", 10L));
        memory.put(receiver, state("70.00", 5L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.hasOutstandingWrites() && System.nanoTime() < deadline) Thread.sleep(10);
        consumer.shutdown();

        assertAll(
                () -> assertFalse(queue.hasOutstandingWrites()),
                () -> assertEquals(new BigDecimal("25.00"), memory.get(sender).balance(),
                        "the later confirmed receipt must replace a same-version provisional balance"),
                () -> assertEquals(new BigDecimal("80.00"), memory.get(receiver).balance()),
                () -> assertFalse(memory.isUnavailable(sender)),
                () -> verify(cacheManager).atomicTransfer(sender, receiver, amount),
                () -> verify(cacheManager).atomicAddBalanceReceipt(sender, new BigDecimal("5.00")),
                () -> assertNotNull(completion.get()),
                () -> assertEquals(EconomyWriteQueue.TransferOutcome.REJECTED, completion.get().outcome()),
                () -> assertEquals(new BigDecimal("20.00"), completion.get().senderBalance()),
                () -> assertEquals(new BigDecimal("80.00"), completion.get().receiverBalance())
        );
    }

    @Test
    void lostTransferResponseQuarantinesBothAccountsWithoutBlindRetry() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount)).thenThrow(
                new RedisMutationException(RedisMutationException.ExecutionState.SENT_UNKNOWN,
                        "response dropped after Redis may have applied the pair", null));
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        String operationId = TransferRequestIds.newOperationId();
        AtomicReference<EconomyWriteQueue.TransferCompletion> completion = new AtomicReference<>();
        assertTrue(queue.registerTransferCompletionListener(operationId, completion::set));
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), new BigDecimal("90.00"), 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, new BigDecimal("10.00"), 4L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));
        UUID laterAccount = UUID.randomUUID();
        EconomyEvent laterEvent = new EconomyEvent(laterAccount, new BigDecimal("5.00"),
                new BigDecimal("5.00"), 1L, EconomyEvent.EventType.DEPOSIT,
                EconomyEvent.EventSource.TEST, "later-transfer-work", System.currentTimeMillis());
        assertTrue(queue.offer(laterEvent));
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("90.00", 8L));
        memory.put(receiver, state("10.00", 4L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!memory.isUnavailable(sender) || !memory.isUnavailable(receiver))
                && System.nanoTime() < deadline) Thread.sleep(10);
        Thread.sleep(250);

        assertAll(
                () -> assertTrue(memory.isUnavailable(sender)),
                () -> assertTrue(memory.isUnavailable(receiver)),
                () -> assertTrue(queue.hasAcceptedWrite(sender),
                        "an ambiguous pair remains owned until a confirmed reconciliation"),
                () -> assertTrue(queue.hasAcceptedWrite(receiver)),
                () -> assertNotNull(completion.get()),
                () -> assertEquals(EconomyWriteQueue.TransferOutcome.UNKNOWN, completion.get().outcome()),
                () -> assertTrue(queue.hasAcceptedWrite(laterAccount),
                        "later accepted work must remain behind the quarantined transfer"),
                () -> verify(cacheManager, times(1)).atomicTransfer(sender, receiver, amount),
                () -> verify(cacheManager, never()).readTransferSnapshotStrict(any(), any()),
                () -> verify(cacheManager, never()).atomicAddBalanceReceipt(any(), any())
        );

        // Release the intentionally quarantined fixture so the daemon consumer can shut down.
        assertTrue(queue.completePair(out, new BigDecimal("90.00"), 8L,
                in, new BigDecimal("10.00"), 4L));
        queue.complete(laterEvent, new BigDecimal("5.00"), 1L);
        consumer.shutdown();
    }

    @Test
    void transferResourceUnavailableIsRetriedAsNotSentWithoutQuarantiningAccounts() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount)).thenReturn(null);
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), new BigDecimal("90.00"), 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, new BigDecimal("10.00"), 4L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("90.00", 8L));
        memory.put(receiver, state("10.00", 4L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        Thread.sleep(250);

        assertAll(
                () -> assertFalse(memory.isUnavailable(sender), "a pre-send outage is retryable, not ambiguous"),
                () -> assertFalse(memory.isUnavailable(receiver)),
                () -> assertTrue(queue.hasAcceptedWrite(sender), "the same pair remains owned for retry"),
                () -> verify(cacheManager, atLeastOnce()).atomicTransfer(sender, receiver, amount)
        );

        // Release the intentionally retryable pair so the daemon consumer can shut down.
        assertTrue(queue.completePair(out, new BigDecimal("90.00"), 8L,
                in, new BigDecimal("10.00"), 4L));
        consumer.shutdown();
    }

    @Test
    void newerDeferredRemoteSnapshotWinsOverOlderTransferReceipt() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("90.00"), new BigDecimal("10.00"), 8L, 4L));
        EconomyWriteQueue queue = pairedQueue(sender, receiver, amount, new BigDecimal("90.00"),
                new BigDecimal("10.00"), 8L, 4L, plugin);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("90.00", 8L));
        memory.put(receiver, state("10.00", 4L));
        assertEquals(MemoryStateManager.RemoteUpdateResult.DEFERRED,
                memory.applyRemoteUpdateResult(sender, new BigDecimal("95.00"), 9L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        consumer.shutdown();

        assertAll(
                () -> assertEquals(new BigDecimal("95.00"), memory.get(sender).balance()),
                () -> assertEquals(9L, memory.get(sender).version()),
                () -> assertFalse(memory.isUnavailable(sender))
        );
    }

    @Test
    void staleNodeWithdrawalRejectionRebasesAndRevalidatesLaterWriteInOrder() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicAddBalanceReceipt(account, new BigDecimal("-80.00"))).thenThrow(
                new RedisMutationException(RedisMutationException.ExecutionState.REJECTED,
                        "authoritative balance rejected the stale-node withdrawal", null));
        when(cacheManager.atomicAddBalanceReceipt(account, new BigDecimal("5.00")))
                .thenReturn(new noietime.syncmoney.core.port.ConfirmedAccount(
                        account, new BigDecimal("25.00"), 9L));
        AtomicInteger snapshotReads = new AtomicInteger();
        CountDownLatch retrySnapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseRetrySnapshot = new CountDownLatch(1);
        when(cacheManager.readAuthorityBalanceSnapshotStrict(account)).thenAnswer(invocation -> {
            if (snapshotReads.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary Redis snapshot read failure");
            }
            retrySnapshotStarted.countDown();
            if (!releaseRetrySnapshot.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release authority snapshot retry");
            }
            return java.util.Optional.of(
                    new CacheManager.CachedBalance(new BigDecimal("20.00"), 8L, System.currentTimeMillis()));
        });
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        long now = System.currentTimeMillis();
        EconomyEvent rejectedWithdrawal = new EconomyEvent(account, new BigDecimal("-80.00"),
                new BigDecimal("20.00"), 8L,
                EconomyEvent.EventType.WITHDRAW, EconomyEvent.EventSource.TEST,
                "rejected-withdrawal", now);
        EconomyEvent laterDeposit = new EconomyEvent(account, new BigDecimal("5.00"),
                new BigDecimal("25.00"), 9L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "later-deposit", now + 1);
        assertTrue(queue.offer(rejectedWithdrawal));
        assertTrue(queue.offer(laterDeposit));
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(account, state("25.00", 9L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        assertTrue(retrySnapshotStarted.await(5, TimeUnit.SECONDS),
                "a transient authority read failure must keep the rejected write in reconciliation");
        try {
            assertAll(
                    () -> assertTrue(queue.hasAcceptedWrite(account),
                            "failed reconciliation keeps ownership of the rejected event"),
                    () -> assertTrue(memory.isUnavailable(account),
                            "the account stays unavailable until its confirmed authority base is read"),
                    () -> verify(cacheManager, times(1))
                            .atomicAddBalanceReceipt(account, new BigDecimal("-80.00"))
            );
        } finally {
            releaseRetrySnapshot.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.hasOutstandingWrites() && System.nanoTime() < deadline) Thread.sleep(10);
        consumer.shutdown();

        assertAll(
                () -> assertFalse(queue.hasOutstandingWrites(),
                        "the rejected intent is reconciled before the later accepted write completes"),
                () -> assertEquals(new BigDecimal("25.00"), memory.get(account).balance(),
                        "the rejected stale withdrawal must not create a credit; the later deposit is revalidated"),
                () -> assertEquals(9L, memory.get(account).version()),
                () -> assertFalse(memory.isUnavailable(account)),
                () -> verify(cacheManager, times(1))
                        .atomicAddBalanceReceipt(account, new BigDecimal("-80.00")),
                () -> verify(cacheManager, times(1))
                        .atomicAddBalanceReceipt(account, new BigDecimal("5.00")),
                () -> verify(cacheManager, times(2)).readAuthorityBalanceSnapshotStrict(account)
        );
    }

    @Test
    void rejectedWriteCanReplaceAHigherOptimisticVersionWithTheLowerAuthorityBase() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(false);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicAddBalanceReceipt(account, new BigDecimal("10.00"))).thenThrow(
                new RedisMutationException(RedisMutationException.ExecutionState.REJECTED,
                        "authoritative validation rejected the locally accepted deposit", null));
        when(cacheManager.readAuthorityBalanceSnapshotStrict(account)).thenReturn(java.util.Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("100.00"), 7L, System.currentTimeMillis())));
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        EconomyEvent rejectedDeposit = new EconomyEvent(account, new BigDecimal("10.00"),
                new BigDecimal("110.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "rejected-deposit-lower-authority-version", System.currentTimeMillis());
        assertTrue(queue.offer(rejectedDeposit));
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(account, state("110.00", 8L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        consumer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (queue.hasOutstandingWrites() && System.nanoTime() < deadline) Thread.sleep(10);
        consumer.shutdown();

        assertAll(
                () -> assertFalse(queue.hasOutstandingWrites()),
                () -> assertEquals(new BigDecimal("100.00"), memory.get(account).balance()),
                () -> assertEquals(7L, memory.get(account).version()),
                () -> assertFalse(memory.isUnavailable(account)),
                () -> verify(cacheManager, times(1)).atomicAddBalanceReceipt(account, new BigDecimal("10.00")),
                () -> verify(cacheManager).readAuthorityBalanceSnapshotStrict(account)
        );
    }

    @Test
    void secondCachedNodeWithdrawalRebasesToTheFirstNodesAuthorityReceipt() throws Exception {
        UUID account = UUID.randomUUID();
        BigDecimal withdrawal = new BigDecimal("80.00");
        Plugin firstPlugin = plugin();
        Plugin secondPlugin = plugin();
        SyncmoneyConfig config = config(false);
        long now = System.currentTimeMillis();

        CacheManager firstCache = mock(CacheManager.class);
        when(firstCache.atomicAddBalanceReceipt(account, withdrawal.negate()))
                .thenReturn(new noietime.syncmoney.core.port.ConfirmedAccount(
                        account, new BigDecimal("20.00"), 8L));
        EconomyWriteQueue firstQueue = new EconomyWriteQueue(4, firstPlugin.getLogger());
        EconomyEvent firstWithdrawal = new EconomyEvent(account, withdrawal.negate(),
                new BigDecimal("20.00"), 8L, EconomyEvent.EventType.WITHDRAW,
                EconomyEvent.EventSource.TEST, "node-a-withdrawal", now);
        assertTrue(firstQueue.offer(firstWithdrawal));
        MemoryStateManager firstMemory = new MemoryStateManager(firstPlugin, firstQueue);
        firstMemory.put(account, state("20.00", 8L));
        RedisManager firstRedis = mock(RedisManager.class);
        when(firstRedis.isDegraded()).thenReturn(false);
        EconomyEventConsumer firstConsumer = consumer(firstPlugin, config, firstQueue, firstCache, firstRedis,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        firstConsumer.start();
        long firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (firstQueue.hasOutstandingWrites() && System.nanoTime() < firstDeadline) Thread.sleep(10);
        firstConsumer.shutdown();

        CacheManager secondCache = mock(CacheManager.class);
        when(secondCache.atomicAddBalanceReceipt(account, withdrawal.negate())).thenThrow(
                new RedisMutationException(RedisMutationException.ExecutionState.REJECTED,
                        "another node already consumed the stale cached balance", null));
        when(secondCache.readAuthorityBalanceSnapshotStrict(account)).thenReturn(java.util.Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("20.00"), 8L, System.currentTimeMillis())));
        EconomyWriteQueue secondQueue = new EconomyWriteQueue(4, secondPlugin.getLogger());
        EconomyEvent secondWithdrawal = new EconomyEvent(account, withdrawal.negate(),
                new BigDecimal("20.00"), 8L, EconomyEvent.EventType.WITHDRAW,
                EconomyEvent.EventSource.TEST, "node-b-withdrawal", now);
        assertTrue(secondQueue.offer(secondWithdrawal));
        MemoryStateManager secondMemory = new MemoryStateManager(secondPlugin, secondQueue);
        secondMemory.put(account, state("20.00", 8L));
        RedisManager secondRedis = mock(RedisManager.class);
        when(secondRedis.isDegraded()).thenReturn(false);
        EconomyEventConsumer secondConsumer = consumer(secondPlugin, config, secondQueue, secondCache, secondRedis,
                null, mock(NameResolver.class), mock(DatabaseManager.class));

        secondConsumer.start();
        long secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (secondQueue.hasOutstandingWrites() && System.nanoTime() < secondDeadline) Thread.sleep(10);
        secondConsumer.shutdown();

        assertAll(
                () -> assertFalse(firstQueue.hasOutstandingWrites()),
                () -> assertFalse(secondQueue.hasOutstandingWrites()),
                () -> assertEquals(new BigDecimal("20.00"), firstMemory.get(account).balance()),
                () -> assertEquals(new BigDecimal("20.00"), secondMemory.get(account).balance(),
                        "the rejected node must use the confirmed base instead of crediting its stale withdrawal"),
                () -> assertFalse(firstMemory.isUnavailable(account)),
                () -> assertFalse(secondMemory.isUnavailable(account)),
                () -> verify(firstCache, times(1)).atomicAddBalanceReceipt(account, withdrawal.negate()),
                () -> verify(secondCache, times(1)).atomicAddBalanceReceipt(account, withdrawal.negate()),
                () -> verify(secondCache).readAuthorityBalanceSnapshotStrict(account)
        );
    }

    @Test
    void postReceiptSqlFailureRetriesThePairWithoutReapplyingRedisTransfer() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = plugin();
        SyncmoneyConfig config = config(true);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount)).thenReturn(
                new CacheManager.TransferResult(new BigDecimal("90.00"), new BigDecimal("10.00"), 8L, 4L));
        DbWriteQueue dbWriteQueue = mock(DbWriteQueue.class);
        when(dbWriteQueue.offerAndTrack(any())).thenReturn(null);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        when(databaseManager.batchInsertOrUpdatePlayersWithStale(anyList()))
                .thenThrow(new IllegalStateException("injected SQL failure"))
                .thenReturn(List.of());
        EconomyWriteQueue queue = pairedQueue(sender, receiver, amount, new BigDecimal("90.00"),
                new BigDecimal("10.00"), 8L, 4L, plugin);
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("90.00", 8L));
        memory.put(receiver, state("10.00", 4L));
        EconomyEventConsumer consumer = consumer(plugin, config, queue, cacheManager, redisManager,
                dbWriteQueue, mock(NameResolver.class), databaseManager);

        consumer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.hasAcceptedWrite(sender) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        consumer.shutdown();

        assertAll(
                () -> assertFalse(queue.hasAcceptedWrite(sender),
                        "the confirmed pair releases only after downstream SQL succeeds"),
                () -> assertFalse(queue.hasAcceptedWrite(receiver)),
                () -> verify(cacheManager, times(1)).atomicTransfer(sender, receiver, amount),
                () -> verify(cacheManager, never()).atomicAddBalanceReceipt(any(), any()),
                () -> verify(databaseManager, times(2)).batchInsertOrUpdatePlayersWithStale(argThat(tasks ->
                        tasks.size() == 1 && tasks.get(0).accountCount() == 2))
        );
    }

    private static EconomyEventConsumer consumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cacheManager, RedisManager redisManager,
            DbWriteQueue dbWriteQueue, NameResolver nameResolver, DatabaseManager databaseManager) {
        return new EconomyEventConsumer(plugin, config, queue, cacheManager, redisManager,
                dbWriteQueue, null, null, nameResolver, null, null, null, databaseManager);
    }

    private static EconomyWriteQueue pairedQueue(UUID sender, UUID receiver, BigDecimal amount,
            BigDecimal senderAfter, BigDecimal receiverAfter, long senderVersion, long receiverVersion,
            Plugin plugin) {
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, amount.negate(), senderAfter, senderVersion,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, amount, receiverAfter, receiverVersion,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));
        return queue;
    }

    private static EconomyFacade.EconomyState state(String balance, long version) {
        return new EconomyFacade.EconomyState(new BigDecimal(balance), version, System.currentTimeMillis());
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyEventConsumerTransferTest"));
        return plugin;
    }

    private static SyncmoneyConfig config(boolean dbEnabled) {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(dbEnabled);
        when(config.getServerName()).thenReturn("transfer-test");
        return config;
    }
}
