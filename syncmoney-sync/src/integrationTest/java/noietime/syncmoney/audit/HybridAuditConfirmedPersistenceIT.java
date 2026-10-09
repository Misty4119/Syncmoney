package noietime.syncmoney.audit;
import noietime.syncmoney.testkit.RedisTestSupport;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.AuditConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridAuditConfirmedPersistenceIT {

    @Test
    void confirmedAuditLuaWritesOnceAndDeduplicatesResidentIds() throws Exception {
        try (DisposableRedisServer redis = DisposableRedisServer.start()) {
            RedisManager redisManager = mock(RedisManager.class);
            when(redisManager.isDegraded()).thenReturn(false);
            when(redisManager.getResource()).thenAnswer(ignored -> RedisTestSupport.connect(redis));

            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
            when(plugin.getResource("lua/atomic_audit.lua"))
                    .thenReturn(Syncmoney.class.getResourceAsStream("/lua/atomic_audit.lua"));

            AuditConfig auditConfig = mock(AuditConfig.class);
            when(auditConfig.isAuditRedisEnabled()).thenReturn(true);
            when(auditConfig.getAuditRedisWindowSize()).thenReturn(10);
            when(auditConfig.getAuditMigrationThreshold()).thenReturn(10);
            when(auditConfig.getAuditMigrationBatchSize()).thenReturn(10);
            when(auditConfig.getAuditMigrationIntervalMs()).thenReturn(0L);
            when(auditConfig.getAuditRedisFlushIntervalMs()).thenReturn(0L);
            SyncmoneyConfig config = mock(SyncmoneyConfig.class);
            when(config.audit()).thenReturn(auditConfig);

            HybridAuditManager manager = new HybridAuditManager(plugin, config, redisManager, null, null);
            try {
                AuditRecord record = new AuditRecord("redis-confirmed-audit-1", 1_800_000_000_000L, 0,
                        AuditRecord.AuditType.DEPOSIT, UUID.randomUUID(), "player", BigDecimal.TEN,
                        new BigDecimal("110.00"), EconomyEvent.EventSource.VAULT_DEPOSIT,
                        "redis-it", null, null, null, 1);

                manager.persistConfirmedRecord(record);
                manager.persistConfirmedRecord(record);

                try (Jedis jedis = RedisTestSupport.connect(redis)) {
                    List<String> entries = jedis.lrange("syncmoney:audit:recent", 0, -1);
                    assertEquals(1, entries.size(), "retrying a resident audit id must not add another list entry");
                    assertTrue(entries.get(0).contains("\"id\":\"" + record.id() + "\""),
                            "the Lua script must preserve the confirmed record id");
                    assertEquals("1", jedis.get("syncmoney:audit:index"),
                            "the Lua script should increment the recent-window index only once");
                }
            } finally {
                manager.close();
            }
        }
    }

}
