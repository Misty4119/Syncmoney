package noietime.syncmoney;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.economy.EconomyModeRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginContextCmiExposureTest {

    @Test
    void pluginContextDoesNotExposeMutableEconomyRouterInCmiMode() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyModeRouter router = mock(EconomyModeRouter.class);
        when(config.isCMIMode()).thenReturn(true);

        PluginContext context = new PluginContext.Builder()
                .setConfig(config)
                .setEconomyModeRouter(router)
                .build();

        assertNull(context.getEconomyModeRouter());
        assertNull(PluginContext.publicEconomyModeRouter(config, router));
    }

    @Test
    void pluginContextKeepsEconomyRouterAvailableInOtherModes() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyModeRouter router = mock(EconomyModeRouter.class);
        when(config.isCMIMode()).thenReturn(false);

        PluginContext context = new PluginContext.Builder()
                .setConfig(config)
                .setEconomyModeRouter(router)
                .build();

        assertSame(router, context.getEconomyModeRouter());
    }

    @Test
    void pluginContextDoesNotExposeCmiPublisherInCmiMode() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        when(config.isCMIMode()).thenReturn(true);

        PluginContext context = new PluginContext.Builder()
                .setConfig(config)
                .setCrossServerSyncManager(syncManager)
                .build();

        assertNull(context.getCrossServerSyncManager());
        assertNull(PluginContext.publicCrossServerSyncManager(config, syncManager));
    }

    @Test
    void pluginContextKeepsPublisherAvailableInOtherModes() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        when(config.isCMIMode()).thenReturn(false);

        PluginContext context = new PluginContext.Builder()
                .setConfig(config)
                .setCrossServerSyncManager(syncManager)
                .build();

        assertSame(syncManager, context.getCrossServerSyncManager());
    }
}
