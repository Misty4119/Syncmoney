package noietime.syncmoney.web.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.security.ApiKeyAuthFilter;
import noietime.syncmoney.web.websocket.SseManager;
import noietime.syncmoney.web.websocket.WebSocketManager;

/**
 * Dispatches incoming HTTP requests to the appropriate sub-system:
 * CORS pre-flight, SSE, WebSocket, authenticated REST API, the health probe,
 * or static file serving.
 *
 * <p>Extracted verbatim (behaviour-preserving) from the former
 * {@code WebAdminServer.handleRequest} so the bootstrap class only owns Undertow
 * lifecycle concerns.</p>
 */
public final class RouteRegistry {

    private final Syncmoney plugin;
    private final WebAdminConfig config;
    private final HttpHandlerRegistry router;
    private final ApiKeyAuthFilter authFilter;
    private final CorsHandler corsHandler;
    private final StaticFileHandler staticFileHandler;

    private volatile SseManager sseManager;
    private volatile WebSocketManager webSocketManager;

    public RouteRegistry(Syncmoney plugin, WebAdminConfig config, HttpHandlerRegistry router,
                         ApiKeyAuthFilter authFilter, CorsHandler corsHandler,
                         StaticFileHandler staticFileHandler) {
        this.plugin = plugin;
        this.config = config;
        this.router = router;
        this.authFilter = authFilter;
        this.corsHandler = corsHandler;
        this.staticFileHandler = staticFileHandler;
    }

    public void setSseManager(SseManager sseManager) {
        this.sseManager = sseManager;
    }

    public void setWebSocketManager(WebSocketManager webSocketManager) {
        this.webSocketManager = webSocketManager;
    }

    /**
     * Handle an incoming request, routing it to the correct sub-system.
     */
    public void handle(HttpServerExchange exchange) {
        try {
            String path = exchange.getRelativePath();

            if ("OPTIONS".equals(exchange.getRequestMethod().toString())) {
                corsHandler.handlePreflight(exchange, config.getCorsAllowedOrigins());
                return;
            }

            String pathForRoute = path.startsWith("/") ? path.substring(1) : path;

            if (path.startsWith("sse") || pathForRoute.startsWith("sse") ||
                path.startsWith("api/sse") || pathForRoute.startsWith("api/sse")) {
                if (sseManager != null) {
                    sseManager.createHandler().handleRequest(exchange);
                }
            } else if (path.startsWith("ws") || pathForRoute.startsWith("ws")) {
                if (webSocketManager != null) {
                    webSocketManager.createHandler().handleRequest(exchange);
                }
            } else if (path.startsWith("api/") || pathForRoute.startsWith("api/")) {
                if (authFilter != null && !authFilter.authenticate(exchange)) {
                    authFilter.sendUnauthorized(exchange);
                    return;
                }
                router.handle(exchange);
            } else if (path.equals("health") || pathForRoute.equals("health")) {
                router.handle(exchange);
            } else {
                staticFileHandler.serve(exchange, path);
            }
        } catch (Exception e) {
            plugin.getLogger().severe(plugin.getMessage("web.server.error-request").replace("{error}", e.getMessage()));
            try {
                if (!exchange.isComplete()) {
                    exchange.setStatusCode(500);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
                    exchange.getResponseSender().send(
                            "{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal server error\"}}");
                }
            } catch (IllegalStateException ignored) {
            }
        }
    }
}
