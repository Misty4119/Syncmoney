package noietime.syncmoney.web.api.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.ConfigManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles config sync operations between central node and managed nodes.
 *
 * Central node endpoints (push config to nodes):
 * - POST /api/nodes/sync       — Push config to all enabled nodes
 * - POST /api/nodes/{index}/sync — Push config to a single node
 *
 * Node endpoint (receive config from central):
 * - POST /api/config/sync      — Receive and apply config from central node
 */
public class ConfigSyncHandler extends NodesApiContext {

    private static final int NODE_SYNC_TIMEOUT_SECONDS = 15;

    public ConfigSyncHandler(Syncmoney plugin, SyncmoneyConfig config,
                           PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        super(plugin, config, permissionChecker, apiKeyStore);
    }

    

    public void handleSyncConfigToAllNodes(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for config sync");
            return;
        }
        if (!config.isCentralMode()) {
            sendError(exchange, 403, "NOT_CENTRAL_MODE", "Config sync is only available on central node");
            return;
        }

        exchange.dispatch(() -> {
            try {
                String body = readRequestBody(exchange);
                JsonNode rootNode = objectMapper.readTree(body);

                JsonNode changesNode = rootNode.has("changes") ? rootNode.get("changes") : null;
                boolean doReload = !rootNode.has("reload") || rootNode.get("reload").asBoolean(true);

                if (changesNode == null || !changesNode.isArray() || changesNode.isEmpty()) {
                    sendError(exchange, 400, "NO_CHANGES", "No changes provided");
                    return;
                }

                List<ConfigManager.ConfigChange> changes = parseChanges(changesNode);
                if (changes.isEmpty()) {
                    sendError(exchange, 400, "NO_CHANGES", "No valid changes found");
                    return;
                }

                List<SyncmoneyConfig.NodeConfig> nodes = getNodes();
                List<Map<String, Object>> results = new ArrayList<>();
                int succeeded = 0;
                int failed = 0;

                for (int i = 0; i < nodes.size(); i++) {
                    SyncmoneyConfig.NodeConfig node = nodes.get(i);
                    if (!node.enabled) continue;

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("index", i);
                    result.put("name", node.name);

                    SyncResult syncResult = pushConfigToNode(node, changes, doReload);
                    result.put("status", syncResult.success ? "synced" : "failed");
                    if (!syncResult.success) {
                        result.put("error", syncResult.error);
                        failed++;
                    } else {
                        succeeded++;
                    }
                    results.add(result);
                }

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("total", nodes.size());
                data.put("succeeded", succeeded);
                data.put("failed", failed);
                data.put("results", results);
                sendJson(exchange, ApiResponse.success(data));

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to sync config to nodes: " + e.getMessage());
                sendError(exchange, 500, "SYNC_FAILED", e.getMessage());
            }
        });
    }

    

    public void handleSyncConfigToNode(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for config sync");
            return;
        }
        if (!config.isCentralMode()) {
            sendError(exchange, 403, "NOT_CENTRAL_MODE", "Config sync is only available on central node");
            return;
        }

        int index = extractIndex(exchange);
        if (index < 0) {
            sendError(exchange, 400, "INVALID_INDEX", "Invalid node index");
            return;
        }

        exchange.dispatch(() -> {
            try {
                List<SyncmoneyConfig.NodeConfig> nodes = getNodes();
                if (index >= nodes.size()) {
                    sendError(exchange, 404, "NODE_NOT_FOUND", "Node not found at index " + index);
                    return;
                }

                SyncmoneyConfig.NodeConfig node = nodes.get(index);
                if (!node.enabled) {
                    sendError(exchange, 400, "NODE_DISABLED", "Node is disabled");
                    return;
                }

                String body = readRequestBody(exchange);
                JsonNode rootNode = objectMapper.readTree(body);

                JsonNode changesNode = rootNode.has("changes") ? rootNode.get("changes") : null;
                boolean doReload = !rootNode.has("reload") || rootNode.get("reload").asBoolean(true);

                if (changesNode == null || !changesNode.isArray() || changesNode.isEmpty()) {
                    sendError(exchange, 400, "NO_CHANGES", "No changes provided");
                    return;
                }

                List<ConfigManager.ConfigChange> changes = parseChanges(changesNode);
                SyncResult syncResult = pushConfigToNode(node, changes, doReload);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("index", index);
                result.put("name", node.name);
                result.put("status", syncResult.success ? "synced" : "failed");
                if (!syncResult.success) result.put("error", syncResult.error);

                if (syncResult.success) {
                    sendJson(exchange, ApiResponse.success(result));
                } else {
                    exchange.setStatusCode(207); 
                    sendJson(exchange, ApiResponse.error("SYNC_FAILED", syncResult.error));
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to sync config to node: " + e.getMessage());
                sendError(exchange, 500, "SYNC_FAILED", e.getMessage());
            }
        });
    }

    

    public void handleReceiveConfigSync(HttpServerExchange exchange) {
        
        
        exchange.dispatch(() -> {
            try {
                String body = readRequestBody(exchange);
                JsonNode rootNode = objectMapper.readTree(body);

                JsonNode changesNode = rootNode.has("changes") ? rootNode.get("changes") : null;
                boolean doReload = !rootNode.has("reload") || rootNode.get("reload").asBoolean(true);

                if (changesNode == null || !changesNode.isArray() || changesNode.isEmpty()) {
                    sendError(exchange, 400, "NO_CHANGES", "No changes provided");
                    return;
                }

                ConfigManager cm = getConfigManager();
                if (cm == null) {
                    sendError(exchange, 500, "CONFIG_MANAGER_UNAVAILABLE", "ConfigManager not initialized");
                    return;
                }

                List<ConfigManager.ConfigChange> changes = parseChanges(changesNode);
                if (changes.isEmpty()) {
                    sendError(exchange, 400, "NO_CHANGES", "No valid changes found");
                    return;
                }

                boolean saved = cm.saveConfigBatch(changes);
                if (!saved) {
                    sendError(exchange, 500, "SAVE_FAILED", "Failed to save config on node");
                    return;
                }

                if (doReload) cm.hotReload();

                List<String> applied = new ArrayList<>();
                for (ConfigManager.ConfigChange c : changes) {
                    applied.add(c.section() + "." + c.key() + "=" + c.value());
                }

                plugin.getLogger().info("Received config sync from central node, applied " +
                        changes.size() + " changes" + (doReload ? " with reload" : " without reload"));

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("applied", changes.size());
                result.put("reloaded", doReload);
                result.put("changes", applied);
                sendJson(exchange, ApiResponse.success(result));

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to receive config sync: " + e.getMessage());
                sendError(exchange, 500, "SYNC_RECEIVE_FAILED", e.getMessage());
            }
        });
    }

    

    private List<ConfigManager.ConfigChange> parseChanges(JsonNode changesNode) {
        List<ConfigManager.ConfigChange> changes = new ArrayList<>();
        for (JsonNode changeNode : changesNode) {
            String section = changeNode.has("section") ? changeNode.get("section").asText() : null;
            String key = changeNode.has("key") ? changeNode.get("key").asText() : null;
            JsonNode valueNode = changeNode.get("value");
            if (section != null && key != null && valueNode != null) {
                changes.add(new ConfigManager.ConfigChange(section, key, parseValue(valueNode)));
            }
        }
        return changes;
    }

    private SyncResult pushConfigToNode(SyncmoneyConfig.NodeConfig node,
                                       List<ConfigManager.ConfigChange> changes,
                                       boolean doReload) {
        if (!node.enabled) return new SyncResult(false, "node_disabled");

        try {
            String apiKey = node.apiKey;
            if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
            }

            URI uri = URI.create(node.url);
            String baseUrl = node.url.replace(uri.getPath(), "");
            String targetUrl = baseUrl + "/api/config/sync";

            List<Map<String, Object>> changesList = new ArrayList<>();
            for (ConfigManager.ConfigChange change : changes) {
                Map<String, Object> changeMap = new LinkedHashMap<>();
                changeMap.put("section", change.section());
                changeMap.put("key", change.key());
                changeMap.put("value", change.value());
                changesList.add(changeMap);
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("changes", changesList);
            requestBody.put("reload", doReload);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(NODE_SYNC_TIMEOUT_SECONDS))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new SyncResult(true, null);
            } else {
                return new SyncResult(false, "http_" + response.statusCode());
            }

        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Network error pushing config to node " + node.name + ": " + e.getMessage());
            return new SyncResult(false, "network_error");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to push config to node " + node.name + ": " + e.getMessage());
            return new SyncResult(false, e.getClass().getSimpleName());
        }
    }

    private record SyncResult(boolean success, String error) {}
}
