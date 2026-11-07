package noietime.syncmoney.shadow;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowSyncTaskMissingDatabaseTest {

    @Test
    void localAndAllTargetsFailClosedWhenDatabaseManagerIsMissing(@TempDir Path tempDir) {
        for (String target : List.of("local", "all")) {
            Path dataFolder = tempDir.resolve(target);
            Syncmoney plugin = mock(Syncmoney.class);
            Logger logger = mock(Logger.class);
            when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
            when(plugin.getLogger()).thenReturn(logger);

            SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
            when(config.isDebug()).thenReturn(false);
            when(config.shadowSync().isShadowSyncEnabled()).thenReturn(true);
            when(config.shadowSync().getShadowSyncTarget()).thenReturn(target);
            when(config.shadowSync().getShadowSyncStorageType()).thenReturn("sqlite");
            when(config.shadowSync().getShadowSyncStorageSqlitePath())
                    .thenReturn(dataFolder.resolve("shadow-history.db").toString());
            when(config.shadowSync().getShadowSyncStorageJsonlPath())
                    .thenReturn(dataFolder.resolve("logs").toString());
            when(config.shadowSync().getShadowSyncTriggerBatchSize()).thenReturn(10);
            when(config.shadowSync().getShadowSyncTriggerMaxDelayMs()).thenReturn(1_000L);

            try (MockedConstruction<CMIDatabaseWriter> cmiWriters = mockConstruction(CMIDatabaseWriter.class)) {
                ShadowSyncTask task = new ShadowSyncTask(plugin, config, null, null, (DatabaseManager) null);
                try {
                    task.start();
                    task.enqueueSyncEvent(UUID.randomUUID(), "AcceptanceShadow", BigDecimal.ONE);

                    assertFalse(task.getStatus().running());
                    assertNull(task.getStorage());
                    assertEquals(0, task.getQueueSize());
                    assertTrue(task.getSyncLog().getRecentLogs(10).isEmpty());
                    assertTrue(cmiWriters.constructed().isEmpty(),
                            "an all-target request must not partially write to CMI without its SQL target");
                    verify(logger).severe("Shadow sync disabled: target '" + target
                            + "' requires a Syncmoney database, but no DatabaseManager is available.");
                    verify(plugin, never()).getServer();
                } finally {
                    task.stop();
                }
            }
        }
    }
}
