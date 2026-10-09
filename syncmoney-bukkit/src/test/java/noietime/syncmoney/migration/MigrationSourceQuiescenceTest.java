package noietime.syncmoney.migration;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationSourceQuiescenceTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    private static void stubContentFingerprint(CMIDatabaseReader reader) {
        when(reader.getSourceSnapshotFingerprint())
                .thenReturn(new MigrationSourceFingerprint.Result(1, "test-cmi-source-fingerprint"));
    }

    @Test
    void cmiSnapshotWaitsUntilNativeEconomyIsDisabledAndReloaded() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceQuiescenceTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        AtomicReference<Runnable> globalTask = new AtomicReference<>();
        AtomicReference<Consumer<ScheduledTask>> asyncTask = new AtomicReference<>();
        doAnswer(invocation -> {
            globalTask.set(invocation.getArgument(1));
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            asyncTask.set(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(true);

        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);
        when(disabler.disableEconomyCommands()).thenReturn(true);
        when(disabler.disableEconomyModule()).thenReturn(true);
        when(disabler.reloadCMIAndReport()).thenReturn(true);

        MigrationTask task = new MigrationTask(plugin, config, cmiReader, mock(EconomyFacade.class),
                mock(DatabaseManager.class), redis, mock(NameResolver.class), mock(MigrationCheckpoint.class),
                null, disabler);

        task.start(false, false);

        verify(cmiReader, never()).getSourceSnapshotFingerprint();
        assertNotNull(globalTask.get(), "CMI quiescence must be scheduled before taking a source snapshot");

        globalTask.get().run();

        verify(disabler).disableEconomyCommands();
        verify(disabler).disableEconomyModule();
        verify(disabler).reloadCMIAndReport();
        assertNotNull(asyncTask.get(), "snapshot database reads must be scheduled off the global thread");
        asyncTask.get().accept(mock(ScheduledTask.class));
        verify(cmiReader).getSourceSnapshotFingerprint();
    }

    @Test
    void migrationAbortsBeforeReadingWhenCmiEconomyAutoDisableIsOff() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceQuiescenceTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(false);
        CMIDatabaseReader reader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(reader);
        when(reader.testConnection()).thenReturn(true);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);

        MigrationTask task = new MigrationTask(plugin, config, reader, mock(EconomyFacade.class),
                mock(DatabaseManager.class), redis, mock(NameResolver.class),
                mock(MigrationCheckpoint.class), null, disabler);

        task.start(false, false);

        verify(reader, never()).getSourceSnapshotFingerprint();
        verify(disabler, never()).disableEconomyCommands();
        verify(disabler, never()).disableEconomyModule();
        verify(disabler, never()).reloadCMIAndReport();
        assertFalse(task.isRunning());
        assertFalse(MigrationLock.isLocked());
    }

    @Test
    void cmiAuthorityMutationStopsCopyBeforeTheNextSyncmoneyBalanceWrite() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceQuiescenceTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        AtomicReference<Runnable> globalTask = new AtomicReference<>();
        ArrayDeque<Consumer<ScheduledTask>> asyncTasks = new ArrayDeque<>();
        doAnswer(invocation -> {
            globalTask.set(invocation.getArgument(1));
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().isMigrationLockEconomy()).thenReturn(true);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(true);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        UUID account = UUID.randomUUID();
        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(cmiReader);
        when(cmiReader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(cmiReader.testConnection()).thenReturn(true);
        when(cmiReader.getTotalPlayerCount()).thenReturn(1);
        when(cmiReader.readPlayers(0, 10)).thenReturn(List.of(new CMIDatabaseReader.CMIPlayerData(
                account, "changed-during-copy", new BigDecimal("25.00"), 0L)));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);
        when(disabler.disableEconomyCommands()).thenReturn(true);
        when(disabler.disableEconomyModule()).thenReturn(true);
        when(disabler.reloadCMIAndReport()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade,
                mock(DatabaseManager.class), redis, mock(NameResolver.class), checkpoint, null, disabler);

        task.start(false, false);
        globalTask.get().run();
        MigrationLock.recordCmiAuthorityMutation();
        asyncTasks.remove().accept(mock(ScheduledTask.class));
        asyncTasks.remove().accept(mock(ScheduledTask.class));

        verify(facade, never()).setBalance(any(UUID.class), any(BigDecimal.class), any());
        verify(checkpoint).fail();
        verify(checkpoint, never()).complete();
        assertNotNull(asyncTasks);
    }

    @Test
    void emptyPageBeforeExpectedOffsetCannotCompleteMigration() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceQuiescenceTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        AtomicReference<Runnable> globalTask = new AtomicReference<>();
        ArrayDeque<Consumer<ScheduledTask>> asyncTasks = new ArrayDeque<>();
        doAnswer(invocation -> {
            globalTask.set(invocation.getArgument(1));
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(true);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        CMIDatabaseReader reader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(reader);
        when(reader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(reader.testConnection()).thenReturn(true);
        when(reader.getTotalPlayerCount()).thenReturn(1);
        when(reader.readPlayers(0, 10)).thenReturn(List.of());
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);
        when(disabler.disableEconomyCommands()).thenReturn(true);
        when(disabler.disableEconomyModule()).thenReturn(true);
        when(disabler.reloadCMIAndReport()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, reader, mock(EconomyFacade.class),
                mock(DatabaseManager.class), redis, mock(NameResolver.class), checkpoint, null, disabler);

        task.start(false, false);
        globalTask.get().run();
        asyncTasks.remove().accept(mock(ScheduledTask.class)); // count and initialize checkpoint
        asyncTasks.remove().accept(mock(ScheduledTask.class)); // read incomplete first page

        verify(checkpoint, never()).complete();
        verify(checkpoint).pause();
        assertFalse(task.isRunning());
    }

    @Test
    void failedPlayerDoesNotAdvanceTheCheckpointPastItsRetryOffset() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceQuiescenceTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        AtomicReference<Runnable> globalTask = new AtomicReference<>();
        ArrayDeque<Consumer<ScheduledTask>> asyncTasks = new ArrayDeque<>();
        doAnswer(invocation -> {
            globalTask.set(invocation.getArgument(1));
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(true);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        UUID account = UUID.randomUUID();
        CMIDatabaseReader reader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(reader);
        when(reader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(reader.testConnection()).thenReturn(true);
        when(reader.getTotalPlayerCount()).thenReturn(1);
        when(reader.readPlayers(0, 10)).thenReturn(List.of(new CMIDatabaseReader.CMIPlayerData(
                account, "failed-row", new BigDecimal("25.00"), 0L)));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);
        when(disabler.disableEconomyCommands()).thenReturn(true);
        when(disabler.disableEconomyModule()).thenReturn(true);
        when(disabler.reloadCMIAndReport()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        when(checkpoint.getFailedCount()).thenReturn(1);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any())).thenReturn(null);
        MigrationTask task = new MigrationTask(plugin, config, reader, facade,
                null, redis, mock(NameResolver.class), checkpoint, null, disabler);

        task.start(false, false);
        globalTask.get().run();
        asyncTasks.remove().accept(mock(ScheduledTask.class));
        asyncTasks.remove().accept(mock(ScheduledTask.class));

        verify(checkpoint, never()).updateProgress(anyInt(), anyInt());
        verify(checkpoint, never()).complete();
        verify(checkpoint).pause();
        assertFalse(task.isRunning());
    }

    @Test
    void secondRedisKeyFailureKeepsMigrationCheckpointIncomplete() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceQuiescenceTest"));
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        AtomicReference<Runnable> globalTask = new AtomicReference<>();
        ArrayDeque<Consumer<ScheduledTask>> asyncTasks = new ArrayDeque<>();
        doAnswer(invocation -> {
            globalTask.set(invocation.getArgument(1));
            return null;
        }).when(globalScheduler).execute(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return null;
        }).when(asyncScheduler).runNow(any(Plugin.class), any());
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isCMIAutoDisableCommands()).thenReturn(true);
        when(config.migration().isCMIAutoDisableEconomy()).thenReturn(true);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(true);
        when(config.getServerName()).thenReturn("test-server");
        UUID account = UUID.randomUUID();
        CMIDatabaseReader reader = mock(CMIDatabaseReader.class);
        stubContentFingerprint(reader);
        when(reader.getSourceIdentity()).thenReturn("cmi-single|unit-test");
        when(reader.testConnection()).thenReturn(true);
        when(reader.getTotalPlayerCount()).thenReturn(1);
        when(reader.readPlayers(0, 10)).thenReturn(List.of(new CMIDatabaseReader.CMIPlayerData(
                account, "redis-failure", new BigDecimal("75.00"), 0L)));
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        redis.clients.jedis.Jedis jedis = mock(redis.clients.jedis.Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.set("syncmoney:balance:" + account, "75.00")).thenReturn("OK");
        when(jedis.set("syncmoney:version:" + account, "1"))
                .thenThrow(new IllegalStateException("version key write failed"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        when(facade.setBalance(any(UUID.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        CMIEconomyDisabler disabler = mock(CMIEconomyDisabler.class);
        when(disabler.disableEconomyCommands()).thenReturn(true);
        when(disabler.disableEconomyModule()).thenReturn(true);
        when(disabler.reloadCMIAndReport()).thenReturn(true);
        MigrationTask task = new MigrationTask(plugin, config, reader, facade,
                null, redis, mock(NameResolver.class), checkpoint, null, disabler);

        task.start(false, false);
        globalTask.get().run();
        asyncTasks.remove().accept(mock(ScheduledTask.class)); // establish the pinned source checkpoint
        asyncTasks.remove().accept(mock(ScheduledTask.class)); // balance SET succeeds, version SET fails

        verify(jedis).set("syncmoney:balance:" + account, "75.00");
        verify(jedis).set("syncmoney:version:" + account, "1");
        verify(checkpoint).recordFailure(org.mockito.ArgumentMatchers.eq("redis-failure"),
                org.mockito.ArgumentMatchers.contains("Redis population failed"));
        verify(checkpoint).pause();
        verify(checkpoint, never()).complete();
        assertFalse(task.isRunning());
    }
}
