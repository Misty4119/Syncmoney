package noietime.syncmoney.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void strictSnapshotDoesNotPromoteLegacyCachedAbsenceToAZeroBalance() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenAnswer(invocation -> java.util.Arrays.asList(null, null));
        CacheManager cache = new CacheManager(plugin, redis, false);

        assertEquals(BigDecimal.ZERO, cache.getBalance(uuid), "the legacy read keeps its zero fallback");
        assertEquals(0L, cache.getVersion(uuid), "legacy version reads keep the zero fallback too");
        assertEquals(BigDecimal.ZERO, cache.getBalance(uuid), "legacy cache hits keep the zero fallback");
        assertTrue(cache.getBalanceSnapshotStrict(uuid).isEmpty(),
                "the strict API read must preserve the confirmed absence");

        org.mockito.Mockito.verify(jedis, org.mockito.Mockito.times(2))
                .mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid);
    }

    @Test
    void confirmedVersionZeroSnapshotReplacesLegacyAbsentCacheEntry() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenReturn(java.util.Arrays.asList(null, null), java.util.Arrays.asList("5.00", null));
        CacheManager cache = new CacheManager(plugin, redis, false);

        assertEquals(BigDecimal.ZERO, cache.getBalance(uuid));
        assertEquals(new BigDecimal("5.00"),
                cache.readAuthorityBalanceSnapshotStrict(uuid).orElseThrow().balance());
        assertEquals(new BigDecimal("5.00"), cache.getBalance(uuid),
                "a confirmed present account replaces a synthetic absent entry even at version zero");
    }

    @Test
    void confirmedVersionZeroCacheUpdateReplacesLegacyAbsentCacheEntry() {
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

        assertEquals(BigDecimal.ZERO, cache.getBalance(uuid));
        assertTrue(cache.updateMemoryCache(uuid, new BigDecimal("5.00"), 0L));
        assertEquals(new BigDecimal("5.00"), cache.getBalance(uuid),
                "a confirmed present cache update replaces absence even at the same synthetic version");
    }

    @Test
    void strictTransferSnapshotRejectsAnAbsentAccountInsteadOfReturningAConfirmedZero() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID sender = UUID.randomUUID();
        UUID absentReceiver = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + sender, "syncmoney:version:" + sender,
                "syncmoney:balance:" + absentReceiver, "syncmoney:version:" + absentReceiver))
                .thenReturn(java.util.Arrays.asList("90.00", "7", null, null));
        CacheManager cache = new CacheManager(plugin, redis, false);

        assertThrows(IllegalStateException.class,
                () -> cache.readTransferSnapshotStrict(sender, absentReceiver),
                "a transfer reconciliation pair requires confirmed presence for both accounts");
    }

    @Test
    void rejectedTransferSnapshotPreservesPerAccountAbsence() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID sender = UUID.randomUUID();
        UUID absentReceiver = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + sender, "syncmoney:version:" + sender,
                "syncmoney:balance:" + absentReceiver, "syncmoney:version:" + absentReceiver))
                .thenReturn(java.util.Arrays.asList("90.00", "7", null, null));
        CacheManager cache = new CacheManager(plugin, redis, false);

        CacheManager.TransferResult snapshot = cache.readRejectedTransferSnapshotStrict(sender, absentReceiver);

        assertTrue(snapshot.fromAccountPresent);
        assertEquals(new BigDecimal("90.00"), snapshot.fromNewBalance);
        assertEquals(7L, snapshot.fromNewVersion);
        org.junit.jupiter.api.Assertions.assertFalse(snapshot.toAccountPresent);
        org.junit.jupiter.api.Assertions.assertNull(snapshot.toNewBalance,
                "an absent account must not be represented by a zero-balance receipt");
        assertEquals(-1L, snapshot.toNewVersion);
    }

    @Test
    void confirmedAuthorityAbsenceClearsTheUnchangedCachedAccount() {
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
        cache.updateMemoryCache(uuid, new BigDecimal("90.00"), 7L);

        assertTrue(cache.readAuthorityBalanceSnapshotStrict(uuid).isEmpty());

        assertTrue(cache.getCachedBalanceSnapshot(uuid).isEmpty(),
                "a confirmed Redis absence must not leave an older positive cache entry visible");
    }

    @Test
    void confirmedAuthorityAbsenceDoesNotClearAConcurrentCacheRefresh() throws Exception {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenAnswer(invocation -> {
                    readEntered.countDown();
                    if (!finishRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to finish the Redis read");
                    }
                    return java.util.Arrays.asList(null, null);
                });
        CacheManager cache = new CacheManager(plugin, redis, false);
        cache.updateMemoryCache(uuid, new BigDecimal("90.00"), 7L);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<java.util.Optional<CacheManager.CachedBalance>> read =
                    executor.submit(() -> cache.readAuthorityBalanceSnapshotStrict(uuid));
            assertTrue(readEntered.await(5, TimeUnit.SECONDS), "the authority read should reach Redis");
            cache.updateMemoryCache(uuid, new BigDecimal("95.00"), 8L);
            finishRead.countDown();

            assertTrue(read.get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(new BigDecimal("95.00"), cache.getCachedBalanceSnapshot(uuid).orElseThrow().balance(),
                    "a cache refresh racing with an older absence response must remain visible");
        } finally {
            finishRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void strictTransferAbsenceClearsTheAbsentAccountsUnchangedCachedBalance() {
        Plugin plugin = mock(Plugin.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CacheManagerColdSnapshotTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID sender = UUID.randomUUID();
        UUID absentReceiver = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + sender, "syncmoney:version:" + sender,
                "syncmoney:balance:" + absentReceiver, "syncmoney:version:" + absentReceiver))
                .thenReturn(java.util.Arrays.asList("90.00", "7", null, null));
        CacheManager cache = new CacheManager(plugin, redis, false);
        cache.updateMemoryCache(absentReceiver, new BigDecimal("50.00"), 3L);

        assertThrows(IllegalStateException.class,
                () -> cache.readTransferSnapshotStrict(sender, absentReceiver));

        assertTrue(cache.getCachedBalanceSnapshot(absentReceiver).isEmpty(),
                "a confirmed absent transfer account must not retain its older cache entry");
    }
}
