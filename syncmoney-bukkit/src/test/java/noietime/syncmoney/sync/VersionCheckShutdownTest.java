package noietime.syncmoney.sync;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.OnlinePlayerRegistry;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VersionCheckShutdownTest {

    @Test
    @SuppressWarnings("unchecked")
    void crossServerVersionCheckDispatchesRedisWorkOffTheGlobalRegionScheduler() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VersionCheckShutdownTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        AtomicReference<Consumer<ScheduledTask>> globalWork = new AtomicReference<>();
        when(globalScheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    globalWork.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("local");
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CacheManager cacheManager = mock(CacheManager.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        CrossServerSyncManager manager = new CrossServerSyncManager(plugin, config,
                redisManager, economyFacade, cacheManager);

        manager.startPeriodicVersionCheck();
        globalWork.get().accept(mock(ScheduledTask.class));

        verify(asyncScheduler).runNow(eq(plugin), any());
        verify(redisManager, never()).getResource();
        verifyNoInteractions(jedis);
        manager.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncManagerWaitsForAndFencesAnInFlightVersionCheck() throws Exception {
        UUIDHolder account = new UUIDHolder();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VersionCheckShutdownTest"));
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        AtomicReference<Consumer<ScheduledTask>> scheduledWork = new AtomicReference<>();
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenAnswer(invocation -> {
                    scheduledWork.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(EconomyMode.SYNC);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        CacheManager cacheManager = mock(CacheManager.class);
        when(economyFacade.getOnlinePlayerUuids()).thenReturn(Set.of(account.uuid));
        when(economyFacade.getMemoryState(account.uuid))
                .thenReturn(new EconomyFacade.EconomyState(BigDecimal.TEN, 1, 0));
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        when(cacheManager.readAuthorityBalanceSnapshotStrict(account.uuid)).thenAnswer(invocation -> {
            readEntered.countDown();
            releaseRead.await();
            return Optional.of(new CacheManager.CachedBalance(new BigDecimal("20.00"), 2L));
        });

        SyncManager manager = new SyncManager(plugin, config, cacheManager,
                economyFacade, mock(RedisManager.class));
        manager.startPeriodicVersionCheck();
        Thread callback = new Thread(() -> scheduledWork.get().accept(mock(ScheduledTask.class)),
                "sync-version-check-test");
        callback.start();
        assertTrue(readEntered.await(5, TimeUnit.SECONDS), "version check did not reach Redis read");

        CountDownLatch shutdownStarted = new CountDownLatch(1);
        CountDownLatch shutdownFinished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            shutdownStarted.countDown();
            manager.shutdown();
            shutdownFinished.countDown();
        }, "sync-version-shutdown-test");
        shutdown.start();
        try {
            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS), "shutdown did not start");
            assertTrue(!shutdownFinished.await(100, TimeUnit.MILLISECONDS),
                    "shutdown must retain Redis until the active check exits");
        } finally {
            releaseRead.countDown();
            callback.join(TimeUnit.SECONDS.toMillis(5));
            shutdown.join(TimeUnit.SECONDS.toMillis(5));
        }

        verify(economyFacade, never()).updateMemoryState(account.uuid,
                new BigDecimal("20.00"), 2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossServerCheckDoesNotWriteRedisAfterShutdownStarts() throws Exception {
        UUIDHolder account = new UUIDHolder();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VersionCheckShutdownTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        OnlinePlayerRegistry onlinePlayerRegistry = mock(OnlinePlayerRegistry.class);
        when(plugin.getOnlinePlayerRegistry()).thenReturn(onlinePlayerRegistry);
        when(onlinePlayerRegistry.getLocalOnlinePlayerIds()).thenReturn(Set.of(account.uuid));
        AtomicReference<Consumer<ScheduledTask>> scheduledWork = new AtomicReference<>();
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    scheduledWork.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });
        AtomicReference<Consumer<ScheduledTask>> asyncWork = new AtomicReference<>();
        when(asyncScheduler.runNow(eq(plugin), any()))
                .thenAnswer(invocation -> {
                    asyncWork.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("local");
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.getMemoryState(account.uuid))
                .thenReturn(new EconomyFacade.EconomyState(new BigDecimal("20.00"), 2L, 0L));
        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        when(cacheManager.readAuthorityBalanceSnapshotStrict(account.uuid)).thenAnswer(invocation -> {
            readEntered.countDown();
            releaseRead.await();
            return Optional.of(new CacheManager.CachedBalance(new BigDecimal("10.00"), 1L));
        });

        CrossServerSyncManager manager = new CrossServerSyncManager(plugin, config,
                redisManager, economyFacade, cacheManager);
        manager.startPeriodicVersionCheck();
        scheduledWork.get().accept(mock(ScheduledTask.class));
        Thread callback = new Thread(() -> asyncWork.get().accept(mock(ScheduledTask.class)),
                "cross-server-version-check-test");
        callback.start();
        assertTrue(readEntered.await(5, TimeUnit.SECONDS), "cross-server check did not reach Redis read");

        CountDownLatch shutdownStarted = new CountDownLatch(1);
        CountDownLatch shutdownFinished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            shutdownStarted.countDown();
            manager.shutdown();
            shutdownFinished.countDown();
        }, "cross-server-version-shutdown-test");
        shutdown.start();
        try {
            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS), "shutdown did not start");
            assertTrue(!shutdownFinished.await(100, TimeUnit.MILLISECONDS),
                    "shutdown must wait before Redis can be closed");
        } finally {
            releaseRead.countDown();
            callback.join(TimeUnit.SECONDS.toMillis(5));
            shutdown.join(TimeUnit.SECONDS.toMillis(5));
        }

        verify(jedis, never()).set(anyString(), anyString());
        verify(economyFacade, never()).updateMemoryState(eq(account.uuid), any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossServerCheckWaitsForAndFencesAnInFlightAuthoritySnapshotRead() throws Exception {
        UUIDHolder account = new UUIDHolder();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VersionCheckShutdownTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        OnlinePlayerRegistry onlinePlayerRegistry = mock(OnlinePlayerRegistry.class);
        when(plugin.getOnlinePlayerRegistry()).thenReturn(onlinePlayerRegistry);
        when(onlinePlayerRegistry.getLocalOnlinePlayerIds()).thenReturn(Set.of(account.uuid));
        AtomicReference<Consumer<ScheduledTask>> scheduledWork = new AtomicReference<>();
        AtomicReference<Consumer<ScheduledTask>> asyncWork = new AtomicReference<>();
        ScheduledTask ownedTask = mock(ScheduledTask.class);
        CountDownLatch shutdownFencedTask = new CountDownLatch(1);
        doAnswer(invocation -> {
            shutdownFencedTask.countDown();
            return null;
        }).when(ownedTask).cancel();
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    scheduledWork.set(invocation.getArgument(1));
                    return ownedTask;
                });
        when(asyncScheduler.runNow(eq(plugin), any()))
                .thenAnswer(invocation -> {
                    asyncWork.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("local");
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.getMemoryState(account.uuid))
                .thenReturn(new EconomyFacade.EconomyState(BigDecimal.TEN, 1L, 0L));
        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CountDownLatch snapshotReadEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshotRead = new CountDownLatch(1);
        when(cacheManager.readAuthorityBalanceSnapshotStrict(account.uuid)).thenAnswer(invocation -> {
            snapshotReadEntered.countDown();
            releaseSnapshotRead.await();
            return Optional.of(new CacheManager.CachedBalance(new BigDecimal("20.00"), 2L));
        });

        CrossServerSyncManager manager = new CrossServerSyncManager(plugin, config,
                redisManager, economyFacade, cacheManager);
        manager.startPeriodicVersionCheck();
        scheduledWork.get().accept(ownedTask);
        Thread callback = new Thread(() -> asyncWork.get().accept(mock(ScheduledTask.class)),
                "cross-server-version-pair-test");
        callback.start();
        assertTrue(snapshotReadEntered.await(5, TimeUnit.SECONDS), "authority snapshot read did not start");

        CountDownLatch shutdownFinished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            manager.shutdown();
            shutdownFinished.countDown();
        }, "cross-server-version-pair-shutdown-test");
        shutdown.start();
        try {
            assertTrue(shutdownFencedTask.await(5, TimeUnit.SECONDS), "shutdown did not fence the task");
            assertTrue(shutdownFinished.getCount() == 1,
                    "shutdown must wait while the authority snapshot is in flight");
            releaseSnapshotRead.countDown();
            callback.join(TimeUnit.SECONDS.toMillis(5));
            shutdown.join(TimeUnit.SECONDS.toMillis(5));

            verify(economyFacade, never()).updateMemoryState(eq(account.uuid), any(), anyLong());
            verify(jedis, never()).set(anyString(), anyString());
            assertTrue(shutdownFinished.getCount() == 0, "shutdown did not wait for the snapshot read");
        } finally {
            releaseSnapshotRead.countDown();
            callback.join(TimeUnit.SECONDS.toMillis(5));
            shutdown.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private static final class UUIDHolder {
        private final java.util.UUID uuid = java.util.UUID.randomUUID();
    }
}
