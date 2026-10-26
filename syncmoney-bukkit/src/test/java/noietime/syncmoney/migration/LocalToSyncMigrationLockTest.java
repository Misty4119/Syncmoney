package noietime.syncmoney.migration;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalToSyncMigrationLockTest {

    @TempDir
    Path tempDir;

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
        doAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(eq(plugin), any());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(),
                mock(MigrationCheckpoint.class), null);

        MigrationLock.enable();
        assertTrue(MigrationLock.lock());

        task.start(true, false);

        verify(asyncScheduler).runNow(eq(plugin), any());
        assertTrue(MigrationLock.isLocked(), "a rejected second migration must not release the active migration lock");
    }

    @Test
    void localToSyncMigrationAlwaysLocksEvenWhenLegacyLockSettingIsDisabled() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        doAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(eq(plugin), any());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(false);
        when(config.local().getLocalSQLitePath()).thenAnswer(invocation -> {
            assertTrue(MigrationLock.isLocked(),
                    "the SQLite snapshot path must be read under the migration write fence");
            return "jdbc:sqlite::memory:";
        });
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(),
                mock(MigrationCheckpoint.class), null);

        task.start(true, false);

        verify(asyncScheduler).runNow(eq(plugin), any());
        assertFalse(task.isRunning(), "the immediate mock callback finishes before start returns");
        assertFalse(MigrationLock.isLocked(), "the write fence is released after snapshot handling completes");
    }

    @Test
    void cancellationBeforeAsyncCallbackDoesNotStrandMigrationLockOrRunningState() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        ScheduledTask canceledTask = mock(ScheduledTask.class);
        when(canceledTask.getExecutionState()).thenReturn(ScheduledTask.ExecutionState.CANCELLED);
        when(asyncScheduler.runNow(eq(plugin), any())).thenReturn(canceledTask);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        AtomicBoolean leaseReleased = new AtomicBoolean();
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(),
                mock(MigrationCheckpoint.class), null, () -> () -> leaseReleased.set(true));

        task.start(true, false);

        assertFalse(task.isRunning(), "a scheduler-cancelled callback never began migration work");
        assertFalse(MigrationLock.isLocked(), "a cancelled, not-yet-started callback must not hold the global write fence");
        assertTrue(leaseReleased.get(), "a scheduler-cancelled migration must release its storage lease");
    }

    @Test
    void recoveryReadinessFailureDoesNotStrandMigrationSchedulingState() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenThrow(new IllegalStateException("readiness probe failed"));
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(),
                mock(MigrationCheckpoint.class), null);

        assertThrows(IllegalStateException.class, () -> task.start(true, false));

        assertFalse(task.isRunning(), "a failed preflight must release the start reservation");
    }

    @Test
    void rechecksLocalRecoveryAfterAcquiringLockBeforeSchedulingSnapshot() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        doAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(eq(plugin), any());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true, false);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(),
                mock(MigrationCheckpoint.class), null);

        task.start(true, false);

        verify(asyncScheduler).runNow(eq(plugin), any());
        assertFalse(task.isRunning(), "a failed post-lock recovery check must leave migration stopped");
        assertFalse(MigrationLock.isLocked(), "a failed post-lock recovery check must release its lock");
        assertFalse(MigrationLock.isEnabled(), "a failed post-lock recovery check must release lock state");
    }

    @Test
    void rechecksLocalRecoveryImmediatelyBeforeAsyncSqliteSnapshot() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        doAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(eq(plugin), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.local().getLocalSQLitePath()).thenReturn("jdbc:sqlite::memory:");
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true, false);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(), checkpoint,
                mock(MigrationBackup.class));
        List<String> errors = new ArrayList<>();
        task.setProgressCallback(new LocalToSyncMigrationTask.LocalToSyncProgressCallback() {
            @Override
            public void onProgress(int current, int total, double percent) {
            }

            @Override
            public void onComplete(int successCount, int failedCount) {
            }

            @Override
            public void onError(String error) {
                errors.add(error);
            }
        });

        task.start(true, false);

        assertEquals(List.of("Local-to-Sync migration is unavailable until accepted LOCAL writes are persisted or recovered"),
                errors, "the async worker must revalidate readiness before touching the source snapshot");
        verify(checkpoint, never()).init(org.mockito.ArgumentMatchers.anyInt());
        assertFalse(task.isRunning());
        assertFalse(MigrationLock.isLocked());
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
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, connectedDatabaseManager(), connectedRedisManager(),
                mock(MigrationCheckpoint.class), null);

        assertThrows(IllegalStateException.class, () -> task.start(true, false));

        assertFalse(task.isRunning(), "failed scheduler submission must not leave migration marked running");
        assertFalse(MigrationLock.isLocked(), "failed scheduler submission must release its migration lock");
        assertFalse(MigrationLock.isEnabled(), "failed scheduler submission must release lock feature state");
    }

    @Test
    void stopDuringLocalToSyncMigrationFinishesCurrentPlayerThenPausesAndReleasesLock() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        java.util.concurrent.atomic.AtomicReference<Consumer<ScheduledTask>> scheduledAction =
                new java.util.concurrent.atomic.AtomicReference<>();
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        when(scheduledTask.getExecutionState()).thenReturn(ScheduledTask.ExecutionState.IDLE);
        doAnswer(invocation -> {
            scheduledAction.set(invocation.getArgument(1));
            return scheduledTask;
        }).when(asyncScheduler).runNow(eq(plugin), any());

        Path sqliteFile = tempDir.resolve("local-players.sqlite");
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, name TEXT, balance REAL, version INTEGER)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_balances (uuid, name, balance, version) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, firstUuid.toString());
                insert.setString(2, "first");
                insert.setDouble(3, 10.00);
                insert.setLong(4, 1L);
                insert.executeUpdate();
                insert.setString(1, secondUuid.toString());
                insert.setString(2, "second");
                insert.setDouble(3, 20.00);
                insert.setLong(4, 2L);
                insert.executeUpdate();
            }
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.local().getLocalSQLitePath()).thenReturn("jdbc:sqlite:" + sqliteFile);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        when(config.getServerName()).thenReturn("test-server");
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isConnected()).thenReturn(true);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicInteger writeAttempts = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (writeAttempts.incrementAndGet() == 1) {
                firstWriteStarted.countDown();
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS));
            }
            return true;
        }).when(database).tryReplacePlayerForMigration(any(), anyString(), any(), anyLong(), anyString());
        RedisManager redis = connectedRedisManager();
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, database, redis, checkpoint, null);

        task.start(true, false);
        assertTrue(task.isRunning(), "the scheduled local migration must be stoppable before its worker completes");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var migration = executor.submit(() -> scheduledAction.get().accept(scheduledTask));
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS), "the first shared write must begin");
            assertTrue(MigrationLock.isLocked(), "the active migration must hold the write fence");
            assertTrue(task.stop(), "stop must be accepted while a local-to-sync migration is running");
            releaseFirstWrite.countDown();
            migration.get(5, TimeUnit.SECONDS);

            org.mockito.Mockito.verify(database, org.mockito.Mockito.times(1)).tryReplacePlayerForMigration(
                    any(), anyString(), any(), anyLong(), anyString());
            verify(checkpoint).pause();
            assertFalse(task.isRunning());
            assertFalse(MigrationLock.isLocked(), "a stopped migration must release its write fence");
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdownNow();
        }
    }

    private static DatabaseManager connectedDatabaseManager() {
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isConnected()).thenReturn(true);
        return database;
    }

    private static RedisManager connectedRedisManager() {
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        return redis;
    }
}
