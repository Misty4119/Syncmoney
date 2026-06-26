package noietime.syncmoney.web.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

/**
 * Centralises CORS header handling for the Web Admin server.
 *
 * <p>Header names/values are preserved exactly as they were emitted inline by
 * {@code WebAdminServer} so the wire behaviour is unchanged.</p>
 */
public final class CorsHandler {

    private static final HttpString ALLOW_ORIGIN = HttpString.tryFromString("Access-Control-Allow-Origin");
    private static final HttpString ALLOW_METHODS = HttpString.tryFromString("Access-Control-Allow-Methods");
    private static final HttpString ALLOW_HEADERS = HttpString.tryFromString("Access-Control-Allow-Headers");

    public void handlePreflight(HttpServerExchange exchange, String allowedOrigins) {
        exchange.getResponseHeaders().put(ALLOW_ORIGIN, allowedOrigins);
        exchange.getResponseHeaders().put(ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().put(ALLOW_HEADERS, "Content-Type, Authorization");
        exchange.setStatusCode(204);
    }

    public void applyStaticCors(HttpServerExchange exchange, String allowedOrigins) {
        exchange.getResponseHeaders().put(ALLOW_ORIGIN, allowedOrigins);
        exchange.getResponseHeaders().put(ALLOW_METHODS, "GET, OPTIONS");
        exchange.getResponseHeaders().put(ALLOW_HEADERS, "Content-Type, Authorization");
    }
}
