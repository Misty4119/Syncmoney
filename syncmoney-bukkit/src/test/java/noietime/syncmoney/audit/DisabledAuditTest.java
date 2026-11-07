package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisabledAuditTest {
    @Test
    void disabledAuditDoesNotOpenDatabaseOrStartSchedulers() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        HikariDataSource database = mock(HikariDataSource.class);
        AuditServiceManager services = new AuditServiceManager(plugin, config, null, database);
        services.initialize();
        assertFalse(services.getAuditLogger().isEnabled());
        assertNull(services.getHybridAuditManager());
        assertNull(services.getAuditLogCleanup());
        assertNull(services.getAuditLogExporter());
        services.getAuditLogger().getPlayerRecords(java.util.UUID.randomUUID(), 10);
        services.shutdown();
        verifyNoInteractions(database);
        verify(plugin, never()).getServer();
    }

    @Test
    void disabledAuditLeavesConfiguredRedisCleanupAndExportInert(@TempDir Path tempDir) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.audit().isAuditEnabled()).thenReturn(false);
        when(config.audit().isAuditRedisEnabled()).thenReturn(true);
        when(config.audit().isAuditCleanupEnabled()).thenReturn(true);
        when(config.audit().isAuditExportEnabled()).thenReturn(true);
        Path exportFolder = tempDir.resolve("audit-export");
        when(config.audit().getAuditExportFolder()).thenReturn(exportFolder.toString());
        HikariDataSource database = mock(HikariDataSource.class);
        RedisManager redisManager = mock(RedisManager.class);
        AuditServiceManager services = new AuditServiceManager(plugin, config, redisManager, database);

        assertFalse(config.audit().isAuditEnabled());
        assertTrue(config.audit().isAuditRedisEnabled());
        assertTrue(config.audit().isAuditCleanupEnabled());
        assertTrue(config.audit().isAuditExportEnabled());

        try (MockedConstruction<HybridAuditManager> hybridManagers = mockConstruction(HybridAuditManager.class)) {
            services.initialize();

            assertFalse(services.getAuditLogger().isEnabled());
            assertNull(services.getHybridAuditManager());
            assertNull(services.getAuditLogCleanup());
            assertNull(services.getAuditLogExporter());
            assertTrue(hybridManagers.constructed().isEmpty());
            assertFalse(Files.exists(exportFolder));
            services.getAuditLogger().getPlayerRecords(java.util.UUID.randomUUID(), 10);
            services.shutdown();
        }

        verifyNoInteractions(database);
        verifyNoInteractions(redisManager);
        verify(plugin, never()).getServer();
    }
}
