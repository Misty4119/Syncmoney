package noietime.syncmoney.migration;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalToSyncMigrationRecoveryGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void previewAndStartRejectBeforeReadingLocalSqliteWhenRecoveryIsPending() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationRecoveryGuardTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(false);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config, economyFacade,
                mock(DatabaseManager.class), mock(RedisManager.class), mock(MigrationCheckpoint.class),
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

        assertNull(task.preview(), "preview must not expose a pre-replay SQLite snapshot");
        task.start(false, false);

        assertFalse(task.isRunning());
        assertEquals(List.of("Local-to-Sync migration is unavailable until accepted LOCAL writes are persisted or recovered"), errors);
    }

    @Test
    void nonLocalFacadeRejectsLocalSnapshotWhileShutdownJournalExists() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationRecoveryGuardTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Path sqlitePath = tempDir.resolve("local.db");
        when(config.local().getLocalSQLitePath()).thenReturn(sqlitePath.toString());
        Files.writeString(tempDir.resolve("local-persistence.journal"), "SYNCLOCAL1\npending");
        EconomyFacade economyFacade = new EconomyFacade(plugin, config, mock(noietime.syncmoney.storage.CacheManager.class),
                mock(noietime.syncmoney.storage.RedisManager.class),
                mock(noietime.syncmoney.storage.db.DatabaseManager.class), null, null,
                mock(noietime.syncmoney.economy.FallbackEconomyWrapper.class), null,
                mock(noietime.syncmoney.economy.OverflowLogInterface.class));

        try {
            assertFalse(economyFacade.isLocalRecoveryReady(),
                    "a mode without LocalEconomyHandler must not treat an unreplayed LOCAL journal as ready");
        } finally {
            economyFacade.shutdown();
        }
    }
}
