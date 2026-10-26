package noietime.syncmoney.web.api.nodes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Handles node proxy requests from central node to remote nodes.
 * Endpoint: POST /api/nodes/{index}/proxy — Proxy requests through central node.
 */
public class NodeProxyHandler extends NodesApiContext {

    private static final int MAX_PATH_DECODE_PASSES = 4;
    static final int MAX_PROXY_REQUEST_BODY_BYTES = 1024 * 1024;
    static final int MAX_PROXY_RESPONSE_BODY_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper REQUEST_BODY_MAPPER = new ObjectMapper();
    private static final Set<String> VIEW_PROXY_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> MANAGE_PROXY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    public NodeProxyHandler(Syncmoney plugin, SyncmoneyConfig config,
                           PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        super(plugin, config, permissionChecker, apiKeyStore);
    }

    public void handleNodeProxy(HttpServerExchange exchange) {
        if (!hasApiKeyPermission(exchange, PERMISSION_VIEW)
                && !hasApiKeyPermission(exchange, PERMISSION_CENTRAL)) {
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

                String body;
                try {
                    body = readProxyRequestBody(exchange);
                } catch (ProxyRequestBodyTooLargeException oversized) {
                    sendError(exchange, 413, "REQUEST_TOO_LARGE",
                            "Node proxy request body exceeds 1 MiB");
                    return;
                }
                Map<String, Object> request = parseJson(body);

                String method = (String) request.get("method");
                String path = (String) request.get("path");
                Object requestBody = request.get("body");

                if (method == null || method.isBlank()) {
                    sendError(exchange, 400, "INVALID_METHOD", "Method is required");
                    return;
                }
                String normalizedMethod;
                try {
                    normalizedMethod = normalizeProxyMethod(method);
                } catch (IllegalArgumentException invalidMethod) {
                    sendError(exchange, 400, "INVALID_METHOD", "Unsupported proxy HTTP method");
                    return;
                }
                if (!hasProxyMethodPermission(normalizedMethod,
                        permission -> hasApiKeyPermission(exchange, permission))) {
                    sendError(exchange, 403, "NODE_ACCESS_DENIED",
                            "Permission denied for this proxied HTTP method");
                    return;
                }
                if (path == null || path.isBlank()) {
                    sendError(exchange, 400, "INVALID_PATH", "Path is required");
                    return;
                }

                URI targetUri;
                try {
                    targetUri = resolveNodeTarget(node.url, path);
                } catch (IllegalArgumentException invalidPath) {
                    sendError(exchange, 400, "INVALID_PATH",
                            "Proxy path must be a safe /api path on the configured node origin");
                    return;
                }

                String targetPath = targetUri.getRawPath();
                String permissionPath = targetPath.startsWith("/") ? targetPath.substring(1) : targetPath;
                String targetPermission = permissionChecker == null
                        ? null
                        : permissionChecker.getRequiredPermission(permissionPath);
                if (!hasProxyTargetPermission(targetPermission,
                        permission -> hasApiKeyPermission(exchange, permission))) {
                    sendError(exchange, 403, "NODE_ACCESS_DENIED",
                            "Permission denied for the target API path");
                    return;
                }

                String apiKey = node.apiKey;
                if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                    apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
                }

                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5)).build();

                java.net.http.HttpResponse<InputStream> response = client.send(
                        buildNodeRequest(targetUri.toASCIIString(), apiKey, normalizedMethod, requestBody),
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                String responseBody;
                try {
                    responseBody = readProxyResponseBody(response);
                } catch (ProxyResponseBodyTooLargeException oversized) {
                    sendError(exchange, 502, "PROXY_RESPONSE_TOO_LARGE",
                            "Node proxy upstream response exceeds 8 MiB");
                    return;
                }

                exchange.setStatusCode(response.statusCode());
                exchange.getResponseHeaders().put(
                        io.undertow.util.Headers.CONTENT_TYPE,
                        "application/json");
                exchange.getResponseSender().send(responseBody);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to proxy request to node: " + e.getMessage());
                sendError(exchange, 500, "PROXY_FAILED", "Failed to proxy request: " + e.getMessage());
            }
        });
    }

    static URI resolveNodeTarget(String nodeUrl, String path) {
        if (nodeUrl == null || nodeUrl.isBlank() || path == null || path.isBlank()) {
            throw new IllegalArgumentException("Node URL and proxy path are required");
        }
        try {
            URI configuredNode = new URI(nodeUrl);
            String scheme = configuredNode.getScheme();
            if (!configuredNode.isAbsolute() || configuredNode.getHost() == null
                    || configuredNode.getRawUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Configured node URL must have an HTTP(S) origin");
            }

            URI requestPath = new URI(path);
            if (requestPath.isAbsolute() || requestPath.getRawAuthority() != null
                    || requestPath.getRawFragment() != null || requestPath.getRawPath() == null
                    || requestPath.getRawPath().isBlank()) {
                throw new IllegalArgumentException("Proxy path must be relative to the configured origin");
            }

            String rawPath = requestPath.getRawPath();
            rejectPathTraversal(rawPath);
            String rootedPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
            if (!(rootedPath.equals("/api") || rootedPath.startsWith("/api/"))) {
                throw new IllegalArgumentException("Proxy path must be under /api");
            }

            URI origin = new URI(scheme.toLowerCase(java.util.Locale.ROOT), null,
                    configuredNode.getHost(), configuredNode.getPort(), null, null, null);
            URI targetPath = new URI(rootedPath
                    + (requestPath.getRawQuery() == null ? "" : "?" + requestPath.getRawQuery()));
            URI target = origin.resolve(targetPath);
            if (!origin.getScheme().equalsIgnoreCase(target.getScheme())
                    || !origin.getHost().equalsIgnoreCase(target.getHost())
                    || origin.getPort() != target.getPort()) {
                throw new IllegalArgumentException("Proxy path cannot change the configured node origin");
            }
            return target;
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid node proxy URI", invalid);
        }
    }

    private static void rejectPathTraversal(String rawPath) {
        String decodedPath = rawPath;
        for (int decode = 0; ; decode++) {
            if (decodedPath.indexOf('\\') >= 0 || decodedPath.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Proxy path contains a blocked character");
            }
            for (String segment : decodedPath.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Proxy path cannot contain dot segments");
                }
            }

            if (!containsPercentEscape(decodedPath)) return;
            if (decode == MAX_PATH_DECODE_PASSES) {
                throw new IllegalArgumentException("Proxy path has excessive encoding");
            }
            String next = URLDecoder.decode(decodedPath.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (next.equals(decodedPath)) return;
            decodedPath = next;
        }
    }

    private static boolean containsPercentEscape(String value) {
        for (int i = 0; i + 2 < value.length(); i++) {
            if (value.charAt(i) == '%' && Character.digit(value.charAt(i + 1), 16) >= 0
                    && Character.digit(value.charAt(i + 2), 16) >= 0) {
                return true;
            }
        }
        return false;
    }

    static java.net.http.HttpRequest buildNodeRequest(String targetUrl, String apiKey,
                                                      String method, Object requestBody) {
        String normalizedMethod = normalizeProxyMethod(method);
        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(30));

        if ("POST".equals(normalizedMethod) || "PUT".equals(normalizedMethod)
                || "PATCH".equals(normalizedMethod)) {
            String body = requestBody != null ? serializeRequestBody(requestBody) : "";
            requestBuilder.method(normalizedMethod, java.net.http.HttpRequest.BodyPublishers.ofString(body));
        } else {
            requestBuilder.method(normalizedMethod, java.net.http.HttpRequest.BodyPublishers.noBody());
        }
        return requestBuilder.build();
    }

    private static String serializeRequestBody(Object requestBody) {
        try {
            return REQUEST_BODY_MAPPER.writeValueAsString(requestBody);
        } catch (JsonProcessingException invalidBody) {
            throw new IllegalArgumentException("Unable to serialize node proxy request body", invalidBody);
        }
    }

    private boolean hasApiKeyPermission(HttpServerExchange exchange, String permission) {
        String apiKey = extractBearerToken(exchange);
        return apiKey != null && permissionChecker != null
                && permissionChecker.hasPermission(apiKey, permission);
    }

    static boolean hasProxyTargetPermission(String requiredPermission, Predicate<String> permissionCheck) {
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return permissionCheck.test(PERMISSION_CENTRAL);
        }
        return permissionCheck.test(requiredPermission) || permissionCheck.test(PERMISSION_CENTRAL);
    }

    private static String readProxyRequestBody(HttpServerExchange exchange)
            throws IOException, ProxyRequestBodyTooLargeException {
        if (exchange.getRequestContentLength() > MAX_PROXY_REQUEST_BODY_BYTES) {
            throw new ProxyRequestBodyTooLargeException();
        }

        exchange.startBlocking();
        byte[] body = exchange.getInputStream().readNBytes(MAX_PROXY_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_PROXY_REQUEST_BODY_BYTES) {
            throw new ProxyRequestBodyTooLargeException();
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readProxyResponseBody(java.net.http.HttpResponse<InputStream> response)
            throws IOException, ProxyResponseBodyTooLargeException {
        try (InputStream body = response.body()) {
            if (response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1L) > MAX_PROXY_RESPONSE_BODY_BYTES) {
                throw new ProxyResponseBodyTooLargeException();
            }

            byte[] content = body.readNBytes(MAX_PROXY_RESPONSE_BODY_BYTES + 1);
            if (content.length > MAX_PROXY_RESPONSE_BODY_BYTES) {
                throw new ProxyResponseBodyTooLargeException();
            }
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private static final class ProxyRequestBodyTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class ProxyResponseBodyTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static boolean hasProxyMethodPermission(String method, Predicate<String> permissionCheck) {
        String required = requiredProxyMethodPermission(method);
        return permissionCheck.test(required) || permissionCheck.test(PERMISSION_CENTRAL);
    }

    private static String requiredProxyMethodPermission(String method) {
        String normalized = normalizeProxyMethod(method);
        if (VIEW_PROXY_METHODS.contains(normalized)) {
            return PERMISSION_VIEW;
        }
        return PERMISSION_MANAGE;
    }

    private static String normalizeProxyMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Proxy HTTP method is required");
        }

        String normalized = method.toUpperCase(java.util.Locale.ROOT);
        if (!VIEW_PROXY_METHODS.contains(normalized) && !MANAGE_PROXY_METHODS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported proxy HTTP method");
        }
        return normalized;
    }
}
