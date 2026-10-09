package noietime.syncmoney.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

class EconomyFacadeApiQueryTest {

    private EconomyFacade facade;

    @AfterEach
    void closeFacade() {
        if (facade != null) {
            facade.shutdown();
        }
    }

    @Test
    void cachedBalanceMissDoesNotReadStorageAndWarmStateIsReturned() throws Exception {
        CacheManager cache = mock(CacheManager.class);
        DatabaseManager database = mock(DatabaseManager.class);
        facade = newFacade(null, cache, mock(RedisManager.class), database,
                mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        UUID cold = UUID.randomUUID();

        assertTrue(facade.getCachedBalanceForApi(cold).isEmpty());
        verify(cache, never()).getBalanceSnapshotStrict(cold);
        verify(database, never()).getPlayerStrict(cold);

        UUID warm = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("12.3400");
        facade.updateMemoryState(warm, balance, 3L);
        assertEquals(Optional.of(balance), facade.getCachedBalanceForApi(warm));
    }

    @Test
    void confirmedLocalAbsenceReturnsEmptyWithoutCreatingOrPersistingZero() throws Exception {
        LocalEconomyHandler local = mock(LocalEconomyHandler.class);
        when(local.isRecoveryReady()).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        when(local.findAccountSnapshot(uuid)).thenReturn(Optional.empty());
        facade = newFacade(local, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        Optional<BigDecimal> result = facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.isEmpty());
        assertFalse(facade.hasInMemory(uuid), "an absent query must not seed a zero-valued account");
        verify(local).findAccountSnapshot(uuid);
        verify(local, never()).setBalance(any(UUID.class), anyString(), any(BigDecimal.class), anyString());
    }

    @Test
    void placeholderSyntheticZeroDoesNotMasqueradeAsApiBalance() throws Exception {
        LocalEconomyHandler local = mock(LocalEconomyHandler.class);
        when(local.isRecoveryReady()).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        CountDownLatch lookupStarted = new CountDownLatch(1);
        when(local.findAccountSnapshot(uuid)).thenAnswer(invocation -> {
            lookupStarted.countDown();
            return Optional.empty();
        });
        facade = newFacade(local, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        assertEquals(null, facade.getBalanceForPlaceholder(uuid));
        assertTrue(lookupStarted.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!facade.hasInMemory(uuid) && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(facade.hasInMemory(uuid), "placeholder loader should reproduce the legacy synthetic-zero cache");

        assertTrue(facade.getCachedBalanceForApi(uuid).isEmpty(),
                "an unverified legacy zero must be a cached miss for the read API");
        assertTrue(facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS).isEmpty(),
                "a strict absence read must remain empty after a legacy placeholder warmed the account");
        verify(local, org.mockito.Mockito.times(2)).findAccountSnapshot(uuid);
    }

    @Test
    void legacyUnavailableBackendZeroIsNotReturnedAsConfirmedApiBalance() throws Exception {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDbEnabled()).thenReturn(false);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        facade = newFacade(null, mock(CacheManager.class), redis, mock(DatabaseManager.class), config);

        assertEquals(BigDecimal.ZERO, facade.getBalance(uuid), "legacy getter falls back to zero during outage");
        assertTrue(facade.getCachedBalanceForApi(uuid).isEmpty(),
                "the legacy fallback value is not an authority-confirmed API snapshot");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalStateException,
                "backend failure must remain exceptional after a legacy zero fallback");
    }

