package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.junit.jupiter.api.Test;
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
}
