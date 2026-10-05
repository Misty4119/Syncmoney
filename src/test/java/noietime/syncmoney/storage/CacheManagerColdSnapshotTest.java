package noietime.syncmoney.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheManagerColdSnapshotTest {

    @Test
    void strictColdSnapshotReadsBalanceAndVersionInOneRedisCommand() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenReturn(List.of("90.00", "7"));
        CacheManager cache = new CacheManager(plugin, redis, false);

        CacheManager.CachedBalance snapshot = cache.getBalanceSnapshotStrict(uuid).orElseThrow();

        assertEquals(new BigDecimal("90.00"), snapshot.balance());
        assertEquals(7L, snapshot.version());
        verify(jedis).mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid);
    }

    @Test
    void ordinaryBalanceReadFillsSharedCacheFromOneRedisPairRead() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenReturn(List.of("90.00", "7"));
        CacheManager cache = new CacheManager(plugin, redis, false);

        assertEquals(new BigDecimal("90.00"), cache.getBalance(uuid));
        assertEquals(7L, cache.getVersion(uuid), "the cached version must come from the same Redis pair read");

        verify(jedis).mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid);
        verify(jedis, never()).get("syncmoney:balance:" + uuid);
        verify(jedis, never()).get("syncmoney:version:" + uuid);
    }

    @Test
    void strictColdSnapshotDoesNotCacheAnAbsentRedisAccountAsZero() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenReturn(java.util.Arrays.asList(null, null));
        CacheManager cache = new CacheManager(plugin, redis, false);

        org.junit.jupiter.api.Assertions.assertTrue(cache.getBalanceSnapshotStrict(uuid).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(cache.getBalanceSnapshotStrict(uuid).isEmpty());

        org.mockito.Mockito.verify(jedis, org.mockito.Mockito.times(2))
                .mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid);
    }
}
