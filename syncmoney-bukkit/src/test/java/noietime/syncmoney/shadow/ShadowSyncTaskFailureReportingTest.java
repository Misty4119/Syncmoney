package noietime.syncmoney.shadow;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.shadow.storage.ShadowSyncRecord;
import noietime.syncmoney.shadow.storage.SqliteShadowStorage;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowSyncTaskFailureReportingTest {

    @Test
    void rejectsMismatchedReceiptAndSkipsCmiAndSqlWhenAuthorityAdvancesBeforeFlush(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "all", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        UUID playerId = UUID.randomUUID();
        BigDecimal receiptBalance = new BigDecimal("17.00");
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(receiptBalance, 1L));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class,
                     (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalance(any(UUID.class))).thenAnswer(invocation -> {
                             authorityStates.put(playerId, state(new BigDecimal("20.00"), 4L));
                             return BigDecimal.ONE;
                         });
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, economyFacade(authorityStates), null, databaseManager);
            try {
                task.start();

                task.enqueueSyncEvent(playerId, "SyntheticPlayer", receiptBalance, 2L);
                assertEquals(0, task.getQueueSize(), "a mismatched receipt must be rejected at admission");

                task.enqueueSyncEvent(playerId, "SyntheticPlayer", receiptBalance, 1L);
                assertEquals(1, task.getQueueSize());
                authorityStates.put(playerId, state(new BigDecimal("18.00"), 2L));
                flushNow(task);

                authorityStates.put(playerId, state(new BigDecimal("19.00"), 3L));
                task.enqueueSyncEvent(playerId, "SyntheticPlayer", new BigDecimal("19.00"), 3L);
                flushNow(task);

                CMIDatabaseWriter writer = cmiWriters.constructed().get(0);
                verify(writer, never()).readCMIBalanceStrict(any(UUID.class));
                verify(writer).readCMIBalance(playerId);
                verify(writer, never()).batchUpdateBalanceResults(any());
                verify(databaseManager, never()).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
            } finally {
                task.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"throw", "null"})
    void closesInitializedResourcesWhenSharedDatabaseLeaseCannotBeAcquired(
            String failureMode, @TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "all", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        java.util.function.Supplier<? extends AutoCloseable> leaseSupplier = failureMode.equals("throw")
                ? () -> { throw new IllegalStateException("simulated lease failure"); }
                : () -> null;

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class,
                     (writer, context) -> when(writer.testConnection()).thenReturn(true));
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, null, null, databaseManager, leaseSupplier);
            try {
                task.start();

                assertFalse(task.isRunning(), "lease acquisition failure must leave Shadow Sync stopped");
                assertEquals(1, cmiWriters.constructed().size());
                verify(cmiWriters.constructed().get(0)).close();
                assertEquals(1, storages.constructed().size());
                verify(storages.constructed().get(0)).close();
                assertNull(task.getStorage(), "initialized history storage must be released on startup failure");
                verify(databaseManager, never()).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void recordsRejectedLocalSqlWriteAsFailure(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "local", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        UUID playerId = UUID.randomUUID();
        when(databaseManager.tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString())).thenReturn(false);

        try (MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, economyFacade(playerId, new BigDecimal("17.00"), 1L), null, databaseManager);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("17.00"));
                flushNow(task);

                ShadowSyncLog.SyncRecord log = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(log.success());
                assertTrue(log.reason().contains("local SQL write was rejected"));

                assertEquals(1, storages.constructed().size());
                ArgumentCaptor<ShadowSyncRecord> recordCaptor = ArgumentCaptor.forClass(ShadowSyncRecord.class);
                verify(storages.constructed().get(0)).saveRecord(recordCaptor.capture());
                assertFalse(recordCaptor.getValue().isSuccess());
                assertTrue(recordCaptor.getValue().getReason().contains("local SQL write was rejected"));
                verify(databaseManager).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
                verify(databaseManager, never()).insertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void recordsNewerSqlSnapshotMismatchAsFailureAfterCompletedWrite(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "local", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        UUID playerId = UUID.randomUUID();
        when(databaseManager.tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString())).thenReturn(true);
        when(databaseManager.getPlayerStrict(playerId)).thenReturn(Optional.of(new DatabaseManager.PlayerRecord(
                playerId, "AcceptanceShadow", new BigDecimal("99.00"), 75L, "newer-server", Instant.now())));

        try (MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, economyFacade(playerId, new BigDecimal("17.00"), 74L), null, databaseManager);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("17.00"));
                flushNow(task);

                ShadowSyncLog.SyncRecord log = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(log.success(),
                        "a strict readback with a newer mismatching SQL snapshot must produce a failed Shadow Sync result");
                assertTrue(log.reason().contains(
                        "local SQL readback did not match the expected authoritative snapshot"));
                verify(databaseManager).getPlayerStrict(playerId);
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void localSqlUsesAuthorityVersionsAndOlderSnapshotCannotOverwriteNewerRow(@TempDir Path tempDir)
            throws Exception {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "local", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("17.00"), 4L));

        AtomicLong persistedVersion = new AtomicLong(3L);
        AtomicReference<BigDecimal> persistedBalance = new AtomicReference<>(new BigDecimal("12.00"));
        when(databaseManager.tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString())).thenAnswer(invocation -> {
                    long incomingVersion = invocation.getArgument(3);
                    if (incomingVersion <= persistedVersion.get()) {
                        return false;
                    }
                    persistedVersion.set(incomingVersion);
                    persistedBalance.set(invocation.getArgument(2));
                    return true;
                });
        when(databaseManager.getPlayerStrict(playerId)).thenAnswer(invocation -> Optional.of(
                new DatabaseManager.PlayerRecord(playerId, "AcceptanceShadow", persistedBalance.get(),
                        persistedVersion.get(), "test-server", Instant.now())));

        try (MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, economyFacade(authorityStates), null, databaseManager);
            try {
                task.start();

                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("17.00"), 4L);
                flushNow(task);
                assertEquals(4L, persistedVersion.get());
                assertEquals(new BigDecimal("17.00"), persistedBalance.get());

                authorityStates.put(playerId, state(new BigDecimal("23.00"), 5L));
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("23.00"), 5L);
                flushNow(task);
                assertEquals(5L, persistedVersion.get());
                assertEquals(new BigDecimal("23.00"), persistedBalance.get());

                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("17.00"), 4L);
                flushNow(task);
                assertEquals(5L, persistedVersion.get(), "an older SQL version must not replace the newer row");
                assertEquals(new BigDecimal("23.00"), persistedBalance.get());

                ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
                verify(databaseManager, times(2)).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), versionCaptor.capture(), anyString());
                assertEquals(List.of(4L, 5L), versionCaptor.getAllValues());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void allTargetContinuesLocalSyncWhenCmiWriterIsUnavailable(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = plugin(tempDir);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        SyncmoneyConfig config = config(tempDir, "all", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        UUID playerId = UUID.randomUUID();
        when(databaseManager.tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString())).thenReturn(true);
        when(databaseManager.getPlayerStrict(playerId)).thenReturn(Optional.of(new DatabaseManager.PlayerRecord(
                playerId, "AcceptanceShadow", new BigDecimal("17.00"), 1L, "test-server", Instant.now())));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> when(writer.testConnection()).thenReturn(false));
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, economyFacade(playerId, new BigDecimal("17.00"), 1L), null, databaseManager);
            try {
                task.start();
                verify(logger).severe(
                        "CMI mirroring remains inactive until plugin restart; continuing with local SQL only.");
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("17.00"));

                assertTrue(task.isRunning(), "the available local target must continue after CMI failure");
                assertEquals(1, task.getQueueSize());
                assertEquals(1, storages.constructed().size(), "history storage must start for the local sink");
                assertEquals(1, cmiWriters.constructed().size());
                verify(cmiWriters.constructed().get(0)).close();

                flushNow(task);

                assertEquals(0, task.getQueueSize());
                ShadowSyncLog.SyncRecord log = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(log.success(), "the aggregate 'all' target must report its unavailable CMI sink");
                assertTrue(log.reason().contains("CMI writer unavailable"));
                verify(databaseManager).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
                ArgumentCaptor<ShadowSyncRecord> recordCaptor = ArgumentCaptor.forClass(ShadowSyncRecord.class);
                verify(storages.constructed().get(0)).saveRecord(recordCaptor.capture());
                assertFalse(recordCaptor.getValue().isSuccess());
                assertTrue(recordCaptor.getValue().getReason().contains("CMI writer unavailable"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void cmiOnlyTargetFailsClosedWhenWriterIsUnavailable(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 1);
        DatabaseManager databaseManager = mock(DatabaseManager.class);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> when(writer.testConnection()).thenReturn(false));
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, null, null, databaseManager);
            try {
                task.start();
                task.enqueueSyncEvent(UUID.randomUUID(), "AcceptanceShadow", BigDecimal.ONE);

                assertFalse(task.isRunning(), "CMI-only mode cannot run without its authoritative target");
                assertEquals(0, task.getQueueSize());
                assertNull(task.getStorage());
                assertTrue(task.getSyncLog().getRecentLogs(10).isEmpty());
                assertEquals(1, cmiWriters.constructed().size());
                verify(cmiWriters.constructed().get(0)).close();
                assertTrue(storages.constructed().isEmpty());
                verify(databaseManager, never()).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void allTargetFailsWhenCmiCannotConfirmEvenIfLocalSqlSucceeds(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "all", 100);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        UUID playerId = UUID.randomUUID();
        when(databaseManager.tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString())).thenReturn(true);
        when(databaseManager.getPlayerStrict(playerId)).thenReturn(Optional.of(new DatabaseManager.PlayerRecord(
                playerId, "AcceptanceShadow", new BigDecimal("17.00"), 1L, "test-server", Instant.now())));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalance(any(UUID.class))).thenReturn(new BigDecimal("1.00"));
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(false));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(
                    plugin, config, economyFacade(playerId, new BigDecimal("17.00"), 1L), null, databaseManager);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("17.00"));
                flushNow(task);

                ShadowSyncLog.SyncRecord log = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(log.success());
                assertTrue(log.reason().contains("CMI balance was not confirmed after batch commit"));
                verify(databaseManager).tryInsertOrUpdatePlayer(
                        any(), anyString(), any(), anyLong(), anyString());
                verify(databaseManager).getPlayerStrict(playerId);
                verify(cmiWriters.constructed().get(0)).batchUpdateBalanceResults(any());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void coalescesRepeatedPlayerSnapshotsAndWritesOnlyTheLatestBalance(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("5.00"), 1L));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalance(any(UUID.class))).thenReturn(new BigDecimal("1.00"));
                         when(writer.batchUpdateBalanceResults(any())).thenAnswer(invocation ->
                                 java.util.Collections.nCopies(
                                         ((List<?>) invocation.getArgument(0)).size(), true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade(authorityStates), null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceRepeated", new BigDecimal("5.00"), 1L);
                authorityStates.put(playerId, state(new BigDecimal("7.00"), 2L));
                task.enqueueSyncEvent(playerId, "AcceptanceRepeated", new BigDecimal("7.00"), 2L);
                flushNow(task);

                List<ShadowSyncLog.SyncRecord> results = task.getSyncLog().getRecentLogs(10);
                assertEquals(1, results.size());
                assertTrue(results.get(0).success());
                assertEquals("7.00", results.get(0).syncmoneyBalance());
                verify(cmiWriters.constructed().get(0)).batchUpdateBalanceResults(
                        org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 1
                                && batch.get(0).uuid().equals(playerId)
                                && batch.get(0).balance().compareTo(new BigDecimal("7.00")) == 0));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void coalescedPositiveTailIsCheckedAgainstInitialCmiBalance(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("100.00"), 1L));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("100.00")));
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade(authorityStates), null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceRollbackTail", new BigDecimal("100.00"), 1L);
                authorityStates.put(playerId, state(BigDecimal.ONE, 2L));
                task.enqueueSyncEvent(playerId, "AcceptanceRollbackTail", BigDecimal.ONE, 2L);
                flushNow(task);

                verify(cmiWriters.constructed().get(0)).readCMIBalanceStrict(playerId);
                verify(cmiWriters.constructed().get(0), never()).batchUpdateBalanceResults(any());
                ShadowSyncLog.SyncRecord result = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(result.success());
                assertTrue(result.reason().contains("rollback protection"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void allowsSinglePositiveFirstCmiWriteWhenCmiAccountIsAbsent(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("1000.00"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.notFound());
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceFirstPositive", new BigDecimal("1000.00"));
                flushNow(task);

                verify(cmiWriters.constructed().get(0)).readCMIBalanceStrict(playerId);
                verify(cmiWriters.constructed().get(0)).batchUpdateBalanceResults(
                        org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 1
                                && batch.get(0).balance().compareTo(new BigDecimal("1000.00")) == 0));
                ShadowSyncLog.SyncRecord result = task.getSyncLog().getRecentLogs(1).get(0);
                assertTrue(result.success());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void blocksSnapshotWhenExternalCmiBalanceChangeExceedsRollbackThreshold(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("100.00"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("100.00")),
                                 CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("300.00")));
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceExternalChange", new BigDecimal("100.00"));
                flushNow(task);
                task.enqueueSyncEvent(playerId, "AcceptanceExternalChange", new BigDecimal("100.00"));
                flushNow(task);

                CMIDatabaseWriter writer = cmiWriters.constructed().get(0);
                verify(writer, times(2)).readCMIBalanceStrict(playerId);
                ArgumentCaptor<List<CMIDatabaseWriter.PlayerBalance>> batches =
                        ArgumentCaptor.forClass(List.class);
                verify(writer).batchUpdateBalanceResults(batches.capture());
                assertEquals(1, batches.getValue().size(), "only the first snapshot may reach the CMI writer");

                List<ShadowSyncLog.SyncRecord> results = task.getSyncLog().getRecentLogs(10);
                assertTrue(results.stream().anyMatch(result -> result.success()
                        && result.playerName().equals("AcceptanceExternalChange")));
                ShadowSyncLog.SyncRecord blocked = results.stream()
                        .filter(result -> !result.success()
                                && result.playerName().equals("AcceptanceExternalChange"))
                        .findFirst().orElseThrow();
                assertTrue(blocked.reason().contains("rollback protection"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void blocksSnapshotWhenCmiBalanceReadFailsAfterBaselineWasEstablished(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("100.00"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("100.00")),
                                 CMIDatabaseWriter.BalanceReadResult.failed());
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceReadFailure", new BigDecimal("100.00"));
                flushNow(task);
                task.enqueueSyncEvent(playerId, "AcceptanceReadFailure", new BigDecimal("100.00"));
                flushNow(task);

                CMIDatabaseWriter writer = cmiWriters.constructed().get(0);
                verify(writer, times(2)).readCMIBalanceStrict(playerId);
                ArgumentCaptor<List<CMIDatabaseWriter.PlayerBalance>> batches =
                        ArgumentCaptor.forClass(List.class);
                verify(writer).batchUpdateBalanceResults(batches.capture());
                assertEquals(1, batches.getValue().size(), "the failed-read snapshot must not be written");

                List<ShadowSyncLog.SyncRecord> results = task.getSyncLog().getRecentLogs(10);
                assertTrue(results.stream().anyMatch(result -> result.success()
                        && result.playerName().equals("AcceptanceReadFailure")));
                ShadowSyncLog.SyncRecord blocked = results.stream()
                        .filter(result -> !result.success()
                                && result.playerName().equals("AcceptanceReadFailure"))
                        .findFirst().orElseThrow();
                assertTrue(blocked.reason().contains("CMI balance read failed"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void blocksMissingCmiAccountAfterRollbackBaselineWasEstablished(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("100.00"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("100.00")),
                                 CMIDatabaseWriter.BalanceReadResult.notFound());
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceMissingAfterBaseline", new BigDecimal("100.00"));
                flushNow(task);
                task.enqueueSyncEvent(playerId, "AcceptanceMissingAfterBaseline", new BigDecimal("100.00"));
                flushNow(task);

                CMIDatabaseWriter writer = cmiWriters.constructed().get(0);
                verify(writer, times(2)).readCMIBalanceStrict(playerId);
                ArgumentCaptor<List<CMIDatabaseWriter.PlayerBalance>> batches =
                        ArgumentCaptor.forClass(List.class);
                verify(writer).batchUpdateBalanceResults(batches.capture());
                assertEquals(1, batches.getValue().size(), "NOT_FOUND is allowed only for an initial baseline");

                ShadowSyncLog.SyncRecord blocked = task.getSyncLog().getRecentLogs(10).stream()
                        .filter(result -> !result.success()
                                && result.playerName().equals("AcceptanceMissingAfterBaseline"))
                        .findFirst().orElseThrow();
                assertTrue(blocked.reason().contains("disappeared after rollback baseline"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void blocksNegativeFirstCmiWriteWhenCmiAccountIsAbsent(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("-1.00"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.notFound());
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceFirstNegative", new BigDecimal("-1.00"));
                flushNow(task);

                verify(cmiWriters.constructed().get(0)).readCMIBalanceStrict(playerId);
                verify(cmiWriters.constructed().get(0), never()).batchUpdateBalanceResults(any());
                ShadowSyncLog.SyncRecord result = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(result.success());
                assertTrue(result.reason().contains("rollback protection"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void blocksSinglePositiveFirstCmiWriteWhenInitialBalanceIsUnreadable(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("25.00"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.failed());
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceUnreadablePositive", new BigDecimal("25.00"));
                flushNow(task);

                verify(cmiWriters.constructed().get(0)).readCMIBalanceStrict(playerId);
                verify(cmiWriters.constructed().get(0), never()).batchUpdateBalanceResults(any());
                ShadowSyncLog.SyncRecord result = task.getSyncLog().getRecentLogs(1).get(0);
                assertFalse(result.success());
                assertTrue(result.reason().contains("CMI balance read failed"));
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void allowsCoalescedPositiveTailWithinRollbackThreshold(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("130.00"), 2L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(playerId)).thenReturn(
                                 CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("100.00")));
                         when(writer.batchUpdateBalanceResults(any())).thenReturn(List.of(true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceRollbackWithin", new BigDecimal("100.00"));
                task.enqueueSyncEvent(playerId, "AcceptanceRollbackWithin", new BigDecimal("130.00"));
                flushNow(task);

                verify(cmiWriters.constructed().get(0)).readCMIBalanceStrict(playerId);
                verify(cmiWriters.constructed().get(0)).batchUpdateBalanceResults(
                        org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 1
                                && batch.get(0).balance().compareTo(new BigDecimal("130.00")) == 0));
                ShadowSyncLog.SyncRecord result = task.getSyncLog().getRecentLogs(1).get(0);
                assertTrue(result.success());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void rollbackProtectedCmiEventIsExcludedWhileAllowedPeerSucceeds(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID blockedPlayerId = UUID.randomUUID();
        UUID allowedPlayerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(blockedPlayerId, state(BigDecimal.ZERO, 2L));
        authorityStates.put(allowedPlayerId, state(new BigDecimal("25.00"), 1L));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(any(UUID.class))).thenAnswer(invocation ->
                                 blockedPlayerId.equals(invocation.getArgument(0))
                                         ? CMIDatabaseWriter.BalanceReadResult.found(new BigDecimal("100.00"))
                                         : CMIDatabaseWriter.BalanceReadResult.notFound());
                         when(writer.batchUpdateBalanceResults(any())).thenAnswer(invocation ->
                                 java.util.Collections.nCopies(((List<?>) invocation.getArgument(0)).size(), true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade(authorityStates), null, null);
            try {
                task.start();
                task.enqueueSyncEvent(blockedPlayerId, "AcceptanceBlocked", new BigDecimal("100.00"));
                task.enqueueSyncEvent(blockedPlayerId, "AcceptanceBlocked", BigDecimal.ZERO);
                task.enqueueSyncEvent(allowedPlayerId, "AcceptanceAllowed", new BigDecimal("25.00"));
                flushNow(task);

                assertEquals(1, cmiWriters.constructed().size());
                ArgumentCaptor<List<CMIDatabaseWriter.PlayerBalance>> batches =
                        ArgumentCaptor.forClass(List.class);
                verify(cmiWriters.constructed().get(0)).batchUpdateBalanceResults(batches.capture());
                List<CMIDatabaseWriter.PlayerBalance> acceptedBatch = batches.getValue();
                assertEquals(1, acceptedBatch.size());
                assertEquals("AcceptanceAllowed", acceptedBatch.get(0).name());

                List<ShadowSyncLog.SyncRecord> results = task.getSyncLog().getRecentLogs(10);
                ShadowSyncLog.SyncRecord blockedResult = results.stream()
                        .filter(result -> result.playerName().equals("AcceptanceBlocked") && !result.success())
                        .findFirst().orElseThrow();
                ShadowSyncLog.SyncRecord allowedResult = results.stream()
                        .filter(result -> result.playerName().equals("AcceptanceAllowed"))
                        .findFirst().orElseThrow();
                assertFalse(blockedResult.success());
                assertTrue(blockedResult.reason().contains("rollback protection"));
                assertTrue(allowedResult.success());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void unreadableInitialCmiBalanceFailsClosedWhileAllowedPeerSucceeds(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        when(config.shadowSync().isShadowSyncRollbackProtection()).thenReturn(true);
        when(config.shadowSync().getShadowSyncRollbackThreshold()).thenReturn(0.5d);
        UUID blockedPlayerId = UUID.randomUUID();
        UUID allowedPlayerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(blockedPlayerId, state(BigDecimal.ZERO, 1L));
        authorityStates.put(allowedPlayerId, state(new BigDecimal("25.00"), 1L));

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalanceStrict(any(UUID.class))).thenAnswer(invocation ->
                                 blockedPlayerId.equals(invocation.getArgument(0))
                                         ? CMIDatabaseWriter.BalanceReadResult.failed()
                                         : CMIDatabaseWriter.BalanceReadResult.notFound());
                         when(writer.batchUpdateBalanceResults(any())).thenAnswer(invocation ->
                                 java.util.Collections.nCopies(((List<?>) invocation.getArgument(0)).size(), true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade(authorityStates), null, null);
            try {
                task.start();
                task.enqueueSyncEvent(blockedPlayerId, "AcceptanceUnreadable", BigDecimal.ZERO);
                task.enqueueSyncEvent(allowedPlayerId, "AcceptanceAllowed", new BigDecimal("25.00"));
                flushNow(task);

                ArgumentCaptor<List<CMIDatabaseWriter.PlayerBalance>> batches =
                        ArgumentCaptor.forClass(List.class);
                verify(cmiWriters.constructed().get(0)).batchUpdateBalanceResults(batches.capture());
                List<CMIDatabaseWriter.PlayerBalance> acceptedBatch = batches.getValue();
                assertEquals(1, acceptedBatch.size());
                assertEquals("AcceptanceAllowed", acceptedBatch.get(0).name());

                List<ShadowSyncLog.SyncRecord> results = task.getSyncLog().getRecentLogs(10);
                ShadowSyncLog.SyncRecord blockedResult = results.stream()
                        .filter(result -> result.playerName().equals("AcceptanceUnreadable") && !result.success())
                        .findFirst().orElseThrow();
                ShadowSyncLog.SyncRecord allowedResult = results.stream()
                        .filter(result -> result.playerName().equals("AcceptanceAllowed"))
                        .findFirst().orElseThrow();
                assertTrue(blockedResult.reason().contains("rollback protection could not be verified"));
                assertTrue(allowedResult.success());
            } finally {
                task.stop();
            }
        }
    }

    @Test
    void serializesConcurrentFlushRequestsSoOlderBatchCannotOverwriteNewerSnapshot(@TempDir Path tempDir)
            throws InterruptedException {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 1);
        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("5.00"), 1L));
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondWriteFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<BigDecimal> persistedWrites = new CopyOnWriteArrayList<>();

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalance(any(UUID.class))).thenReturn(new BigDecimal("1.00"));
                         when(writer.batchUpdateBalanceResults(any())).thenAnswer(invocation -> {
                             List<CMIDatabaseWriter.PlayerBalance> batch = invocation.getArgument(0);
                             int call = calls.incrementAndGet();
                             if (call == 1) {
                                 firstWriteStarted.countDown();
                                 if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                                     throw new IllegalStateException("Timed out waiting to release first CMI write");
                                 }
                             }
                             batch.stream().map(CMIDatabaseWriter.PlayerBalance::balance).forEach(persistedWrites::add);
                             if (call == 2) {
                                 secondWriteFinished.countDown();
                             }
                             return java.util.Collections.nCopies(batch.size(), true);
                         });
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade(authorityStates), null, null);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceOrdered", new BigDecimal("5.00"));
                assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));

                authorityStates.put(playerId, state(new BigDecimal("7.00"), 2L));
                task.enqueueSyncEvent(playerId, "AcceptanceOrdered", new BigDecimal("7.00"));
                releaseFirstWrite.countDown();

                assertTrue(secondWriteFinished.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(new BigDecimal("5.00"), new BigDecimal("7.00")), persistedWrites);
            } finally {
                releaseFirstWrite.countDown();
                task.stop();
            }
        }
    }

    @Test
    void stopDrainsBelowThresholdEventBeforeClosingCmiAndHistoryResources(@TempDir Path tempDir) {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 100);
        UUID playerId = UUID.randomUUID();
        EconomyFacade authority = economyFacade(playerId, new BigDecimal("9.25"), 1L);

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalance(any(UUID.class))).thenReturn(new BigDecimal("1.00"));
                         when(writer.batchUpdateBalanceResults(any())).thenAnswer(invocation ->
                                 java.util.Collections.nCopies(((List<?>) invocation.getArgument(0)).size(), true));
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, authority, null, null);
            task.start();
            task.enqueueSyncEvent(playerId, "AcceptanceShutdown", new BigDecimal("9.25"));

            assertEquals(1, task.getQueueSize(), "below-threshold event should wait for the shutdown drain");
            task.stop();

            CMIDatabaseWriter writer = cmiWriters.constructed().get(0);
            SqliteShadowStorage storage = storages.constructed().get(0);
            InOrder closeOrder = org.mockito.Mockito.inOrder(writer, storage);
            closeOrder.verify(writer).batchUpdateBalanceResults(any());
            closeOrder.verify(storage).saveRecord(any());
            closeOrder.verify(writer).close();
            closeOrder.verify(storage).close();
            assertEquals(0, task.getQueueSize());
        }
    }

    @Test
    void stopWaitsForActiveWriteThenDrainsPreviouslyAcceptedEventBeforeClosingResources(@TempDir Path tempDir)
            throws Exception {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "cmi", 1);
        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("5.00"), 1L));
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<BigDecimal> persistedWrites = new CopyOnWriteArrayList<>();
        ExecutorService stopper = Executors.newSingleThreadExecutor();

        try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(
                     CMIDatabaseWriter.class, (writer, context) -> {
                         when(writer.testConnection()).thenReturn(true);
                         when(writer.readCMIBalance(any(UUID.class))).thenReturn(new BigDecimal("1.00"));
                         when(writer.batchUpdateBalanceResults(any())).thenAnswer(invocation -> {
                             List<CMIDatabaseWriter.PlayerBalance> batch = invocation.getArgument(0);
                             int call = calls.incrementAndGet();
                             if (call == 1) {
                                 firstWriteStarted.countDown();
                                 if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                                     throw new IllegalStateException("Timed out waiting to release first CMI write");
                                 }
                             }
                             batch.stream().map(CMIDatabaseWriter.PlayerBalance::balance).forEach(persistedWrites::add);
                             return java.util.Collections.nCopies(batch.size(), true);
                         });
                     });
             MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade(authorityStates), null, null);
            task.start();
            task.enqueueSyncEvent(playerId, "AcceptanceShutdown", new BigDecimal("5.00"));
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));

            authorityStates.put(playerId, state(new BigDecimal("7.00"), 2L));
            task.enqueueSyncEvent(playerId, "AcceptanceShutdown", new BigDecimal("7.00"));
            Future<?> stopping = stopper.submit(task::stop);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (task.isRunning() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(task.isRunning(), "stop should close event admission before draining");
            assertFalse(stopping.isDone(), "stop must wait while the current sink write is active");

            CMIDatabaseWriter writer = cmiWriters.constructed().get(0);
            verify(writer, never()).close();
            releaseFirstWrite.countDown();
            stopping.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(new BigDecimal("5.00"), new BigDecimal("7.00")), persistedWrites);
            InOrder closeOrder = org.mockito.Mockito.inOrder(writer, storages.constructed().get(0));
            closeOrder.verify(writer).batchUpdateBalanceResults(any());
            closeOrder.verify(storages.constructed().get(0)).saveRecord(any());
            closeOrder.verify(writer).batchUpdateBalanceResults(any());
            closeOrder.verify(storages.constructed().get(0)).saveRecord(any());
            closeOrder.verify(writer).close();
            closeOrder.verify(storages.constructed().get(0)).close();
            assertEquals(0, task.getQueueSize());
        } finally {
            releaseFirstWrite.countDown();
            stopper.shutdownNow();
        }
    }

    @Test
    void keepsSharedDatabaseOpenThroughPostTerminationShadowDrain(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "local", 1);
        StorageManager storageManager = new StorageManager(plugin, mock(SyncmoneyConfig.class));
        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        setField(storageManager, "cacheManager", cacheManager);
        setField(storageManager, "redisManager", redisManager);
        setField(storageManager, "databaseManager", databaseManager);

        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("5.00"), 1L));
        EconomyFacade economyFacade = economyFacade(authorityStates);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondWriteFinished = new CountDownLatch(1);
        CountDownLatch databaseClosedSignal = new CountDownLatch(1);
        CountDownLatch redisClosedSignal = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicLong persistedVersion = new AtomicLong();
        AtomicInteger writesAfterDatabaseClose = new AtomicInteger();
        AtomicBoolean databaseClosed = new AtomicBoolean();
        AtomicReference<BigDecimal> persistedBalance = new AtomicReference<>();
        ExecutorService stopper = Executors.newSingleThreadExecutor();

        doAnswer(invocation -> {
            if (databaseClosed.get()) {
                writesAfterDatabaseClose.incrementAndGet();
            }
            int call = writeCalls.incrementAndGet();
            if (call == 1) {
                firstWriteStarted.countDown();
                while (true) {
                    try {
                        releaseFirstWrite.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Model a JDBC operation that remains active after the shutdown interrupt.
                        workerInterrupted.countDown();
                    }
                }
            }
            persistedVersion.set(invocation.getArgument(3));
            persistedBalance.set(invocation.getArgument(2));
            if (call == 2) {
                secondWriteFinished.countDown();
            }
            return true;
        }).when(databaseManager).tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString());
        when(databaseManager.getPlayerStrict(playerId)).thenAnswer(invocation -> Optional.of(
                new DatabaseManager.PlayerRecord(playerId, "AcceptanceShadow", persistedBalance.get(),
                        persistedVersion.get(), "test-server", Instant.now())));
        doAnswer(invocation -> {
            databaseClosed.set(true);
            databaseClosedSignal.countDown();
            return null;
        }).when(databaseManager).close();
        doAnswer(invocation -> {
            redisClosedSignal.countDown();
            return null;
        }).when(redisManager).close();

        try (MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade, null, databaseManager,
                    storageManager::acquireDatabaseUseLease, 500L, 500L);
            task.start();
            task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("5.00"));
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));
            authorityStates.put(playerId, state(new BigDecimal("7.00"), 2L));
            task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("7.00"));

            long stopStartedNanos = System.nanoTime();
            Future<?> stopping = stopper.submit(task::stop);
            assertTrue(workerInterrupted.await(5, TimeUnit.SECONDS),
                    "the bounded final-drain wait should interrupt the active worker");

            storageManager.shutdown();
            verify(databaseManager, never()).close();
            verify(redisManager, never()).close();

            stopping.get(5, TimeUnit.SECONDS);
            long stopElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartedNanos);
            assertTrue(stopElapsedMillis <= 800L,
                    "one bounded shutdown deadline should cap stop() at 800 ms, elapsed " + stopElapsedMillis + " ms");
            assertEquals(1, task.getQueueSize(),
                    "stop may return after bounded waits while the active sink remains uninterruptible");
            assertEquals(1, writeCalls.get(), "the queued event must wait for late worker termination");
            assertFalse(databaseClosed.get(), "the shared database lease must remain held after stop returns");

            releaseFirstWrite.countDown();
            assertTrue(secondWriteFinished.await(5, TimeUnit.SECONDS),
                    "late executor termination should run the final Shadow Sync drain");
            assertTrue(databaseClosedSignal.await(5, TimeUnit.SECONDS),
                    "the database should close after the late final drain releases its lease");
            assertTrue(redisClosedSignal.await(5, TimeUnit.SECONDS),
                    "Redis should close after the database manager finishes closing");

            assertEquals(2, writeCalls.get(), "the queued event should receive the late final drain");
            assertEquals(0, writesAfterDatabaseClose.get(),
                    "the shared database must remain open until the late final drain has completed");
            assertTrue(databaseClosed.get(), "storage should close after the Shadow Sync worker releases its lease");
            verify(databaseManager).close();
            verify(redisManager).close();
            assertEquals(0, task.getQueueSize());
        } finally {
            releaseFirstWrite.countDown();
            stopper.shutdownNow();
        }
    }

    @Test
    void finalDrainAfterTimeoutRunsOnFlushWorkerWhenTerminationRacesStop(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = plugin(tempDir);
        SyncmoneyConfig config = config(tempDir, "local", 1);
        StorageManager storageManager = new StorageManager(plugin, mock(SyncmoneyConfig.class));
        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        setField(storageManager, "cacheManager", cacheManager);
        setField(storageManager, "redisManager", redisManager);
        setField(storageManager, "databaseManager", databaseManager);

        UUID playerId = UUID.randomUUID();
        Map<UUID, EconomyFacade.EconomyState> authorityStates = new ConcurrentHashMap<>();
        authorityStates.put(playerId, state(new BigDecimal("5.00"), 1L));
        EconomyFacade economyFacade = economyFacade(authorityStates);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondWrite = new CountDownLatch(1);
        CountDownLatch secondWriteFinished = new CountDownLatch(1);
        CountDownLatch databaseClosedSignal = new CountDownLatch(1);
        CountDownLatch redisClosedSignal = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicLong persistedVersion = new AtomicLong();
        AtomicInteger writesAfterDatabaseClose = new AtomicInteger();
        AtomicBoolean databaseClosed = new AtomicBoolean();
        AtomicReference<BigDecimal> persistedBalance = new AtomicReference<>();
        AtomicReference<String> finalDrainThread = new AtomicReference<>();
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        Future<?> stopping = null;
        boolean storageShutdownRequested = false;

        doAnswer(invocation -> {
            if (databaseClosed.get()) {
                writesAfterDatabaseClose.incrementAndGet();
            }
            int call = writeCalls.incrementAndGet();
            if (call == 1) {
                firstWriteStarted.countDown();
                while (true) {
                    try {
                        releaseFirstWrite.await();
                        break;
                    } catch (InterruptedException ignored) {
                        workerInterrupted.countDown();
                    }
                }
            } else if (call == 2) {
                finalDrainThread.set(Thread.currentThread().getName());
                secondWriteStarted.countDown();
                while (true) {
                    try {
                        if (!releaseSecondWrite.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release final Shadow Sync drain");
                        }
                        break;
                    } catch (InterruptedException ignored) {
                        // The bounded stop may interrupt the owner again while the final drain is active.
                    }
                }
                secondWriteFinished.countDown();
            }
            persistedVersion.set(invocation.getArgument(3));
            persistedBalance.set(invocation.getArgument(2));
            return true;
        }).when(databaseManager).tryInsertOrUpdatePlayer(
                any(), anyString(), any(), anyLong(), anyString());
        when(databaseManager.getPlayerStrict(playerId)).thenAnswer(invocation -> Optional.of(
                new DatabaseManager.PlayerRecord(playerId, "AcceptanceShadow", persistedBalance.get(),
                        persistedVersion.get(), "test-server", Instant.now())));
        doAnswer(invocation -> {
            databaseClosed.set(true);
            databaseClosedSignal.countDown();
            return null;
        }).when(databaseManager).close();
        doAnswer(invocation -> {
            redisClosedSignal.countDown();
            return null;
        }).when(redisManager).close();

        try (MockedConstruction<SqliteShadowStorage> storages = mockConstruction(SqliteShadowStorage.class)) {
            ShadowSyncTask task = new ShadowSyncTask(plugin, config, economyFacade, null, databaseManager,
                    storageManager::acquireDatabaseUseLease, 50L, 50L);
            try {
                task.start();
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("5.00"));
                assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));
                authorityStates.put(playerId, state(new BigDecimal("7.00"), 2L));
                task.enqueueSyncEvent(playerId, "AcceptanceShadow", new BigDecimal("7.00"));

                stopping = stopper.submit(task::stop);
                assertTrue(workerInterrupted.await(5, TimeUnit.SECONDS),
                        "the final-drain timeout should interrupt the active flush worker");
                releaseFirstWrite.countDown();
                assertTrue(secondWriteStarted.await(5, TimeUnit.SECONDS),
                        "the queued event should receive its final drain after the active write ends");
                stopping.get(200, TimeUnit.MILLISECONDS);

                storageManager.shutdown();
                storageShutdownRequested = true;
                verify(databaseManager, never()).close();
                verify(redisManager, never()).close();
                assertFalse(databaseClosed.get(), "the shared database lease must cover the active final drain");
                assertEquals("syncmoney-shadow-flush", finalDrainThread.get(),
                        "the last queue drain must stay on its owning worker, never the stop caller");

                releaseSecondWrite.countDown();
                assertTrue(secondWriteFinished.await(5, TimeUnit.SECONDS));
                assertTrue(databaseClosedSignal.await(5, TimeUnit.SECONDS),
                        "the shared database should close after the worker completes the final drain");
                assertTrue(redisClosedSignal.await(5, TimeUnit.SECONDS),
                        "Redis should close after the database manager finishes closing");
                stopping.get(5, TimeUnit.SECONDS);

                assertEquals(2, writeCalls.get());
                assertEquals(0, writesAfterDatabaseClose.get());
                assertTrue(databaseClosed.get());
                assertEquals(0, task.getQueueSize());
                verify(databaseManager).close();
                verify(redisManager).close();
            } finally {
                releaseFirstWrite.countDown();
                releaseSecondWrite.countDown();
                if (stopping != null) {
                    stopping.get(5, TimeUnit.SECONDS);
                }
                if (!storageShutdownRequested) {
                    storageManager.shutdown();
                }
                stopper.shutdownNow();
            }
        }
    }

    private static void flushNow(ShadowSyncTask task) {
        try {
            var method = ShadowSyncTask.class.getDeclaredMethod("flush");
            method.setAccessible(true);
            method.invoke(task);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not flush Shadow Sync test batch", e);
        }
    }

    private static Syncmoney plugin(Path dataFolder) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ShadowSyncTaskFailureReportingTest"));
        return plugin;
    }

    private static EconomyFacade economyFacade(UUID uuid, BigDecimal balance, long version) {
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.getMemoryState(uuid)).thenReturn(state(balance, version));
        return economyFacade;
    }

    private static EconomyFacade economyFacade(Map<UUID, EconomyFacade.EconomyState> states) {
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.getMemoryState(any(UUID.class)))
                .thenAnswer(invocation -> states.get(invocation.getArgument(0)));
        return economyFacade;
    }

    private static EconomyFacade.EconomyState state(BigDecimal balance, long version) {
        return new EconomyFacade.EconomyState(balance, version, 1L);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static SyncmoneyConfig config(Path dataFolder, String target, int batchSize) {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.isDebug()).thenReturn(false);
        when(config.getServerName()).thenReturn("test-server");
        when(config.getQueueCapacity()).thenReturn(10_000);
        when(config.shadowSync().isShadowSyncEnabled()).thenReturn(true);
        when(config.shadowSync().getShadowSyncTarget()).thenReturn(target);
        when(config.shadowSync().getShadowSyncStorageType()).thenReturn("sqlite");
        when(config.shadowSync().getShadowSyncStorageSqlitePath())
                .thenReturn(dataFolder.resolve("shadow-history.db").toString());
        when(config.shadowSync().getShadowSyncStorageJsonlPath())
                .thenReturn(dataFolder.resolve("logs").toString());
        when(config.shadowSync().isShadowSyncHistoryEnabled()).thenReturn(true);
        when(config.shadowSync().getShadowSyncHistoryRetentionDays()).thenReturn(0);
        when(config.shadowSync().getShadowSyncTriggerBatchSize()).thenReturn(batchSize);
        when(config.shadowSync().getShadowSyncTriggerMaxDelayMs()).thenReturn(60_000L);
        return config;
    }
}
