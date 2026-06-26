package noietime.syncmoney.web.security;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.permission.AdminPermissionService;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permission checker for API endpoints.
 * Integrates with AdminPermissionService for role-based access control.
 */
public class PermissionChecker {

    private final Syncmoney plugin;
    private final SyncmoneyConfig syncmoneyConfig;
    private final Map<String, Set<String>> apiKeyPermissions = new ConcurrentHashMap<>();

    /**
     * Built-in default endpoint → permission mapping. Used as a fallback when no
     * {@code web-admin.endpoint-permissions} section is present in config, so the
     * shipped config.yml does not need to change.
     */
    private static final Map<String, String> DEFAULT_ENDPOINT_PERMISSIONS = Map.ofEntries(
            Map.entry("api/audit/player", "syncmoney.admin.audit"),
            Map.entry("api/audit/search", "syncmoney.admin.audit"),
            Map.entry("api/audit/search-cursor", "syncmoney.admin.audit"),
            Map.entry("api/audit/stats", "syncmoney.admin.audit"),
            Map.entry("api/system/status", "syncmoney.admin"),
            Map.entry("api/system/metrics", "syncmoney.admin"),
            Map.entry("api/system/redis", "syncmoney.admin"),
            Map.entry("api/system/breaker", "syncmoney.admin"),
            Map.entry("api/config", "syncmoney.admin"),
            Map.entry("api/config/reload", "syncmoney.admin"),
            Map.entry("api/config/validate", "syncmoney.admin"),
            Map.entry("api/settings/timezone", "syncmoney.admin"),
            Map.entry("api/settings/theme", "syncmoney.admin"),
            Map.entry("api/nodes", "syncmoney.web.nodes.view"),
            Map.entry("api/nodes/manage", "syncmoney.web.nodes.manage"));


    private final Map<String, String> endpointPermissions;

    public PermissionChecker(Syncmoney plugin) {
        this(plugin, plugin.getSyncmoneyConfig());
    }

    public PermissionChecker(Syncmoney plugin, SyncmoneyConfig syncmoneyConfig) {
        this.plugin = plugin;
        this.syncmoneyConfig = syncmoneyConfig;
        this.endpointPermissions = loadEndpointPermissions();
        loadPermissionsFromConfig();
    }

    /**
     * Resolve the FileConfiguration to read from, preferring the injected
     * {@link SyncmoneyConfig} (never bypassing it via {@code plugin.getConfig()}).
     */
    private Configuration resolveConfig() {
        if (syncmoneyConfig != null && syncmoneyConfig.getConfig() != null) {
            return syncmoneyConfig.getConfig();
        }
        return plugin.getConfig();
    }

