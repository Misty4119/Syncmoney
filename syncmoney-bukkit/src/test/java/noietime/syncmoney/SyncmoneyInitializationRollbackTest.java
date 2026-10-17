package noietime.syncmoney;

import noietime.syncmoney.config.ShadowSyncConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyServiceManager;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.util.ConfigMerger;
import noietime.syncmoney.util.MessageService;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncmoneyInitializationRollbackTest {

    @Test
    void initializationFailureAfterStorageAssignmentRollsBackAndDisablesPlugin() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class, CALLS_REAL_METHODS);
        setShutdownGuard(plugin);
        Logger logger = Logger.getAnonymousLogger();
        PluginManager pluginManager = mock(PluginManager.class);
        Server server = mock(Server.class);
        doReturn(logger).when(plugin).getLogger();
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(pluginManager);

        IllegalStateException storageCloseFailure = new IllegalStateException("storage close failed");
        IllegalStateException economyInitializationFailure = new IllegalStateException("economy init failed");

        try (MockedConstruction<SyncmoneyConfig> configs = mockConstruction(SyncmoneyConfig.class,
                    (config, context) -> {
                        ShadowSyncConfig shadowSyncConfig = mock(ShadowSyncConfig.class);
                        when(shadowSyncConfig.isShadowSyncEnabled()).thenReturn(false);
                        when(config.shadowSync()).thenReturn(shadowSyncConfig);
                        when(config.getServerName()).thenReturn("lifecycle-test");
                    });
             MockedConstruction<ConfigMerger> configMergers = mockConstruction(ConfigMerger.class,
                    (merger, context) -> when(merger.mergeAll()).thenReturn(List.of()));
             MockedConstruction<MessageService> messageServices = mockConstruction(MessageService.class);
             MockedConstruction<StorageManager> storageManagers = mockConstruction(StorageManager.class,
                    (storage, context) -> doThrow(storageCloseFailure).when(storage).shutdown());
             MockedConstruction<EconomyServiceManager> economyManagers = mockConstruction(EconomyServiceManager.class,
                    (economy, context) -> {
                        doThrow(economyInitializationFailure).when(economy).initialize();
                        doThrow(new IllegalStateException("economy close failed")).when(economy).shutdown();
                    })) {
            plugin.onEnable();

            StorageManager storageManager = storageManagers.constructed().get(0);
            EconomyServiceManager economyServiceManager = economyManagers.constructed().get(0);
            verify(storageManager).initialize();
            verify(economyServiceManager).initialize();
            InOrder rollbackOrder = inOrder(economyServiceManager, storageManager, pluginManager);
            rollbackOrder.verify(economyServiceManager).shutdown();
            rollbackOrder.verify(storageManager).shutdown();
            rollbackOrder.verify(pluginManager).disablePlugin(plugin);

            // Bukkit may invoke onDisable after disablePlugin; rollback must remain idempotent.
            plugin.onDisable();
            verify(storageManager, times(1)).shutdown();
        }
    }

    private static void setShutdownGuard(Syncmoney plugin) throws ReflectiveOperationException {
        Field shutdownStarted = Syncmoney.class.getDeclaredField("shutdownStarted");
        shutdownStarted.setAccessible(true);
        shutdownStarted.set(plugin, new AtomicBoolean());
    }
}
