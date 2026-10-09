package noietime.syncmoney.sync;
import noietime.syncmoney.testkit.RedisTestSupport;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheManagerScriptReloadIT {
    private static DisposableRedisServer redis;
    private static CacheManager cacheManager;

    @BeforeAll
    static void startRedisAndCacheManager() {
        try {
        redis = DisposableRedisServer.start();
            Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerScriptReloadIT"));
            when(plugin.getResource(anyString())).thenAnswer(invocation -> {
                String path = invocation.getArgument(0);
                return CacheManager.class.getClassLoader().getResourceAsStream(path);
            });
            RedisManager redisManager = mock(RedisManager.class);
            when(redisManager.getResource()).thenAnswer(invocation -> RedisTestSupport.connect(redis));
            cacheManager = new CacheManager(plugin, redisManager, true);
        } catch (Exception e) {
            throw new RuntimeException("Could not start isolated Redis for CacheManager", e);
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @BeforeEach
    void flushScripts() {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.flushDB();
            jedis.scriptFlush();
        }
    }

    @Test
    void addReloadsAfterNoscriptAndReturnsTheConfirmedPair() {
        UUID player = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.set("syncmoney:balance:" + player, "100.00");
            jedis.set("syncmoney:version:" + player, "7");
        }

        ConfirmedAccount receipt = cacheManager.atomicAddBalanceReceipt(player, new BigDecimal("-10.00"));

        assertNotNull(receipt, "NOSCRIPT must reload the canonical add script and retry once");
        assertEquals(new BigDecimal("90.00"), receipt.balance());
        assertEquals(8L, receipt.version());
    }

    @Test
    void setReloadsAfterNoscriptAndReturnsTheConfirmedPair() {
        UUID player = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.set("syncmoney:balance:" + player, "100.00");
            jedis.set("syncmoney:version:" + player, "7");
        }

        ConfirmedAccount receipt = cacheManager.atomicSetBalanceReceipt(player, new BigDecimal("20.00"));

        assertNotNull(receipt, "NOSCRIPT must reload the canonical set script and retry once");
        assertEquals(new BigDecimal("20.00"), receipt.balance());
        assertEquals(8L, receipt.version());
    }

    @Test
    void transferReloadsAfterNoscriptAndReturnsBothConfirmedAccounts() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.set("syncmoney:balance:" + sender, "100.00");
            jedis.set("syncmoney:version:" + sender, "7");
            jedis.set("syncmoney:balance:" + receiver, "0.00");
            jedis.set("syncmoney:version:" + receiver, "2");
        }

        CacheManager.TransferResult receipt = cacheManager.atomicTransfer(sender, receiver,
                new BigDecimal("10.00"));

        assertNotNull(receipt, "NOSCRIPT must reload the canonical transfer script and retry once");
        assertEquals(new BigDecimal("90.00"), receipt.fromNewBalance);
        assertEquals(new BigDecimal("10.00"), receipt.toNewBalance);
        assertEquals(8L, receipt.fromNewVersion);
        assertEquals(3L, receipt.toNewVersion);
    }
}
