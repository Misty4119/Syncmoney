package noietime.syncmoney.web.api.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles node CRUD operations: list, create, update, delete, ping.
 * Endpoints:
 * - GET    /api/nodes        — List all nodes
 * - GET    /api/nodes/status — Get detailed status of all nodes
 * - POST   /api/nodes         — Create a new node
 * - PUT    /api/nodes/{index} — Update a node
 * - DELETE /api/nodes/{index} — Delete a node
 * - POST   /api/nodes/{index}/ping — Ping a single node
 */
public class NodeOperationsHandler extends NodesApiContext {

    private final Map<String, String> nodeStatusCache = new ConcurrentHashMap<>();

    public NodeOperationsHandler(Syncmoney plugin, SyncmoneyConfig config,
                               PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        super(plugin, config, permissionChecker, apiKeyStore);
    }

    public void setNodeStatusCache(Map<String, String> cache) {
        // Allow external cache injection from NodeHealthChecker
    }

    public void updateNodeStatus(String nodeUrl, String status) {
        nodeStatusCache.put(nodeUrl, status);
    }

    public String getNodeStatus(String nodeUrl) {
        return nodeStatusCache.getOrDefault(nodeUrl, "unknown");
    }

    // ─── Route entry points ───────────────────────────────────────────────────

    public void handleGetNodes(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_VIEW) && !hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node viewing");
            return;
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < getNodes().size(); i++) {
            SyncmoneyConfig.NodeConfig node = getNodes().get(i);
            Map<String, Object> nodeData = new LinkedHashMap<>();
            nodeData.put("index", i);
            nodeData.put("name", node.name);
            nodeData.put("url", node.url);
            nodeData.put("apiKey", maskApiKey(node.apiKey));
            nodeData.put("enabled", node.enabled);
            nodeData.put("status", nodeStatusCache.getOrDefault(node.url, node.enabled ? "unknown" : "disabled"));
            nodes.add(nodeData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("centralMode", config.isCentralMode());
        result.put("total", nodes.size());
        result.put("selfUrl", config.getConfig().getString("web-admin.central-node.url", null));
        sendJson(exchange, ApiResponse.success(result));
    }

    public void handleCreateNode(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_MANAGE)) {
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

            List<SyncmoneyConfig.NodeConfig> nodes = getNodes();
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

            Map<String, Object> nodeMap = new LinkedHashMap<>();
            nodeMap.put("name", name);
            nodeMap.put("url", url);
            nodeMap.put("api-key", newNode.apiKey);
            nodeMap.put("enabled", newNode.enabled);
            nodeMaps.add(nodeMap);

            config.getConfig().set("web-admin.nodes", nodeMaps);
            plugin.saveConfig();
            nodeStatusCache.put(newNode.url, "unknown");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", newIndex);
            result.put("name", name);
            result.put("url", url);
            result.put("enabled", newNode.enabled);
            result.put("status", "unknown");
            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create node: " + e.getMessage());
            sendError(exchange, 500, "CREATE_NODE_FAILED", "Failed to create node: " + e.getMessage());
        }
    }

    public void handleUpdateNode(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_MANAGE)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node management");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        try {
            List<SyncmoneyConfig.NodeConfig> nodes = getNodes();
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

            if (name != null && !name.isBlank()) updateNodeInConfig(index, "name", name);
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
            if (enabled != null) updateNodeInConfig(index, "enabled", enabled);

            plugin.saveConfig();

            SyncmoneyConfig.NodeConfig node = nodes.get(index);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("name", node.name);
            result.put("url", node.url);
            result.put("apiKey", maskApiKey(node.apiKey));
            result.put("enabled", node.enabled);
            result.put("status", nodeStatusCache.getOrDefault(node.url, "unknown"));
            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update node: " + e.getMessage());
            sendError(exchange, 500, "UPDATE_NODE_FAILED", "Failed to update node: " + e.getMessage());
        }
    }

    public void handleDeleteNode(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_MANAGE)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node management");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        try {
            List<SyncmoneyConfig.NodeConfig> nodes = getNodes();
            if (index >= nodes.size()) {
                sendError(exchange, 404, "NODE_NOT_FOUND", "Node not found at index " + index);
                return;
            }

            SyncmoneyConfig.NodeConfig nodeToDelete = nodes.get(index);
            String nodeUrl = nodeToDelete.url;

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

            nodeStatusCache.remove(nodeUrl);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", true);
            result.put("index", index);
            result.put("deletedUrl", nodeUrl);
            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to delete node: " + e.getMessage());
            sendError(exchange, 500, "DELETE_NODE_FAILED", "Failed to delete node: " + e.getMessage());
        }
    }

    public void handlePingNode(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_VIEW) && !hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node operations");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        try {
            List<SyncmoneyConfig.NodeConfig> nodes = getNodes();
            if (index >= nodes.size()) {
                sendError(exchange, 404, "NODE_NOT_FOUND", "Node not found at index " + index);
                return;
            }

            SyncmoneyConfig.NodeConfig node = nodes.get(index);
            String status = pingNode(node);
            nodeStatusCache.put(node.url, status);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("name", node.name);
            result.put("status", status);
            result.put("url", node.url);
            sendJson(exchange, ApiResponse.success(result));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to ping node: " + e.getMessage());
            sendError(exchange, 500, "PING_FAILED", "Failed to ping node: " + e.getMessage());
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    private void updateNodeInConfig(int index, String field, Object value) {
        config.getConfig().set("web-admin.nodes." + index + "." + field, value);
    }

    private String pingNode(SyncmoneyConfig.NodeConfig node) {
        if (!node.enabled) return "disabled";
        try {
            String apiKey = node.apiKey;
            if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
            }

            URI uri = URI.create(node.url);
            String pingUrl = node.url.replace(uri.getPath(), "") + "/api/system/status";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(pingUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET().build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200 ? "online" : "offline";
        } catch (Exception e) {
            plugin.getLogger().fine("Node ping failed for " + node.name + ": " + e.getMessage());
            return "offline";
        }
    }
}
