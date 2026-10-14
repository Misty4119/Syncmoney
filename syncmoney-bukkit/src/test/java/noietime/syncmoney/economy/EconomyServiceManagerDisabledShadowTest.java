package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EconomyServiceManagerDisabledShadowTest {

    @Test
    void disabledShadowSyncDoesNotCreateTaskOrTouchStorage(@TempDir Path dataFolder) throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyServiceManagerDisabledShadowTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.shadowSync().isShadowSyncEnabled()).thenReturn(false);
        StorageManager storageManager = mock(StorageManager.class);
        EconomyServiceManager manager = new EconomyServiceManager(plugin, config, storageManager);

        try {
            manager.startShadowSyncIfEnabled();

            assertAll(
                    () -> assertNull(manager.getShadowSyncTask()),
                    () -> assertFalse(Files.exists(dataFolder.resolve("logs").resolve("shadow-sync"))));
            verifyNoInteractions(storageManager);
            verify(plugin, never()).getServer();
        } finally {
            manager.shutdown();
        }
    }
}
