package noietime.syncmoney.web.api;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.security.PermissionChecker;

import java.util.Deque;

/**
 * Base class for all Web Admin REST API handlers.
 *
 * <p>Extracts the boilerplate that used to be copy-pasted into every handler:
 * JSON/error responses, Bearer-token extraction, permission gating, query-parameter
 * parsing, path-segment extraction, and pagination (limit/cursor) parsing.</p>
 *
 * <p>The helpers intentionally reproduce the exact behaviour (status codes, response
 * shape produced by {@link ApiResponse}, and clamping rules) that the individual
 * handlers relied on, so behaviour and response formats remain unchanged.</p>
 */
public abstract class AbstractApiHandler {

    protected final Syncmoney plugin;

    protected AbstractApiHandler(Syncmoney plugin) {
        this.plugin = plugin;
    }

    // ─── Responses ──────────────────────────────────────────────────────────

    /**
     * Send a JSON body with the standard {@code application/json;charset=UTF-8} content type.
     */
    protected void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }

    /**
     * Set the status code and send a standard {@link ApiResponse#error(String, String)} body.
     */
    protected void sendError(HttpServerExchange exchange, int statusCode, String code, String message) {
        exchange.setStatusCode(statusCode);
        sendJson(exchange, ApiResponse.error(code, message));
    }

    /**
     * Unified exception handling: log the failure and emit a 500 error response that
     * mirrors the existing per-handler catch blocks.
     */
    protected void handleException(HttpServerExchange exchange, Throwable error, String code) {
        plugin.getLogger().severe("API error [" + code + "]: " + error.getMessage());
        if (exchange.isComplete()) {
            return;
        }
        try {
            sendError(exchange, 500, code, error.getMessage());
        } catch (IllegalStateException ignored) {
            // Response already started elsewhere.
        }
    }

    // ─── Authentication helpers ─────────────────────────────────────────────

    /**
     * Extract the Bearer token from the {@code Authorization} header.
     *
     * @return the raw token, or {@code null} if the header is missing/malformed
     */
    protected String extractBearerToken(HttpServerExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7);
    }

    /**
     * Gate a request behind a permission. Sends a 403 error and returns {@code false}
     * when the permission is missing; returns {@code true} when access is granted.
     */
    protected boolean requirePermission(HttpServerExchange exchange, PermissionChecker checker, String permission) {
        if (checker != null && checker.hasPermission(permission)) {
            return true;
        }
        sendError(exchange, 403, "FORBIDDEN", "Insufficient permissions");
        return false;
    }

    // ─── Query / path parameter parsing ─────────────────────────────────────

    /**
     * Get a query parameter as a String, or {@code null} when absent.
     */
    protected String getQueryParam(HttpServerExchange exchange, String name) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    /**
     * Get a query parameter as an int with a default fallback on absence/parse failure.
     */
    protected int getQueryParamAsInt(HttpServerExchange exchange, String name, int defaultValue) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.getFirst());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extract a path segment by zero-based index from the relative URL.
     * Example: "api/audit/player/Steve" → index 3 returns "Steve".
     *
     * @return the segment, or an empty string if the index is out of range
     */
    protected String extractPathParamAt(HttpServerExchange exchange, int index) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        return (parts.length > index) ? parts[index] : "";
    }

    // ─── Pagination ─────────────────────────────────────────────────────────

    /**
     * Clamp a raw page-size/limit value into the inclusive [min, max] range.
     * Mirrors the {@code Math.min(Math.max(raw, min), max)} pattern previously
     * duplicated across handlers.
     */
    protected int clampLimit(int raw, int min, int max) {
        return Math.min(Math.max(raw, min), max);
    }

    /**
     * Read a pagination limit query parameter and clamp it into [min, max].
     */
    protected int parseLimit(HttpServerExchange exchange, String param, int defaultValue, int min, int max) {
        return clampLimit(getQueryParamAsInt(exchange, param, defaultValue), min, max);
    }

    /**
     * Parse a {@code "timestamp,sequence"} cursor string.
     *
     * @return a {@code long[]{timestamp, sequence}} pair, or {@code null} when the cursor
     *         is empty, malformed, or contains non-numeric parts
     */
    protected long[] parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        String[] parts = cursor.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(parts[0].trim());
            long sequence = Long.parseLong(parts[1].trim());
            return new long[]{timestamp, sequence};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