    private Map<String, String> loadEndpointPermissions() {
        Map<String, String> result = new LinkedHashMap<>(DEFAULT_ENDPOINT_PERMISSIONS);
        try {
            ConfigurationSection section = resolveConfig().getConfigurationSection("web-admin.endpoint-permissions");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String permission = section.getString(key);
                    if (permission != null && !permission.isBlank()) {
                        result.put(key, permission);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load endpoint permissions from config, using defaults: " + e.getMessage());
        }
        return result;
    }

    /**
     * Load API key permissions from configuration.
     */
    private void loadPermissionsFromConfig() {
        try {
            Configuration config = resolveConfig();
            ConfigurationSection keysSection = config.getConfigurationSection("web-admin.api-keys");

            if (keysSection != null) {
                for (String key : keysSection.getKeys(false)) {
                    ConfigurationSection keySection = keysSection.getConfigurationSection(key);
                    if (keySection != null) {
                        List<String> permissions = keySection.getStringList("permissions");
                        if (permissions != null && !permissions.isEmpty()) {
                            apiKeyPermissions.put(key, new HashSet<>(permissions));
                            plugin.getLogger().fine("Loaded permissions for API key: " + key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load API key permissions: " + e.getMessage());
        }
    }

    /**
     * Check if the request has permission for the given endpoint.
     *
     * @param exchange HTTP exchange
     * @param apiKey   The API key used for authentication
     * @param endpoint The API endpoint path
     * @return true if permission is granted
     */
    public boolean checkPermission(HttpServerExchange exchange, String apiKey, String endpoint) {
        String requiredPermission = getRequiredPermission(endpoint);

        if (requiredPermission == null) {
            return true;
        }

        return hasPermission(apiKey, requiredPermission);
    }

    /**
     * Get the required permission for an endpoint.
     *
     * @param endpoint The API endpoint path
     * @return Required permission string, or null if no permission required
     */
    public String getRequiredPermission(String endpoint) {
        for (Map.Entry<String, String> entry : endpointPermissions.entrySet()) {
            if (endpoint.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Check if the API key has the specified permission.
     * Uses config-based permissions if available, otherwise falls back to console permission check.
     *
     * @param apiKey             The API key used for authentication
     * @param requiredPermission The required permission
     * @return true if has permission
     */
    public boolean hasPermission(String apiKey, String requiredPermission) {
        Set<String> configuredPermissions = apiKeyPermissions.get(apiKey);
        if (configuredPermissions != null) {
            if (configuredPermissions.contains("*") || configuredPermissions.contains("full-access")) {
                return true;
            }
            if (configuredPermissions.contains(requiredPermission)) {
                return true;
            }
            plugin.getLogger().warning("API key denied: missing permission " + requiredPermission);
            return false;
        }

        return hasConsolePermission(requiredPermission);
    }

    /**
     * Check console permission (fallback method).
     *
     * @param permission The permission string to check
     * @return true if has permission
     */
    private boolean hasConsolePermission(String permission) {
        try {
            var server = plugin.getServer();
            if (server == null) {
                plugin.getLogger().warning("Server is null, denying access");
                return false;
            }

            var console = server.getConsoleSender();
            if (console == null) {
                return false;
            }

            return console.hasPermission(permission);
        } catch (Exception e) {
            plugin.getLogger().severe("Error checking permission: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if the current context has the specified permission.
     * Uses console sender for permission check.
     *
     * @param permission The permission string to check
     * @return true if has permission
     */
    public boolean hasPermission(String permission) {
        return hasConsolePermission(permission);
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(
                    a.getBytes(StandardCharsets.UTF_8),
                    b.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    /**
     * Check permission level for admin operations.
     *
     * @param requiredLevel The minimum required permission level
     * @return true if the current user has the required level
     */
    public boolean hasPermissionLevel(AdminPermissionService.PermissionLevel requiredLevel) {
        try {
            var server = plugin.getServer();
            if (server == null) {
                return false;
            }

            var console = server.getConsoleSender();
            if (console == null) {
                return false;
            }

            var permissionService = plugin.getPermissionService();
            if (permissionService == null) {
                return false;
            }

            var level = permissionService.getPermissionLevel(console);
            if (level == null) {
                return false;
            }

            return level.getLevel() >= requiredLevel.getLevel();
        } catch (Exception e) {
            plugin.getLogger().severe("Error checking permission level: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the current permission level for the API caller.
     *
     * @return The current permission level, or null if not authorized
     */
    public AdminPermissionService.PermissionLevel getCurrentLevel() {
        try {
            var permissionService = plugin.getPermissionService();
            if (permissionService == null) {
                return null;
            }

            var server = plugin.getServer();
            if (server == null) {
                return null;
            }

            return permissionService.getPermissionLevel(server.getConsoleSender());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if the endpoint requires authentication.
     *
     * @param endpoint The API endpoint path
     * @return true if authentication is required
     */
    public boolean requiresAuthentication(String endpoint) {
        if (endpoint.startsWith("api/")) {
            return true;
        }
        if (endpoint.startsWith("health")) {
            return false;
        }
        return false;
    }
}
