package noietime.syncmoney.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class CacheManagerTransferExecutionTest {

    @Test
    void transferWithoutRedisResourceIsClassifiedAsNotSent() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(jedis.scriptLoad(anyString())).thenReturn("atomic-transfer-sha");
        AtomicInteger resources = new AtomicInteger();
        when(redisManager.getResource()).thenAnswer(invocation -> resources.incrementAndGet() == 1 ? jedis : null);
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);

        RedisMutationException failure = assertThrows(RedisMutationException.class,
                () -> cacheManager.atomicTransfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00")));

        assertEquals(RedisMutationException.ExecutionState.NOT_SENT, failure.executionState());
    }

    @Test
    void authoritySnapshotReadBypassesAnUnexpiredLocalCacheEntry() throws Exception {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID account = UUID.randomUUID();
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("script-sha");
        when(jedis.mget(eq("syncmoney:balance:" + account), eq("syncmoney:version:" + account)))
                .thenReturn(List.of("20.00", "8"));
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);
        cacheManager.updateMemoryCache(account, new BigDecimal("100.00"), 7L);

        CacheManager.CachedBalance snapshot = cacheManager.readAuthorityBalanceSnapshotStrict(account).orElseThrow();

        assertEquals(new BigDecimal("20.00"), snapshot.balance());
        assertEquals(8L, snapshot.version());
    }
}
