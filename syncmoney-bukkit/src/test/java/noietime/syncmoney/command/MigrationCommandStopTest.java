package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.migration.CMIDatabaseReader;
import noietime.syncmoney.migration.MigrationCheckpoint;
import noietime.syncmoney.migration.MigrationTask;
import noietime.syncmoney.migration.LocalToSyncMigrationTask;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationCommandStopTest {

    @Test
    @SuppressWarnings("unchecked")
    void clearCommandCannotDeleteCheckpointWhileMigrationIsInFlight(@TempDir Path tempDir) {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        List<Consumer<ScheduledTask>> asyncActions = new ArrayList<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCommandStopTest"));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        doAnswer(invocation -> {
            asyncActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any(Consumer.class));

        try (MockedConstruction<MigrationCheckpoint> checkpoints = mockConstruction(MigrationCheckpoint.class);
             MockedConstruction<MigrationTask> migrations = mockConstruction(
                     MigrationTask.class,
                     (migration, context) -> when(migration.isRunning()).thenReturn(true));
             MockedConstruction<LocalToSyncMigrationTask> localMigrations = mockConstruction(
                     LocalToSyncMigrationTask.class)) {
            MigrationCommand command = new MigrationCommand(
                    plugin,
                    config,
                    mock(EconomyFacade.class),
                    mock(DatabaseManager.class),
                    mock(RedisManager.class),
                    mock(NameResolver.class));
            MigrationTask cmiMigration = migrations.constructed().get(0);
            LocalToSyncMigrationTask localMigration = localMigrations.constructed().get(0);

            assertTrue(command.onCommand(sender, null, "syncmoney", new String[]{"clear"}));
            asyncActions.get(0).accept(mock(ScheduledTask.class));

            verify(checkpoints.constructed().get(0), never()).clear();
            verify(plugin).getMessage("migration.already-running");
            verify(plugin, never()).getMessage("migration.cleared");
            assertEquals(1, migrations.constructed().size());
            assertEquals(1, localMigrations.constructed().size());

            when(cmiMigration.isRunning()).thenReturn(false);
            when(localMigration.isRunning()).thenReturn(true);
            assertTrue(command.onCommand(sender, null, "syncmoney", new String[]{"clear"}));
            asyncActions.get(1).accept(mock(ScheduledTask.class));

            verify(checkpoints.constructed().get(0), never()).clear();
            verify(plugin, never()).getMessage("migration.cleared");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopCommandRequestsLocalToSyncStopOnAsyncScheduler(@TempDir Path tempDir) {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        List<Consumer<ScheduledTask>> asyncActions = new ArrayList<>();
        AtomicReference<Consumer<ScheduledTask>> globalReply = new AtomicReference<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCommandStopTest"));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        doAnswer(invocation -> {
            asyncActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
        doAnswer(invocation -> {
            globalReply.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any(Consumer.class));

        try (MockedConstruction<LocalToSyncMigrationTask> migrations = mockConstruction(
                LocalToSyncMigrationTask.class,
                (migration, context) -> {
                    when(migration.isRunning()).thenReturn(true);
                    when(migration.stop()).thenReturn(true);
                })) {
            MigrationCommand command = new MigrationCommand(
                    plugin,
                    config,
                    mock(EconomyFacade.class),
                    mock(DatabaseManager.class),
                    mock(RedisManager.class),
                    mock(NameResolver.class));

            assertEquals(1, migrations.constructed().size());
            LocalToSyncMigrationTask migration = migrations.constructed().get(0);
            assertTrue(command.onCommand(sender, null, "syncmoney", new String[]{"stop"}));
            assertEquals(1, asyncActions.size(), "stop dispatch must be submitted to the async scheduler");

            asyncActions.get(0).accept(mock(ScheduledTask.class));

            verify(migration).stop();
            verify(plugin).getMessage("migration.stop-requested");
            assertTrue(globalReply.get() != null, "the command reply must be routed to the global scheduler");
        }
    }

    @Test
    void shutdownRequestsBothMigrationTasksToStopBeforeStorageTeardown(@TempDir Path tempDir) {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCommandStopTest"));

        try (MockedConstruction<MigrationCheckpoint> checkpoints = mockConstruction(MigrationCheckpoint.class);
             MockedConstruction<MigrationTask> migrations = mockConstruction(MigrationTask.class);
             MockedConstruction<LocalToSyncMigrationTask> localMigrations = mockConstruction(LocalToSyncMigrationTask.class)) {
            MigrationCommand command = new MigrationCommand(
                    plugin,
                    config,
                    mock(EconomyFacade.class),
                    mock(DatabaseManager.class),
                    mock(RedisManager.class),
                    mock(NameResolver.class));
            MigrationTask cmiMigration = migrations.constructed().get(0);
            LocalToSyncMigrationTask localMigration = localMigrations.constructed().get(0);

            command.shutdown();

            verify(cmiMigration).shutdown();
            verify(localMigration).stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shutdownDoesNotWaitForSlowCmiPrerequisiteDispatch(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        List<Consumer<ScheduledTask>> asyncActions = new ArrayList<>();
        CountDownLatch cmiInitializationStarted = new CountDownLatch(1);
        CountDownLatch finishCmiInitialization = new CountDownLatch(1);
        AtomicReference<Throwable> commandFailure = new AtomicReference<>();
        AtomicReference<CMIDatabaseReader> cmiReader = new AtomicReference<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCommandStopTest"));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        doAnswer(invocation -> {
            asyncActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any(Consumer.class));

        try (MockedConstruction<MigrationTask> migrations = mockConstruction(MigrationTask.class);
             MockedConstruction<LocalToSyncMigrationTask> localMigrations = mockConstruction(LocalToSyncMigrationTask.class)) {
            MigrationCommand command = new MigrationCommand(
                    plugin,
                    config,
                    mock(EconomyFacade.class),
                    mock(DatabaseManager.class),
                    mock(RedisManager.class),
                    mock(NameResolver.class));
            MigrationTask cmiMigration = migrations.constructed().get(0);

            assertTrue(command.onCommand(sender, null, "syncmoney", new String[]{"cmi"}));
            Thread commandThread = new Thread(() -> {
                // Mockito construction mocks are thread-local, so install this seam on the async worker.
                try (MockedConstruction<CMIDatabaseReader> readers = mockConstruction(
                        CMIDatabaseReader.class,
                        (reader, context) -> {
                            cmiReader.set(reader);
                            when(reader.testConnection()).thenReturn(true);
                            doAnswer(invocation -> {
                                cmiInitializationStarted.countDown();
                                assertTrue(finishCmiInitialization.await(5, TimeUnit.SECONDS));
                                return null;
                            }).when(reader).initialize();
                        })) {
                    asyncActions.get(0).accept(mock(ScheduledTask.class));
                } catch (Throwable failure) {
                    commandFailure.set(failure);
                }
            });
            commandThread.start();
            boolean cmiInitializationEntered = cmiInitializationStarted.await(2, TimeUnit.SECONDS);
            if (!cmiInitializationEntered && commandFailure.get() != null) {
                throw new AssertionError("async CMI command failed before initialization", commandFailure.get());
            }
            assertTrue(cmiInitializationEntered, "CMI prerequisite setup should reach its blocking test seam");

            FutureTask<Void> shutdown = new FutureTask<>(() -> {
                command.shutdown();
                return null;
            });
            Thread shutdownThread = new Thread(shutdown);
            shutdownThread.start();
            try {
                shutdown.get(1, TimeUnit.SECONDS);
            } finally {
                finishCmiInitialization.countDown();
                commandThread.join(2_000);
                shutdownThread.join(2_000);
            }

            assertTrue(!commandThread.isAlive(), "the async command should finish after the prerequisite is released");
            assertTrue(!shutdownThread.isAlive(), "shutdown should finish without waiting for command I/O");
            assertNull(commandFailure.get(), "the async migration command should finish without an exception");
            verify(cmiReader.get()).initialize();
            verify(cmiMigration).shutdown();
            verify(cmiMigration, never()).start(false, false, null);
            verify(localMigrations.constructed().get(0)).stop();
        }
    }
}
