package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.AuditConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import org.bukkit.plugin.Plugin;
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

class AuditLoggerConfirmedPersistenceTest {
    @TempDir
    Path temp;

    @Test
    void retryAfterCommittedAuditRowIsIdempotent() throws Exception {
        try (HikariDataSource dataSource = dataSource("idempotent")) {
            AuditLogger logger = logger(dataSource);
            UUID player = UUID.randomUUID();
            AuditRecord first = record("audit-confirmed-1", player, "first-name", "server-a");
            AuditRecord retry = record(first.id(), player, "renamed-player", "server-b");

            logger.persistConfirmedRecord(first);
            logger.persistConfirmedRecord(retry);

            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void confirmedAuditPersistencePropagatesBackendFailure() throws Exception {
        try (HikariDataSource dataSource = dataSource("unavailable")) {
            AuditLogger logger = logger(dataSource);
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("DROP TABLE syncmoney_audit_log");
            }

            assertThrows(SQLException.class, () -> logger.persistConfirmedRecord(record(
                    "audit-failure-1", UUID.randomUUID(), "player", "server")));
        }
    }

    private HikariDataSource dataSource(String suffix) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + temp.resolve(suffix + ".db"));
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    private static AuditLogger logger(HikariDataSource dataSource) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AuditLoggerConfirmedPersistenceTest"));
        AuditConfig auditConfig = mock(AuditConfig.class);
        when(auditConfig.isAuditEnabled()).thenReturn(true);
        when(auditConfig.getAuditFlushIntervalMs()).thenReturn(0L);
        when(auditConfig.getAuditBatchSize()).thenReturn(10);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.audit()).thenReturn(auditConfig);
        return new AuditLogger(plugin, config, dataSource);
    }

    private static AuditRecord record(String id, UUID player, String playerName, String server) {
        return new AuditRecord(id, 1_800_000_000_000L, 0, AuditRecord.AuditType.DEPOSIT,
                player, playerName, BigDecimal.TEN, new BigDecimal("110.00"),
                EconomyEvent.EventSource.VAULT_DEPOSIT, server, null, null, null, 1);
    }
}
