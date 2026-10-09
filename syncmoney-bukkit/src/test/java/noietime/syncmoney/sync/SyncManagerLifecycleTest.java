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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
