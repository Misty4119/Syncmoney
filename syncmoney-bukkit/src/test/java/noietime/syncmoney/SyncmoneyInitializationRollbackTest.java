package noietime.syncmoney;

import noietime.syncmoney.config.ShadowSyncConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.command.CommandServiceManager;
import noietime.syncmoney.audit.AuditServiceManager;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.breaker.BreakerManager;
import noietime.syncmoney.economy.EconomyServiceManager;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.economy.EventConsumerManager;
import noietime.syncmoney.listener.ListenerServiceManager;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.sync.SyncManager;
import noietime.syncmoney.util.ConfigMerger;
import noietime.syncmoney.util.MessageService;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
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

    @Test
    void laterInitializationFailureAttemptsEveryAssignedOwnerAfterCloseFailures() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class, CALLS_REAL_METHODS);
        setShutdownGuard(plugin);
        Logger logger = mock(Logger.class);
        PluginManager pluginManager = mock(PluginManager.class);
        Server server = mock(Server.class);
        doReturn(logger).when(plugin).getLogger();
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(pluginManager);

        IllegalStateException commandInitializationFailure = new IllegalStateException("command init failed");

        try (MockedConstruction<SyncmoneyConfig> configs = mockConstruction(SyncmoneyConfig.class,
                    (config, context) -> {
                        ShadowSyncConfig shadowSyncConfig = mock(ShadowSyncConfig.class);
                        when(shadowSyncConfig.isShadowSyncEnabled()).thenReturn(false);
                        when(config.shadowSync()).thenReturn(shadowSyncConfig);
                        when(config.getServerName()).thenReturn("lifecycle-test");
                        when(config.getEconomyMode()).thenReturn(EconomyMode.LOCAL);
                    });
             MockedConstruction<ConfigMerger> configMergers = mockConstruction(ConfigMerger.class,
                    (merger, context) -> when(merger.mergeAll()).thenReturn(List.of()));
             MockedConstruction<MessageService> messageServices = mockConstruction(MessageService.class);
             MockedConstruction<StorageManager> storageManagers = mockConstruction(StorageManager.class,
                    (storage, context) -> doThrow(new IllegalStateException("storage close failed"))
                            .when(storage).shutdown());
             MockedConstruction<EconomyServiceManager> economyManagers = mockConstruction(EconomyServiceManager.class,
                    (economy, context) -> {
                        doThrow(new IllegalStateException("economy quiesce failed")).when(economy).quiesce();
                        doThrow(new IllegalStateException("economy close failed")).when(economy).shutdown();
                    });
             MockedConstruction<SyncManager> syncManagers = mockConstruction(SyncManager.class,
                    (sync, context) -> {
                        doThrow(new IllegalStateException("sync inbound stop failed"))
                                .when(sync).stopInboundProducers();
                        doThrow(new IllegalStateException("sync close failed")).when(sync).shutdown();
                    });
             MockedConstruction<BreakerManager> breakerManagers = mockConstruction(BreakerManager.class,
                    (breaker, context) -> doThrow(new IllegalStateException("breaker close failed"))
                            .when(breaker).shutdown());
             MockedConstruction<BaltopManager> baltopManagers = mockConstruction(BaltopManager.class,
                    (baltop, context) -> doThrow(new IllegalStateException("baltop save failed"))
                            .when(baltop).saveToDatabase());
             MockedConstruction<ListenerServiceManager> listenerManagers = mockConstruction(ListenerServiceManager.class,
                    (listeners, context) -> doThrow(new IllegalStateException("listener close failed"))
                            .when(listeners).shutdown());
             MockedConstruction<AuditServiceManager> auditManagers = mockConstruction(AuditServiceManager.class,
                    (audit, context) -> doThrow(new IllegalStateException("audit close failed"))
                            .when(audit).shutdown());
             MockedConstruction<EventConsumerManager> consumerManagers = mockConstruction(EventConsumerManager.class,
                    (consumers, context) -> doThrow(new IllegalStateException("consumer close failed"))
                            .when(consumers).shutdown());
             MockedConstruction<CommandServiceManager> commandManagers = mockConstruction(CommandServiceManager.class,
                    (commands, context) -> {
                        doThrow(commandInitializationFailure).when(commands).initialize();
                        doThrow(new IllegalStateException("command close failed")).when(commands).shutdown();
                    })) {
            plugin.onEnable();

            StorageManager storageManager = storageManagers.constructed().get(0);
            EconomyServiceManager economyServiceManager = economyManagers.constructed().get(0);
            SyncManager syncManager = syncManagers.constructed().get(0);
            BreakerManager breakerManager = breakerManagers.constructed().get(0);
            BaltopManager baltopManager = baltopManagers.constructed().get(0);
            ListenerServiceManager listenerServiceManager = listenerManagers.constructed().get(0);
            AuditServiceManager auditServiceManager = auditManagers.constructed().get(0);
            EventConsumerManager eventConsumerManager = consumerManagers.constructed().get(0);
            CommandServiceManager commandServiceManager = commandManagers.constructed().get(0);

            InOrder rollbackOrder = inOrder(commandServiceManager, economyServiceManager, syncManager,
                    eventConsumerManager, auditServiceManager, listenerServiceManager, baltopManager,
                    breakerManager, storageManager, pluginManager);
            rollbackOrder.verify(commandServiceManager).initialize();
            verify(logger).log(ArgumentMatchers.eq(Level.SEVERE),
                    ArgumentMatchers.eq("Syncmoney initialization failed; rolling back initialized owners"),
                    ArgumentMatchers.same(commandInitializationFailure));
            rollbackOrder.verify(economyServiceManager).quiesce();
            rollbackOrder.verify(syncManager).stopInboundProducers();
            rollbackOrder.verify(commandServiceManager).shutdown();
            rollbackOrder.verify(eventConsumerManager).shutdown();
            rollbackOrder.verify(auditServiceManager).shutdown();
            rollbackOrder.verify(listenerServiceManager).shutdown();
            rollbackOrder.verify(baltopManager).saveToDatabase();
            rollbackOrder.verify(breakerManager).shutdown();
            rollbackOrder.verify(syncManager).shutdown();
            rollbackOrder.verify(economyServiceManager).shutdown();
            rollbackOrder.verify(storageManager).shutdown();
            rollbackOrder.verify(pluginManager).disablePlugin(plugin);

            // A later Bukkit disable callback must not repeat any owner cleanup.
            plugin.onDisable();
            verify(economyServiceManager, times(1)).quiesce();
            verify(commandServiceManager, times(1)).shutdown();
            verify(eventConsumerManager, times(1)).shutdown();
            verify(auditServiceManager, times(1)).shutdown();
            verify(listenerServiceManager, times(1)).shutdown();
            verify(baltopManager, times(1)).saveToDatabase();
            verify(breakerManager, times(1)).shutdown();
            verify(syncManager, times(1)).stopInboundProducers();
            verify(syncManager, times(1)).shutdown();
            verify(economyServiceManager, times(1)).shutdown();
            verify(storageManager, times(1)).shutdown();
        }
    }

    private static void setShutdownGuard(Syncmoney plugin) throws ReflectiveOperationException {
        Field shutdownStarted = Syncmoney.class.getDeclaredField("shutdownStarted");
        shutdownStarted.setAccessible(true);
        shutdownStarted.set(plugin, new AtomicBoolean());
    }
}
