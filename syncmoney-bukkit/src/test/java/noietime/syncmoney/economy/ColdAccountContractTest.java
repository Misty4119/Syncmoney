package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ColdAccountContractTest {

    private EconomyFacade facade;
    private LocalEconomyHandler localHandler;

    @TempDir
    Path tempDir;

    @AfterEach
    void closeFacade() {
        if (facade != null) {
            facade.shutdown();
        }
        if (localHandler != null) {
            localHandler.close();
        }
    }

    @Test
    void coldPlaceholderReadsScheduleOneOffThreadLoadAndKeepNewerMemoryState() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.isRecoveryReady()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        CountDownLatch repositoryEntered = new CountDownLatch(1);
        CountDownLatch releaseRepository = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        UUID uuid = UUID.randomUUID();

        when(localHandler.findAccountSnapshot(uuid)).thenAnswer(invocation -> {
            reads.incrementAndGet();
            repositoryEntered.countDown();
            assertTrue(releaseRepository.await(2, TimeUnit.SECONDS));
            return Optional.of(new LocalEconomyHandler.AccountSnapshot(new BigDecimal("100.00"), 1L));
        });

        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));

        long start = System.nanoTime();
        assertNull(facade.getBalanceForPlaceholder(uuid));
        long callerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(callerMillis < 500, "cold placeholder call must not wait for SQLite");
        assertTrue(repositoryEntered.await(1, TimeUnit.SECONDS));
        assertNull(facade.getBalanceForPlaceholder(uuid), "a duplicate miss should use the pending load");
        assertFalse(facade.hasInMemory(uuid));

        assertTrue(facade.updateMemoryState(uuid, new BigDecimal("200.00"), 5L));
        releaseRepository.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!facade.hasInMemory(uuid) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(new BigDecimal("200.00"), facade.getMemoryState(uuid).balance(),
                "the stale cold-load result must not replace a newer memory update");
        assertEquals(1, reads.get(), "same-account cold loads must be deduplicated");
        assertEquals(1, reads.get());
    }

    @Test
    void failedLocalColdReadDoesNotCacheZeroAsAnAccountBalance() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        localHandler = new LocalEconomyHandler(plugin, tempDir.resolve("closed-local.db").toString());
        localHandler.close();

        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, new EconomyWriteQueue(8),
                mock(FallbackEconomyWrapper.class), null, mock(OverflowLogInterface.class));
        UUID uuid = UUID.randomUUID();

        assertNull(facade.getBalanceForPlaceholder(uuid), "cold placeholder reads stay unavailable until loading completes");
        facade.shutdown();

        assertFalse(facade.hasInMemory(uuid), "a failed SQLite read must not be cached as a confirmed zero balance");
    }

    @Test
    void unresolvedLocalRecoveryNeverServesCachedOrRepositoryBalance() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        LocalEconomyHandler repository = mock(LocalEconomyHandler.class);
        when(repository.isRecoveryReady()).thenReturn(false);
        UUID cachedUuid = UUID.randomUUID();
        UUID coldUuid = UUID.randomUUID();
        when(repository.findAccountSnapshot(coldUuid)).thenReturn(Optional.of(
                new LocalEconomyHandler.AccountSnapshot(new BigDecimal("735.25"), 4L)));
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), repository, new EconomyWriteQueue(8),
                mock(FallbackEconomyWrapper.class), null, mock(OverflowLogInterface.class));
        facade.updateMemoryState(cachedUuid, new BigDecimal("120.00"), 7L);

        assertNull(facade.getBalanceForPlaceholder(cachedUuid),
                "LOCAL state must stay unavailable while startup recovery is unresolved");
        assertNull(facade.getBalanceForPlaceholder(coldUuid),
                "cold LOCAL state must not expose a pre-replay repository snapshot");
        assertThrows(IllegalStateException.class, () -> facade.getBalance(coldUuid),
                "the synchronous internal getter must fail explicitly instead of seeding stale state");
        assertFalse(facade.hasInMemory(coldUuid));
        verify(repository, never()).findAccountSnapshot(coldUuid);
    }

    @Test
    void failedSharedColdReadDoesNotCacheZeroAsAnAccountBalance() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        CountDownLatch redisRead = new CountDownLatch(1);
        when(redis.getResource()).thenAnswer(invocation -> {
            redisRead.countDown();
            throw new IllegalStateException("injected Redis read failure");
        });
        CacheManager cache = new CacheManager(plugin, redis, false);
        facade = new EconomyFacade(plugin, config, cache, redis, mock(DatabaseManager.class), null,
                new EconomyWriteQueue(8), mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));
        UUID uuid = UUID.randomUUID();

        assertNull(facade.getBalanceForPlaceholder(uuid));
        assertTrue(redisRead.await(1, TimeUnit.SECONDS));
        facade.shutdown();

        assertFalse(facade.hasInMemory(uuid),
                "a Redis failure must not be cached as a confirmed zero balance when SQL fallback is disabled");
    }

    @Test
    void sharedColdReadFallsBackToSqlWhenRedisHasNoAccountKeys() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDbEnabled()).thenReturn(true);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        DatabaseManager database = mock(DatabaseManager.class);
        UUID uuid = UUID.randomUUID();
        when(jedis.mget("syncmoney:balance:" + uuid, "syncmoney:version:" + uuid))
                .thenReturn(Arrays.asList(null, null));
        when(database.getPlayerStrict(uuid)).thenReturn(Optional.of(new DatabaseManager.PlayerRecord(uuid,
                "offline", new BigDecimal("735.25"), 17L, "server-a", Instant.now())));
        CacheManager cache = new CacheManager(plugin, redis, false);
        facade = new EconomyFacade(plugin, config, cache, redis, database, null,
                new EconomyWriteQueue(8), mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));

        assertNull(facade.getBalanceForPlaceholder(uuid));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!facade.hasInMemory(uuid) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertTrue(facade.hasInMemory(uuid), "the confirmed SQL account should complete the cold load");
        assertEquals(new BigDecimal("735.25"), facade.getMemoryState(uuid).balance());
        assertEquals(17L, facade.getMemoryState(uuid).version());
        verify(database).getPlayerStrict(uuid);
    }

    @Test
    void localAsyncLoadPreservesPersistedVersionAndRejectsBeforeIntegerOverflow() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        Path database = tempDir.resolve("near-overflow-local.db");
        localHandler = new LocalEconomyHandler(plugin, database.toString());
        UUID uuid = UUID.randomUUID();
        localHandler.setBalance(uuid, "near-overflow", new BigDecimal("100.00"), "ADMIN_SET");
        localHandler.close();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement("UPDATE player_balances SET version = ? WHERE uuid = ?")) {
            statement.setInt(1, Integer.MAX_VALUE - 1);
            statement.setString(2, uuid.toString());
            assertEquals(1, statement.executeUpdate());
        }
        localHandler = new LocalEconomyHandler(plugin, database.toString());

        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));

        assertNull(facade.getBalanceForPlaceholder(uuid));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!facade.hasInMemory(uuid) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(facade.hasInMemory(uuid));
        assertEquals(Integer.MAX_VALUE - 1L, facade.getMemoryState(uuid).version(),
                "the cold LOCAL snapshot must seed memory with the persisted SQLite version");

        assertEquals(new BigDecimal("-1"), facade.deposit(uuid, BigDecimal.ONE,
                EconomyEvent.EventSource.VAULT_DEPOSIT));
        assertEquals(new BigDecimal("100.00"), facade.getMemoryState(uuid).balance());
        assertEquals(0, queue.size(), "version overflow must be rejected before queue admission");
    }

    @Test
    void localMutationAndReadFailClosedWhenLocalRecoveryIsUnavailable() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        localHandler = new LocalEconomyHandler(plugin, tempDir.resolve("unavailable-local.db").toString());
        localHandler.close();
        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));
        UUID coldReadUuid = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> facade.getBalance(coldReadUuid),
                "a synchronous read must not report a false zero while LOCAL recovery is unresolved");
        UUID depositUuid = UUID.randomUUID();
        UUID withdrawUuid = UUID.randomUUID();
        UUID setUuid = UUID.randomUUID();
        BigDecimal deposit = facade.deposit(depositUuid, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);
        BigDecimal withdraw = facade.withdraw(withdrawUuid, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);
        BigDecimal set = facade.setBalance(setUuid, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);

        assertAll(
                () -> assertFalse(facade.hasInMemory(coldReadUuid), "a failed read must not cache a zero balance"),
                () -> assertEquals(new BigDecimal("-1"), deposit,
                        "deposit must reject instead of accepting against an unavailable account"),
                () -> assertEquals(new BigDecimal("-1"), withdraw,
                        "withdraw must reject instead of using an unavailable account"),
                () -> assertEquals(new BigDecimal("-1"), set,
                        "set must reject instead of replacing an unavailable account"),
                () -> assertFalse(facade.hasInMemory(depositUuid)),
                () -> assertFalse(facade.hasInMemory(withdrawUuid)),
                () -> assertFalse(facade.hasInMemory(setUuid)),
                () -> assertEquals(0, queue.size(), "a rejected mutation must not enqueue persistence")
        );
    }

    @Test
    void coldTransferRejectsBeforeDebitingSenderWhenEitherAccountIsNotReady() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        LocalEconomyHandler repository = mock(LocalEconomyHandler.class);
        when(repository.isRecoveryReady()).thenReturn(true);
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        CountDownLatch repositoryEntered = new CountDownLatch(1);
        CountDownLatch releaseRepository = new CountDownLatch(1);
        when(repository.findAccountSnapshot(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            UUID requested = invocation.getArgument(0);
            repositoryEntered.countDown();
            assertTrue(releaseRepository.await(10, TimeUnit.SECONDS));
            BigDecimal balance = requested.equals(sender) ? new BigDecimal("100.00") : new BigDecimal("40.00");
            return Optional.of(new LocalEconomyHandler.AccountSnapshot(balance, 1L));
        });

        EconomyWriteQueue queue = new EconomyWriteQueue(8);
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), repository, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var transfer = caller.submit(() -> facade.atomicTransfer(sender, receiver,
                    new BigDecimal("10.00"), EconomyEvent.EventSource.TEST));
            assertTrue(repositoryEntered.await(2, TimeUnit.SECONDS), "cold transfer should schedule account loading");
            BigDecimal result = null;
            boolean returnedBeforeRepositoryRelease;
            try {
                result = transfer.get(500, TimeUnit.MILLISECONDS);
                returnedBeforeRepositoryRelease = true;
            } catch (TimeoutException expectedWhileCharacterizingOldBehavior) {
                returnedBeforeRepositoryRelease = false;
            }

            releaseRepository.countDown();
            if (!returnedBeforeRepositoryRelease) {
                transfer.get(2, TimeUnit.SECONDS);
            }
            assertTrue(returnedBeforeRepositoryRelease,
                    "a cold transfer must reject without waiting for the repository");
            assertEquals(new BigDecimal("-1"), result, "cold transfer returns the existing failure sentinel");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!facade.hasInMemory(sender) || !facade.hasInMemory(receiver))
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(new BigDecimal("100.00"), facade.getBalance(sender),
                    "cold receiver state must never cause the sender to be debited first");
            assertEquals(new BigDecimal("40.00"), facade.getBalance(receiver));
            assertEquals(0, queue.size(), "a cold transfer must not enqueue either leg");
        } finally {
            releaseRepository.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void existingOfflineLocalBalanceWarmsFromSqliteWithoutWritingZero() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ColdAccountContractTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        localHandler = new LocalEconomyHandler(plugin, tempDir.resolve("existing-local.db").toString());
        UUID uuid = UUID.randomUUID();
        localHandler.setBalance(uuid, "offline", new BigDecimal("100.00"), "ADMIN_SET");
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, new EconomyWriteQueue(8),
                mock(FallbackEconomyWrapper.class), null, mock(OverflowLogInterface.class));

        assertNull(facade.getBalanceForPlaceholder(uuid), "the cold lookup is scheduled off the caller thread");
        facade.shutdown();

        assertEquals(new BigDecimal("100.00"), facade.getBalanceForPlaceholder(uuid));
        assertEquals(new BigDecimal("100.00"), localHandler.findBalance(uuid).orElseThrow());
    }
}
