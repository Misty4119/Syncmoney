package noietime.syncmoney.web.api.auth;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.WebAdminConfig;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SEC-04 FIX] Handler for WebSocket session token generation.
 * 
 * This endpoint generates a one-time session token for WebSocket connections,
 * avoiding the need to pass API keys directly in the URL.
 * 
 * Flow:
 * 1. Client POSTs to /api/auth/ws-token with Authorization: Bearer <api-key>
 * 2. Server validates the API key and generates a short-lived token
 * 3. Client uses the token to connect via WebSocket: /ws?token=xxx
 * 4. Server validates the token and establishes the connection
 */
public class WsTokenHandler {

    private final Syncmoney plugin;
    private final WebAdminConfig config;
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    
    private static final long TOKEN_VALIDITY_MS = 60000;

    public WsTokenHandler(Syncmoney plugin, WebAdminConfig config) {
        this.plugin = plugin;
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


        String token = UUID.randomUUID().toString();
        long expires = System.currentTimeMillis() + TOKEN_VALIDITY_MS;


        tokens.put(token, new TokenEntry(token, expires));


        cleanupExpiredTokens();


        String response = ApiResponse.success(Map.of(
            "token", token,
            "expires", expires,
            "validityMs", TOKEN_VALIDITY_MS
        ));

        exchange.getResponseHeaders().put(
            io.undertow.util.Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(200);
        exchange.getResponseSender().send(response);

        plugin.getLogger().fine("Generated WebSocket session token");
    }

    /**
     * Validate a session token for WebSocket connection.
     * Returns true if valid, false otherwise.
     */
    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        TokenEntry entry = tokens.get(token);
        if (entry == null) {
            return false;
        }


        if (System.currentTimeMillis() > entry.expires) {
            tokens.remove(token);
            return false;
        }


        tokens.remove(token);
        return true;
    }

    /**
     * Clean up expired tokens to prevent memory buildup.
     */
    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(entry -> entry.getValue().expires < now);
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
