package noietime.syncmoney.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheManagerDegradedStateTest {

    @Test
    void addTransportFailureImmediatelyMarksSharedRedisDegraded() {
        Plugin plugin = plugin();
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = preparedJedis(redisManager);
        doThrow(new JedisConnectionException("connection lost"))
                .when(jedis).evalsha(eq("add-sha"), eq(3), any(String[].class));
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);

        assertEquals(new BigDecimal("-1"), cacheManager.atomicAddBalance(UUID.randomUUID(), BigDecimal.ONE));
        verify(redisManager).setDegraded(true);
    }

    @Test
    void setTransportFailureImmediatelyMarksSharedRedisDegraded() {
        Plugin plugin = plugin();
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = preparedJedis(redisManager);
        doThrow(new JedisConnectionException("connection lost"))
                .when(jedis).evalsha(eq("set-sha"), eq(3), any(String[].class));
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);

        assertEquals(-1, cacheManager.atomicSetBalance(UUID.randomUUID(), new BigDecimal("40.00")));
        verify(redisManager).setDegraded(true);
    }

    @Test
    void nonTransportRedisFailureDoesNotMarkSharedRedisDegraded() {
        Plugin plugin = plugin();
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = preparedJedis(redisManager);
        doThrow(new IllegalStateException("script rejected the request"))
                .when(jedis).evalsha(eq("add-sha"), eq(3), any(String[].class));
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);

        assertEquals(new BigDecimal("-1"), cacheManager.atomicAddBalance(UUID.randomUUID(), BigDecimal.ONE));
        verify(redisManager, never()).setDegraded(true);
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerDegradedStateTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        return plugin;
    }

    private static Jedis preparedJedis(RedisManager redisManager) {
        Jedis jedis = mock(Jedis.class);
        when(jedis.scriptLoad(anyString())).thenReturn("set-sha", "add-sha", "transfer-sha");
        when(redisManager.getResource()).thenReturn(jedis);
        return jedis;
    }
}
