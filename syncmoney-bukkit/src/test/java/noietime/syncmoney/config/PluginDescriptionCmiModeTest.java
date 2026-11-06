package noietime.syncmoney.config;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptionCmiModeTest {

    @Test
    void vaultIsOptionalAndCmiIsLoadedBeforeSyncmoneyWhenPresent() throws Exception {
        try (InputStream descriptor = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(descriptor, "packaged plugin.yml must be available to the test runtime");
            PluginDescriptionFile pluginDescription = new PluginDescriptionFile(descriptor);

            assertFalse(pluginDescription.getDepend().contains("Vault"));
            assertTrue(pluginDescription.getSoftDepend().contains("Vault"));
            assertTrue(pluginDescription.getSoftDepend().contains("CMI"));
            assertEquals(Set.of("syncmoney"), pluginDescription.getCommands().keySet(),
                    "Wallet commands must be registered dynamically only after the economy mode is known");
        }
    }

    @Test
    void paperDependenciesLoadOptionalProvidersBeforeSyncmoney() throws Exception {
        try (InputStream descriptor = getClass().getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(descriptor, "packaged paper-plugin.yml must be available to the test runtime");
            YamlConfiguration paperPlugin = new YamlConfiguration();
            paperPlugin.loadFromString(new String(descriptor.readAllBytes(), StandardCharsets.UTF_8));

            assertEquals("BEFORE", paperPlugin.getString("dependencies.server.CMI.load"));
            assertFalse(paperPlugin.getBoolean("dependencies.server.CMI.required", true));
            assertEquals("BEFORE", paperPlugin.getString("dependencies.server.Vault.load"));
            assertFalse(paperPlugin.getBoolean("dependencies.server.Vault.required", true));
            assertEquals("BEFORE", paperPlugin.getString("dependencies.server.PlaceholderAPI.load"));
            assertFalse(paperPlugin.getBoolean("dependencies.server.PlaceholderAPI.required", true));
        }
    }
}
