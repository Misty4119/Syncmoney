package noietime.syncmoney.sync;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.testkit.RedisTestSupport;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CMIRedisMirrorVersionOrderingIT {
    private static DisposableRedisServer redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = DisposableRedisServer.start();
    }

    @BeforeEach
    void clearRedis() {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.flushDB();
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @Test
    void adjacentVersionsAboveDoublePrecisionAdvanceButOlderMessagesCannotRegressTheMirror() {
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000041");
        String prefix = "syncmoney:cmi";
        RedisManager redisManager = redisManager();
        CMIRedisMirror mirror = new CMIRedisMirror(redisManager, prefix);

        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED,
                mirror.compareAndWrite(account, new BigDecimal("100.00"), 17_000_000_000_000_000L));
        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED,
                mirror.compareAndWrite(account, new BigDecimal("200.00"), 17_000_000_000_000_001L));
        assertEquals(CMIRedisMirror.WriteResult.REJECTED,
                mirror.compareAndWrite(account, new BigDecimal("50.00"), 17_000_000_000_000_000L));

        Optional<CMIRedisMirror.Snapshot> snapshot = mirror.read(account);
        assertTrue(snapshot.isPresent());
        assertEquals(new BigDecimal("200.00"), snapshot.orElseThrow().balance());
        assertEquals(17_000_000_000_000_001L, snapshot.orElseThrow().version());
    }

    @Test
    void equalVersionWithDifferentBalanceAndPartialPairsFailClosed() {
        UUID equalVersionAccount = UUID.fromString("00000000-0000-0000-0000-000000000042");
        UUID partialAccount = UUID.fromString("00000000-0000-0000-0000-000000000043");
        String prefix = "syncmoney:cmi";
        RedisManager redisManager = redisManager();
        CMIRedisMirror mirror = new CMIRedisMirror(redisManager, prefix);

        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED,
                mirror.compareAndWrite(equalVersionAccount, new BigDecimal("100.00"), 101L));
        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED,
                mirror.compareAndWrite(equalVersionAccount, new BigDecimal("100.0"), 101L),
                "same-version replay with an equivalent decimal value is idempotent");
        assertEquals(CMIRedisMirror.WriteResult.REJECTED,
                mirror.compareAndWrite(equalVersionAccount, new BigDecimal("200.00"), 101L));
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.set(RedisKeys.cmiBalance(prefix, partialAccount), "900.00");
        }
        assertEquals(CMIRedisMirror.WriteResult.REJECTED,
                mirror.compareAndWrite(partialAccount, new BigDecimal("10.00"), 1L));

        Optional<CMIRedisMirror.Snapshot> equalVersion = mirror.read(equalVersionAccount);
        Optional<CMIRedisMirror.Snapshot> partial = mirror.read(partialAccount);
        assertTrue(equalVersion.isPresent());
        assertEquals(new BigDecimal("100.00"), equalVersion.orElseThrow().balance());
        assertEquals(101L, equalVersion.orElseThrow().version());
        assertTrue(partial.isEmpty());
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            assertEquals("900.00", jedis.get(RedisKeys.cmiBalance(prefix, partialAccount)));
            assertEquals(null, jedis.get(RedisKeys.cmiVersion(prefix, partialAccount)));
        }
    }

    @Test
    void malformedExistingVersionFailsClosed() {
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000044");
        String prefix = "syncmoney:cmi";
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.mset(RedisKeys.cmiBalance(prefix, account), "100.00",
                    RedisKeys.cmiVersion(prefix, account), "1700000000000000x");
        }

        CMIRedisMirror mirror = new CMIRedisMirror(redisManager(), prefix);
        assertEquals(CMIRedisMirror.WriteResult.REJECTED,
                mirror.compareAndWrite(account, new BigDecimal("200.00"), 17_000_000_000_000_001L));
        assertFalse(mirror.read(account).isPresent());
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            assertEquals("100.00", jedis.get(RedisKeys.cmiBalance(prefix, account)));
            assertEquals("1700000000000000x", jedis.get(RedisKeys.cmiVersion(prefix, account)));
        }
    }

    private RedisManager redisManager() {
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenAnswer(ignored -> RedisTestSupport.connect(redis));
        return redisManager;
    }
}
