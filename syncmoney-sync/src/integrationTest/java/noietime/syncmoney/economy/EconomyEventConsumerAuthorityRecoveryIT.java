package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.CacheManagerIntegrationTestFactory;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.recovery.RecoverySpool;
import noietime.syncmoney.testkit.RedisTestSupport;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EconomyEventConsumerAuthorityRecoveryIT {
    @TempDir
    Path temp;

    @Test
    void missingRedisAccountWithStrictSqlSnapshotIsQuarantinedWithoutMutationOrAcknowledgement() throws Exception {
        UUID uuid = UUID.randomUUID();
        DatabaseManager.PlayerRecord strictSql = new DatabaseManager.PlayerRecord(
                uuid, "synthetic", new BigDecimal("900.00"), 41L, "sql-server", Instant.parse("2026-01-01T00:00:00Z"));
        EconomyEvent stale = new EconomyEvent(uuid, new BigDecimal("5.00"), new BigDecimal("5.00"), 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "r52d-stale-missing-redis", System.currentTimeMillis());

        try (DisposableRedisServer redis = DisposableRedisServer.start()) {
            Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyEventConsumerAuthorityRecoveryIT"));
            when(plugin.getResource(anyString())).thenAnswer(invocation -> {
                String resource = invocation.getArgument(0);
                return CacheManager.class.getClassLoader().getResourceAsStream(resource);
            });

            RedisManager redisManager = mock(RedisManager.class);
            when(redisManager.getResource()).thenAnswer(invocation -> RedisTestSupport.connect(redis));
            when(redisManager.isDegraded()).thenReturn(false);
            CacheManager cacheManager = CacheManagerIntegrationTestFactory.newSynchronizedModeCacheManager(
                    plugin, redisManager, true);

            try {
                SyncmoneyConfig config = mock(SyncmoneyConfig.class);
                when(config.isSyncMode()).thenReturn(true);
                when(config.isPubsubEnabled()).thenReturn(false);
                when(config.isDbEnabled()).thenReturn(true);
                when(config.getServerName()).thenReturn("test-server");

                DatabaseManager database = mock(DatabaseManager.class);
                when(database.isConnected()).thenReturn(true);
                when(database.getPlayerStrict(uuid)).thenReturn(Optional.of(strictSql));

                EconomyWriteQueue queue = new EconomyWriteQueue(8);
                List<UUID> quarantined = new CopyOnWriteArrayList<>();
                queue.setQuarantinedAccountHandler(quarantined::add);
                assertTrue(queue.offer(stale));

                RecoverySpool spool = new RecoverySpool(temp.resolve("authority-recovery.jsonl"));
                DbWriteQueue dbWriteQueue = mock(DbWriteQueue.class);
                EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cacheManager,
                        redisManager, dbWriteQueue, null, null, mock(NameResolver.class), null, null, null,
                        database, spool);

                invokeProcessEvent(consumer, stale);

                assertEquals(1, spool.pending().size());
                assertTrue(spool.pending().get(0).payload().contains("\"stage\":\"AMBIGUOUS\""));
                assertTrue(queue.hasAcceptedWrite(uuid), "the event remains owned because it was not acknowledged");
                assertEquals(List.of(uuid), quarantined);
                verify(database).getPlayerStrict(uuid);
                verify(dbWriteQueue, never()).offerAndTrack(org.mockito.ArgumentMatchers.any());
                assertEquals(new BigDecimal("900.00"), strictSql.balance());
                assertEquals(41L, strictSql.version());

                try (Jedis jedis = RedisTestSupport.connect(redis)) {
                    assertNull(jedis.get("syncmoney:balance:" + uuid));
                    assertNull(jedis.get("syncmoney:version:" + uuid));
                }
            } finally {
                cacheManager.shutdown();
            }
        }
    }

    private static void invokeProcessEvent(EconomyEventConsumer consumer, EconomyEvent event) throws Exception {
        Method processEvent = EconomyEventConsumer.class.getDeclaredMethod("processEvent", EconomyEvent.class);
        processEvent.setAccessible(true);
        processEvent.invoke(consumer, event);
    }
}
