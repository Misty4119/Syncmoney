package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisabledBreakerTest {
    @Test
    void disabledProtectionCreatesNeitherGuardNorMonitors() {
        var plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        var facade = mock(EconomyFacade.class);
        var manager = new BreakerManager(plugin, config, facade, null);
        manager.initialize();
        assertNull(manager.getCircuitBreaker());
        assertNull(manager.getPlayerTransactionGuard());
        assertNull(manager.getNotificationService());
        verify(facade).setPlayerTransactionGuard(null);
        verify(facade).setCircuitBreaker(null);
        manager.shutdown();
        verify(plugin, never()).getServer();
    }
}
