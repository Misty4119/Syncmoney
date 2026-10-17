package noietime.syncmoney.storage.db;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseManagerInitializationFailureTest {

    @Test
    void closesCreatedPoolWhenSchemaInitializationFails() throws Exception {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = mysqlConfig();

        try (MockedConstruction<HikariDataSource> dataSources = mockConstruction(
                HikariDataSource.class,
                (dataSource, context) -> when(dataSource.getConnection())
                        .thenThrow(new SQLException("controlled schema connection failure")))) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> new DatabaseManager(plugin, config));

            assertEquals("Database initialization failed", failure.getMessage());
            assertEquals(1, dataSources.constructed().size());
            verify(dataSources.constructed().get(0)).close();
        }
    }

    @Test
    void preservesSchemaFailureWhenPoolCloseAlsoFails() throws Exception {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = mysqlConfig();

        try (MockedConstruction<HikariDataSource> dataSources = mockConstruction(
                HikariDataSource.class,
                (dataSource, context) -> {
                    when(dataSource.getConnection())
                            .thenThrow(new SQLException("controlled schema connection failure"));
                    when(dataSource.isClosed()).thenReturn(false);
                    org.mockito.Mockito.doThrow(new IllegalStateException("controlled pool close failure"))
                            .when(dataSource).close();
                })) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> new DatabaseManager(plugin, config));

            assertEquals("Database initialization failed", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertNotNull(failure.getSuppressed()[0]);
            assertEquals("controlled pool close failure", failure.getSuppressed()[0].getMessage());
        }
    }

    private static Syncmoney plugin() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DatabaseManagerInitializationFailureTest"));
        return plugin;
    }

    private static SyncmoneyConfig mysqlConfig() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.database().getDatabaseType()).thenReturn("mysql");
        when(config.database().getDatabaseName()).thenReturn("invalid.name");
        when(config.database().getDatabaseUsername()).thenReturn("test-user");
        when(config.database().getDatabasePassword()).thenReturn("test-password");
        when(config.database().getDatabasePoolSize()).thenReturn(4);
        when(config.database().getDatabaseConnectionTimeout()).thenReturn(250);
        when(config.database().getDatabaseMaxLifetime()).thenReturn(1_800_000L);
        when(config.database().getDatabaseIdleTimeout()).thenReturn(600_000L);
        when(config.isDebug()).thenReturn(false);
        return config;
    }
}
