package noietime.syncmoney.web.api.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.ConfigManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared context and utilities for node-related handlers.
 * All node handlers extend this to access plugin, config, and utility methods.
 */
public abstract class NodesApiContext {

    protected static final Set<String> BLOCKED_IP_PREFIXES = Set.of(
            "127.", "0.", "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
            "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
            "192.168.", "169.254.", "224.", "239.", "240.", "241.", "242.",
            "243.", "244.", "245.", "246.", "247.", "248.", "249.", "250.",
            "251.", "252.", "253.", "254.", "255."
    );
    protected static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback"
    );

    protected static final String PERMISSION_VIEW = "syncmoney.web.nodes.view";
    protected static final String PERMISSION_MANAGE = "syncmoney.web.nodes.manage";
    protected static final String PERMISSION_CENTRAL = "syncmoney.web.central";

    protected final Syncmoney plugin;
    protected final SyncmoneyConfig config;
    protected final PermissionChecker permissionChecker;
    protected final NodeApiKeyStore apiKeyStore;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected NodesApiContext(Syncmoney plugin, SyncmoneyConfig config,
                             PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        this.plugin = plugin;
        this.config = config;
        this.permissionChecker = permissionChecker;
        this.apiKeyStore = apiKeyStore;
    }

    // ─── Utility methods ───────────────────────────────────────────────────────

    protected String readRequestBody(HttpServerExchange exchange) throws Exception {
        exchange.startBlocking();
        return new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJson(String json) throws Exception {
        return objectMapper.readValue(json, Map.class);
    }

    protected void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }

    protected void sendError(HttpServerExchange exchange, int statusCode, String code, String message) {
        exchange.setStatusCode(statusCode);
        sendJson(exchange, ApiResponse.error(code, message));
    }

    protected String maskApiKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * Validates that a URL/path does not point to internal or blocked resources (SSRF protection).
     * Checks against blocked hostnames, private IP prefixes, loopback, and link-local addresses.
     *
     * @param urlString the URL string to validate
     * @return true if the URL is allowed, false if it is blocked
     */
    protected boolean isUrlAllowed(String urlString) {
        if (urlString == null || urlString.isBlank()) return false;
        try {
            String decodedUrl = URLDecoder.decode(urlString, StandardCharsets.UTF_8);
            URI uri = new URI(decodedUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;

            String hostLower = host.toLowerCase();
            if (BLOCKED_HOSTNAMES.contains(hostLower) || hostLower.endsWith(".local")) {
                return false;
            }
            for (String prefix : BLOCKED_IP_PREFIXES) {
                if (hostLower.startsWith(prefix)) return false;
            }
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isLinkLocalAddress()) return false;

            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (Exception e) {
            plugin.getLogger().fine("URL validation error for " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    protected String getMasterKey() {
        return config.getConfig().getString("web-admin.security.api-key", "default-master-key");
    }

    protected int extractIndex(HttpServerExchange exchange) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) path = path.substring(1);
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("nodes") && i + 1 < parts.length) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    protected Object parseValue(com.fasterxml.jackson.databind.JsonNode valueNode) {
        if (valueNode.isNull()) return null;
        if (valueNode.isBoolean()) return valueNode.asBoolean();
        if (valueNode.isIntegralNumber()) return valueNode.asLong();
        if (valueNode.isFloatingPointNumber()) return valueNode.asDouble();
        return valueNode.asText();
    }

    protected boolean hasPermission(String permission) {
        return permissionChecker.hasPermission(permission);
    }

    protected List<SyncmoneyConfig.NodeConfig> getNodes() {
        return config.getNodes();
    }

    protected ConfigManager getConfigManager() {
        return configManager;
    }

    private ConfigManager configManager;

    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }
}
