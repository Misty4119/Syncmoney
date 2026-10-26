package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;

class CMIEconomyMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void rejectsCmiHandlerWritesAndMirrorEventsWhileMigrationLockIsActive() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.get(anyString())).thenReturn("100.00");
        when(jedis.set(anyString(), anyString())).thenReturn("OK");
        clearInvocations(redis);
        CMIApi cmiApi = mock(CMIApi.class);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, redis, null, cmiApi);
        UUID account = UUID.randomUUID();

        MigrationLock.enable();
        assertTrue(MigrationLock.lock());

        assertEquals(new BigDecimal("-1"), handler.deposit(account, BigDecimal.ONE));
        assertEquals(new BigDecimal("-1"), handler.withdraw(account, BigDecimal.ONE));
        assertEquals(new BigDecimal("-1"), handler.setBalance(account, BigDecimal.TEN));
        handler.syncAbsoluteBalance(account, BigDecimal.TEN, BigDecimal.TEN, true, "CMI");

        verify(redis, never()).getResource();
    }

    @Test
    void mirrorAttemptReportsLockRejectionSoListenerCanRetryWithoutCachingIt() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, redis, null, mock(CMIApi.class));
        UUID account = UUID.randomUUID();

        Method trySync = CMIEconomyHandler.class.getDeclaredMethod("trySyncAbsoluteBalance",
                UUID.class, BigDecimal.class, BigDecimal.class, boolean.class, String.class);
        trySync.setAccessible(true);
        MigrationLock.enable();
        assertTrue(MigrationLock.lock());
        assertEquals(false, trySync.invoke(handler, account, BigDecimal.TEN, BigDecimal.TEN, true, "CMI"));

        MigrationLock.unlock();
        assertEquals(true, trySync.invoke(handler, account, BigDecimal.TEN, BigDecimal.TEN, true, "CMI"));
        verify(redis).getResource();
    }

    @Test
    void joinMirrorReconciliationDoesNotMutateCmiWhileMigrationLockIsActive() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        RedisManager redis = mock(RedisManager.class);
        CMIApi cmiApi = mock(CMIApi.class);
        UUID account = UUID.randomUUID();
        when(cmiApi.getBalance(account)).thenReturn(100.0);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, redis, null, cmiApi);
        Method applyJoinMirror = CMIEconomyHandler.class.getDeclaredMethod(
                "applyJoinMirror", UUID.class, BigDecimal.class, long.class);
        applyJoinMirror.setAccessible(true);

        MigrationLock.enable();
        assertTrue(MigrationLock.lock());

        applyJoinMirror.invoke(handler, account, new BigDecimal("150.00"), 2L);

        verify(cmiApi, never()).setBalance(account, 150.0);
    }

    @Test
    void joinMirrorDoesNotMarkFailedCmiWriteAsApplied() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        RedisManager redis = mock(RedisManager.class);
        CMIApi cmiApi = mock(CMIApi.class);
        UUID account = UUID.randomUUID();
        when(cmiApi.getBalance(account)).thenReturn(100.0, 100.0);
        when(cmiApi.setBalance(account, 150.0)).thenReturn(false);
        CMIEconomyHandler handler = spy(new CMIEconomyHandler(plugin, config, redis, null, cmiApi));
        Method applyJoinMirror = CMIEconomyHandler.class.getDeclaredMethod(
                "applyJoinMirror", UUID.class, BigDecimal.class, long.class);
        applyJoinMirror.setAccessible(true);

        applyJoinMirror.invoke(handler, account, new BigDecimal("150.00"), 2L);

        verify(handler, never()).notifyInboundApplied(account, new BigDecimal("150.00"), 2L);
    }
}
