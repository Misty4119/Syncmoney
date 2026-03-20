package noietime.syncmoney.web.api.nodes;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API handler for node management in Central Mode.
 * Provides CRUD operations for managing server nodes.
 *
 * Endpoints:
 * - GET    /api/nodes           — List all nodes
 * - GET    /api/nodes/status   — Get detailed status of all nodes
 * - POST   /api/nodes           — Add a new node (requires syncmoney.web.nodes.manage)
 * - PUT    /api/nodes/{index}  — Update a node (requires syncmoney.web.nodes.manage)
 * - DELETE /api/nodes/{index}  — Delete a node (requires syncmoney.web.nodes.manage)
 * - POST   /api/nodes/{index}/ping — Manually ping a single node
 */
public class NodesApiHandler {

    private static final String PERMISSION_VIEW = "syncmoney.web.nodes.view";
    private static final String PERMISSION_MANAGE = "syncmoney.web.nodes.manage";
    private static final String PERMISSION_CENTRAL = "syncmoney.web.central";

    private static final Set<String> BLOCKED_IP_PREFIXES = Set.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "0."
    );

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback"
    );

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final PermissionChecker permissionChecker;
    private final NodeApiKeyStore apiKeyStore;
    private final Map<Integer, String> nodeStatusCache = new ConcurrentHashMap<>();
    private final Object healthCheckerLock = new Object();
    private volatile noietime.syncmoney.web.nodes.NodeHealthChecker nodeHealthChecker;

    public NodesApiHandler(Syncmoney plugin, SyncmoneyConfig config,
                          PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        this.plugin = plugin;
        this.config = config;
        this.permissionChecker = permissionChecker;
        this.apiKeyStore = apiKeyStore;
    }

    /**
     * Set the NodeHealthChecker reference for retrieving node statuses.
     */
    public void setNodeHealthChecker(noietime.syncmoney.web.nodes.NodeHealthChecker healthChecker) {
        this.nodeHealthChecker = healthChecker;
    }

    /**
     * Register all nodes API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.get("api/nodes", exchange -> handleGetNodes(exchange));
        router.get("api/nodes/status", exchange -> handleGetNodesStatus(exchange));
        router.post("api/nodes", exchange -> handleCreateNode(exchange));
        router.put("api/nodes/{index}", exchange -> handleUpdateNode(exchange));
        router.delete("api/nodes/{index}", exchange -> handleDeleteNode(exchange));
        router.post("api/nodes/{index}/ping", exchange -> handlePingNode(exchange));
    }

    /**
     * GET /api/nodes — List all configured nodes.
     */
    private void handleGetNodes(HttpServerExchange exchange) {
        if (!permissionChecker.hasPermission(PERMISSION_VIEW) &&
            !permissionChecker.hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node viewing");
            return;
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<SyncmoneyConfig.NodeConfig> configuredNodes = config.getNodes();

        for (int i = 0; i < configuredNodes.size(); i++) {
            SyncmoneyConfig.NodeConfig node = configuredNodes.get(i);
            Map<String, Object> nodeData = new LinkedHashMap<>();
            nodeData.put("index", i);
            nodeData.put("name", node.name);
            nodeData.put("url", maskApiKey(node.url));
            nodeData.put("apiKey", maskApiKey(node.apiKey));
            nodeData.put("enabled", node.enabled);
            nodeData.put("status", nodeStatusCache.getOrDefault(i, "unknown"));
            nodes.add(nodeData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("centralMode", config.isCentralMode());
        result.put("total", nodes.size());
        result.put("selfUrl", config.getConfig().getString("web-admin.central-node.url", null));

        sendJson(exchange, ApiResponse.success(result));
    }

    /**
     * GET /api/nodes/status — Get detailed status of all nodes.
     * Returns node health status, online players, max players, and economy mode.
     */
    private void handleGetNodesStatus(HttpServerExchange exchange) {
        if (!permissionChecker.hasPermission(PERMISSION_VIEW) &&
            !permissionChecker.hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node status viewing");
            return;
        }

        List<SyncmoneyConfig.NodeConfig> configuredNodes = config.getNodes();
        Map<String, Object> allStatuses = new LinkedHashMap<>();

        for (int i = 0; i < configuredNodes.size(); i++) {
            SyncmoneyConfig.NodeConfig node = configuredNodes.get(i);
            Map<String, Object> nodeStatus = new LinkedHashMap<>();

            String healthStatus = nodeStatusCache.getOrDefault(i, "unknown");

            try {
                NodeDetailedStatus detailedStatus = fetchNodeDetailedStatus(node);
                if (detailedStatus != null) {
                    nodeStatus.put("serverName", detailedStatus.serverName());
                    nodeStatus.put("serverId", detailedStatus.serverId());
                    nodeStatus.put("onlinePlayers", detailedStatus.onlinePlayers());
                    nodeStatus.put("maxPlayers", detailedStatus.maxPlayers());
                    nodeStatus.put("economyMode", detailedStatus.economyMode());
                    nodeStatus.put("status", detailedStatus.online() ? "online" : healthStatus);
                    nodeStatus.put("lastPing", detailedStatus.lastPing());
                } else {
                    nodeStatus.put("status", "offline");
                    nodeStatus.put("serverName", node.name);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to fetch detailed status from node " + node.name + ": " + e.getMessage());
                nodeStatus.put("status", healthStatus);
                nodeStatus.put("serverName", node.name);
            }

            allStatuses.put(node.url, nodeStatus);
        }

        sendJson(exchange, ApiResponse.success(allStatuses));
    }

    /**
     * Fetch detailed status from a node's /api/system/status endpoint.
     */
    private NodeDetailedStatus fetchNodeDetailedStatus(SyncmoneyConfig.NodeConfig node) {
        if (!node.enabled) {
            return null;
        }

        try {
            String apiKey = node.apiKey;
            if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
            }

            URI uri = URI.create(node.url);
            String statusUrl = node.url.replace(uri.getPath(), "") + "/api/system/status";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseDetailedStatus(response.body());
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Failed to fetch detailed status from " + node.name + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Parse detailed status from JSON response.
     */
    @SuppressWarnings("unchecked")
    private NodeDetailedStatus parseDetailedStatus(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(json, Map.class);

            Map<String, Object> responseData = (Map<String, Object>) data.get("data");
            if (responseData == null) {
                responseData = data;
            }

            Map<String, Object> pluginInfo = (Map<String, Object>) responseData.get("plugin");
            String serverName = "unknown";
            String serverId = "unknown";
            String economyMode = "unknown";

            if (pluginInfo != null) {
                serverName = (String) pluginInfo.getOrDefault("name", "unknown");
                serverId = (String) pluginInfo.getOrDefault("serverId", "unknown");
                economyMode = (String) pluginInfo.getOrDefault("mode", "unknown");
            }

            Integer onlinePlayers = 0;
            Integer maxPlayers = 0;

            Object onlinePlayersObj = responseData.get("onlinePlayers");
            if (onlinePlayersObj instanceof Number) {
                onlinePlayers = ((Number) onlinePlayersObj).intValue();
            }

            Object maxPlayersObj = responseData.get("maxPlayers");
            if (maxPlayersObj instanceof Number) {
                maxPlayers = ((Number) maxPlayersObj).intValue();
            }

            return new NodeDetailedStatus(
                    serverName,
                    serverId,
                    economyMode,
                    onlinePlayers,
                    maxPlayers,
                    System.currentTimeMillis(),
                    true
            );

        } catch (Exception e) {
            plugin.getLogger().fine("Failed to parse detailed status: " + e.getMessage());
            return null;
        }
    }

    /**
     * Record for detailed node status.
     */
    private record NodeDetailedStatus(
            String serverName,
            String serverId,
            String economyMode,
            int onlinePlayers,
            int maxPlayers,
            long lastPing,
            boolean online
    ) {}

    /**
     * POST /api/nodes — Create a new node.
     */
    private void handleCreateNode(HttpServerExchange exchange) {
        if (!permissionChecker.hasPermission(PERMISSION_MANAGE)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node management");
            return;
        }

        try {
            String body = readRequestBody(exchange);
            Map<String, Object> request = parseJson(body);

            String name = (String) request.get("name");
            String url = (String) request.get("url");
            String apiKey = (String) request.get("apiKey");
            Boolean enabled = (Boolean) request.getOrDefault("enabled", true);

            if (name == null || name.isBlank()) {
                sendError(exchange, 400, "INVALID_NODE_NAME", "Node name is required");
                return;
            }

            if (url == null || url.isBlank()) {
                sendError(exchange, 400, "INVALID_NODE_URL", "Node URL is required");
                return;
            }

            if (!isUrlAllowed(url)) {
                sendError(exchange, 400, "SSRF_BLOCKED", "URL is not allowed (SSRF protection)");
                return;
            }

            List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
            int newIndex = nodes.size();

            SyncmoneyConfig.NodeConfig newNode = new SyncmoneyConfig.NodeConfig();
            newNode.name = name;
            newNode.url = url;
            newNode.apiKey = apiKeyStore != null ? apiKeyStore.encrypt(apiKey, getMasterKey()) : apiKey;
            newNode.enabled = enabled != null ? enabled : true;

            List<Map<String, Object>> nodeMaps = new ArrayList<>();
            List<?> rawNodeMaps = config.getConfig().getMapList("web-admin.nodes");
            if (rawNodeMaps != null) {
                for (Object item : rawNodeMaps) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        nodeMaps.add(map);
                    }
                }
            }

            Map<String, Object> nodeMap = new HashMap<>();
            nodeMap.put("name", name);
            nodeMap.put("url", url);
            nodeMap.put("api-key", newNode.apiKey);
            nodeMap.put("enabled", newNode.enabled);
            nodeMaps.add(nodeMap);

            config.getConfig().set("web-admin.nodes", nodeMaps);
            plugin.saveConfig();

            nodeStatusCache.put(newIndex, "unknown");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", newIndex);
            result.put("name", name);
            result.put("url", maskApiKey(url));
            result.put("enabled", newNode.enabled);
            result.put("status", "unknown");

            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create node: " + e.getMessage());
            sendError(exchange, 500, "CREATE_NODE_FAILED", "Failed to create node: " + e.getMessage());
        }
    }

    /**
     * PUT /api/nodes/{index} — Update an existing node.
     */
    private void handleUpdateNode(HttpServerExchange exchange) {
        if (!permissionChecker.hasPermission(PERMISSION_MANAGE)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node management");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        try {
            List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
            if (index >= nodes.size()) {
                sendError(exchange, 404, "NODE_NOT_FOUND", "Node not found at index " + index);
                return;
            }

            String body = readRequestBody(exchange);
            Map<String, Object> request = parseJson(body);

            String name = (String) request.get("name");
            String url = (String) request.get("url");
            String apiKey = (String) request.get("apiKey");
            Boolean enabled = (Boolean) request.get("enabled");

            if (name != null && !name.isBlank()) {
                updateNodeInConfig(index, "name", name);
            }

            if (url != null && !url.isBlank()) {
                if (!isUrlAllowed(url)) {
                    sendError(exchange, 400, "SSRF_BLOCKED", "URL is not allowed (SSRF protection)");
                    return;
                }
                updateNodeInConfig(index, "url", url);
            }

            if (apiKey != null && !apiKey.isBlank()) {
                String encryptedKey = apiKeyStore != null ? apiKeyStore.encrypt(apiKey, getMasterKey()) : apiKey;
                updateNodeInConfig(index, "api-key", encryptedKey);
            }

            if (enabled != null) {
                updateNodeInConfig(index, "enabled", enabled);
            }

            plugin.saveConfig();

            SyncmoneyConfig.NodeConfig node = nodes.get(index);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("name", node.name);
            result.put("url", maskApiKey(node.url));
            result.put("apiKey", maskApiKey(node.apiKey));
            result.put("enabled", node.enabled);
            result.put("status", nodeStatusCache.getOrDefault(index, "unknown"));

            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update node: " + e.getMessage());
            sendError(exchange, 500, "UPDATE_NODE_FAILED", "Failed to update node: " + e.getMessage());
        }
    }

    /**
     * DELETE /api/nodes/{index} — Delete a node.
     */
    private void handleDeleteNode(HttpServerExchange exchange) {
        if (!permissionChecker.hasPermission(PERMISSION_MANAGE)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node management");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        try {
            List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
            if (index >= nodes.size()) {
                sendError(exchange, 404, "NODE_NOT_FOUND", "Node not found at index " + index);
                return;
            }

            List<Map<String, Object>> nodeMaps = new ArrayList<>();
            List<?> rawNodeMaps = config.getConfig().getMapList("web-admin.nodes");
            if (rawNodeMaps != null) {
                for (Object item : rawNodeMaps) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        nodeMaps.add(map);
                    }
                }
            }
            
            if (index < nodeMaps.size()) {
                nodeMaps.remove(index);
                config.getConfig().set("web-admin.nodes", nodeMaps);
                plugin.saveConfig();
            }

            nodeStatusCache.remove(index);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", true);
            result.put("index", index);

            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to delete node: " + e.getMessage());
            sendError(exchange, 500, "DELETE_NODE_FAILED", "Failed to delete node: " + e.getMessage());
        }
    }

    /**
     * POST /api/nodes/{index}/ping — Manually ping a single node.
     */
    private void handlePingNode(HttpServerExchange exchange) {
        if (!permissionChecker.hasPermission(PERMISSION_VIEW) &&
            !permissionChecker.hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node operations");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        try {
            List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
            if (index >= nodes.size()) {
                sendError(exchange, 404, "NODE_NOT_FOUND", "Node not found at index " + index);
                return;
            }

            SyncmoneyConfig.NodeConfig node = nodes.get(index);
            String status = pingNode(node);

            nodeStatusCache.put(index, status);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("name", node.name);
            result.put("status", status);
            result.put("url", maskApiKey(node.url));

            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to ping node: " + e.getMessage());
            nodeStatusCache.put(index, "offline");
            sendError(exchange, 500, "PING_FAILED", "Failed to ping node: " + e.getMessage());
        }
    }

    /**
     * Ping a node by making an HTTP request to its /api/system/status endpoint.
     */
    private String pingNode(SyncmoneyConfig.NodeConfig node) {
        if (!node.enabled) {
            return "disabled";
        }

        try {
            String apiKey = node.apiKey;
            if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
            }

            URI uri = URI.create(node.url);
            String pingUrl = node.url.replace(uri.getPath(), "") + "/api/system/status";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(pingUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return "online";
            } else {
                return "offline";
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Node ping failed for " + node.name + ": " + e.getMessage());
            return "offline";
        }
    }

    /**
     * Update a specific field in the node configuration at the given index.
     */
    private void updateNodeInConfig(int index, String field, Object value) {
        String path = "web-admin.nodes." + index + "." + field;
        config.getConfig().set(path, value);
    }

    /**
     * Check if a URL is allowed (SSRF protection).
     */
    private boolean isUrlAllowed(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            return false;
        }

        try {
            String decodedUrl = URLDecoder.decode(urlString, StandardCharsets.UTF_8);
            URI uri = new URI(decodedUrl);

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }

            String hostLower = host.toLowerCase();
            if (BLOCKED_HOSTNAMES.contains(hostLower)) {
                return false;
            }

            if (hostLower.equals("localhost") || hostLower.endsWith(".local")) {
                return false;
            }

            for (String prefix : BLOCKED_IP_PREFIXES) {
                if (hostLower.startsWith(prefix)) {
                    return false;
                }
            }

            try {
                InetAddress address = InetAddress.getByName(host);
                byte[] addressBytes = address.getAddress();

                if (address.isLoopbackAddress()) {
                    return false;
                }

                if (address.isLinkLocalAddress()) {
                    return false;
                }

                if (address.isSiteLocalAddress()) {
                    return false;
                }

                if (addressBytes.length == 4) {
                    int firstOctet = addressBytes[0] & 0xFF;
                    int secondOctet = addressBytes[1] & 0xFF;

                    if (firstOctet == 10) {
                        return false;
                    }
                    if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
                        return false;
                    }
                    if (firstOctet == 192 && secondOctet == 168) {
                        return false;
                    }
                }
            } catch (Exception e) {
            }

            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().fine("URL validation error for " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Mask API key for display (show only first and last 4 characters).
     */
    private String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * Get master key for API key encryption/decryption.
     * Uses the main API key as the master key.
     */
    private String getMasterKey() {
        return config.getConfig().getString("web-admin.security.api-key", "default-master-key");
    }

    /**
     * Extract node index from URL path.
     */
    private int extractIndex(HttpServerExchange exchange) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
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

    /**
     * Read request body as string.
     */
    private String readRequestBody(HttpServerExchange exchange) throws Exception {
        exchange.startBlocking();
        java.io.InputStream is = exchange.getInputStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Parse JSON string to Map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.readValue(json, Map.class);
    }

    /**
     * Update node status in cache (called by NodeHealthChecker).
     */
    public void updateNodeStatus(int index, String status) {
        nodeStatusCache.put(index, status);
    }

    /**
     * Get current status of a node.
     */
    public String getNodeStatus(int index) {
        return nodeStatusCache.getOrDefault(index, "unknown");
    }

    /**
     * Send error response.
     */
    private void sendError(HttpServerExchange exchange, int statusCode, String code, String message) {
        exchange.setStatusCode(statusCode);
        sendJson(exchange, ApiResponse.error(code, message));
    }

    /**
     * Send JSON response.
     */
    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }
}
