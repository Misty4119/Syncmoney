package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.AuditConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.storage.RedisManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridAuditManagerConfirmedPersistenceTest {
    @TempDir
    Path temp;

    @Test
    void sqlReceiptSatisfiesConfirmedAuditWhenRedisRecentWindowIsUnavailable() throws Exception {
        try (HikariDataSource dataSource = dataSource("sql-fallback")) {
            Syncmoney plugin = plugin();
            SyncmoneyConfig config = config(true);
            new AuditLogger(plugin, config, dataSource);
            HybridAuditManager manager = new HybridAuditManager(plugin, config, unavailableRedis(), dataSource, null);

            manager.persistConfirmedRecord(record("hybrid-audit-1"));

            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void confirmedAuditSurfacesFailureWhenRedisIsItsOnlyConfiguredStore() {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = config(true);
        HybridAuditManager manager = new HybridAuditManager(plugin, config, unavailableRedis(), null, null);

        assertThrows(SQLException.class, () -> manager.persistConfirmedRecord(record("redis-only-audit-1")));
    }

    private HikariDataSource dataSource(String suffix) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + temp.resolve(suffix + ".db"));
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    private static Syncmoney plugin() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HybridAuditManagerConfirmedPersistenceTest"));
        return plugin;
    }

    private static SyncmoneyConfig config(boolean redisEnabled) {
        AuditConfig audit = mock(AuditConfig.class);
        when(audit.isAuditEnabled()).thenReturn(true);
        when(audit.isAuditRedisEnabled()).thenReturn(redisEnabled);
        when(audit.getAuditBatchSize()).thenReturn(10);
        when(audit.getAuditFlushIntervalMs()).thenReturn(0L);
        when(audit.getAuditRedisWindowSize()).thenReturn(10);
        when(audit.getAuditMigrationThreshold()).thenReturn(10);
        when(audit.getAuditMigrationBatchSize()).thenReturn(10);
        when(audit.getAuditMigrationIntervalMs()).thenReturn(0L);
        when(audit.getAuditRedisFlushIntervalMs()).thenReturn(0L);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.audit()).thenReturn(audit);
        return config;
    }

    private static RedisManager unavailableRedis() {
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        when(redis.getResource()).thenThrow(new IllegalStateException("Redis audit backend unavailable"));
        return redis;
    }

    private static AuditRecord record(String id) {
        return new AuditRecord(id, 1_800_000_000_000L, 0, AuditRecord.AuditType.DEPOSIT,
                UUID.randomUUID(), "player", BigDecimal.TEN, new BigDecimal("110.00"),
                EconomyEvent.EventSource.VAULT_DEPOSIT, "server", null, null, null, 1);
    }
}
