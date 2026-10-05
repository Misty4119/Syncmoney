package noietime.syncmoney.migration;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalToSyncMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void doesNotStartSqliteSnapshotWhenAnotherMigrationOwnsTheLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                mock(EconomyFacade.class), mock(DatabaseManager.class), mock(RedisManager.class),
                mock(MigrationCheckpoint.class), null);

        MigrationLock.enable();
        assertTrue(MigrationLock.lock());

        task.start(true, false);

        verify(asyncScheduler, never()).runNow(eq(plugin), any());
        assertTrue(MigrationLock.isLocked(), "a rejected second migration must not release the active migration lock");
    }

    @Test
    void schedulerSubmissionFailureReleasesTheLockAndRunningState() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        org.mockito.Mockito.doThrow(new IllegalStateException("scheduler stopped"))
                .when(asyncScheduler).runNow(eq(plugin), any());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                mock(EconomyFacade.class), mock(DatabaseManager.class), mock(RedisManager.class),
                mock(MigrationCheckpoint.class), null);

        assertThrows(IllegalStateException.class, () -> task.start(true, false));

        assertFalse(task.isRunning(), "failed scheduler submission must not leave migration marked running");
        assertFalse(MigrationLock.isLocked(), "failed scheduler submission must release its migration lock");
        assertFalse(MigrationLock.isEnabled(), "failed scheduler submission must release lock feature state");
    }
}
