package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EconomyServiceManagerDisabledShadowTest {

    @Test
    void cmiModeDoesNotConstructSyncmoneyEconomyOrVaultServices() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyServiceManagerDisabledShadowTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.getEconomyMode()).thenReturn(EconomyMode.CMI);
        when(config.isCMIMode()).thenReturn(true);
        StorageManager storageManager = mock(StorageManager.class);
        when(storageManager.getRedisManager()).thenReturn(mock(RedisManager.class));
        EconomyServiceManager manager = new EconomyServiceManager(plugin, config, storageManager);

        try (MockedConstruction<EconomyFacade> facades = mockConstruction(EconomyFacade.class);
             MockedConstruction<SyncmoneyVaultProvider> vaultProviders = mockConstruction(SyncmoneyVaultProvider.class);
             MockedConstruction<NameResolver> nameResolvers = mockConstruction(NameResolver.class);
             MockedConstruction<ShadowSyncTask> shadowTasks = mockConstruction(ShadowSyncTask.class);
             MockedConstruction<CrossServerSyncManager> syncManagers =
                     mockConstruction(CrossServerSyncManager.class)) {
            manager.initialize();

            assertAll(
                    () -> assertNull(manager.getEconomyFacade()),
                    () -> assertNull(manager.getEconomyWriteQueue()),
                    () -> assertNull(manager.getFallbackWrapper()),
                    () -> assertNull(manager.getNameResolver()),
                    () -> assertNull(manager.getVaultProvider()),
                    () -> assertNull(manager.getShadowSyncTask()),
                    () -> assertTrue(facades.constructed().isEmpty()),
                    () -> assertTrue(vaultProviders.constructed().isEmpty()),
                    () -> assertTrue(nameResolvers.constructed().isEmpty()),
                    () -> assertTrue(shadowTasks.constructed().isEmpty()),
                    () -> assertEquals(1, syncManagers.constructed().size()));
            verify(syncManagers.constructed().get(0), never()).startPeriodicVersionCheck();
            verify(storageManager, never()).getCacheManager();
            verify(plugin, never()).getServer();
        } finally {
            manager.shutdown();
        }
    }

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

    @Test
    void cmiModeSkipsShadowSyncEvenWhenItIsEnabled(@TempDir Path dataFolder) throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyServiceManagerDisabledShadowTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.isCMIMode()).thenReturn(true);
        when(config.shadowSync().isShadowSyncEnabled()).thenReturn(true);
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
