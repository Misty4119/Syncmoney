package noietime.syncmoney.command;

import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import noietime.syncmoney.sync.RedisProcessTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayLuaScriptManagerNoscriptIT {
    private static RedisProcessTestSupport redis;
    private static PayLuaScriptManager scriptManager;

    @BeforeAll
    static void startRedisAndScriptManager() {
        try {
            redis = RedisProcessTestSupport.start();
            Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("PayLuaScriptManagerNoscriptIT"));
            when(plugin.getResource(anyString())).thenAnswer(invocation -> {
                String path = invocation.getArgument(0);
                return PayLuaScriptManager.class.getClassLoader().getResourceAsStream(path);
            });
            RedisManager redisManager = mock(RedisManager.class);
            when(redisManager.getResource()).thenAnswer(invocation -> redis.connect());
            scriptManager = new PayLuaScriptManager(plugin, redisManager);
            scriptManager.load();
        } catch (Exception e) {
            throw new RuntimeException("Could not start isolated Redis for pay script manager", e);
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @BeforeEach
    void flushScripts() {
        try (Jedis jedis = redis.connect()) {
            jedis.flushDB();
            jedis.scriptFlush();
        }
    }

    @Test
    void payTransferReloadsScriptAfterNoscriptAndReturnsBothAccounts() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        try (Jedis jedis = redis.connect()) {
            jedis.set("syncmoney:balance:" + sender, "100.00");
            jedis.set("syncmoney:version:" + sender, "7");
            jedis.set("syncmoney:balance:" + target, "0.00");
            jedis.set("syncmoney:version:" + target, "2");
        }

        Object result = scriptManager.evalTransfer(sender, target, new BigDecimal("10.00"));

        List<?> receipt = assertInstanceOf(List.class, result);
        assertEquals(List.of("90.00", "10.00", "8", "3"), receipt);
        try (Jedis jedis = redis.connect()) {
            assertEquals(0, new BigDecimal("90.00").compareTo(
                    new BigDecimal(jedis.get("syncmoney:balance:" + sender))));
            assertEquals(0, new BigDecimal("10.00").compareTo(
                    new BigDecimal(jedis.get("syncmoney:balance:" + target))));
            assertEquals("8", jedis.get("syncmoney:version:" + sender));
            assertEquals("3", jedis.get("syncmoney:version:" + target));
        }
    }

    @Test
    void insufficientFundsIsClassifiedAsRejectedInsteadOfUnknown() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        try (Jedis jedis = redis.connect()) {
            jedis.set("syncmoney:balance:" + sender, "5.00");
            jedis.set("syncmoney:version:" + sender, "7");
            jedis.set("syncmoney:balance:" + target, "0.00");
            jedis.set("syncmoney:version:" + target, "2");
        }

        RedisMutationException rejection = assertThrows(RedisMutationException.class,
                () -> scriptManager.evalTransfer(sender, target, new BigDecimal("10.00")));

        assertEquals("REJECTED", rejection.executionState().name());
        assertTrue(rejection.getMessage().contains("INSUFFICIENT_FUNDS"),
                "known rejection details must remain available to /pay's insufficient-funds response");
        try (Jedis jedis = redis.connect()) {
            assertEquals("5.00", jedis.get("syncmoney:balance:" + sender));
            assertEquals("0.00", jedis.get("syncmoney:balance:" + target));
            assertEquals("7", jedis.get("syncmoney:version:" + sender));
            assertEquals("2", jedis.get("syncmoney:version:" + target));
        }
    }

    @Test
    void payTransferLoadsScriptLazilyAfterStartupRedisRecovery() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        try (Jedis jedis = redis.connect()) {
            jedis.set("syncmoney:balance:" + sender, "100.00");
            jedis.set("syncmoney:version:" + sender, "7");
            jedis.set("syncmoney:balance:" + target, "0.00");
            jedis.set("syncmoney:version:" + target, "2");
        }

        AtomicBoolean degraded = new AtomicBoolean(true);
        RedisManager recoveringRedisManager = mock(RedisManager.class);
        when(recoveringRedisManager.isDegraded()).thenAnswer(invocation -> degraded.get());
        when(recoveringRedisManager.getResource()).thenAnswer(invocation -> redis.connect());
        PayLuaScriptManager recoveringManager = new PayLuaScriptManager(plugin(), recoveringRedisManager);

        recoveringManager.load();
        assertNull(recoveringManager.getTransferScriptSha(),
                "startup while Redis is degraded should keep the source without attempting SCRIPT LOAD");

        degraded.set(false);
        List<?> receipt = assertInstanceOf(List.class,
                recoveringManager.evalTransfer(sender, target, new BigDecimal("10.00")));

        assertEquals(List.of("90.00", "10.00", "8", "3"), receipt);
        assertTrue(recoveringManager.getTransferScriptSha() != null,
                "the first request after Redis recovers should load and retain the script SHA");
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PayLuaScriptManagerNoscriptIT-recovery"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return PayLuaScriptManager.class.getClassLoader().getResourceAsStream(path);
        });
        return plugin;
    }
}
