package noietime.syncmoney.web.api.nodes;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles node status queries with parallel fetching.
 * Endpoint: GET /api/nodes/status — Get detailed status of all nodes.
 */
public class NodeStatusHandler extends NodesApiContext {

    public NodeStatusHandler(Syncmoney plugin, SyncmoneyConfig config,
                          PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        super(plugin, config, permissionChecker, apiKeyStore);
    }

    public void handleGetNodesStatus(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_VIEW) && !hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node status viewing");
            return;
        }

        List<SyncmoneyConfig.NodeConfig> nodes = getNodes();

        
        Map<String, Object> allStatuses = nodes.stream().collect(
                java.util.stream.Collectors.toConcurrentMap(
                        node -> node.url,
                        node -> fetchNodeStatusWithTimeout(node)
                )
        );

        sendJson(exchange, ApiResponse.success(allStatuses));
    }

    private Map<String, Object> fetchNodeStatusWithTimeout(SyncmoneyConfig.NodeConfig node) {
        Map<String, Object> nodeStatus = new java.util.LinkedHashMap<>();

        try {
            CompletableFuture<NodeDetailedStatus> future = CompletableFuture.supplyAsync(
                    () -> fetchNodeDetailedStatus(node));

            NodeDetailedStatus detailed = future.get(5, TimeUnit.SECONDS);

            if (detailed != null) {
                nodeStatus.put("serverName", detailed.serverName());
                nodeStatus.put("serverId", detailed.serverId());
                nodeStatus.put("onlinePlayers", detailed.onlinePlayers());
                nodeStatus.put("maxPlayers", detailed.maxPlayers());
                nodeStatus.put("economyMode", detailed.economyMode());
                nodeStatus.put("status", detailed.online() ? "online" : "offline");
                nodeStatus.put("lastPing", detailed.lastPing());
            } else {
                nodeStatus.put("status", "offline");
                nodeStatus.put("serverName", node.name);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            plugin.getLogger().fine("Timeout fetching status from node " + node.name + ": " + e.getMessage());
            nodeStatus.put("status", "unknown");
            nodeStatus.put("serverName", node.name);
            nodeStatus.put("stale", true);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to fetch status from node " + node.name + ": " + e.getMessage());
            nodeStatus.put("status", "offline");
            nodeStatus.put("serverName", node.name);
        }

        return nodeStatus;
    }

    private NodeDetailedStatus fetchNodeDetailedStatus(SyncmoneyConfig.NodeConfig node) {
        if (!node.enabled) return null;

        try {
            String apiKey = node.apiKey;
            if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
            }

            URI uri = URI.create(node.url);
            String statusUrl = node.url.replace(uri.getPath(), "") + "/api/system/status";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET().build();

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

    @SuppressWarnings("unchecked")
    private NodeDetailedStatus parseDetailedStatus(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(json, Map.class);

            Map<String, Object> responseData = (Map<String, Object>) data.get("data");
            if (responseData == null) responseData = data;

            Map<String, Object> pluginInfo = (Map<String, Object>) responseData.get("plugin");
            String serverName = "unknown";
            String serverId = "unknown";
            String economyMode = "unknown";

            if (pluginInfo != null) {
                serverName = (String) responseData.getOrDefault("serverName", "unknown");
                serverId = (String) pluginInfo.getOrDefault("serverId", "unknown");
                economyMode = (String) pluginInfo.getOrDefault("mode", "unknown");
            }

            int onlinePlayers = 0;
            int maxPlayers = 0;

            Object opObj = responseData.get("onlinePlayers");
            if (opObj instanceof Number) onlinePlayers = ((Number) opObj).intValue();

            Object mpObj = responseData.get("maxPlayers");
            if (mpObj instanceof Number) maxPlayers = ((Number) mpObj).intValue();

            return new NodeDetailedStatus(serverName, serverId, economyMode, onlinePlayers,
                    maxPlayers, System.currentTimeMillis(), true);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to parse detailed status: " + e.getMessage());
            return null;
        }
    }

    private record NodeDetailedStatus(
            String serverName,
            String serverId,
            String economyMode,
            int onlinePlayers,
            int maxPlayers,
            long lastPing,
            boolean online
    ) {}
}
