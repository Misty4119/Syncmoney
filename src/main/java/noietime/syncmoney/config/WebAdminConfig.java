package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [SYNC-CONFIG-157] Web Admin configuration settings.
 * Provides getters for web-admin.* settings including server, security, and nodes configuration.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class WebAdminConfig {

    private final FileConfiguration config;

    public WebAdminConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-157a] Whether central management mode is enabled.
     */
    public boolean isCentralMode() {
        return config.getBoolean("web-admin.central-mode", false);
    }

    /**
     * [SYNC-CONFIG-157b] Gets the HTTP server port.
     */
    public int getPort() {
        return config.getInt("web-admin.server.port", 8080);
    }

    /**
     * [SYNC-CONFIG-157c] Gets the API key for authentication.
     */
    public String getApiKey() {
        return config.getString("web-admin.security.api-key", "change-me-in-production");
    }

    /**
     * [SYNC-CONFIG-157d] Gets the rate limit (requests per minute).
     */
    public int getRateLimit() {
        return config.getInt("web-admin.security.rate-limit.requests-per-minute", 60);
    }

    /**
     * [SYNC-CONFIG-157e] Gets the list of configured nodes for central management.
     */
    public List<NodeConfig> getNodes() {
        List<NodeConfig> nodes = new ArrayList<>();
        var list = config.getMapList("web-admin.nodes");
        if (list != null) {
            for (var item : list) {
                if (item instanceof Map) {
                    NodeConfig node = new NodeConfig();
                    node.name = (String) ((Map) item).getOrDefault("name", "Unknown");
                    node.url = (String) ((Map) item).getOrDefault("url", "http://localhost:8080");
                    node.apiKey = (String) ((Map) item).getOrDefault("api-key", "");
                    node.enabled = (Boolean) ((Map) item).getOrDefault("enabled", true);
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    /**
     * [SYNC-CONFIG-157f] Node configuration for central management mode.
     */
    public static class NodeConfig {
        public String name;
        public String url;
        public String apiKey;
        public boolean enabled;
    }
}