    @Test
    void confirmedZeroSnapshotBecomesAvailableInApiCache() throws Exception {
        LocalEconomyHandler local = mock(LocalEconomyHandler.class);
        when(local.isRecoveryReady()).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        when(local.findAccountSnapshot(uuid))
                .thenReturn(Optional.of(new LocalEconomyHandler.AccountSnapshot(BigDecimal.ZERO, 0L)));
        facade = newFacade(local, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        assertEquals(Optional.of(BigDecimal.ZERO),
                facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(Optional.of(BigDecimal.ZERO), facade.getCachedBalanceForApi(uuid),
                "a strict source read confirms a real zero/version-zero account for later cache reads");
    }

    @Test
    void legacySharedZeroFallbackDoesNotMasqueradeAsAnApiAccount() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyFacadeApiQueryTest"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> new java.io.ByteArrayInputStream(new byte[0]));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenAnswer(invocation -> java.util.Arrays.asList(null, null));
        CacheManager cache = new CacheManager(plugin, redis, false);
        DatabaseManager database = mock(DatabaseManager.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDbEnabled()).thenReturn(false);
        facade = newFacade(null, cache, redis, database, config);

        assertEquals(BigDecimal.ZERO, facade.getBalance(uuid), "the legacy getter keeps its zero fallback");
        assertTrue(facade.getCachedBalanceForApi(uuid).isEmpty(),
                "an unverified shared fallback is not a confirmed cached API balance");
        assertTrue(facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS).isEmpty(),
                "a strict query must recheck Redis and retain confirmed absence");
        verify(jedis, org.mockito.Mockito.times(2))
                .mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid);
    }

    @Test
    void confirmedApiReadReplacesAPlaceholderSyntheticZero() throws Exception {
        LocalEconomyHandler local = mock(LocalEconomyHandler.class);
        when(local.isRecoveryReady()).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("84.25");
        CountDownLatch lookupStarted = new CountDownLatch(1);
        when(local.findAccountSnapshot(uuid)).thenAnswer(invocation -> {
            if (lookupStarted.getCount() > 0) {
                lookupStarted.countDown();
                return Optional.empty();
            }
            return Optional.of(new LocalEconomyHandler.AccountSnapshot(balance, 6L));
        });
        facade = newFacade(local, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        assertEquals(null, facade.getBalanceForPlaceholder(uuid));
        assertTrue(lookupStarted.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!facade.hasInMemory(uuid) && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(facade.hasInMemory(uuid));

        assertEquals(Optional.of(balance),
                facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(Optional.of(balance), facade.getCachedBalanceForApi(uuid),
                "confirmed storage data must replace the legacy placeholder's unverified zero");
    }

    @Test
    void coldLocalBalanceLoadsOnTheOwnedWorkerAndWarmsExistingState() throws Exception {
        LocalEconomyHandler local = mock(LocalEconomyHandler.class);
        when(local.isRecoveryReady()).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("735.25");
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        when(local.findAccountSnapshot(uuid)).thenAnswer(invocation -> {
            readerThread.set(Thread.currentThread());
            return Optional.of(new LocalEconomyHandler.AccountSnapshot(balance, 4L));
        });
        facade = newFacade(local, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        Thread caller = Thread.currentThread();

        Optional<BigDecimal> result = facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(Optional.of(balance), result);
        assertTrue(readerThread.get() != caller, "storage reads must run on the owned account loader");
        assertEquals(Optional.of(balance), facade.getCachedBalanceForApi(uuid));
    }

    @Test
    void unavailableSharedBackendCompletesExceptionallyInsteadOfReturningEmpty() throws Exception {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDbEnabled()).thenReturn(false);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(true);
        CacheManager cache = mock(CacheManager.class);
        UUID uuid = UUID.randomUUID();
        facade = newFacade(null, cache, redis, mock(DatabaseManager.class), config);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> facade.loadBalanceForApi(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertFalse(facade.hasInMemory(uuid));
        verify(cache, never()).getBalanceSnapshotStrict(uuid);
    }

    @Test
    void invalidUuidIsRejectedAndShutdownRejectsNewLoads() throws Exception {
        LocalEconomyHandler local = mock(LocalEconomyHandler.class);
        when(local.isRecoveryReady()).thenReturn(true);
        facade = newFacade(local, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        ExecutionException invalid = assertThrows(ExecutionException.class,
                () -> facade.loadBalanceForApi(null).toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(invalid.getCause() instanceof IllegalArgumentException);
        assertThrows(IllegalArgumentException.class, () -> facade.getCachedBalanceForApi(null));

        facade.shutdown();
        ExecutionException afterShutdown = assertThrows(ExecutionException.class,
                () -> facade.loadBalanceForApi(UUID.randomUUID()).toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(afterShutdown.getCause() instanceof IllegalStateException);
    }

    private static EconomyFacade newFacade(
            LocalEconomyHandler local,
            CacheManager cache,
            RedisManager redis,
            DatabaseManager database,
            SyncmoneyConfig config) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyFacadeApiQueryTest"));
        return new EconomyFacade(plugin, config, cache, redis, database, local,
                new EconomyWriteQueue(8), mock(FallbackEconomyWrapper.class), null,
                mock(OverflowLogInterface.class));
    }
}
