package noietime.syncmoney.economy;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIApi;
import noietime.syncmoney.sync.CMIRedisMirror;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CMIRedisMirrorTest {

    @Test
    void readReturnsOneAtomicBalanceVersionSnapshot() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000013");
        String prefix = "cmi:";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(balanceKey, versionKey)).thenReturn(Arrays.asList("12.50", "12345"));

        Optional<CMIRedisMirror.Snapshot> result = new CMIRedisMirror(redisManager, prefix).read(uuid);

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("12.50"), result.orElseThrow().balance());
        assertEquals(12345L, result.orElseThrow().version());
        verify(jedis).mget(balanceKey, versionKey);
        verify(jedis, never()).get(anyString());
    }

    @Test
    void incompletePairIsNotReturnedAsAReconciliationSnapshot() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000014");
        String prefix = "cmi:";
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(RedisKeys.cmiBalance(prefix, uuid), RedisKeys.cmiVersion(prefix, uuid)))
                .thenReturn(Arrays.asList("12.50", null));

        Optional<CMIRedisMirror.Snapshot> result = new CMIRedisMirror(redisManager, prefix).read(uuid);

        assertTrue(result.isEmpty());
    }

    @Test
    void compareAndWriteUsesAnAtomicVersionComparisonForBothMirrorKeys() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000015");
        String prefix = "cmi:";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        CMIRedisMirror.WriteResult result = new CMIRedisMirror(redisManager, prefix)
                .compareAndWrite(uuid, new BigDecimal("20.00"), 17_000_000_000_000_001L);

        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED, result);
        verify(jedis).eval(anyString(), eq(2), eq(balanceKey), eq(versionKey),
                eq("20.00"), eq("17000000000000001"));
        verify(jedis, never()).mset(anyString(), anyString(), anyString(), anyString());
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void compareAndWriteDistinguishesRejectedAndUnavailableMirrorStates() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000016");
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        CMIRedisMirror mirror = new CMIRedisMirror(redisManager, "cmi:");
        assertEquals(CMIRedisMirror.WriteResult.REJECTED,
                mirror.compareAndWrite(uuid, new BigDecimal("20.00"), 18L));

        when(redisManager.isDegraded()).thenReturn(true);
        assertEquals(CMIRedisMirror.WriteResult.UNAVAILABLE,
                mirror.compareAndWrite(uuid, new BigDecimal("20.00"), 19L));
    }

    @Test
    void rejectedOutboundMirrorWriteDoesNotPublishOrReportCmiMutationAsAccepted() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000017");
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CMIRedisMirrorTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        when(config.cmi().isCMICrossServerSync()).thenReturn(true);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        when(jedis.get(anyString())).thenReturn("10.00");
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, redisManager, syncManager,
                mock(CMIApi.class));

        assertFalse(handler.trySyncAbsoluteBalance(uuid, new BigDecimal("20.00"),
                new BigDecimal("10.00"), true, "test"));
        assertEquals(new BigDecimal("-1"), handler.deposit(uuid, new BigDecimal("1.00")));
        assertEquals(new BigDecimal("-1"), handler.withdraw(uuid, new BigDecimal("1.00")));
        assertEquals(new BigDecimal("-1"), handler.setBalance(uuid, new BigDecimal("20.00")));

        verify(syncManager, never()).publishCMIUpdate(any(UUID.class), any(BigDecimal.class), anyLong(),
                anyString(), anyDouble(), anyString());
    }
}
