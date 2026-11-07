package noietime.syncmoney.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import noietime.syncmoney.protocol.RedisOperationFingerprint;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class CacheManagerTransferExecutionTest {

    @Test
    void pairedTransferDisabledForCmiModeDoesNotReachRedis() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        RedisManager redisManager = mock(RedisManager.class);
        CacheManager cacheManager = new CacheManager(plugin, redisManager, false, false);

        CacheManager.TransferResult result = cacheManager.atomicTransfer(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));

        org.junit.jupiter.api.Assertions.assertNull(result);
        org.mockito.Mockito.verify(redisManager, org.mockito.Mockito.never()).getResource();
    }

    @Test
    void publicThreeArgumentConstructionDisablesPairedTransfersByDefault() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        RedisManager redisManager = mock(RedisManager.class);
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);

        CacheManager.TransferResult result = cacheManager.atomicTransfer(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));

        org.junit.jupiter.api.Assertions.assertNull(result);
        org.mockito.Mockito.verify(redisManager, org.mockito.Mockito.never()).getResource();
    }

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
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true, true);

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
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true, true);
        cacheManager.updateMemoryCache(account, new BigDecimal("100.00"), 7L);

        CacheManager.CachedBalance snapshot = cacheManager.readAuthorityBalanceSnapshotStrict(account).orElseThrow();

        assertEquals(new BigDecimal("20.00"), snapshot.balance());
        assertEquals(8L, snapshot.version());
    }

    @Test
    void delayedTransferReceiptCannotRegressNewerPubSubSnapshots() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("script-sha");
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true, true);
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        String fingerprint = RedisOperationFingerprint.transfer(from, to, new BigDecimal("10.00"));
        Object delayedReceipt = new Object() {
            @Override
            public String toString() {
                cacheManager.updateMemoryCache(from, new BigDecimal("95.00"), 6L);
                cacheManager.updateMemoryCache(to, new BigDecimal("55.00"), 6L);
                return "v1|" + fingerprint + "|APPLIED|-|2|90.00|5|60.00|5";
            }
        };
        when(jedis.evalsha(anyString(), eq(5), any(String[].class))).thenReturn(delayedReceipt);

        CacheManager.TransferResult receipt = cacheManager.atomicTransfer(from, to, new BigDecimal("10.00"));

        assertEquals(new BigDecimal("90.00"), receipt.fromNewBalance);
        assertEquals(new BigDecimal("60.00"), receipt.toNewBalance);
        CacheManager.CachedBalance fromCached = cacheManager.getCachedBalanceSnapshot(from).orElseThrow();
        CacheManager.CachedBalance toCached = cacheManager.getCachedBalanceSnapshot(to).orElseThrow();
        assertEquals(new BigDecimal("95.00"), fromCached.balance());
        assertEquals(6L, fromCached.version());
        assertEquals(new BigDecimal("55.00"), toCached.balance());
        assertEquals(6L, toCached.version());
    }

    @Test
    void delayedRedisBalanceSnapshotCannotRegressNewerPubSubSnapshot() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("script-sha");
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true, true);
        UUID account = UUID.randomUUID();
        when(jedis.mget(eq("syncmoney:balance:" + account), eq("syncmoney:version:" + account)))
                .thenAnswer(invocation -> {
                    cacheManager.updateMemoryCache(account, new BigDecimal("80.00"), 9L);
                    return List.of("50.00", "8");
                });

        BigDecimal balance = cacheManager.getBalance(account);

        assertEquals(new BigDecimal("80.00"), balance);
        CacheManager.CachedBalance cached = cacheManager.getCachedBalanceSnapshot(account).orElseThrow();
        assertEquals(new BigDecimal("80.00"), cached.balance());
        assertEquals(9L, cached.version());
    }

    @Test
    void confirmedRedisAbsenceDoesNotReturnAnExpiredPositiveBalance() throws Exception {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("script-sha");
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true, true);
        UUID account = UUID.randomUUID();
        putExpiredCacheEntry(cacheManager, account, new BigDecimal("50.00"), 8L);
        when(jedis.mget(eq("syncmoney:balance:" + account), eq("syncmoney:version:" + account)))
                .thenReturn(java.util.Arrays.asList(null, null));

        BigDecimal balance = cacheManager.getBalance(account);

        assertEquals(BigDecimal.ZERO, balance);
        CacheManager.CachedBalance cached = cacheManager.getCachedBalanceSnapshot(account).orElseThrow();
        assertEquals(false, cached.accountPresent());
        assertEquals(BigDecimal.ZERO, cached.balance());
    }

    @Test
    void confirmedRedisAbsenceDoesNotClearAConcurrentNewerPubSubSnapshot() throws Exception {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerTransferExecutionTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("script-sha");
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true, true);
        UUID account = UUID.randomUUID();
        putExpiredCacheEntry(cacheManager, account, new BigDecimal("50.00"), 8L);
        when(jedis.mget(eq("syncmoney:balance:" + account), eq("syncmoney:version:" + account)))
                .thenAnswer(invocation -> {
                    cacheManager.updateMemoryCache(account, new BigDecimal("75.00"), 9L);
                    return java.util.Arrays.asList(null, null);
                });

        BigDecimal balance = cacheManager.getBalance(account);

        assertEquals(new BigDecimal("75.00"), balance);
        CacheManager.CachedBalance cached = cacheManager.getCachedBalanceSnapshot(account).orElseThrow();
        assertEquals(9L, cached.version());
    }

    private static void putExpiredCacheEntry(CacheManager cacheManager, UUID account,
            BigDecimal balance, long version) throws Exception {
        Field field = CacheManager.class.getDeclaredField("memoryCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CacheManager.CachedBalance> memoryCache =
                (Map<UUID, CacheManager.CachedBalance>) field.get(cacheManager);
        long expiredAt = System.currentTimeMillis() - (cacheManager.getExpirationMinutes() + 1L) * 60_000L;
        memoryCache.put(account, new CacheManager.CachedBalance(balance, version, expiredAt));
    }
}
