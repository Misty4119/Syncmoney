package noietime.syncmoney.storage.db;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbWriterConsumerTransferTest {

    @Test
    void shutdownKeepsRetryingAnInMemoryStalePairUntilItCanPersist() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DbWriterConsumerTransferTest"));
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        DbWriteQueue queue = new DbWriteQueue(8);
        DbWriteQueue.DbWriteTask staleTask = new DbWriteQueue.DbWriteTask("transfer-shutdown-op",
                List.of(
                        new DbWriteQueue.AccountWrite(new ConfirmedAccount(sender,
                                new BigDecimal("90.00"), 8L), "sender", "server-a", Instant.now()),
                        new DbWriteQueue.AccountWrite(new ConfirmedAccount(receiver,
                                new BigDecimal("10.00"), 4L), "receiver", "server-a", Instant.now())));
        assertTrue(queue.offer(staleTask));

        CacheManager cacheManager = mock(CacheManager.class);
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicReference<DbWriterConsumer> consumerRef = new AtomicReference<>();
        when(cacheManager.readTransferSnapshotStrict(sender, receiver)).thenAnswer(invocation -> {
            if (snapshotReads.incrementAndGet() == 1) {
                consumerRef.get().stop();
                throw new IllegalStateException("temporary Redis snapshot outage");
            }
            return new CacheManager.TransferResult(new BigDecimal("91.00"),
                    new BigDecimal("11.00"), 9L, 5L);
        });

        CountDownLatch refreshedPairPersisted = new CountDownLatch(1);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        when(databaseManager.batchInsertOrUpdatePlayersWithStale(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<DbWriteQueue.DbWriteTask> tasks = invocation.getArgument(0);
            boolean latest = tasks.size() == 1
                    && tasks.get(0).accounts().get(0).account().version() == 9L
                    && tasks.get(0).accounts().get(1).account().version() == 5L;
            if (latest) {
                refreshedPairPersisted.countDown();
                return List.of();
            }
            return tasks.stream().filter(task -> task.accountCount() == 2).toList();
        });

        DbWriterConsumer consumer = new DbWriterConsumer(plugin, queue, databaseManager,
                cacheManager, mock(RedisManager.class));
        consumerRef.set(consumer);
        Thread thread = new Thread(consumer, "db-writer-transfer-shutdown-retry-test");
        thread.start();

        boolean persistedBeforeExit = refreshedPairPersisted.await(6, TimeUnit.SECONDS);
        thread.join(3_000);

        assertAll(
                () -> assertTrue(persistedBeforeExit,
                        "a transient authority read failure during shutdown must retain the pair for retry"),
                () -> assertEquals(2, snapshotReads.get()),
                () -> assertFalse(thread.isAlive(), "the writer should exit after the pair is reconciled")
        );
    }

    @Test
    void staleTransferPairSurvivesRepeatedRedisSnapshotReadFailures() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DbWriterConsumerTransferTest"));
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        DbWriteQueue queue = new DbWriteQueue(8);
        DbWriteQueue.DbWriteTask staleTask = new DbWriteQueue.DbWriteTask("transfer-op",
                List.of(
                        new DbWriteQueue.AccountWrite(new ConfirmedAccount(sender,
                                new BigDecimal("90.00"), 8L), "sender", "server-a", Instant.now()),
                        new DbWriteQueue.AccountWrite(new ConfirmedAccount(receiver,
                                new BigDecimal("10.00"), 4L), "receiver", "server-a", Instant.now())));
        assertTrue(queue.offer(staleTask));

        CacheManager cacheManager = mock(CacheManager.class);
        AtomicInteger snapshotReads = new AtomicInteger();
        when(cacheManager.readTransferSnapshotStrict(sender, receiver)).thenAnswer(invocation -> {
            if (snapshotReads.incrementAndGet() <= 4) {
                throw new IllegalStateException("temporary Redis snapshot outage");
            }
            return new CacheManager.TransferResult(new BigDecimal("91.00"),
                    new BigDecimal("11.00"), 9L, 5L);
        });

        CountDownLatch refreshedPairPersisted = new CountDownLatch(1);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        when(databaseManager.batchInsertOrUpdatePlayersWithStale(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<DbWriteQueue.DbWriteTask> tasks = invocation.getArgument(0);
            boolean latest = tasks.size() == 1
                    && tasks.get(0).accounts().get(0).account().version() == 9L
                    && tasks.get(0).accounts().get(1).account().version() == 5L;
            if (latest) {
                refreshedPairPersisted.countDown();
                return List.of();
            }
            return tasks.stream().filter(task -> task.accountCount() == 2).toList();
        });

        RedisManager redisManager = mock(RedisManager.class);
        DbWriterConsumer consumer = new DbWriterConsumer(plugin, queue, databaseManager,
                cacheManager, redisManager);
        Thread thread = new Thread(consumer, "db-writer-transfer-retry-test");
        thread.start();

        assertTrue(refreshedPairPersisted.await(8, TimeUnit.SECONDS),
                "repeated Redis read failures must retain the transfer until a fresh authority pair can be read");
        consumer.stop();
        thread.join(3_000);

        assertAll(
                () -> assertEquals(5, snapshotReads.get(), "the transfer should retry after four read failures"),
                () -> assertTrue(queue.isEmpty()),
                () -> assertFalse(thread.isAlive(), "the writer should drain once Redis and SQL recover")
        );
    }
}
