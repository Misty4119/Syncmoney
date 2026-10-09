package noietime.syncmoney.web.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * HTTP route registry for handling REST API endpoints.
 * Maps HTTP methods and paths to handlers.
 */
public class HttpHandlerRegistry {

    private final Map<String, RouteHandler> routes = new ConcurrentHashMap<>();

    /**
     * Functional interface for route handlers.
     */
    @FunctionalInterface
    public interface RouteHandler {
        void handle(HttpServerExchange exchange) throws Exception;
    }

    /**
     * Register a route with method and path.
     *
     * @param method  HTTP method (GET, POST, etc)
     * @param path    Path pattern (e.g., /api/health)
     * @param handler The handler to execute
     */
    public void register(String method, String path, RouteHandler handler) {
        String key = normalizeKey(method, path);
        routes.put(key, handler);
    }

    /**
     * Convenience method for GET requests.
     */
    public void get(String path, RouteHandler handler) {
        register("GET", path, handler);
    }

    /**
     * Convenience method for POST requests.
     */
    public void post(String path, RouteHandler handler) {
        register("POST", path, handler);
    }

    /**
     * Convenience method for PUT requests.
     */
    public void put(String path, RouteHandler handler) {
        register("PUT", path, handler);
    }

    /**
     * Convenience method for DELETE requests.
     */
    public void delete(String path, RouteHandler handler) {
        register("DELETE", path, handler);
    }

    /**
     * Handle an incoming HTTP exchange.
     *
     * @param exchange The HTTP exchange
     */
    public void handle(HttpServerExchange exchange) {
        try {
            String method = exchange.getRequestMethod().toString();
            String path = exchange.getRelativePath();

            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            String key = normalizeKey(method, path);
            RouteHandler handler = routes.get(key);

            if (handler != null) {
                handler.handle(exchange);
            } else {
                handleWithParams(exchange, method, path);
            }
        } catch (Exception e) {
            handleException(exchange, e);
        }
    }

    /**
     * Try to match path with parameters (e.g., /api/audit/player/{name}).
     */
    private void handleWithParams(HttpServerExchange exchange, String method, String path) {
        for (Map.Entry<String, RouteHandler> entry : routes.entrySet()) {
            String routeKey = entry.getKey();
            if (routeKey.startsWith(method + ":")) {
                String routePath = routeKey.substring(method.length() + 1);
                if (matchPath(routePath, path)) {
                    try {
                        entry.getValue().handle(exchange);
                        return;
                    } catch (Exception e) {
                        handleException(exchange, e);
                        return;
                    }
                }
            }
        }
        handleNotFound(exchange);
    }

    /**
     * Handle exceptions with appropriate HTTP status codes.
     */
    private void handleException(HttpServerExchange exchange, Throwable error) {

        if (exchange.isComplete()) {

            return;
        }

        int statusCode = 500;
        String errorCode = "INTERNAL_ERROR";
        String errorMessage = "Internal Server Error";

        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            statusCode = 400;
            errorCode = "BAD_REQUEST";
            errorMessage = error.getMessage() != null ? error.getMessage() : "Invalid request";
        } else if (error instanceof SecurityException) {
            statusCode = 403;
            errorCode = "FORBIDDEN";
            errorMessage = "Access denied";
        } else if (error instanceof java.util.NoSuchElementException) {
            statusCode = 404;
            errorCode = "NOT_FOUND";
            errorMessage = "Resource not found";
        } else if (error instanceof UnsupportedOperationException) {
            statusCode = 405;
            errorCode = "METHOD_NOT_ALLOWED";
            errorMessage = "Operation not supported";
        }

        try {
            exchange.setStatusCode(statusCode);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");

            String response = String.format(
                    "{\"success\":false,\"error\":{\"code\":\"%s\",\"message\":\"%s\"}}",
                    errorCode, escapeJson(errorMessage));

            exchange.getResponseSender().send(response);
        } catch (IllegalStateException ignored) {

        }
    }

    /**
     * Escape special characters for JSON string.
     */
    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Simple path matching with {param} support.
     */
    private boolean matchPath(String routePath, String requestPath) {
        String[] routeParts = routePath.split("/");
        String[] requestParts = requestPath.split("/");

        if (routeParts.length != requestParts.length) {
            return false;
        }

        for (int i = 0; i < routeParts.length; i++) {
            if (routeParts[i].startsWith("{") && routeParts[i].endsWith("}")) {
                continue;
            }
            if (!routeParts[i].equals(requestParts[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extract a path parameter value from the URL.
     * 
     * @param exchange  The HTTP exchange
     * @param paramName The parameter name (without braces)
     * @return The parameter value or empty string
     */
    public String extractPathParam(HttpServerExchange exchange, String paramName) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(paramName)) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private String normalizeKey(String method, String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return method.toUpperCase() + ":" + path;
    }

    private void handleNotFound(HttpServerExchange exchange) {
        exchange.setStatusCode(404);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender()
                .send("{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Endpoint not found\"}}");
    }

    /**
     * Check if a route is registered.
     */
    public boolean hasRoute(String method, String path) {
        String key = normalizeKey(method, path);
        return routes.containsKey(key);
    }

    /**
     * Get the number of registered routes.
     */
    public int getRouteCount() {
        return routes.size();
    }
}
