package noietime.syncmoney.storage;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriterConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageManagerDisabledBackendTest {

    @Test
    void localModeSkipsRedisAndSharedSqlResources() {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = config(EconomyMode.LOCAL);

        try (MockedConstruction<DatabaseManager> databaseManagers = mockConstruction(DatabaseManager.class);
             MockedConstruction<DbWriterConsumer> writerConsumers = mockConstruction(DbWriterConsumer.class)) {
            StorageManager manager = new StorageManager(plugin, config);
            try {
                manager.initialize();

                Map<String, Object> redisStats = manager.getRedisManager().getPoolStats();
                assertEquals("LOCAL mode - Redis not available", redisStats.get("error"));
                assertTrue(manager.getRedisManager().isDegraded());
                assertFalse(manager.getCacheManager().isRedisRequired());
                assertNull(manager.getDatabaseManager());
                assertNotNull(manager.getDbWriteQueue(), "the lightweight compatibility queue remains available");
                assertTrue(databaseManagers.constructed().isEmpty());
                assertTrue(writerConsumers.constructed().isEmpty());
                verify(plugin, never()).getServer();
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void localRedisModeKeepsRedisRequiredButSkipsSharedSqlResources() {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = config(EconomyMode.LOCAL_REDIS);
        AtomicReference<RedisManager> redisManager = new AtomicReference<>();

        try (MockedConstruction<RedisManager> redisManagers = mockConstruction(RedisManager.class, (mock, context) -> {
                 redisManager.set(mock);
                 assertSame(plugin, context.arguments().get(0));
                 assertSame(config, context.arguments().get(1));
                 assertEquals(Boolean.TRUE, context.arguments().get(2));
             });
             MockedConstruction<CacheManager> cacheManagers = mockConstruction(CacheManager.class, (mock, context) -> {
                 assertSame(plugin, context.arguments().get(0));
                 assertSame(redisManager.get(), context.arguments().get(1));
                 assertEquals(Boolean.TRUE, context.arguments().get(2));
             });
             MockedConstruction<DatabaseManager> databaseManagers = mockConstruction(DatabaseManager.class);
             MockedConstruction<DbWriterConsumer> writerConsumers = mockConstruction(DbWriterConsumer.class)) {
            StorageManager manager = new StorageManager(plugin, config);
            try {
                manager.initialize();

                assertEquals(1, redisManagers.constructed().size());
                assertEquals(1, cacheManagers.constructed().size());
                assertNull(manager.getDatabaseManager());
                assertNotNull(manager.getDbWriteQueue(), "the lightweight compatibility queue remains available");
                assertTrue(databaseManagers.constructed().isEmpty());
                assertTrue(writerConsumers.constructed().isEmpty());
                verify(plugin, never()).getServer();
            } finally {
                manager.shutdown();
            }
        }
    }

    private static Syncmoney plugin() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StorageManagerDisabledBackendTest"));
        return plugin;
    }

    private static SyncmoneyConfig config(EconomyMode mode) {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.getEconomyMode()).thenReturn(mode);
        when(config.getQueueCapacity()).thenReturn(32);
        when(config.isDebug()).thenReturn(false);
        return config;
    }
}
