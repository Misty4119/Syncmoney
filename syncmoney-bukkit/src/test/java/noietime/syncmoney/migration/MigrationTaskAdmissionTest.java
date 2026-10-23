package noietime.syncmoney.migration;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.economy.OverflowLogInterface;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MigrationTaskAdmissionTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void startRejectsMissingSharedSqlTargetBeforeReadingCmiOrTouchingRedis() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskAdmissionTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDatabaseEnabled()).thenReturn(false);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);

        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        RedisManager redisManager = mock(RedisManager.class);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, null,
                redisManager, null, checkpoint, null, null);

        task.start(false, false);

        assertFalse(task.isRunning());
        verify(cmiReader, never()).testConnection();
        verify(cmiReader, never()).getSourceSnapshotFingerprint();
        verify(facade, never()).setBalance(any(UUID.class), any(BigDecimal.class), any());
        verify(redisManager, never()).isConnected();
        verify(redisManager, never()).getResource();
        verifyNoInteractions(checkpoint);
    }

    @Test
    void resumeRejectsMissingSharedSqlTargetBeforeResumingCheckpointOrTouchingRedis() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskAdmissionTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isDatabaseEnabled()).thenReturn(false);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);

        CMIDatabaseReader cmiReader = mock(CMIDatabaseReader.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        RedisManager redisManager = mock(RedisManager.class);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        MigrationTask task = new MigrationTask(plugin, config, cmiReader, facade, null,
                redisManager, null, checkpoint, null, null);

        assertFalse(task.resume());

        verifyNoInteractions(checkpoint);
        verify(cmiReader, never()).initialize();
        verify(cmiReader, never()).getSourceSnapshotFingerprint();
        verify(facade, never()).setBalance(any(UUID.class), any(BigDecimal.class), any());
        verify(redisManager, never()).isConnected();
        verify(redisManager, never()).getResource();
    }

    @Test
    void rejectedSharedWriteDoesNotAdvanceCheckpointOrPersistMigrationRow() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskAdmissionTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        UUID account = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();
        EconomyWriteQueue queue = new EconomyWriteQueue(1, Logger.getLogger("MigrationTaskAdmissionTest"));
        EconomyEvent occupyingWrite = new EconomyEvent(otherAccount, BigDecimal.ONE,
                new BigDecimal("1.00"), 1L, EconomyEvent.EventType.DEPOSIT,
                EconomyEvent.EventSource.TEST, "occupying-write", 1L);
        assertTrue(queue.offerBlocking(occupyingWrite, 1, TimeUnit.SECONDS));

        DatabaseManager databaseManager = mock(DatabaseManager.class);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        EconomyFacade facade = new EconomyFacade(plugin, config, mock(CacheManager.class),
                mock(RedisManager.class), databaseManager, null, queue,
                mock(FallbackEconomyWrapper.class), mock(PlayerTransactionGuard.class),
                mock(OverflowLogInterface.class));
        MigrationTask task = new MigrationTask(plugin, config, null, facade, databaseManager,
                null, null, checkpoint, null, null);

        try {
            assertTrue(facade.updateMemoryState(account, new BigDecimal("100.00"), 7L));

            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(
                            account, "account", new BigDecimal("150.00"), 0L)));

            assertTrue(failure.getCause() instanceof IllegalStateException,
                    "a rejected balance write must fail this player's migration");
            assertEquals(new BigDecimal("100.00"), facade.getMemoryState(account).balance(),
                    "queue saturation must leave the account memory unchanged");
            verify(checkpoint, never()).addBackupAmount(any(BigDecimal.class));
            verify(databaseManager, never()).insertOrUpdatePlayer(
                    eq(account), anyString(), any(BigDecimal.class), anyLong(), anyString());
        } finally {
            facade.shutdown();
        }
    }

    @Test
    void failedSqlReceiptRejectsMigrationBeforeCheckpointingBackupAmount() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskAdmissionTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);

        UUID account = UUID.randomUUID();
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        when(databaseManager.tryReplacePlayerForMigration(any(UUID.class), anyString(), any(BigDecimal.class),
                anyLong(), nullable(String.class))).thenReturn(false);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        when(facade.setBalance(eq(account), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        MigrationTask task = new MigrationTask(plugin, config, null, facade, databaseManager,
                null, null, checkpoint, null, null);

        assertTrue(task.acquireMigrationLockForExecution());
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(
                        account, "account", new BigDecimal("150.00"), 0L)));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        verify(checkpoint, never()).addBackupAmount(any(BigDecimal.class));
        verify(databaseManager).tryReplacePlayerForMigration(any(UUID.class), anyString(), any(BigDecimal.class),
                anyLong(), nullable(String.class));
    }

    @Test
    void unavailableRedisRejectsMigrationBeforeCheckpointingBackupAmount() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskAdmissionTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(true);

        UUID account = UUID.randomUUID();
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isConnected()).thenReturn(false);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        when(facade.setBalance(eq(account), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        MigrationTask task = new MigrationTask(plugin, config, null, facade, null,
                redisManager, null, checkpoint, null, null);

        assertTrue(task.acquireMigrationLockForExecution());
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(
                        account, "account", new BigDecimal("150.00"), 0L)));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals(1, task.getFailedRedisCount());
        verify(checkpoint, never()).addBackupAmount(any(BigDecimal.class));
    }

    @Test
    void cmiSourceMutationBetweenRedisBalanceAndVersionWritesStopsThePair() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationTaskAdmissionTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getMigrationBatchSize()).thenReturn(10);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(true);

        UUID account = UUID.randomUUID();
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString())).thenAnswer(invocation -> {
            assertTrue(MigrationLock.recordCmiAuthorityMutation());
            return "OK";
        });
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.getBalance(account)).thenReturn(BigDecimal.ZERO);
        when(facade.setBalance(eq(account), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        MigrationTask task = new MigrationTask(plugin, config, null, facade, null,
                redis, null, checkpoint, null, null);

        assertTrue(task.acquireMigrationLockForExecution());
        java.lang.reflect.Field leaseField = MigrationTask.class.getDeclaredField("migrationLockLease");
        leaseField.setAccessible(true);
        assertTrue(((MigrationLock.MigrationLease) leaseField.get(task)).beginSourceSnapshot());

        invokeMigratePlayer(task, new CMIDatabaseReader.CMIPlayerData(
                account, "account", new BigDecimal("150.00"), 0L));

        verify(jedis).set("syncmoney:balance:" + account, "150.00");
        verify(jedis, never()).set("syncmoney:version:" + account, "1");
        verify(checkpoint, never()).addBackupAmount(any(BigDecimal.class));
    }

    private static void invokeMigratePlayer(MigrationTask task,
            CMIDatabaseReader.CMIPlayerData player) throws ReflectiveOperationException {
        Method migratePlayer = MigrationTask.class.getDeclaredMethod("migratePlayer",
                CMIDatabaseReader.CMIPlayerData.class, boolean.class);
        migratePlayer.setAccessible(true);
        migratePlayer.invoke(task, player, true);
    }
}
