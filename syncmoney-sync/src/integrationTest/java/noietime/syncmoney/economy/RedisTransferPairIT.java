package noietime.syncmoney.economy;
import noietime.syncmoney.testkit.RedisTestSupport;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.CacheManagerIntegrationTestFactory;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.recovery.RecoverySpool;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisTransferPairIT {
    private static DisposableRedisServer redis;
    private static CacheManager firstCache;
    private static CacheManager secondCache;

    @TempDir
    Path temp;

    @BeforeAll
    static void startRedisAndCaches() throws Exception {
        redis = DisposableRedisServer.start();
        firstCache = cacheManager("RedisTransferPairIT-first");
        secondCache = cacheManager("RedisTransferPairIT-second");
    }

    @BeforeEach
    void clearRedis() {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.flushDB();
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @Test
    void livePlayerTransferPersistsAdmissionBeforeQueueCommitAndAppliesOneRedisPair() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            setAccount(jedis, sender, "100.00", 7L);
            setAccount(jedis, receiver, "5.00", 3L);
        }

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RedisTransferPairIT-live-pay"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(false);
        when(config.isSyncMode()).thenReturn(true);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenAnswer(invocation -> RedisTestSupport.connect(redis));
        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        queue.closeAdmission();
        EconomyFacade facade = new EconomyFacade(plugin, config, firstCache, redisManager,
                mock(DatabaseManager.class), null, queue, new FallbackEconomyWrapper(plugin, redisManager),
                null, mock(OverflowLogInterface.class));
        assertTrue(facade.updateMemoryState(sender, new BigDecimal("100.00"), 7L));
        assertTrue(facade.updateMemoryState(receiver, new BigDecimal("5.00"), 3L));

        RecoverySpool spool = new RecoverySpool(temp.resolve("live-pay-recovery-v1.jsonl"));
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, firstCache,
                redisManager, dbQueue, null, null, mock(NameResolver.class), null, null, null,
                mock(DatabaseManager.class), spool);
        AtomicBoolean checkpointedBeforeCommit = new AtomicBoolean();
        AtomicReference<String> operationId = new AtomicReference<>();
        AtomicReference<EconomyWriteQueue.TransferCompletion> completion = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);
        queue.setTransferAdmissionHandler((first, second) -> {
            boolean queueWasEmpty = queue.isEmpty();
            boolean accepted = consumer.checkpointPayTransferAdmission(first, second);
            if (!accepted) return false;
            String id = TransferRequestIds.operationId(first);
            operationId.set(id);
            boolean checkpointForced = false;
            try {
                List<RecoverySpool.Entry> pending = spool.pending();
                checkpointForced = pending.size() == 1 && pending.get(0).id().equals(id)
                        && pending.get(0).payload().contains("\"stage\":\"RETRYABLE\"");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            boolean listenerRegistered = queue.registerTransferCompletionListener(id, result -> {
                completion.set(result);
                settled.countDown();
            });
            checkpointedBeforeCommit.set(queueWasEmpty && checkpointForced && listenerRegistered
                    && TransferRequestIds.arePair(first, second));
            return listenerRegistered;
        });

        consumer.start();
        try {
            awaitQueueAdmission(queue, sender);
            BigDecimal senderBalance = facade.atomicTransfer(sender, receiver, amount,
                    EconomyEvent.EventSource.PLAYER_TRANSFER);

            assertEquals(new BigDecimal("90.00"), senderBalance);
            assertTrue(checkpointedBeforeCommit.get(),
                    "the forced recovery checkpoint and listener must exist before either queue leg is visible");
            assertTrue(settled.await(10, TimeUnit.SECONDS), "the live queued transfer must receive a Redis result");
            assertEquals(EconomyWriteQueue.TransferOutcome.CONFIRMED, completion.get().outcome());
            await(() -> queue.isEmpty() && !queue.hasOutstandingWrites() && pending(spool).isEmpty(),
                    5, TimeUnit.SECONDS, "the pair must drain and its recovery checkpoint must be acknowledged");

            BigDecimal senderBalanceInRedis;
            BigDecimal receiverBalanceInRedis;
            try (Jedis jedis = RedisTestSupport.connect(redis)) {
                senderBalanceInRedis = readBalance(jedis, sender);
                receiverBalanceInRedis = readBalance(jedis, receiver);
                assertEquals("8", jedis.get("syncmoney:version:" + sender));
                assertEquals("4", jedis.get("syncmoney:version:" + receiver));
            }
            assertEquals(new BigDecimal("90.00"), senderBalanceInRedis);
            assertEquals(new BigDecimal("15.00"), receiverBalanceInRedis);
            assertEquals(new BigDecimal("90.00"), facade.getMemoryState(sender).balance());
            assertEquals(new BigDecimal("15.00"), facade.getMemoryState(receiver).balance());
            assertTrue(spool.pending().isEmpty());
            assertTrue(operationId.get() != null && !operationId.get().isBlank());
        } finally {
            consumer.stop();
            consumer.shutdown();
            facade.shutdown();
        }
    }

    @Test
    void twoNodesWithCachedSenderBalance100CannotBothTransfer80() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID firstReceiver = UUID.randomUUID();
        UUID secondReceiver = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            setAccount(jedis, sender, "100.00", 7L);
            setAccount(jedis, firstReceiver, "0.00", 3L);
            setAccount(jedis, secondReceiver, "0.00", 3L);
        }

        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RedisTransferPairIT-consumer"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(false);

        Node first = acceptedNode(plugin, firstCache, sender, firstReceiver);
        Node second = acceptedNode(plugin, secondCache, sender, secondReceiver);
        CountDownLatch settled = new CountDownLatch(2);
        assertTrue(first.queue.registerTransferCompletionListener(first.operationId,
                ignored -> settled.countDown()));
        assertTrue(second.queue.registerTransferCompletionListener(second.operationId,
                ignored -> settled.countDown()));
        EconomyEventConsumer firstConsumer = consumer(plugin, config, first.queue, firstCache);
        EconomyEventConsumer secondConsumer = consumer(plugin, config, second.queue, secondCache);
        startTogether(firstConsumer, secondConsumer);

        boolean bothSettled = settled.await(10, TimeUnit.SECONDS);
        if (!bothSettled) {
            firstConsumer.stop();
            secondConsumer.stop();
            releaseIfPending(first);
            releaseIfPending(second);
        }
        firstConsumer.shutdown();
        secondConsumer.shutdown();

        BigDecimal senderBalance;
        BigDecimal firstReceiverBalance;
        BigDecimal secondReceiverBalance;
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            senderBalance = readBalance(jedis, sender);
            firstReceiverBalance = readBalance(jedis, firstReceiver);
            secondReceiverBalance = readBalance(jedis, secondReceiver);
        }

        assertAll(
                () -> assertTrue(bothSettled, "both authority outcomes must settle"),
                () -> assertEquals(0, new BigDecimal("20.00").compareTo(senderBalance)),
                () -> assertEquals(0, new BigDecimal("100.00").compareTo(
                        senderBalance.add(firstReceiverBalance).add(secondReceiverBalance)),
                        "the Redis authority total must remain 100, never 180 or 20"),
                () -> assertEquals(1, creditedReceiverCount(firstReceiverBalance, secondReceiverBalance),
                        "Redis must confirm exactly one cached transfer and reject the other"),
                () -> assertEquals(0, new BigDecimal("80.00").compareTo(
                        firstReceiverBalance.add(secondReceiverBalance))),
                () -> assertEquals(0, new BigDecimal("20.00").compareTo(first.memory.get(sender).balance())),
                () -> assertEquals(0, new BigDecimal("20.00").compareTo(second.memory.get(sender).balance())),
                () -> assertTrue(first.queue.isEmpty() && second.queue.isEmpty()),
                () -> assertTrue(!first.queue.hasOutstandingWrites() && !second.queue.hasOutstandingWrites())
        );
    }

    @Test
    void legacyTransferReceiptExpiresToBoundRedisStorage() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            setAccount(jedis, sender, "20.00", 7L);
            setAccount(jedis, receiver, "5.00", 3L);
        }

        CacheManager.TransferResult result = firstCache.atomicTransfer(sender, receiver,
                new BigDecimal("10.00"));

        assertEquals(new BigDecimal("10.00"), result.fromNewBalance);
        assertEquals(new BigDecimal("15.00"), result.toNewBalance);
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            java.util.Set<String> receiptKeys = jedis.keys("syncmoney:operation:receipt:*");
            assertEquals(1, receiptKeys.size());
            assertTrue(jedis.ttl(receiptKeys.iterator().next()) > 0,
                    "legacy operations without a replay ID must not leave permanent Redis receipt keys");
        }
    }

    @Test
    void sameStableOperationIdReturnsOriginalReceiptAndPayloadCollisionFailsClosed() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        String operationId = "direct-transfer-restart-test";
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            setAccount(jedis, sender, "20.00", 7L);
            setAccount(jedis, receiver, "5.00", 3L);
        }

        CacheManager.TransferResult first = firstCache.atomicTransfer(sender, receiver,
                new BigDecimal("10.00"), operationId);
        CacheManager.TransferResult afterRestartRetry = secondCache.atomicTransfer(sender, receiver,
                new BigDecimal("10.00"), operationId);

        assertEquals(first.fromNewBalance, afterRestartRetry.fromNewBalance);
        assertEquals(first.toNewBalance, afterRestartRetry.toNewBalance);
        assertEquals(first.fromNewVersion, afterRestartRetry.fromNewVersion);
        assertEquals(first.toNewVersion, afterRestartRetry.toNewVersion);
        assertThrows(RedisMutationException.class, () -> secondCache.atomicTransfer(sender, receiver,
                new BigDecimal("11.00"), operationId));

        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            assertEquals("10.00", jedis.get("syncmoney:balance:" + sender));
            assertEquals("15.00", jedis.get("syncmoney:balance:" + receiver));
            assertEquals("8", jedis.get("syncmoney:version:" + sender));
            assertEquals("4", jedis.get("syncmoney:version:" + receiver));
            assertEquals(-1L, jedis.ttl(RedisKeys.operationReceipt(operationId)),
                    "stable /pay recovery receipts must remain available without expiry");
        }
    }

    @Test
    void stableAddAndSetIdsAreIdempotentAndBindOperationTypeAndPayload() throws Exception {
        UUID account = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            setAccount(jedis, account, "10.00", 2L);
        }

        var firstAdd = firstCache.atomicAddBalanceReceipt(account, new BigDecimal("5.00"), "add-operation");
        var repeatedAdd = secondCache.atomicAddBalanceReceipt(account, new BigDecimal("5.00"), "add-operation");
        assertEquals(firstAdd, repeatedAdd);
        assertEquals(new BigDecimal("15.00"), repeatedAdd.balance());
        assertEquals(3L, repeatedAdd.version());
        assertThrows(RedisMutationException.class, () -> secondCache.atomicAddBalanceReceipt(
                account, new BigDecimal("6.00"), "add-operation"));

        var firstSet = firstCache.atomicSetBalanceReceipt(account, new BigDecimal("25.00"), "set-operation");
        var repeatedSet = secondCache.atomicSetBalanceReceipt(account, new BigDecimal("25.00"), "set-operation");
        assertEquals(firstSet, repeatedSet);
        assertEquals(new BigDecimal("25.00"), repeatedSet.balance());
        assertEquals(4L, repeatedSet.version());
        assertThrows(RedisMutationException.class, () -> secondCache.atomicSetBalanceReceipt(
                account, new BigDecimal("26.00"), "set-operation"));

        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            assertEquals("25.00", jedis.get("syncmoney:balance:" + account));
            assertEquals("4", jedis.get("syncmoney:version:" + account));
            assertEquals(-1L, jedis.ttl(RedisKeys.operationReceipt("add-operation")),
                    "stable add receipts must remain available without expiry");
            assertEquals(-1L, jedis.ttl(RedisKeys.operationReceipt("set-operation")),
                    "stable set receipts must remain available without expiry");
        }
    }

    @Test
    void legacyAddAndSetReceiptsExpireToBoundRedisStorage() throws Exception {
        UUID account = UUID.randomUUID();
        UUID absentAccount = UUID.randomUUID();
        UUID forceSyncAccount = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            setAccount(jedis, account, "10.00", 2L);
        }

        var add = firstCache.atomicAddBalanceReceipt(account, new BigDecimal("5.00"));
        var requireExisting = firstCache.atomicAddBalanceReceiptRequireExisting(
                account, new BigDecimal("1.00"));
        var requireAbsent = firstCache.atomicAddBalanceReceiptRequireAbsent(
                absentAccount, new BigDecimal("3.00"));
        var set = firstCache.atomicSetBalanceReceipt(account, new BigDecimal("25.00"));
        long compatibilitySetVersion = firstCache.atomicSetBalance(account, new BigDecimal("26.00"));
        BigDecimal compatibilityAddBalance = firstCache.atomicAddBalance(account, new BigDecimal("1.00"));
        assertTrue(firstCache.forceSyncToRedis(forceSyncAccount, new BigDecimal("30.00")));

        assertEquals(new BigDecimal("15.00"), add.balance());
        assertEquals(new BigDecimal("16.00"), requireExisting.balance());
        assertEquals(new BigDecimal("3.00"), requireAbsent.balance());
        assertEquals(new BigDecimal("25.00"), set.balance());
        assertEquals(6L, compatibilitySetVersion);
        assertEquals(new BigDecimal("27.00"), compatibilityAddBalance);
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            java.util.Set<String> receiptKeys = jedis.keys("syncmoney:operation:receipt:*");
            assertEquals(7, receiptKeys.size());
            assertTrue(receiptKeys.stream().allMatch(key -> jedis.ttl(key) > 0),
                    "legacy operations without a replay ID must not leave permanent Redis receipt keys");
        }
    }

    private static CacheManager cacheManager(String loggerName) {
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(loggerName));
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                CacheManager.class.getClassLoader().getResourceAsStream(invocation.getArgument(0)));
        RedisManager manager = mock(RedisManager.class);
        when(manager.isDegraded()).thenReturn(false);
        when(manager.getResource()).thenAnswer(invocation -> RedisTestSupport.connect(redis));
        return CacheManagerIntegrationTestFactory.newSynchronizedModeCacheManager(plugin, manager, true);
    }

    private static Node acceptedNode(Plugin plugin, CacheManager cache, UUID sender, UUID receiver) {
        String operationId = TransferRequestIds.newOperationId();
        EconomyWriteQueue queue = new EconomyWriteQueue(4, plugin.getLogger());
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, new AccountState(new BigDecimal("20.00"), 8L, System.currentTimeMillis()));
        memory.put(receiver, new AccountState(new BigDecimal("80.00"), 4L, System.currentTimeMillis()));

        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, new BigDecimal("-80.00"), new BigDecimal("20.00"), 8L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, new BigDecimal("80.00"), new BigDecimal("80.00"), 4L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.TEST,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation senderReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation receiverReservation = queue.tryReserve(receiver);
        assertNotNull(senderReservation);
        assertNotNull(receiverReservation);
        assertTrue(queue.commitPair(senderReservation, out, receiverReservation, in, null));
        return new Node(queue, memory, operationId);
    }

    private static void awaitQueueAdmission(EconomyWriteQueue queue, UUID probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            EconomyWriteQueue.Reservation reservation = queue.tryReserve(probe);
            if (reservation != null) {
                reservation.close();
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("consumer did not reopen write admission after recovery validation");
    }

    private static List<RecoverySpool.Entry> pending(RecoverySpool spool) {
        try {
            return spool.pending();
        } catch (java.io.IOException failure) {
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

    private static EconomyEventConsumer consumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cache) {
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenAnswer(invocation -> RedisTestSupport.connect(redis));
        return new EconomyEventConsumer(plugin, config, queue, cache, redisManager,
                mock(DbWriteQueue.class), null, null, mock(NameResolver.class), null,
                (ShadowSyncTask) null, null, mock(DatabaseManager.class));
    }

    private static void startTogether(EconomyEventConsumer first, EconomyEventConsumer second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Thread firstStarter = new Thread(() -> startAfterBarrier(first, ready, start), "redis-transfer-first-start");
        Thread secondStarter = new Thread(() -> startAfterBarrier(second, ready, start), "redis-transfer-second-start");
        firstStarter.start();
        secondStarter.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both node consumers must reach the start barrier");
        start.countDown();
        firstStarter.join(TimeUnit.SECONDS.toMillis(5));
        secondStarter.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(!firstStarter.isAlive() && !secondStarter.isAlive());
    }

    private static void startAfterBarrier(EconomyEventConsumer consumer,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (start.await(5, TimeUnit.SECONDS)) consumer.start();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseIfPending(Node node) throws InterruptedException {
        EconomyEvent out = node.queue.poll();
        EconomyEvent in = node.queue.poll();
        if (out != null && in != null && TransferRequestIds.arePair(out, in)) {
            node.queue.completePair(out, out.balanceAfter(), out.version(), in, in.balanceAfter(), in.version());
        }
    }

    private static int creditedReceiverCount(BigDecimal first, BigDecimal second) {
        return (first.compareTo(new BigDecimal("80.00")) == 0 ? 1 : 0)
                + (second.compareTo(new BigDecimal("80.00")) == 0 ? 1 : 0);
    }

    private static void setAccount(Jedis jedis, UUID uuid, String balance, long version) {
        jedis.set("syncmoney:balance:" + uuid, balance);
        jedis.set("syncmoney:version:" + uuid, Long.toString(version));
    }

    private static BigDecimal readBalance(Jedis jedis, UUID uuid) {
        return new BigDecimal(jedis.get("syncmoney:balance:" + uuid));
    }

    private record Node(EconomyWriteQueue queue, MemoryStateManager memory, String operationId) { }
}
