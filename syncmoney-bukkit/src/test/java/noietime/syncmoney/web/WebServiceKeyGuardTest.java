package noietime.syncmoney.web;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebServiceKeyGuardTest {

    @Test
    void disablesBothShippedApiKeyPlaceholdersAndWhitespaceVariants() {
        assertTrue(WebServiceManager.isUnsafeApiKey("change-me-in-production"));
        assertTrue(WebServiceManager.isUnsafeApiKey("change-me"));
        assertTrue(WebServiceManager.isUnsafeApiKey(" CHANGE-ME-IN-PRODUCTION "));
        assertTrue(WebServiceManager.isUnsafeApiKey(" "));
        assertTrue(WebServiceManager.isUnsafeApiKey(null));
    }

    @Test
    void acceptsAConfiguredNonPlaceholderKey() {
        assertFalse(WebServiceManager.isUnsafeApiKey("operator-managed-secret"));
    }

    @Test
    void shippedPlaceholdersDisableOnlyWebAdmin(@TempDir Path dataFolder) {
        for (String key : new String[] {"change-me", "change-me-in-production"}) {
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getLogger("WebServiceKeyGuardTest"));
            SyncmoneyConfig config = mock(SyncmoneyConfig.class);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("web-admin.enabled", true);
            yaml.set("web-admin.security.api-key", key);
            when(config.getConfig()).thenReturn(yaml);

            WebServiceManager manager = new WebServiceManager(plugin, config,
                    null, null, null, null);
            manager.initialize();

            assertTrue(manager.getWebAdminConfig().isEnabled());
            assertNull(manager.getWebAdminServer());
        }
    }
}
