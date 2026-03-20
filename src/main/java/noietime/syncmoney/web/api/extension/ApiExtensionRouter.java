package noietime.syncmoney.web.api.extension;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.web.api.ApiResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Router for API extension routes.
 *
 * All routes registered through this router are automatically prefixed
 * with /api/extensions/{extensionName}/
 *
 * Example:
 * ```java
 * // Registers route: /api/extensions/my-extension/stats
 * router.get("stats", exchange -> { ... });
 * ```
 */
public class ApiExtensionRouter {

    private final String extensionName;
    private final Map<String, ExtensionRouteHandler> routes = new ConcurrentHashMap<>();

    /**
     * Create a new router for an extension.
     *
     * @param extensionName the name of the extension
     */
    public ApiExtensionRouter(String extensionName) {
        this.extensionName = extensionName;
    }

    /**
     * Register a GET route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void get(String path, Consumer<HttpServerExchange> handler) {
        routes.put("GET:" + normalizePath(path), new ExtensionRouteHandler("GET", path, handler));
    }

    /**
     * Register a POST route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void post(String path, Consumer<HttpServerExchange> handler) {
        routes.put("POST:" + normalizePath(path), new ExtensionRouteHandler("POST", path, handler));
    }

    /**
     * Register a PUT route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void put(String path, Consumer<HttpServerExchange> handler) {
        routes.put("PUT:" + normalizePath(path), new ExtensionRouteHandler("PUT", path, handler));
    }

    /**
     * Register a DELETE route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void delete(String path, Consumer<HttpServerExchange> handler) {
        routes.put("DELETE:" + normalizePath(path), new ExtensionRouteHandler("DELETE", path, handler));
    }

    /**
     * Register a PATCH route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void patch(String path, Consumer<HttpServerExchange> handler) {
        routes.put("PATCH:" + normalizePath(path), new ExtensionRouteHandler("PATCH", path, handler));
    }

    /**
     * Register a HEAD route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void head(String path, Consumer<HttpServerExchange> handler) {
        routes.put("HEAD:" + normalizePath(path), new ExtensionRouteHandler("HEAD", path, handler));
    }

    /**
     * Register an OPTIONS route.
     *
     * @param path the path relative to the extension prefix
     * @param handler the handler to execute
     */
    public void options(String path, Consumer<HttpServerExchange> handler) {
        routes.put("OPTIONS:" + normalizePath(path), new ExtensionRouteHandler("OPTIONS", path, handler));
    }

    /**
     * Get all registered routes.
     *
     * @return map of route key to route handler
     */
    Map<String, ExtensionRouteHandler> getRoutes() {
        return routes;
    }

    /**
     * Get the extension name.
     *
     * @return extension name
     */
    String getExtensionName() {
        return extensionName;
    }

    /**
     * Normalize a path by removing leading/trailing slashes.
     */
    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        path = path.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Extension route handler record.
     */
    public record ExtensionRouteHandler(
            String method,
            String path,
            Consumer<HttpServerExchange> handler
    ) {
        /**
         * Build the full route path with extension prefix.
         */
        public String getFullPath(String extensionName) {
            String normalizedPath = path.isEmpty() ? "" : "/" + path;
            return "/api/extensions/" + extensionName + normalizedPath;
        }
    }
}
