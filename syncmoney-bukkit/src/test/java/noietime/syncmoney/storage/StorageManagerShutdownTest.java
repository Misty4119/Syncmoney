package noietime.syncmoney.storage;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriterConsumer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageManagerShutdownTest {

    @Test
    void storageDependenciesRemainOpenWhileDatabaseWriterIsStillRunning() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StorageManagerShutdownTest"));
        StorageManager manager = new StorageManager(plugin, mock(SyncmoneyConfig.class), 25, 25);
        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        DbWriterConsumer consumer = mock(DbWriterConsumer.class);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch dependenciesClosed = new CountDownLatch(2);
        doAnswer(invocation -> {
            dependenciesClosed.countDown();
            return null;
        }).when(databaseManager).close();
        doAnswer(invocation -> {
            dependenciesClosed.countDown();
            return null;
        }).when(redisManager).close();
        doAnswer(invocation -> {
            while (true) {
                try {
                    releaseWriter.await();
                    return null;
                } catch (InterruptedException ignored) {
                    // Model a JDBC operation that does not terminate on interrupt.
                }
            }
        }).when(consumer).run();

        set(manager, "cacheManager", cacheManager);
        set(manager, "redisManager", redisManager);
        set(manager, "databaseManager", databaseManager);
        set(manager, "dbWriterConsumer", consumer);
        Thread writer = manager.createDbWriterThread(consumer);
        set(manager, "dbWriterThread", writer);
        writer.start();

        try {
            manager.shutdown();

            verify(databaseManager, never()).close();
            verify(redisManager, never()).close();
            assertTrue(writer.isAlive(), "the simulated database operation should still be running");
        } finally {
            releaseWriter.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(writer.isAlive(), "test database writer should finish after release");
        assertTrue(dependenciesClosed.await(5, TimeUnit.SECONDS),
                "storage dependencies should close after the writer relinquishes them");
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
