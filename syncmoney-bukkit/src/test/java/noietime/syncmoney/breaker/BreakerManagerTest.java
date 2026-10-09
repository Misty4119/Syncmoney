package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.RedisManager;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BreakerManagerTest {

    @Test
    void sharedModeMonitorsRedisEvenWhenCircuitBreakerIsDisabled() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BreakerManagerTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.getEconomyMode()).thenReturn(EconomyMode.SYNC);
        when(config.circuitBreaker().isCircuitBreakerEnabled()).thenReturn(false);
        when(config.playerProtection().isPlayerProtectionEnabled()).thenReturn(false);
        when(config.discordWebhook().isDiscordWebhookEnabled()).thenReturn(false);

        RedisManager redisManager = mock(RedisManager.class);
        BreakerManager manager = new BreakerManager(plugin, config, mock(EconomyFacade.class), redisManager);
        try {
            manager.initialize();

            verify(redisManager, atLeastOnce()).isDegraded();
        } finally {
            manager.shutdown();
        }
    }
}
