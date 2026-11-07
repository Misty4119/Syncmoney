package noietime.syncmoney.web.api.auth;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.api.AbstractApiHandler;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.WebAdminConfig;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SEC-04 FIX] Issues one-time session tokens for the SSE endpoint.
 *
 * The legacy {@code /api/auth/ws-token} route name remains for compatibility.
 * The token avoids passing the API key directly in the SSE URL.
 *
 * Flow:
 * 1. Client POSTs to {@code /api/auth/ws-token} with Authorization: Bearer <api-key>.
 * 2. Server validates the API key and generates a short-lived token.
 * 3. Client opens {@code /api/sse?token=...} with the token.
 * 4. The SSE handler atomically consumes the token before opening the stream.
 */
public class WsTokenHandler extends AbstractApiHandler {

    private final WebAdminConfig config;
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    
    private static final long TOKEN_VALIDITY_MS = 60000;

    public WsTokenHandler(Syncmoney plugin, WebAdminConfig config) {
        super(plugin);
        this.config = config;
    }

    /**
     * Register the /api/auth/ws-token route.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.post("api/auth/ws-token", this::handleTokenRequest);
    }

    /**
     * Handle POST /api/auth/ws-token request.
     */
    private void handleTokenRequest(HttpServerExchange exchange) {

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendError(exchange, "INVALID_AUTH", "Missing or invalid Authorization header");
            return;
        }

        String providedKey = authHeader.substring(7);
        String expectedKey = config.getApiKey();


        if (!constantTimeEquals(providedKey, expectedKey)) {
            sendError(exchange, "INVALID_API_KEY", "Invalid API key");
            return;
        }


        long now = System.currentTimeMillis();
        String token = issueToken(now);
        long expires = now + TOKEN_VALIDITY_MS;


        String response = ApiResponse.success(Map.of(
            "token", token,
            "expires", expires,
            "validityMs", TOKEN_VALIDITY_MS
        ));

        exchange.getResponseHeaders().put(
            io.undertow.util.Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(200);
        exchange.getResponseSender().send(response);

        plugin.getLogger().fine("Generated SSE session token");
    }

    /**
     * Validate and consume a one-time token for the SSE connection.
     * Returns true only for an unexpired token that has not already been used.
     */
    public boolean validateToken(String token) {
        return validateToken(token, System.currentTimeMillis());
    }

    String issueToken(long nowMillis) {
        String token = UUID.randomUUID().toString();
        long expires = nowMillis + TOKEN_VALIDITY_MS;
        tokens.put(token, new TokenEntry(token, expires));
        cleanupExpiredTokens();
        return token;
    }

    boolean validateToken(String token, long nowMillis) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // remove(key) is atomic: concurrent SSE requests cannot both consume
        // the same short-lived session token.
        TokenEntry entry = tokens.remove(token);
        return entry != null && nowMillis < entry.expires;
    }

    /**
     * Clean up expired tokens to prevent memory buildup.
     */
    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(entry -> entry.getValue().expires <= now);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Send error response.
     */
    private void sendError(HttpServerExchange exchange, String code, String message) {
        String response = ApiResponse.error(code, message);
        exchange.getResponseHeaders().put(
            io.undertow.util.Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(401);
        exchange.getResponseSender().send(response);
    }

    /**
     * Token entry with expiration time.
     */
    private static class TokenEntry {
        final String token;
        final long expires;

        TokenEntry(String token, long expires) {
            this.token = token;
            this.expires = expires;
        }
    }
}
