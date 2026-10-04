package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
