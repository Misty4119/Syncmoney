package noietime.syncmoney.breaker;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionStateManagerTest {

    @Test
    void failedHealthCheckMarksRedisDegradedAndHealthyCheckClearsIt() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConnectionStateManagerTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.circuitBreaker().getCircuitBreakerRedisDisconnectLockSeconds()).thenReturn(60);

        RedisManager redisManager = mock(RedisManager.class);
        AtomicBoolean degraded = new AtomicBoolean();
        when(redisManager.isDegraded()).thenAnswer(invocation -> degraded.get());
        when(redisManager.isConnected()).thenReturn(false, true);
        doAnswer(invocation -> {
            degraded.set(invocation.getArgument(0));
            return null;
        }).when(redisManager).setDegraded(anyBoolean());

        ConnectionStateManager stateManager = new ConnectionStateManager(plugin, config, redisManager, true, null);
        try {
            checkNow(stateManager);
            boolean degradedAfterFailure = degraded.get();
            checkNow(stateManager);

            assertTrue(degradedAfterFailure, "a failed Redis health check must immediately gate new shared writes");
            assertFalse(degraded.get(), "a successful health check must restore the shared write gate");
        } finally {
            stateManager.shutdown();
        }
    }

    @Test
    void startupDegradedRedisTriggersConfiguredLockdown() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConnectionStateManagerTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.circuitBreaker().getCircuitBreakerRedisDisconnectLockSeconds()).thenReturn(0);

        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(true);
        when(redisManager.isConnected()).thenReturn(false);
        EconomicCircuitBreaker circuitBreaker = mock(EconomicCircuitBreaker.class);

        ConnectionStateManager stateManager = new ConnectionStateManager(plugin, config, redisManager, true,
                circuitBreaker);
        try {
            checkNow(stateManager);

            verify(circuitBreaker).triggerLockdown(anyString(), eq(LockReason.REDIS_DISCONNECT));
        } finally {
            stateManager.shutdown();
        }
    }

    private static void checkNow(ConnectionStateManager stateManager) throws Exception {
        Method checkConnection = ConnectionStateManager.class.getDeclaredMethod("checkConnection");
        checkConnection.setAccessible(true);
        checkConnection.invoke(stateManager);
    }
}
