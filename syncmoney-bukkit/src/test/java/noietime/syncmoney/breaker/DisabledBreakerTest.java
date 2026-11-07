package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
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

    @Test
    void playerProtectionRemainsAvailableWhenGlobalCircuitBreakerIsDisabled() {
        var plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.getEconomyMode()).thenReturn(EconomyMode.LOCAL);
        when(config.circuitBreaker().isCircuitBreakerEnabled()).thenReturn(false);
        when(config.playerProtection().isPlayerProtectionEnabled()).thenReturn(true);
        when(config.playerProtection().isPlayerProtectionGlobalLockEnabled()).thenReturn(false);
        when(config.discordWebhook().isDiscordWebhookEnabled()).thenReturn(false);
        var facade = mock(EconomyFacade.class);
        var manager = new BreakerManager(plugin, config, facade, null);

        try {
            manager.initialize();

            assertNull(manager.getCircuitBreaker());
            var guard = manager.getPlayerTransactionGuard();
            assertNotNull(guard);
            verify(facade).setPlayerTransactionGuard(guard);
            verify(facade).setCircuitBreaker(null);
        } finally {
            manager.shutdown();
        }

        verify(plugin, never()).getServer();
    }
}
