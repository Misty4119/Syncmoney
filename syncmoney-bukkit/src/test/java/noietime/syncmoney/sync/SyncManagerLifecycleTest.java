package noietime.syncmoney.sync;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncManagerLifecycleTest {

    @Test
    @SuppressWarnings("unchecked")
    void cancelsVersionCheckTaskWhenShutdownRacesSchedulerRegistration() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        ScheduledTask scheduledDuringShutdown = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(EconomyMode.SYNC);
        AtomicReference<SyncManager> managerReference = new AtomicReference<>();
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenAnswer(invocation -> {
                    managerReference.get().shutdown();
                    return scheduledDuringShutdown;
                });

        SyncManager syncManager = new SyncManager(plugin, config,
                mock(CacheManager.class), mock(EconomyFacade.class), mock(RedisManager.class));
        managerReference.set(syncManager);
        syncManager.startPeriodicVersionCheck();

        verify(scheduledDuringShutdown).cancel();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelsOwnedPeriodicVersionCheckTasks() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        AsyncScheduler syncScheduler = mock(AsyncScheduler.class);
        GlobalRegionScheduler crossServerScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask syncTask = mock(ScheduledTask.class);
        ScheduledTask economyTask = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(syncScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(crossServerScheduler);
        when(syncScheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(syncTask);
        when(crossServerScheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong()))
                .thenReturn(economyTask);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(EconomyMode.SYNC);
        SyncManager syncManager = new SyncManager(plugin, config,
                mock(CacheManager.class), mock(EconomyFacade.class), mock(RedisManager.class));
        CrossServerSyncManager crossServer = new CrossServerSyncManager(plugin, config,
                mock(RedisManager.class), mock(EconomyFacade.class), mock(CacheManager.class));

        syncManager.startPeriodicVersionCheck();
        crossServer.startPeriodicVersionCheck();
        syncManager.shutdown();
        crossServer.shutdown();

        verify(syncTask).cancel();
        verify(economyTask).cancel();
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsInboundVersionCheckBeforeDrainAndDoesNotScheduleItAgain() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        ScheduledTask syncTask = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(syncTask);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(EconomyMode.SYNC);
        SyncManager syncManager = new SyncManager(plugin, config,
                mock(CacheManager.class), mock(EconomyFacade.class), mock(RedisManager.class));

        syncManager.startPeriodicVersionCheck();
        syncManager.stopInboundProducers();
        syncManager.startPeriodicVersionCheck();
        syncManager.shutdown();

        verify(syncTask).cancel();
        verify(scheduler).runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopInboundProducersWaitsForAnAlreadyRunningVersionCheck() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        ScheduledTask syncTask = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);

        CountDownLatch snapshotReadStarted = new CountDownLatch(1);
        CountDownLatch releaseSnapshotRead = new CountDownLatch(1);
        CountDownLatch taskCancellationObserved = new CountDownLatch(1);
        UUID playerId = UUID.randomUUID();
        CacheManager cacheManager = mock(CacheManager.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.getOnlinePlayerUuids()).thenReturn(Set.of(playerId));
        when(cacheManager.readAuthorityBalanceSnapshotStrict(playerId)).thenAnswer(invocation -> {
            snapshotReadStarted.countDown();
            releaseSnapshotRead.await();
            return Optional.empty();
        });
        doAnswer(invocation -> {
            taskCancellationObserved.countDown();
            return null;
        }).when(syncTask).cancel();

        AtomicReference<Consumer<ScheduledTask>> scheduledCheck = new AtomicReference<>();
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenAnswer(invocation -> {
                    scheduledCheck.set(invocation.getArgument(1));
                    return syncTask;
                });

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(EconomyMode.SYNC);
        SyncManager syncManager = new SyncManager(plugin, config,
                cacheManager, economyFacade, mock(RedisManager.class));
        syncManager.startPeriodicVersionCheck();

        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        Thread checkThread = new Thread(() -> {
            try {
                scheduledCheck.get().accept(syncTask);
            } catch (Throwable failure) {
                backgroundFailure.set(failure);
            }
        }, "sync-manager-version-check-test");
        Thread stopThread = new Thread(syncManager::stopInboundProducers, "sync-manager-stop-test");

        checkThread.start();
        try {
            assertTrue(snapshotReadStarted.await(5, TimeUnit.SECONDS), "version check did not reach Redis snapshot read");
            stopThread.start();
            assertTrue(taskCancellationObserved.await(5, TimeUnit.SECONDS), "stop did not cancel its scheduled task");
            assertTrue(awaitThreadState(stopThread, Thread.State.WAITING, 2, TimeUnit.SECONDS),
                    "stop should wait for the active version check to finish");
        } finally {
            releaseSnapshotRead.countDown();
            if (checkThread.isAlive()) checkThread.join(TimeUnit.SECONDS.toMillis(5));
            if (stopThread.isAlive()) stopThread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(checkThread.isAlive(), "version check thread did not finish");
        assertFalse(stopThread.isAlive(), "stop thread did not finish after the version check released");
        assertNull(backgroundFailure.get(), "version check failed unexpectedly");
    }

    private static boolean awaitThreadState(Thread thread, Thread.State expected, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) return true;
            if (!thread.isAlive()) return false;
            Thread.sleep(1);
        }
        return thread.getState() == expected;
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelsCrossServerVersionCheckTaskWhenShutdownRacesSchedulerRegistration() {
        Syncmoney plugin = mock(Syncmoney.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask scheduledDuringShutdown = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        AtomicReference<CrossServerSyncManager> managerReference = new AtomicReference<>();
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    managerReference.get().shutdown();
                    return scheduledDuringShutdown;
                });

        CrossServerSyncManager manager = new CrossServerSyncManager(plugin, mock(SyncmoneyConfig.class),
                mock(RedisManager.class), mock(EconomyFacade.class), mock(CacheManager.class));
        managerReference.set(manager);
        manager.startPeriodicVersionCheck();

        verify(scheduledDuringShutdown).cancel();
    }
}
