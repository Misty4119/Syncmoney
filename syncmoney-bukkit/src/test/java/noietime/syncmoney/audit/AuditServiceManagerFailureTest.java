package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceManagerFailureTest {

    @Test
    void partialInitializationStopsEveryCreatedAuditOwnerAfterStartAndCloseFailures() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(config.audit().isAuditEnabled()).thenReturn(true);
        when(config.audit().isAuditRedisEnabled()).thenReturn(true);
        when(config.audit().isAuditCleanupEnabled()).thenReturn(true);
        when(config.audit().isAuditExportEnabled()).thenReturn(true);

        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        HikariDataSource dataSource = mock(HikariDataSource.class);

        try (MockedConstruction<AuditLogger> auditLoggers = mockConstruction(AuditLogger.class, (mock, context) ->
                     doThrow(new IllegalStateException("audit logger close failed")).when(mock).close());
             MockedConstruction<HybridAuditManager> hybridManagers = mockConstruction(HybridAuditManager.class,
                     (mock, context) -> doThrow(new IllegalStateException("hybrid audit close failed"))
                             .when(mock).close());
             MockedConstruction<AuditLogCleanup> cleanups = mockConstruction(AuditLogCleanup.class);
             MockedConstruction<AuditLogExporter> exporters = mockConstruction(AuditLogExporter.class,
                     (mock, context) -> doThrow(new IllegalStateException("export start failed"))
                             .when(mock).start())) {
            AuditServiceManager services = new AuditServiceManager(plugin, config, redisManager, dataSource);

            IllegalStateException startFailure = assertThrows(IllegalStateException.class, services::initialize);
            assertEquals("export start failed", startFailure.getMessage());

            services.shutdown();

            AuditLogger auditLogger = auditLoggers.constructed().get(0);
            HybridAuditManager hybridAuditManager = hybridManagers.constructed().get(0);
            AuditLogCleanup cleanup = cleanups.constructed().get(0);
            AuditLogExporter exporter = exporters.constructed().get(0);
            InOrder order = inOrder(hybridAuditManager, auditLogger, cleanup, exporter);
            order.verify(hybridAuditManager).close();
            order.verify(auditLogger).close();
            order.verify(cleanup).stop();
            order.verify(exporter).stop();
            verify(cleanup).start();
            verify(exporter).start();
        }
    }
}
