package noietime.syncmoney.web.security;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.server.WebAdminConfig;

/**
 * API Key authentication filter with rate limiting.
 * Validates Bearer tokens and enforces rate limits.
 */
public class ApiKeyAuthFilter {

    private final Syncmoney plugin;
    private final WebAdminConfig config;
    private final RateLimiter rateLimiter;
    private final PermissionChecker permissionChecker;

    public ApiKeyAuthFilter(Syncmoney plugin, WebAdminConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.rateLimiter = new RateLimiter(config.getRateLimitPerMinute());
        this.permissionChecker = new PermissionChecker(plugin);
    }

    /**
     * Authenticate the request.
     * Checks both rate limiting and API key validation.
     * 
     * @return true if authentication successful
     */
    public boolean authenticate(HttpServerExchange exchange) {
        if (config.isRateLimitEnabled()) {
            String clientId = getClientId(exchange);
            if (!rateLimiter.isAllowed(clientId)) {
                exchange.setStatusCode(429);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
                exchange.getResponseSender().send(
                        "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests, please try again later\"}}");
                return false;
            }
        }

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }

        String token = auth.substring(7);
        return validateToken(token);
    }

    /**
     * Constant-time token comparison to prevent timing attacks.
     */
    private boolean validateToken(String token) {
        String expected = config.getApiKey();
        if (expected == null || token == null)
            return false;
        return java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Check if the client has the required permission.
     * Validates the API token and checks endpoint permissions using PermissionChecker.
     */
    public boolean checkPermission(HttpServerExchange exchange, String requiredPermission) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }

        String token = auth.substring(7);
        String expectedKey = config.getApiKey();


        if (expectedKey == null || !java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return false;
        }

        String path = exchange.getRelativePath();
        return permissionChecker.checkPermission(exchange, token, path);
    }

    /**
     * Get client identifier for rate limiting.
     * Uses X-Forwarded-For header if available (for proxied requests).
     */
    private String getClientId(HttpServerExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }

        if (exchange.getSourceAddress() != null) {
            return exchange.getSourceAddress().getAddress().getHostAddress();
        }

        return "unknown";
    }

    /**
     * Send unauthorized response.
     */
    public static void sendUnauthorized(HttpServerExchange exchange) {
        exchange.setStatusCode(401);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(
                "{\"success\":false,\"error\":{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Missing or invalid authorization header\"}}");
    }

    /**
     * Send forbidden response.
     */
    public static void sendForbidden(HttpServerExchange exchange) {
        exchange.setStatusCode(403);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(
                "{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Insufficient permissions\"}}");
    }

    /**
     * Get the rate limiter instance.
     */
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
