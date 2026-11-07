package noietime.syncmoney.web.api.extension;

/**
 * Interface for API extensions.
 *
 * API extensions allow third-party developers to add custom endpoints
 * to the Syncmoney Web Admin API.
 *
 * Example usage:
 * ```java
 * public class MyExtension implements ApiExtension {
 *     @Override
 *     public String getName() { return "my-extension"; }
 *
 *     @Override
 *     public String getVersion() { return "1.0.0"; }
 *
 *     @Override
 *     public void registerRoutes(ApiExtensionRouter router) {
 *         router.get("stats", exchange -> { ... });
 *         router.post("sync", exchange -> { ... });
 *     }
 * }
 * ```
 */
public interface ApiExtension {

    /**
     * Get the unique name of this extension.
     * This is used as the prefix for all extension routes.
     *
     * @return extension name (lowercase, alphanumeric with hyphens)
     */
    String getName();

    /**
     * Get the version of this extension.
     *
     * @return semantic version string
     */
    String getVersion();

    /**
     * Get a description of this extension.
     *
     * @return human-readable description
     */
    default String getDescription() {
        return "No description provided";
    }

    /**
     * Register all routes for this extension.
     * Routes are automatically prefixed with /api/extensions/{name}/
     *
     * @param router the extension router for registering routes
     */
    void registerRoutes(ApiExtensionRouter router);

    /**
     * Called when the extension is enabled.
     * Use this for initialization logic.
     */
    default void onEnable() {
    }

    /**
     * Called when the extension is disabled.
     * Use this for cleanup logic.
     */
    default void onDisable() {
    }

    /**
     * Check if this extension is enabled.
     *
     * @return true if enabled
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Get the priority of this extension.
     * Higher priority extensions are loaded first.
     *
     * @return priority (default 0)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Get the author of this extension.
     *
     * @return author name or organization
     */
    default String getAuthor() {
        return "Unknown";
    }
}
