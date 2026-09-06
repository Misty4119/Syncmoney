package noietime.syncmoney.breaker;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceMonitorTest {
    @Test void localModeDoesNotTreatAbsentRedisPoolAsUnhealthy() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.getEconomyMode()).thenReturn(EconomyMode.LOCAL);
        when(config.circuitBreaker().getCircuitBreakerMemoryWarningThreshold()).thenReturn(100);
        RedisManager redis = mock(RedisManager.class);
        ResourceMonitor monitor = new ResourceMonitor(mock(Plugin.class), config, redis);
        try {
            assertTrue(monitor.isHealthy());
            verifyNoInteractions(redis);
        } finally { monitor.shutdown(); }
    }
}
