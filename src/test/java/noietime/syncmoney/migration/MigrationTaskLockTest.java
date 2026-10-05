package noietime.syncmoney.migration;

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
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MigrationTaskLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void lockRejectsExternalAdminWritesButAllowsMigrationWorkerWrite() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        UUID account = UUID.randomUUID();
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
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
            MigrationLock.enable();
            assertTrue(MigrationLock.lock());

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
            verify(localHandler, never()).deposit(account, null, new BigDecimal("10.00"), "VAULT_DEPOSIT");
            verify(localHandler, never()).withdraw(account, null, new BigDecimal("10.00"), "VAULT_WITHDRAW");
            verify(localHandler, never()).deposit(account, null, new BigDecimal("10.00"), "PLUGIN_DEPOSIT");
            verify(localHandler, never()).withdraw(account, null, new BigDecimal("10.00"), "PLUGIN_WITHDRAW");

            invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(account, "account",
                    new BigDecimal("150.00"), 0L));

            assertEquals(new BigDecimal("150.00"), facade.getMemoryState(account).balance());
            verify(localHandler).setBalance(account, null, new BigDecimal("150.00"), "ADMIN_SET");
            verify(checkpoint).addBackupAmount(new BigDecimal("150.00"));
            assertTrue(MigrationLock.isLocked(), "migration remains locked until migration completion");
            assertTrue(triedReentrantWrite.get(), "the migration write must exercise the nested external-write guard");
        } finally {
            facade.shutdown();
        }
    }

    @Test
    void migrationAbortsBeforeSnapshotWhileProducerIsPausedInsidePreEvent() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
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
        when(cmiReader.testConnection()).thenReturn(true);
        RedisManager migrationRedis = mock(RedisManager.class);
        when(migrationRedis.isConnected()).thenReturn(true);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, null, migrationRedis,
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
            verify(cmiReader, never()).getTotalPlayerCount();
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
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(0);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        EconomyFacade facade = mock(EconomyFacade.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, null, redis,
                null, mock(MigrationCheckpoint.class), null, null);

        task.start(false, false);

        assertTrue(!MigrationLock.isLocked(), "a migration that exits before starting must not leave writes locked");
        assertTrue(!MigrationLock.isEnabled(), "a completed no-data attempt must release its lock feature state");
    }

    @Test
    void sourceCountFailureReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenThrow(new IllegalStateException("source count failed"));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), null,
                redis, null, mock(MigrationCheckpoint.class), null, null);

        assertThrows(IllegalStateException.class, () -> task.start(false, false));

        assertFalse(MigrationLock.isLocked(), "a failed startup must not leave writes locked");
        assertFalse(MigrationLock.isEnabled(), "a failed startup must release its lock feature state");
    }

    @Test
    void workerReadFailureReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.AsyncScheduler asyncScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        doAnswer(invocation -> {
            java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> action =
                    invocation.getArgument(1);
            action.accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        when(cmiReader.readPlayers(0, 10)).thenThrow(new IllegalStateException("source read failed"));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), null,
                redis, null, checkpoint, null, null);

        task.start(false, false);

        verify(checkpoint).fail();
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
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class),
                mock(EconomyFacade.class), null, null, null, checkpoint, null, null);

        assertTrue(task.resume());

        assertTrue(MigrationLock.isLocked(), "resuming after restart must reacquire the configured lock");
        verify(asyncScheduler).runNow(any(Plugin.class), any());
    }

    @Test
    void startSchedulerFailureReleasesMigrationLock() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskLockTest"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(null);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), null,
                redis, null, checkpoint, null, null);

        assertThrows(NullPointerException.class, () -> task.start(false, false));

        verify(checkpoint).fail();
        assertFalse(MigrationLock.isLocked(), "a failed worker schedule must release the economy lock");
        assertFalse(MigrationLock.isEnabled(), "a failed worker schedule must release its lock feature state");
    }

    @Test
    void explicitStopKeepsTheLockForAResumablePause() {
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
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class), null,
                redis, null, checkpoint, null, null);

        task.start(false, false);
        task.stop();
        worker.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        org.mockito.Mockito.verify(checkpoint, org.mockito.Mockito.atLeastOnce()).pause();
        verify(checkpoint, never()).complete();
        verify(cmiReader, never()).readPlayers(0, 10);
        assertTrue(MigrationLock.isLocked(), "an in-process resumable pause must continue blocking external writes");
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
        doAnswer(invocation -> {
            worker.set(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        UUID account = UUID.randomUUID();
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(2);
        when(cmiReader.readPlayers(0, 10)).thenReturn(List.of(new CMIDatabaseReader.CMIPlayerData(
                account, "stop-mid-batch", new BigDecimal("25.00"), 0L)));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        AtomicReference<MigrationTask> taskRef = new AtomicReference<>();
        org.mockito.Mockito.when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> {
                    taskRef.get().stop();
                    return invocation.getArgument(1);
                });
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, null,
                redis, null, checkpoint, null, null);
        taskRef.set(task);

        task.start(false, false);
        worker.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        org.mockito.Mockito.verify(checkpoint, org.mockito.Mockito.atLeastOnce()).pause();
        verify(checkpoint).updateProgress(1, 1);
        verify(checkpoint, never()).complete();
        assertTrue(MigrationLock.isLocked(), "stopping at a batch boundary retains the lock for resume");
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
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class),
                mock(EconomyFacade.class), null, null, null, checkpoint, null, null);

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
        doAnswer(invocation -> null).when(globalScheduler).run(any(Plugin.class), any());
        ArrayDeque<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> workers =
                new ArrayDeque<>();
        doAnswer(invocation -> {
            workers.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
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
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, null,
                redis, null, checkpoint, null, null);

        task.start(false, false);
        assertEquals(1, workers.size(), "starting migration schedules the first batch");
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
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(null);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.resume()).thenReturn(true);
        when(checkpoint.getState()).thenReturn(MigrationCheckpoint.MigrationState.PAUSED);
        MigrationTask task = new MigrationTask(plugin, config, mock(CMIDatabaseReader.class),
                mock(EconomyFacade.class), null, null, null, checkpoint, null, null);

        assertThrows(NullPointerException.class, task::resume);

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
}
