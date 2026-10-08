package noietime.syncmoney.economy;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIRedisMirror;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
    void writeReplacesTheExistingMirrorKeysWithOneAtomicCommand() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000015");
        String prefix = "cmi:";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        when(redisManager.getResource()).thenReturn(jedis);

        new CMIRedisMirror(redisManager, prefix).write(uuid, new BigDecimal("20.00"), 98765L);

        verify(jedis).mset(balanceKey, "20.00", versionKey, "98765");
        verify(jedis, never()).set(anyString(), anyString());
    }
}
