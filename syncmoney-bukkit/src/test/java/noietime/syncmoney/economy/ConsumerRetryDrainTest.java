package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConsumerRetryDrainTest {
    @Test
    void shutdownDrainsRetryEvenWhenQueueAndAcceptedOwnershipAreAlreadyEmpty() throws Exception {
        UUID account = UUID.randomUUID();
        String requestId = "retry-drain-without-queue-owner";
        Plugin plugin = mock(Plugin.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        CacheManager cache = mock(CacheManager.class);
        RedisManager redis = mock(RedisManager.class);
        DbWriteQueue dbQueue = mock(DbWriteQueue.class);
        NameResolver nameResolver = mock(NameResolver.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        CountDownLatch firstDownstream = new CountDownLatch(1);
        CountDownLatch releaseFirstFailure = new CountDownLatch(1);
        CountDownLatch retryDownstream = new CountDownLatch(1);
        AtomicInteger dbAttempts = new AtomicInteger();
        AtomicInteger redisMutations = new AtomicInteger();
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConsumerRetryDrainTest"));
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(true);
        when(config.getServerName()).thenReturn("test-server");
        when(nameResolver.getNameCachedOnly(account)).thenReturn("account");
        when(cache.atomicAddBalanceReceipt(eq(account), any(BigDecimal.class), anyString())).thenAnswer(invocation -> {
            redisMutations.incrementAndGet();
            return new ConfirmedAccount(account, new BigDecimal("110.00"), 9L);
        });
        when(dbQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            int attempt = dbAttempts.incrementAndGet();
            if (attempt == 1) {
                firstDownstream.countDown();
                assertTrue(releaseFirstFailure.await(5, TimeUnit.SECONDS));
                return CompletableFuture.completedFuture(false);
            }
            retryDownstream.countDown();
            return CompletableFuture.completedFuture(true);
        });

        EconomyEvent event = new EconomyEvent(account, BigDecimal.TEN, new BigDecimal("110.00"), 9L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                requestId, System.currentTimeMillis());
        assertTrue(queue.offer(event));
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cache,
                redis, dbQueue, null, null, nameResolver, null, null, null, null);

        try {
            consumer.start();
            assertTrue(firstDownstream.await(5, TimeUnit.SECONDS));
            assertEquals(0, queue.size(), "the consumer has already removed the event from the queue");

            consumer.stop();
            queue.complete(event, new BigDecimal("110.00"), 9L);
            assertFalse(queue.hasOutstandingWrites(),
                    "fixture removes queue ownership while retaining the failed downstream retry");
            releaseFirstFailure.countDown();

            consumer.shutdown();

            assertTrue(retryDownstream.await(1, TimeUnit.SECONDS),
                    "shutdown must drain failedEvents even when the queue and accepted-write set are empty");
            assertEquals(1, redisMutations.get(), "confirmed receipt retries must not repeat Redis mutation");
            assertEquals(2, dbAttempts.get());
            assertTrue(queue.isEmpty());
        } finally {
            releaseFirstFailure.countDown();
            consumer.shutdown();
        }
    }
}
