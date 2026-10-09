package noietime.syncmoney.web.api.nodes;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Handles node proxy requests from central node to remote nodes.
 * Endpoint: POST /api/nodes/{index}/proxy — Proxy requests through central node.
 */
public class NodeProxyHandler extends NodesApiContext {

    public NodeProxyHandler(Syncmoney plugin, SyncmoneyConfig config,
                           PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        super(plugin, config, permissionChecker, apiKeyStore);
    }

    public void handleNodeProxy(HttpServerExchange exchange) {
        if (!hasPermission(PERMISSION_VIEW) && !hasPermission(PERMISSION_CENTRAL)) {
            sendError(exchange, 403, "NODE_ACCESS_DENIED", "Permission denied for node proxy operations");
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
                Map<String, Object> request = parseJson(body);

                String method = (String) request.get("method");
                String path = (String) request.get("path");
                Object requestBody = request.get("body");

                if (method == null || method.isBlank()) {
                    sendError(exchange, 400, "INVALID_METHOD", "Method is required");
                    return;
                }
                if (path == null || path.isBlank()) {
                    sendError(exchange, 400, "INVALID_PATH", "Path is required");
                    return;
                }

                // SSRF protection: validate path does not point to internal/blocked resources
                if (!isUrlAllowed(path)) {
                    sendError(exchange, 400, "SSRF_BLOCKED",
                            "Proxy path is blocked due to SSRF protection: " + path);
                    return;
                }

                String apiKey = node.apiKey;
                if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                    apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
                }

                URI uri = URI.create(node.url);
                String baseUrl = node.url.replace(uri.getPath(), "");
                String targetUrl = baseUrl + path;

                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5)).build();

                java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30));

                if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                    String bodyStr = requestBody != null ? requestBody.toString() : "";
                    requestBuilder.method(method.toUpperCase(),
                            java.net.http.HttpRequest.BodyPublishers.ofString(bodyStr));
                } else {
                    requestBuilder.method(method.toUpperCase(),
                            java.net.http.HttpRequest.BodyPublishers.noBody());
                }

                java.net.http.HttpResponse<String> response = client.send(requestBuilder.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());

                exchange.setStatusCode(response.statusCode());
                exchange.getResponseHeaders().put(
                        io.undertow.util.Headers.CONTENT_TYPE,
                        "application/json");
                exchange.getResponseSender().send(response.body());

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to proxy request to node: " + e.getMessage());
                sendError(exchange, 500, "PROXY_FAILED", "Failed to proxy request: " + e.getMessage());
            }
        });
    }
}
