package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsumerFailureSemanticsTest {

    @Test
    void negativeWithdrawalFallbackDoesNotCredit() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        AtomicReference<BigDecimal> fallbackBalance = new AtomicReference<>();
        CountDownLatch fallbackApplied = new CountDownLatch(1);
        when(harness.cacheManager.atomicAddBalance(account, new BigDecimal("-10.00"))).thenReturn(null);
        when(harness.cacheManager.getBalance(account)).thenReturn(new BigDecimal("100.00"));
        when(harness.cacheManager.atomicSetBalance(eq(account), any(BigDecimal.class))).thenAnswer(invocation -> {
            fallbackBalance.set(invocation.getArgument(1));
            fallbackApplied.countDown();
            return 1L;
        });

        try {
            harness.startWith(event(account, new BigDecimal("-10.00"), new BigDecimal("90.00"),
                    EconomyEvent.EventType.WITHDRAW, "withdraw-fallback"));
            assertTrue(fallbackApplied.await(5, TimeUnit.SECONDS), "the Redis failure fallback should run");
            harness.close();

            assertEquals(new BigDecimal("90.00"), fallbackBalance.get(),
                    "a signed withdrawal delta must lower the fallback balance from 100 to 90");
        } finally {
            harness.close();
        }
    }

    @Test
    void setFallbackUsesAbsoluteTarget() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        AtomicReference<BigDecimal> lastSet = new AtomicReference<>();
        CountDownLatch fallbackApplied = new CountDownLatch(1);
        when(harness.cacheManager.getBalance(account)).thenReturn(new BigDecimal("80.00"));
        when(harness.cacheManager.atomicSetBalance(eq(account), any(BigDecimal.class))).thenAnswer(invocation -> {
            lastSet.set(invocation.getArgument(1));
            fallbackApplied.countDown();
            return 0L;
        });

        try {
            harness.startWith(event(account, new BigDecimal("-60.00"), new BigDecimal("40.00"),
                    EconomyEvent.EventType.SET_BALANCE, "set-fallback"));
            assertTrue(fallbackApplied.await(5, TimeUnit.SECONDS), "the SET fallback should run");
            harness.close();

            assertEquals(new BigDecimal("40.00"), lastSet.get(),
                    "SET fallback must use its absolute target even when Redis currently has 80");
        } finally {
            harness.close();
        }
    }

    @Test
    void postRedisFailureDoesNotApplyDeltaTwice() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.config.getServerName()).thenReturn("test-server");
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        when(harness.cacheManager.getVersion(account)).thenReturn(8L);

        AtomicReference<BigDecimal> durableBalance = new AtomicReference<>(new BigDecimal("100.00"));
        AtomicInteger redisMutations = new AtomicInteger();
        AtomicInteger dbOffers = new AtomicInteger();
        CountDownLatch dbOfferAttempts = new CountDownLatch(2);
        when(harness.cacheManager.atomicAddBalance(account, new BigDecimal("10.00"))).thenAnswer(invocation -> {
            redisMutations.incrementAndGet();
            BigDecimal after = durableBalance.updateAndGet(balance -> balance.add(new BigDecimal("10.00")));
            return after;
        });
        when(harness.dbWriteQueue.offer(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            dbOfferAttempts.countDown();
            if (dbOffers.incrementAndGet() == 1) {
                throw new IllegalStateException("downstream DB queue failure");
            }
            return true;
        });

        try {
            harness.startWith(event(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                    EconomyEvent.EventType.DEPOSIT, "post-redis-failure"));
            assertTrue(dbOfferAttempts.await(5, TimeUnit.SECONDS),
                    "the downstream DB enqueue should be retried after its first failure");
            harness.close();

            assertAll(
                    () -> assertEquals(1, redisMutations.get(),
                            "a confirmed Redis mutation must not be repeated when downstream work fails"),
                    () -> assertEquals(new BigDecimal("110.00"), durableBalance.get(),
                            "the repeated event currently changes durable balance to 120.00"),
                    () -> verify(harness.dbWriteQueue, times(2)).offer(any(DbWriteQueue.DbWriteTask.class))
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void shutdownRetriesFailedDownstreamWorkWithoutReapplyingRedis() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.config.getServerName()).thenReturn("test-server");
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        when(harness.cacheManager.getVersion(account)).thenReturn(8L);
        AtomicInteger redisMutations = new AtomicInteger();
        AtomicInteger dbOffers = new AtomicInteger();
        CountDownLatch dbOfferEntered = new CountDownLatch(1);
        CountDownLatch allowDbFailure = new CountDownLatch(1);
        CountDownLatch dbOfferAttempts = new CountDownLatch(2);
        when(harness.cacheManager.atomicAddBalance(account, new BigDecimal("10.00"))).thenAnswer(invocation -> {
            redisMutations.incrementAndGet();
            return new BigDecimal("110.00");
        });
        when(harness.dbWriteQueue.offer(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            int attempt = dbOffers.incrementAndGet();
            dbOfferAttempts.countDown();
            if (attempt == 1) {
                dbOfferEntered.countDown();
                if (!allowDbFailure.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("DB failure barrier timed out");
                }
                throw new IllegalStateException("downstream DB queue failure");
            }
            return true;
        });

        try {
            harness.startWith(event(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                    EconomyEvent.EventType.DEPOSIT, "shutdown-pending-retry"));
            assertTrue(dbOfferEntered.await(5, TimeUnit.SECONDS), "the consumer should reach the failing DB step");
            assertEquals(0, harness.queue.size(), "the worker has taken the event from the public queue");

            harness.stop();
            allowDbFailure.countDown();
            harness.consumer.shutdown();
            boolean downstreamRetryObserved = dbOfferAttempts.await(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(downstreamRetryObserved,
                            "shutdown should retry accepted downstream work before the consumer exits"),
                    () -> assertEquals(1, redisMutations.get(),
                            "a downstream-only retry must not apply the accepted Redis delta twice"),
                    () -> verify(harness.dbWriteQueue, times(2)).offer(any(DbWriteQueue.DbWriteTask.class)),
                    () -> assertEquals(0, harness.queue.size(), "the retry is no longer in the public queue")
            );
        } finally {
            allowDbFailure.countDown();
            harness.close();
        }
    }

    private static EconomyEvent event(UUID account, BigDecimal delta, BigDecimal after,
            EconomyEvent.EventType type, String requestId) {
        return new EconomyEvent(account, delta, after, 8L, type, EconomyEvent.EventSource.TEST,
                requestId, System.currentTimeMillis());
    }

    private static final class ConsumerHarness implements AutoCloseable {
        private final Plugin plugin = mock(Plugin.class);
        private final SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        private final EconomyWriteQueue queue = new EconomyWriteQueue(10);
        private final CacheManager cacheManager = mock(CacheManager.class);
        private final RedisManager redisManager = mock(RedisManager.class);
        private final DbWriteQueue dbWriteQueue = mock(DbWriteQueue.class);
        private final NameResolver nameResolver = mock(NameResolver.class);
        private final EconomyEventConsumer consumer;

        private ConsumerHarness(String loggerName) {
            when(plugin.getLogger()).thenReturn(Logger.getLogger(loggerName));
            when(config.isPubsubEnabled()).thenReturn(false);
            when(config.isDbEnabled()).thenReturn(false);
            consumer = new EconomyEventConsumer(plugin, config, queue, cacheManager, redisManager,
                    dbWriteQueue, null, null, nameResolver, null, null, null,
                    mock(DatabaseManager.class));
        }

        private void startWith(EconomyEvent event) throws InterruptedException {
            assertTrue(queue.offerBlocking(event, 1, TimeUnit.SECONDS), "fixture event must enter the queue");
            consumer.start();
        }

        private void stop() {
            consumer.stop();
        }

        @Override
        public void close() {
            consumer.stop();
            consumer.shutdown();
        }
    }
}
