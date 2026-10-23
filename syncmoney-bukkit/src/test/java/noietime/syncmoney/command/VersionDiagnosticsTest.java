package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.shadow.ShadowSyncTask;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class VersionDiagnosticsTest {

    @Test
    void reportsTheEvidenceBacked14PaperSupportBounds() {
        Syncmoney plugin = mock(Syncmoney.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                "Syncmoney", "1.4.0-alpha.1", "noietime.syncmoney.Syncmoney"));
        when(server.getName()).thenReturn("Paper");
        when(server.getVersion()).thenReturn("Paper 1.21.6-48");
        when(server.getBukkitVersion()).thenReturn("1.21.6-R0.1-SNAPSHOT");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            String rendered = VersionDiagnostics.basic(plugin).render();

            assertTrue(rendered.contains("support.minimum_minecraft: Paper 1.21.6+"));
            assertTrue(rendered.contains(
                    "support.latest_verified_server: Paper 1.21.6-48 (1.4.0 minimum smoke; latest-build matrix pending)"));
        }
    }

    @Test
    void reportsStoppedShadowSyncTaskAsNotRunning() {
        assertTrue(renderFullReportWithShadowSyncState(false)
                .contains("module.shadow_sync: true running=false"));
    }

    @Test
    void reportsActiveShadowSyncTaskAsRunning() {
        assertTrue(renderFullReportWithShadowSyncState(true)
                .contains("module.shadow_sync: true running=true"));
    }

    private static String renderFullReportWithShadowSyncState(boolean running) {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        ShadowSyncTask shadowSyncTask = mock(ShadowSyncTask.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                "Syncmoney", "1.4.0-alpha.1", "noietime.syncmoney.Syncmoney"));
        when(plugin.getSyncmoneyConfig()).thenReturn(config);
        when(plugin.getShadowSyncTask()).thenReturn(shadowSyncTask);
        when(shadowSyncTask.isRunning()).thenReturn(running);
        when(server.getName()).thenReturn("Paper");
        when(server.getVersion()).thenReturn("Paper 1.21.6-48");
        when(server.getBukkitVersion()).thenReturn("1.21.6-R0.1-SNAPSHOT");
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(config.isRedisEnabled()).thenReturn(false);
        when(config.isDatabaseEnabled()).thenReturn(false);
        when(config.shadowSync().isShadowSyncEnabled()).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            return VersionDiagnostics.full(plugin).join().render();
        }
    }
}
