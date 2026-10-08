package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.RedisProcessTestSupport;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisTransferPairIT {
    private static RedisProcessTestSupport redis;
    private static CacheManager firstCache;
    private static CacheManager secondCache;

    @BeforeAll
    static void startRedisAndCaches() throws Exception {
        redis = RedisProcessTestSupport.start();
        firstCache = cacheManager("RedisTransferPairIT-first");
        secondCache = cacheManager("RedisTransferPairIT-second");
    }

    @BeforeEach
    void clearRedis() {
        try (Jedis jedis = redis.connect()) {
            jedis.flushDB();
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @Test
    void twoNodesWithCachedSenderBalance100CannotBothTransfer80() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID firstReceiver = UUID.randomUUID();
        UUID secondReceiver = UUID.randomUUID();
        try (Jedis jedis = redis.connect()) {
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
        EconomyEventConsumer firstConsumer = consumer(plugin, config, first.queue, firstCache);
        EconomyEventConsumer secondConsumer = consumer(plugin, config, second.queue, secondCache);
        startTogether(firstConsumer, secondConsumer);

        boolean bothSettled = awaitSettled(first.queue, second.queue, 10, TimeUnit.SECONDS);
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
        try (Jedis jedis = redis.connect()) {
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

    private static boolean awaitSettled(EconomyWriteQueue first, EconomyWriteQueue second,
            long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (first.isEmpty() && second.isEmpty()
                    && !first.hasOutstandingWrites() && !second.hasOutstandingWrites()) return true;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return first.isEmpty() && second.isEmpty()
                && !first.hasOutstandingWrites() && !second.hasOutstandingWrites();
    }

    private static CacheManager cacheManager(String loggerName) {
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(loggerName));
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                CacheManager.class.getClassLoader().getResourceAsStream(invocation.getArgument(0)));
        RedisManager manager = mock(RedisManager.class);
        when(manager.isDegraded()).thenReturn(false);
        when(manager.getResource()).thenAnswer(invocation -> new Jedis("127.0.0.1", redis.port()));
        return new CacheManager(plugin, manager, true);
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
        return new Node(queue, memory);
    }

    private static EconomyEventConsumer consumer(Plugin plugin, SyncmoneyConfig config,
            EconomyWriteQueue queue, CacheManager cache) {
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenAnswer(invocation -> new Jedis("127.0.0.1", redis.port()));
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

    private record Node(EconomyWriteQueue queue, MemoryStateManager memory) { }
}
