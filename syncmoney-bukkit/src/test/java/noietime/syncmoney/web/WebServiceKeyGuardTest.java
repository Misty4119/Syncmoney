package noietime.syncmoney.web;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.security.ApiKeyAuthFilter;
import noietime.syncmoney.web.security.RateLimiter;
import noietime.syncmoney.web.server.WebAdminConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
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

    @Test
    void explicitlyDisabledWebAdminCreatesNoServerOrAuthFilter(@TempDir Path dataFolder) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WebServiceKeyGuardTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("web-admin.enabled", false);
        yaml.set("web-admin.security.api-key", "operator-managed-secret");
        when(config.getConfig()).thenReturn(yaml);

        WebServiceManager manager = new WebServiceManager(plugin, config,
                null, null, null, null);
        try {
            manager.initialize();

            assertFalse(manager.getWebAdminConfig().isEnabled());
            assertNull(manager.getWebAdminServer());
            assertNull(manager.getApiKeyAuthFilter());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void shutdownStopsWebAdminRateLimiterCleanupExecutor(@TempDir Path dataFolder) throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WebServiceKeyGuardTest"));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("web-admin.security.api-key", "test-api-key");
        WebAdminConfig webConfig = new WebAdminConfig();
        webConfig.load(yaml);
        ApiKeyAuthFilter authFilter = new ApiKeyAuthFilter(plugin, webConfig);
        ScheduledExecutorService cleanupExecutor = cleanupExecutor(authFilter.getRateLimiter());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        WebServiceManager manager = new WebServiceManager(plugin, config, null, null, null, null);
        Field authFilterField = WebServiceManager.class.getDeclaredField("apiKeyAuthFilter");
        authFilterField.setAccessible(true);
        authFilterField.set(manager, authFilter);

        assertFalse(cleanupExecutor.isShutdown());
        manager.shutdown();

        boolean shutdown = cleanupExecutor.isShutdown();
        if (!shutdown) {
            cleanupExecutor.shutdownNow();
        }
        assertTrue(shutdown, "WebServiceManager must close the owned API rate limiter");
    }

    private static ScheduledExecutorService cleanupExecutor(RateLimiter rateLimiter) throws Exception {
        Field executorField = RateLimiter.class.getDeclaredField("cleanupExecutor");
        executorField.setAccessible(true);
        return (ScheduledExecutorService) executorField.get(rateLimiter);
    }
}
