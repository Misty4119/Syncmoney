package noietime.syncmoney.web.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.util.List;

/**
 * Centralises CORS header handling for the Web Admin server.
 *
 * <p>Configured origins are an explicit allowlist. An empty allowlist emits no
 * cross-origin response header; wildcard access is enabled only by an explicit
 * {@code *} entry.</p>
 */
public final class CorsHandler {

    private static final HttpString ALLOW_ORIGIN = HttpString.tryFromString("Access-Control-Allow-Origin");
    private static final HttpString ALLOW_METHODS = HttpString.tryFromString("Access-Control-Allow-Methods");
    private static final HttpString ALLOW_HEADERS = HttpString.tryFromString("Access-Control-Allow-Headers");

    public void handlePreflight(HttpServerExchange exchange, List<String> allowedOrigins) {
        applyAllowedOrigin(exchange, allowedOrigins);
        exchange.getResponseHeaders().put(ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().put(ALLOW_HEADERS, "Content-Type, Authorization");
        exchange.setStatusCode(204);
    }

    public void applyRestCors(HttpServerExchange exchange, List<String> allowedOrigins) {
        applyAllowedOrigin(exchange, allowedOrigins);
    }

    public void applyStaticCors(HttpServerExchange exchange, List<String> allowedOrigins) {
        applyAllowedOrigin(exchange, allowedOrigins);
        exchange.getResponseHeaders().put(ALLOW_METHODS, "GET, OPTIONS");
        exchange.getResponseHeaders().put(ALLOW_HEADERS, "Content-Type, Authorization");
    }

    private static void applyAllowedOrigin(HttpServerExchange exchange, List<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) return;

        String requestOrigin = exchange.getRequestHeaders().getFirst(Headers.ORIGIN);
        if (requestOrigin == null || requestOrigin.isBlank()) return;

        if (allowedOrigins.contains("*")) {
            exchange.getResponseHeaders().put(ALLOW_ORIGIN, "*");
        } else if (allowedOrigins.contains(requestOrigin)) {
            exchange.getResponseHeaders().put(ALLOW_ORIGIN, requestOrigin);
            exchange.getResponseHeaders().add(Headers.VARY, "Origin");
        }
    }
}
