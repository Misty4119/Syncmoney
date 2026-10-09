package noietime.syncmoney.web.api.extension;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manager for API extensions.
 *
 * Handles registration, lifecycle, and routing of API extensions.
 *
 * Extensions are discovered through configuration and loaded
 * at startup. Each extension gets its own route prefix:
 * /api/extensions/{extension-name}/
 *
 * Example configuration in config.yml:
 * ```yaml
 * web-admin:
 *   extensions:
 *     enabled: true
 *     load:
 *       - my-extension
 * ```
 */
public class ApiExtensionManager {

    private final Syncmoney plugin;
    private final HttpHandlerRegistry router;
    private final Map<String, ApiExtension> extensions = new ConcurrentHashMap<>();
    private final Map<String, ApiExtensionRouter> extensionRouters = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /**
     * Create a new extension manager.
     *
     * @param plugin the Syncmoney plugin instance
     * @param router the HTTP handler registry
     */
    public ApiExtensionManager(Syncmoney plugin, HttpHandlerRegistry router) {
        this.plugin = plugin;
        this.router = router;
    }

    /**
     * Initialize and load all extensions.
     * Should be called during WebAdminServer startup.
     */
    public void initialize() {
        if (initialized) {
            plugin.getLogger().warning("ApiExtensionManager already initialized");
            return;
        }

        plugin.getLogger().info("Initializing API Extension Manager...");

        loadExtensions();

        registerExtensionRoutes();

        for (ApiExtension extension : extensions.values()) {
            try {
                plugin.getLogger().info("Enabling extension: " + extension.getName() + " v" + extension.getVersion());
                extension.onEnable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to enable extension " + extension.getName() + ": " + e.getMessage(), e);
            }
        }

        initialized = true;
        plugin.getLogger().info("API Extension Manager initialized with " + extensions.size() + " extensions");
    }

    /**
     * Load extensions from configuration.
     */
    private void loadExtensions() {
        plugin.getLogger().fine("No extensions configured - using built-in handlers only");
    }

    /**
     * Register an extension dynamically.
     *
     * @param extension the extension to register
     * @return true if registered successfully
     */
    public boolean registerExtension(ApiExtension extension) {
        if (extension == null || extension.getName() == null) {
            return false;
        }

        String name = extension.getName().toLowerCase();

        if (extensions.containsKey(name)) {
            plugin.getLogger().warning("Extension already registered: " + name);
            return false;
        }

        extensions.put(name, extension);

        ApiExtensionRouter extRouter = new ApiExtensionRouter(name);
        extensionRouters.put(name, extRouter);

        if (initialized) {
            extension.registerRoutes(extRouter);
            registerRoutes(name, extRouter);
        }

        plugin.getLogger().info("Registered extension: " + name + " v" + extension.getVersion());
        return true;
    }

    /**
     * Unregister an extension.
     *
     * @param name the extension name
     * @return true if unregistered successfully
     */
    public boolean unregisterExtension(String name) {
        name = name.toLowerCase();

        ApiExtension extension = extensions.remove(name);
        if (extension == null) {
            return false;
        }

        try {
            extension.onDisable();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Error disabling extension " + name + ": " + e.getMessage(), e);
        }

        extensionRouters.remove(name);

        plugin.getLogger().info("Unregistered extension: " + name);
        return true;
    }

    /**
     * Get an extension by name.
     *
     * @param name the extension name
     * @return the extension or empty if not found
     */
    public Optional<ApiExtension> getExtension(String name) {
        return Optional.ofNullable(extensions.get(name.toLowerCase()));
    }

    /**
     * Get all registered extensions.
     *
     * @return collection of extensions
     */
    public Collection<ApiExtension> getExtensions() {
        return Collections.unmodifiableCollection(extensions.values());
    }

    /**
     * Get the number of registered extensions.
     *
     * @return extension count
     */
    public int getExtensionCount() {
        return extensions.size();
    }

    /**
     * Register routes for all extensions.
     */
    private void registerExtensionRoutes() {
        for (Map.Entry<String, ApiExtensionRouter> entry : extensionRouters.entrySet()) {
            registerRoutes(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Register routes from an extension router.
     */
    private void registerRoutes(String extensionName, ApiExtensionRouter extRouter) {
        for (ApiExtensionRouter.ExtensionRouteHandler route : extRouter.getRoutes().values()) {
            String fullPath = "/api/extensions/" + extensionName + "/" + route.path();
            String method = route.method();

            HttpHandlerRegistry.RouteHandler handler = exchange -> route.handler().accept(exchange);

            switch (method) {
                case "GET" -> router.get(fullPath, handler);
                case "POST" -> router.post(fullPath, handler);
                case "PUT" -> router.put(fullPath, handler);
                case "DELETE" -> router.delete(fullPath, handler);
                default -> plugin.getLogger().warning("Unknown HTTP method: " + method);
            }

            plugin.getLogger().fine("Registered extension route: " + method + " " + fullPath);
        }
    }

    /**
     * Create a request context for an extension handler.
     *
     * @param exchange the HTTP exchange
     * @param extensionName the extension name
     * @param routePath the matched route path
     * @return a new RequestContext
     */
    public RequestContext createContext(io.undertow.server.HttpServerExchange exchange,
                                       String extensionName, String routePath) {
        return new RequestContext(exchange, plugin, extensionName, routePath);
    }

    /**
     * Shutdown the extension manager.
     * Calls onDisable for each extension and clears all registrations.
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down API Extension Manager...");

        for (ApiExtension extension : extensions.values()) {
            try {
                plugin.getLogger().info("Disabling extension: " + extension.getName());
                extension.onDisable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error disabling extension " + extension.getName() + ": " + e.getMessage(), e);
            }
        }

        extensions.clear();
        extensionRouters.clear();
        initialized = false;

        plugin.getLogger().info("API Extension Manager shutdown complete");
    }

    /**
     * Check if the manager is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get a summary of all registered extensions.
     *
     * @return map of extension names to their info
     */
    public Map<String, Map<String, String>> getExtensionSummary() {
        return extensions.values().stream()
                .collect(Collectors.toMap(
                        ApiExtension::getName,
                        ext -> Map.of(
                                "version", ext.getVersion(),
                                "author", ext.getAuthor(),
                                "description", ext.getDescription(),
                                "enabled", String.valueOf(ext.isEnabled())
                        )
                ));
    }
}
