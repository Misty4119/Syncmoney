package noietime.syncmoney.migration;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.economy.OverflowLogInterface;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class MigrationTaskLockTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void lockRejectsExternalAdminWritesButAllowsMigrationWorkerWrite() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        UUID account = UUID.randomUUID();
        LocalEconomyHandler localHandler = spy(new LocalEconomyHandler(plugin,
                tempDir.resolve("migration-local.db").toString()));
        AtomicReference<EconomyFacade> facadeRef = new AtomicReference<>();
        AtomicBoolean triedReentrantWrite = new AtomicBoolean();
        doAnswer(invocation -> {
            if (triedReentrantWrite.compareAndSet(false, true)) {
                assertEquals(new BigDecimal("-1"), facadeRef.get().setBalance(account,
                        new BigDecimal("175.00"), EconomyEvent.EventSource.ADMIN_SET),
                        "a nested external write must not reuse the migration worker's one-write permit");
            }
            return null;
        }).when(localHandler).setBalance(any(UUID.class), isNull(), any(BigDecimal.class), any(String.class));
        EconomyFacade facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                null, localHandler, new EconomyWriteQueue(4), mock(FallbackEconomyWrapper.class),
                mock(PlayerTransactionGuard.class), mock(OverflowLogInterface.class));
        facadeRef.set(facade);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, null, facade, null, null,
                null, checkpoint, null, null);

        try {
            assertTrue(facade.updateMemoryState(account, new BigDecimal("100.00"), 7L));
            assertTrue(task.acquireMigrationLockForExecution(), "the CMI task must own the migration lock it bypasses");

            assertEquals(new BigDecimal("-1"), facade.setBalance(account, new BigDecimal("120.00"),
                    EconomyEvent.EventSource.ADMIN_SET));
            assertEquals(new BigDecimal("-1"), facade.setBalance(account, new BigDecimal("130.00"),
                    EconomyEvent.EventSource.COMMAND_ADMIN));
            assertEquals(new BigDecimal("-1"), facade.deposit(account, new BigDecimal("10.00"),
                    EconomyEvent.EventSource.VAULT_DEPOSIT));
            assertEquals(new BigDecimal("-1"), facade.withdraw(account, new BigDecimal("10.00"),
                    EconomyEvent.EventSource.VAULT_WITHDRAW));
            assertEquals(new BigDecimal("-1"), facade.pluginDeposit(account, new BigDecimal("10.00"), "ExampleShop"));
            assertEquals(new BigDecimal("-1"), facade.pluginWithdraw(account, new BigDecimal("10.00"), "ExampleShop"));
            assertEquals(new BigDecimal("-1"), facade.atomicTransfer(account, UUID.randomUUID(),
                    new BigDecimal("10.00"), EconomyEvent.EventSource.PLAYER_TRANSFER));
            assertTrue(facade.pluginAtomicTransfer(account, UUID.randomUUID(),
                    new BigDecimal("10.00"), "ExampleShop") == null);
            assertEquals(new BigDecimal("100.00"), facade.getMemoryState(account).balance());
            verify(localHandler, never()).setBalance(account, null, new BigDecimal("120.00"), "ADMIN_SET");
            verify(localHandler, never()).setBalance(account, null, new BigDecimal("130.00"), "COMMAND_ADMIN");

            invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(account, "account",
                    new BigDecimal("150.00"), 0L));

            assertEquals(new BigDecimal("150.00"), facade.getMemoryState(account).balance());
            verify(localHandler).setBalance(account, null, new BigDecimal("150.00"), "ADMIN_SET");
            verify(checkpoint).addBackupAmount(new BigDecimal("150.00"));
            assertTrue(MigrationLock.isLocked(), "migration remains locked until migration completion");
            assertTrue(triedReentrantWrite.get(), "the migration write must exercise the nested external-write guard");
        } finally {
            facade.shutdown();
            localHandler.close();
        }
    }

    @Test
    void cmiMigrationWithoutItsOwnLockCannotBorrowAnotherMigrationPermit() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationLockEconomy()).thenReturn(false);

        UUID account = UUID.randomUUID();
        LocalEconomyHandler localHandler = spy(new LocalEconomyHandler(plugin,
                tempDir.resolve("borrowed-migration-lock.db").toString()));
        EconomyFacade facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                null, localHandler, new EconomyWriteQueue(4), mock(FallbackEconomyWrapper.class),
                mock(PlayerTransactionGuard.class), mock(OverflowLogInterface.class));
        MigrationTask task = new MigrationTask(plugin, config, null, facade, null, null,
                null, mock(MigrationCheckpoint.class), null, null);

        try {
            assertTrue(facade.updateMemoryState(account, new BigDecimal("100.00"), 7L));
            MigrationLock.enable();
            assertTrue(MigrationLock.lock(), "another migration owns the active lock");

            assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(account, "account",
                            new BigDecimal("150.00"), 0L)));

            assertEquals(new BigDecimal("100.00"), facade.getMemoryState(account).balance(),
                    "a CMI worker without lock ownership cannot overwrite the active migration snapshot");
            verify(localHandler, never()).setBalance(account, null, new BigDecimal("150.00"), "ADMIN_SET");
            assertTrue(MigrationLock.isLocked(), "the rejected CMI worker must not release the other migration's lock");
        } finally {
            facade.shutdown();
            localHandler.close();
        }
    }

    @Test
    void migrationAbortsBeforeSnapshotWhileProducerIsPausedInsidePreEvent() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.isDatabaseEnabled()).thenReturn(true);
        UUID account = UUID.randomUUID();
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        EconomyFacade facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                null, null, queue, new FallbackEconomyWrapper(plugin, mock(RedisManager.class)),
                null, mock(OverflowLogInterface.class));
        assertTrue(facade.updateMemoryState(account, new BigDecimal("100.00"), 7L));

        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            when(busPlugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
            SyncmoneyEventBus.init(busPlugin);
        }
        SyncmoneyEventBus eventBus = SyncmoneyEventBus.getInstance();
        CountDownLatch producerReachedPreEvent = new CountDownLatch(1);
        CountDownLatch resumeProducer = new CountDownLatch(1);
        SyncmoneyListener<AsyncPreTransactionEvent> pauseProducer = event -> {
            producerReachedPreEvent.countDown();
            try {
                if (!resumeProducer.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("producer interleaving barrier timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        };
        eventBus.register(AsyncPreTransactionEvent.class, pauseProducer);

        AtomicReference<BigDecimal> writeResult = new AtomicReference<>();
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                writeResult.set(facade.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST));
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "migration-lock-pre-event-producer");
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader, 0);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        RedisManager migrationRedis = mock(RedisManager.class);
        when(migrationRedis.isConnected()).thenReturn(true);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, successfulDatabase(), migrationRedis,
                null, mock(MigrationCheckpoint.class), null, null);

        try {
            producer.start();
            assertTrue(producerReachedPreEvent.await(5, TimeUnit.SECONDS),
                    "producer should reach the callback after entering its write path");

            task.start(false, false);
            resumeProducer.countDown();
            producer.join(TimeUnit.SECONDS.toMillis(5));

            assertTrue(!producer.isAlive(), "producer should finish after the callback barrier is released");
            if (producerFailure.get() != null) {
                throw new AssertionError("producer failed unexpectedly", producerFailure.get());
            }
            verify(cmiReader, never()).getSourceSnapshotFingerprint();
            assertTrue(!MigrationLock.isLocked(),
                    "migration must leave the economy unlocked when it cannot quiesce an in-flight producer");
            assertEquals(0, writeResult.get().compareTo(new BigDecimal("110.00")),
                    "the already-admitted write may finish after the migration lock attempt is refused");
            assertEquals(new BigDecimal("110.00"), facade.getMemoryState(account).balance());
        } finally {
            resumeProducer.countDown();
            producer.join(TimeUnit.SECONDS.toMillis(5));
            eventBus.unregister(AsyncPreTransactionEvent.class, pauseProducer);
            MigrationLock.unlock();
            MigrationLock.disable();
            facade.shutdown();
        }
    }

    @Test
    void migrationWithNoSourceRowsReleasesTheLockBeforeReturning() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        runGlobalTasksImmediately(plugin, server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runAsyncTasksImmediately(asyncScheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader, 0);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(0);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        EconomyFacade facade = mock(EconomyFacade.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, successfulDatabase(), redis,
                null, mock(MigrationCheckpoint.class), null, successfulDisabler());

        task.start(false, false);

        assertTrue(!MigrationLock.isLocked(), "a migration that exits before starting must not leave writes locked");
        assertTrue(!MigrationLock.isEnabled(), "a completed no-data attempt must release its lock feature state");
    }

    @Test
    void sourceSnapshotFailureReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        runGlobalTasksImmediately(plugin, server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runAsyncTasksImmediately(asyncScheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getSourceSnapshotFingerprint()).thenThrow(new IllegalStateException("source snapshot failed"));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), successfulDatabase(),
                redis, null, mock(MigrationCheckpoint.class), null, successfulDisabler());

        task.start(false, false);

        assertFalse(MigrationLock.isLocked(), "a failed startup must not leave writes locked");
        assertFalse(MigrationLock.isEnabled(), "a failed startup must release its lock feature state");
    }

    @Test
    void workerReadFailurePausesMigrationAndReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);
        doAnswer(invocation -> {
            java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> action =
                    invocation.getArgument(1);
            action.accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        when(cmiReader.readPlayers(0, 10)).thenThrow(new IllegalStateException("source read failed"));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());

        task.start(false, false);

        verify(checkpoint).pause();
        verify(checkpoint, never()).fail();
        assertFalse(MigrationLock.isLocked(), "a terminal worker failure must release the economy lock");
        assertFalse(MigrationLock.isEnabled(), "a terminal worker failure must release its lock feature state");
    }

    @Test
    void resumeReacquiresMigrationLockAfterProcessRestart() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        when(checkpoint.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(checkpoint.getSourceFingerprint()).thenReturn("test-cmi-source-fingerprint");
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class),
                mock(EconomyFacade.class), successfulDatabase(), null, null, checkpoint, null, successfulDisabler());

        assertTrue(task.resume());

        assertTrue(MigrationLock.isLocked(), "resuming after restart must reacquire the configured lock");
        verify(asyncScheduler).runNow(any(Plugin.class), any());
    }

    @Test
    void resumeRejectsChangedSourceBeforeAnyTargetWrite() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        AtomicReference<java.util.function.Consumer<ScheduledTask>> worker = new AtomicReference<>();
        doAnswer(invocation -> {
            worker.set(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        when(checkpoint.getSourceIdentity()).thenReturn("cmi-single|previous-source");
        when(checkpoint.getSourceFingerprint()).thenReturn("test-cmi-source-fingerprint");
        CMIDatabaseReader reader = mock(CMIDatabaseReader.class);
        when(reader.isInitialized()).thenReturn(true);
        when(reader.testConnection()).thenReturn(true);
        when(reader.getSourceIdentity()).thenReturn("cmi-single|current-source");
        EconomyFacade facade = mock(EconomyFacade.class);
        DatabaseManager database = mock(DatabaseManager.class);
        RedisManager redis = mock(RedisManager.class);
        MigrationTask task = new MigrationTask(plugin, config, reader, facade, database, redis,
                null, checkpoint, null, successfulDisabler());

        assertTrue(task.resume());
        worker.get().accept(mock(ScheduledTask.class));

        verify(reader, never()).readPlayers(anyInt(), anyInt());
        verify(facade, never()).getBalance(any(UUID.class));
        verify(facade, never()).setBalance(any(UUID.class), any(BigDecimal.class), any());
        verify(database, never()).tryReplacePlayerForMigration(any(UUID.class), any(), any(), anyLong(), any());
        verify(redis, never()).getResource();
        verify(checkpoint).pause();
        verify(checkpoint, never()).complete();
    }

    @Test
    void resumePreservesForceOverwriteForPositiveDestinationBalances() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Path databaseFile = tempDir.resolve("cmi-force-resume.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('first-player', '00000000-0000-0000-0000-000000000004', '25.00', 1)");
            statement.execute("INSERT INTO users VALUES ('resume-player', '00000000-0000-0000-0000-000000000003', '75.00', 2)");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getCMISqlitePaths()).thenReturn(List.of(databaseFile.toString()));
        when(config.migration().getCMIMergeStrategy()).thenReturn("latest");
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        ArrayDeque<java.util.function.Consumer<ScheduledTask>> workers = new ArrayDeque<>();
        doAnswer(invocation -> {
            workers.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);

        UUID firstAccount = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000003");
        EconomyFacade facade = mock(EconomyFacade.class);
        AtomicBoolean failFirstWrite = new AtomicBoolean(true);
        when(facade.getBalance(firstAccount)).thenReturn(BigDecimal.ZERO);
        when(facade.getBalance(account)).thenReturn(new BigDecimal("250.00"));
        org.mockito.Mockito.when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> {
                    UUID target = invocation.getArgument(0);
                    BigDecimal balance = invocation.getArgument(1);
                    if (target.equals(firstAccount) && failFirstWrite.compareAndSet(true, false)) {
                        return new BigDecimal("-1");
                    }
                    return balance;
                });
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        MigrationTask task = new MigrationTask(plugin, config, null, facade, successfulDatabase(), redis,
                null, checkpoint, null, successfulDisabler());

        CMIMultiServerReader source = new CMIMultiServerReader(plugin, config);
        task.start(true, false, source);
        workers.remove().accept(mock(ScheduledTask.class)); // take the source snapshot and create checkpoint
        workers.remove().accept(mock(ScheduledTask.class)); // fail the first row and pause at offset zero
        assertEquals(MigrationCheckpoint.MigrationState.PAUSED, checkpoint.getState());
        assertEquals(0, checkpoint.getCurrentOffset());

        assertTrue(task.resume());
        workers.remove().accept(mock(ScheduledTask.class)); // validate the checkpoint source
        workers.remove().accept(mock(ScheduledTask.class)); // retry first row, then process positive target

        source.shutdown();
        java.nio.file.Files.delete(databaseFile);
        verify(facade).setBalance(account, new BigDecimal("75.00"), EconomyEvent.EventSource.ADMIN_SET);
        assertEquals(MigrationCheckpoint.MigrationState.COMPLETED, checkpoint.getState());
    }

    @Test
    void resumeRejectsSourceContentChangeWhenRowCountIsUnchanged() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Path pluginData = tempDir.resolve("plugins/Syncmoney");
        Path cmiSettings = tempDir.resolve("plugins/CMI/Settings");
        Files.createDirectories(pluginData);
        Files.createDirectories(cmiSettings);
        Files.writeString(cmiSettings.resolve("DataBaseInfo.yml"), "storage:\n  method: SQLite\n");
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());

        Path databaseFile = tempDir.resolve("plugins/CMI/cmi.sqlite.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('first-player', '00000000-0000-0000-0000-000000000051', '40.00', 1)");
            statement.execute("INSERT INTO users VALUES ('second-player', '00000000-0000-0000-0000-000000000052', '75.00', 2)");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(1);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        Server server = mock(Server.class);
        AsyncScheduler initialScheduler = mock(AsyncScheduler.class);
        ArrayDeque<java.util.function.Consumer<ScheduledTask>> initialWorkers = new ArrayDeque<>();
        doAnswer(invocation -> {
            initialWorkers.add(invocation.getArgument(1));
            return null;
        }).when(initialScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(initialScheduler);
        runGlobalTasksImmediately(plugin, server);

        CMIDatabaseReader initialReader = new CMIDatabaseReader(plugin, config);
        initialReader.initialize();
        CMIDatabaseReader.CMIPlayerData first = initialReader.readPlayers(0, 1).get(0);
        CMIDatabaseReader.CMIPlayerData second = initialReader.readPlayers(1, 1).get(0);
        java.util.concurrent.ConcurrentHashMap<UUID, BigDecimal> targetBalances = new java.util.concurrent.ConcurrentHashMap<>();
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(any(UUID.class))).thenAnswer(invocation ->
                targetBalances.getOrDefault(invocation.getArgument(0), BigDecimal.ZERO));
        org.mockito.Mockito.when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> {
                    targetBalances.put(invocation.getArgument(0), invocation.getArgument(1));
                    return invocation.getArgument(1);
                });
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        MigrationTask initialTask = new MigrationTask(plugin, config, initialReader, facade, successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());

        initialTask.start(false, false);
        initialWorkers.remove().accept(mock(ScheduledTask.class)); // save the initial source checkpoint
        initialWorkers.remove().accept(mock(ScheduledTask.class)); // migrate one row and advance the checkpoint offset
        assertEquals(1, checkpoint.getCurrentOffset());
        initialTask.stop();
        initialReader.shutdown();

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE users SET Balance = '95.00' WHERE username = '" + second.playerName() + "'");
        }

        MigrationLock.unlock();
        MigrationLock.disable(); // simulate the static lock state being reset by a process restart
        AsyncScheduler resumeScheduler = mock(AsyncScheduler.class);
        ArrayDeque<java.util.function.Consumer<ScheduledTask>> resumeWorkers = new ArrayDeque<>();
        doAnswer(invocation -> {
            resumeWorkers.add(invocation.getArgument(1));
            return null;
        }).when(resumeScheduler).runNow(any(Plugin.class), any());
        when(server.getAsyncScheduler()).thenReturn(resumeScheduler);
        CMIDatabaseReader resumeReader = new CMIDatabaseReader(plugin, config);
        resumeReader.initialize();
        MigrationTask resumedTask = new MigrationTask(plugin, config, resumeReader, facade, successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());

        try {
            assertTrue(resumedTask.resume());
            while (!resumeWorkers.isEmpty()) {
                resumeWorkers.remove().accept(mock(ScheduledTask.class));
            }

            assertEquals(MigrationCheckpoint.MigrationState.FAILED, checkpoint.getState(),
                    "a changed source with the same row count invalidates the old checkpoint");
            verify(facade, never()).setBalance(second.uuid(), new BigDecimal("95.00"), EconomyEvent.EventSource.ADMIN_SET);
            assertEquals(BigDecimal.ZERO, targetBalances.getOrDefault(second.uuid(), BigDecimal.ZERO),
                    "the changed source row must not be copied from the invalidated snapshot");
        } finally {
            resumeReader.shutdown();
        }
    }

    @Test
    void resumeRejectsLegacyCheckpointWithoutSourceFingerprint() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Path pluginData = tempDir.resolve("plugins/Syncmoney");
        Files.createDirectories(pluginData);
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());
        Files.writeString(pluginData.resolve("migration_checkpoint.json"), """
                {
                  "state": "PAUSED",
                  "currentOffset": 1,
                  "totalPlayers": 2,
                  "migratedCount": 1,
                  "failedCount": 0,
                  "startTime": "2026-10-06T00:00:00Z",
                  "lastUpdateTime": "2026-10-06T00:00:00Z",
                  "failures": {},
                  "sourceIdentity": "cmi-single|legacy-source",
                  "forceOverwrite": true
                }
                """);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class), mock(EconomyFacade.class), successfulDatabase(),
                null, null, checkpoint, null, successfulDisabler());

        assertFalse(task.resume(), "a legacy CMI checkpoint has no content proof for its prior source snapshot");
        assertEquals(MigrationCheckpoint.MigrationState.FAILED, checkpoint.getState());
    }

    @Test
    void sourceMutationBeforeMigrationCommitFailsCheckpointInsteadOfAllowingResume() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Path pluginData = tempDir.resolve("plugins/Syncmoney");
        Path cmiSettings = tempDir.resolve("plugins/CMI/Settings");
        Files.createDirectories(pluginData);
        Files.createDirectories(cmiSettings);
        Files.writeString(cmiSettings.resolve("DataBaseInfo.yml"), "storage:\n  method: SQLite\n");
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());

        Path databaseFile = tempDir.resolve("plugins/CMI/cmi.sqlite.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('first-player', '00000000-0000-0000-0000-000000000041', '40.00', 1)");
            statement.execute("INSERT INTO users VALUES ('second-player', '00000000-0000-0000-0000-000000000042', '75.00', 2)");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(1);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        ArrayDeque<java.util.function.Consumer<ScheduledTask>> workers = new ArrayDeque<>();
        doAnswer(invocation -> {
            workers.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);

        UUID firstAccount = UUID.fromString("00000000-0000-0000-0000-000000000041");
        UUID secondAccount = UUID.fromString("00000000-0000-0000-0000-000000000042");
        java.util.concurrent.ConcurrentHashMap<UUID, BigDecimal> targetBalances = new java.util.concurrent.ConcurrentHashMap<>();
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(any(UUID.class))).thenAnswer(invocation ->
                targetBalances.getOrDefault(invocation.getArgument(0), BigDecimal.ZERO));
        when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> {
                    UUID account = invocation.getArgument(0);
                    BigDecimal balance = invocation.getArgument(1);
                    if (account.equals(secondAccount)) {
                        assertTrue(MigrationLock.recordCmiAuthorityMutation());
                        return new BigDecimal("-1.00");
                    }
                    targetBalances.put(account, balance);
                    return balance;
                });
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        CMIDatabaseReader source = new CMIDatabaseReader(plugin, config);
        source.initialize();
        MigrationTask task = new MigrationTask(plugin, config, source, facade, successfulDatabase(), redis,
                null, checkpoint, null, successfulDisabler());

        try {
            task.start(false, false);
            workers.remove().accept(mock(ScheduledTask.class)); // create the checkpoint and schedule row one
            workers.remove().accept(mock(ScheduledTask.class)); // migrate the first row and schedule row two
            assertEquals(MigrationCheckpoint.MigrationState.RUNNING, checkpoint.getState());
            workers.remove().accept(mock(ScheduledTask.class)); // source changes before row two's memory commit

            assertEquals(new BigDecimal("40.00"), targetBalances.get(firstAccount));
            assertEquals(MigrationCheckpoint.MigrationState.FAILED, checkpoint.getState(),
                    "a dirty source must invalidate the whole snapshot instead of creating a resumable partial target");
            assertFalse(task.resume(), "an invalidated source snapshot must not be resumable");
        } finally {
            source.shutdown();
        }
    }

    @Test
    void multiServerCheckpointResumeReadsThePinnedMultiServerSource() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Path databaseFile = tempDir.resolve("cmi-multi-resume.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('already-migrated', '00000000-0000-0000-0000-000000000001', '40.00', 1)");
            statement.execute("INSERT INTO users VALUES ('resume-player', '00000000-0000-0000-0000-000000000002', '75.00', 2)");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getCMISqlitePaths()).thenReturn(List.of(databaseFile.toString()));
        when(config.migration().getCMIMergeStrategy()).thenReturn("latest");
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        String sourceIdentity = new CMIMultiServerReader(plugin, config).getSourceIdentity();

        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        ArrayDeque<java.util.function.Consumer<ScheduledTask>> workers = new ArrayDeque<>();
        doAnswer(invocation -> {
            workers.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);

        UUID previouslyMigrated = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000002");
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        when(checkpoint.getSourceIdentity()).thenReturn(sourceIdentity);
        CMIMultiServerReader fingerprintReader = new CMIMultiServerReader(plugin, config);
        fingerprintReader.initialize();
        String sourceFingerprint = fingerprintReader.getSourceSnapshotFingerprint().value();
        fingerprintReader.shutdown();
        when(checkpoint.getSourceFingerprint()).thenReturn(sourceFingerprint);
        when(checkpoint.getCurrentOffset()).thenReturn(1);
        when(checkpoint.getTotalPlayers()).thenReturn(2);
        when(checkpoint.getMigratedCount()).thenReturn(1);
        when(checkpoint.getFailedCount()).thenReturn(0);
        MigrationTask task = new MigrationTask(plugin, config, null, facade, successfulDatabase(), null,
                null, checkpoint, null, successfulDisabler());

        assertTrue(task.resume());
        workers.remove().accept(mock(ScheduledTask.class)); // validate and initialize the pinned source
        workers.remove().accept(mock(ScheduledTask.class)); // read the resumed migration batch

        verify(facade).setBalance(account, new BigDecimal("75.00"), EconomyEvent.EventSource.ADMIN_SET);
        verify(facade, never()).setBalance(previouslyMigrated, new BigDecimal("40.00"), EconomyEvent.EventSource.ADMIN_SET);
        verify(checkpoint).complete();
        java.lang.reflect.Field multiReaderField = MigrationTask.class.getDeclaredField("multiServerReader");
        multiReaderField.setAccessible(true);
        ((CMIMultiServerReader) multiReaderField.get(task)).shutdown();
        java.nio.file.Files.delete(databaseFile);
    }

    @Test
    void multiServerResumeRejectsChangedContentWithTheSameRowCount() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Path pluginData = tempDir.resolve("plugins/Syncmoney");
        Files.createDirectories(pluginData);
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());
        Path databaseFile = tempDir.resolve("cmi-multi-changed-resume.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('changed-player', '00000000-0000-0000-0000-000000000071', '40.00', 1)");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        stubCmiQuiescence(config);
        when(config.migration().getCMISqlitePaths()).thenReturn(List.of(databaseFile.toString()));
        when(config.migration().getCMIMergeStrategy()).thenReturn("latest");
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIMultiServerReader fingerprintReader = new CMIMultiServerReader(plugin, config);
        fingerprintReader.initialize();
        String sourceIdentity = fingerprintReader.getSourceIdentity();
        String sourceFingerprint = fingerprintReader.getSourceSnapshotFingerprint().value();
        fingerprintReader.shutdown();

        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        checkpoint.init(1, sourceIdentity, false, sourceFingerprint);
        checkpoint.pause();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE users SET Balance = '55.00' WHERE username = 'changed-player'");
        }

        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        ArrayDeque<java.util.function.Consumer<ScheduledTask>> workers = new ArrayDeque<>();
        doAnswer(invocation -> {
            workers.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);

        EconomyFacade facade = mock(EconomyFacade.class);
        MigrationTask task = new MigrationTask(plugin, config, null, facade, successfulDatabase(),
                null, null, checkpoint, null, successfulDisabler());
        try {
            assertTrue(task.resume());
            workers.remove().accept(mock(ScheduledTask.class));

            assertEquals(MigrationCheckpoint.MigrationState.FAILED, checkpoint.getState(),
                    "multi-server resume must fail closed when a source balance changes without changing the row count");
            verify(facade, never()).setBalance(any(UUID.class), any(BigDecimal.class), any());
        } finally {
            java.lang.reflect.Field multiReaderField = MigrationTask.class.getDeclaredField("multiServerReader");
            multiReaderField.setAccessible(true);
            Object multiReader = multiReaderField.get(task);
            if (multiReader instanceof CMIMultiServerReader source) {
                source.shutdown();
            }
        }
    }

    @Test
    void startSchedulerFailureReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        runGlobalTasksImmediately(plugin, server);
        when(server.getAsyncScheduler()).thenReturn(null);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());

        task.start(false, false);

        verify(checkpoint, never()).fail();
        assertFalse(MigrationLock.isLocked(), "a failed worker schedule must release the economy lock");
        assertFalse(MigrationLock.isEnabled(), "a failed worker schedule must release its lock feature state");
    }

    @Test
    void stoppingBeforeTheSourceSnapshotReleasesTheLockWithoutPausingAnUnstartedCheckpoint() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        AtomicReference<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> worker =
                new AtomicReference<>();
        doAnswer(invocation -> {
            worker.set(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());

        task.start(false, false);
        task.stop();
        worker.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        verify(checkpoint, never()).pause();
        verify(checkpoint, never()).complete();
        verify(cmiReader, never()).readPlayers(0, 10);
        assertFalse(MigrationLock.isLocked(), "a stop before snapshot initialization must release the write fence");
    }

    @Test
    void stoppingDuringCurrentBatchPausesAfterThatBatchWithoutCompletingOrUnlocking() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        AtomicReference<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> worker =
                new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger scheduledWorkers = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            scheduledWorkers.incrementAndGet();
            worker.set(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        runGlobalTasksImmediately(plugin, server);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDatabaseEnabled()).thenReturn(true);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        UUID account = UUID.randomUUID();
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader, 2);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(2);
        when(cmiReader.readPlayers(0, 10)).thenReturn(List.of(new CMIDatabaseReader.CMIPlayerData(
                account, "stop-mid-batch", new BigDecimal("25.00"), 0L)));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        AtomicReference<MigrationTask> taskRef = new AtomicReference<>();
        AtomicBoolean resumeDuringBatchAccepted = new AtomicBoolean(true);
        AtomicBoolean busyDuringStopDrain = new AtomicBoolean();
        org.mockito.Mockito.when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> {
                    taskRef.get().stop();
                    busyDuringStopDrain.set(taskRef.get().isRunning());
                    resumeDuringBatchAccepted.set(taskRef.get().resume());
                    return invocation.getArgument(1);
                });
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        when(checkpoint.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(checkpoint.getSourceFingerprint()).thenReturn("test-cmi-source-fingerprint");
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());
        taskRef.set(task);

        task.start(false, false);
        worker.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
        worker.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        assertTrue(busyDuringStopDrain.get(), "the task remains busy until the active batch drains");
        assertFalse(resumeDuringBatchAccepted.get(), "resume must wait until the stopped worker leaves its batch");
        assertEquals(2, scheduledWorkers.get(), "stop/resume must not enqueue a second worker while the batch is active");
        org.mockito.Mockito.verify(checkpoint, org.mockito.Mockito.atLeastOnce()).pause();
        verify(checkpoint).updateProgress(1, 1);
        verify(checkpoint, never()).complete();
        assertFalse(task.isRunning(), "the task becomes resumable after the stopped batch has exited");
        assertTrue(MigrationLock.isLocked(), "stopping at a batch boundary retains the lock for resume");

        assertTrue(task.resume(), "resume is accepted after the stopped worker has drained");
        task.stop();
        worker.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
        assertEquals(3, scheduledWorkers.get());
        assertFalse(MigrationLock.isLocked(), "stopping before resumed snapshot initialization releases the lock");
    }

    @Test
    void resumeWaitsForActiveWritersInsteadOfStartingUnlocked() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.isDatabaseEnabled()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        when(checkpoint.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(checkpoint.getSourceFingerprint()).thenReturn("test-cmi-source-fingerprint");
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class),
                mock(EconomyFacade.class), successfulDatabase(), null, null, checkpoint, null, null);

        MigrationLock.enable();
        try (MigrationLock.WriteLease activeWrite = MigrationLock.tryAcquireWrite()) {
            assertFalse(task.resume(), "resume must be refused until active writes finish");
        }

        verify(checkpoint).pause();
        verify(asyncScheduler, never()).runNow(any(Plugin.class), any());
        assertFalse(MigrationLock.isLocked());
    }

    @Test
    void resumeDoesNotAdoptAndReleaseAnotherMigrationsLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        doAnswer(invocation -> {
            java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> worker =
                    invocation.getArgument(1);
            worker.accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.RUNNING);
        MigrationTask task = new MigrationTask(plugin, config, null, mock(EconomyFacade.class), null,
                null, null, checkpoint, null, null);

        MigrationLock.enable();
        assertTrue(MigrationLock.lock(), "the active LOCAL-TO-SYNC migration owns the shared lock");

        assertFalse(task.resume(), "a CMI task must not claim a lock owned by LOCAL-TO-SYNC");

        assertTrue(MigrationLock.isLocked(), "a rejected resume must leave the other migration's lock active");
        verify(asyncScheduler, never()).runNow(any(Plugin.class), any());
        verify(checkpoint, never()).resume();
        verify(checkpoint, never()).fail();
    }

    @Test
    void cmiMigrationSchedulesEveryBatchAndKeepsTheLockUntilTheLastBatch() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
        ArrayDeque<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> workers =
                new ArrayDeque<>();
        doAnswer(invocation -> {
            workers.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader, 11);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(11);
        List<CMIDatabaseReader.CMIPlayerData> firstBatch = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> new CMIDatabaseReader.CMIPlayerData(
                        UUID.randomUUID(), "batch-" + index, new BigDecimal("25.00"), 0L))
                .toList();
        List<CMIDatabaseReader.CMIPlayerData> finalBatch = List.of(new CMIDatabaseReader.CMIPlayerData(
                UUID.randomUUID(), "batch-final", new BigDecimal("25.00"), 0L));
        when(cmiReader.readPlayers(0, 10)).thenReturn(firstBatch);
        when(cmiReader.readPlayers(10, 10)).thenReturn(finalBatch);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(any(UUID.class))).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, successfulDatabase(),
                redis, null, checkpoint, null, successfulDisabler());

        task.start(false, false);
        assertEquals(1, workers.size(), "starting migration schedules the first batch");
        workers.remove().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
        assertEquals(1, workers.size(), "source snapshot initialization schedules the first batch");
        workers.remove().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        assertEquals(1, workers.size(), "a remaining row schedules the next CMI batch");
        assertTrue(MigrationLock.isLocked(), "the migration lock remains held between batches");
        verify(cmiReader).readPlayers(0, 10);

        workers.remove().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        verify(cmiReader).readPlayers(10, 10);
        verify(checkpoint).complete();
        assertFalse(MigrationLock.isLocked(), "the final batch releases the migration lock");
    }

    @Test
    void resumeSchedulerFailureReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        runGlobalTasksImmediately(plugin, server);
        when(server.getAsyncScheduler()).thenReturn(null);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        stubCmiQuiescence(config);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        when(checkpoint.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(checkpoint.getSourceFingerprint()).thenReturn("test-cmi-source-fingerprint");
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class),
                mock(EconomyFacade.class), successfulDatabase(), null, null, checkpoint, null, successfulDisabler());

        assertTrue(task.resume());

        verify(checkpoint).fail();
        assertFalse(MigrationLock.isLocked(), "a failed resume schedule must release the economy lock");
        assertFalse(MigrationLock.isEnabled(), "a failed resume schedule must release its lock feature state");
    }

    private static void invokeMigratePlayer(MigrationTask task, CMIDatabaseReader.CMIPlayerData player) throws Exception {
        Method migratePlayer = MigrationTask.class.getDeclaredMethod("migratePlayer",
                CMIDatabaseReader.CMIPlayerData.class, boolean.class);
        migratePlayer.setAccessible(true);
        migratePlayer.invoke(task, player, true);
    }

    private static CMIEconomyDisabler successfulDisabler() {
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);
        when(disabler.disableEconomyCommands()).thenReturn(true);
        when(disabler.disableEconomyModule()).thenReturn(true);
        when(disabler.reloadCMIAndReport()).thenReturn(true);
        return disabler;
    }

    private static void stubContentFingerprint(CMIDatabaseReader reader) {
        stubContentFingerprint(reader, 1);
    }

    private static void stubContentFingerprint(CMIDatabaseReader reader, int rowCount) {
        when(reader.getSourceSnapshotFingerprint())
                .thenReturn(new MigrationSourceFingerprint.Result(rowCount, "test-cmi-source-fingerprint"));
    }

    private static void stubCmiQuiescence(SyncmoneyConfig config) {
        when(config.isDatabaseEnabled()).thenReturn(true);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(true);
    }

    private static DatabaseManager successfulDatabase() {
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.tryReplacePlayerForMigration(any(UUID.class), any(), any(), anyLong(), any()))
                .thenReturn(true);
        return database;
    }

    private static void runGlobalTasksImmediately(Plugin plugin, Server server) {
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
    }

    private static void runAsyncTasksImmediately(AsyncScheduler asyncScheduler) {
        doAnswer(invocation -> {
            java.util.function.Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
    }
}
