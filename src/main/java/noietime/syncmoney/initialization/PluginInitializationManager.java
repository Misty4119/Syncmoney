package noietime.syncmoney.initialization;

import noietime.syncmoney.Syncmoney;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Manages plugin initialization and shutdown phases.
 * Coordinates all components in the correct dependency order.
 * 
 * Initialization phases:
 * 1. Configuration (Config, Messages)
 * 2. Storage (Redis, Cache, Database)
 * 3. Economy System (Facade, Handlers)
 * 4. Integration (Vault, Cross-server sync)
 * 5. Commands & Listeners
 */
public class PluginInitializationManager {

    private final Syncmoney plugin;
    private final CopyOnWriteArrayList<Initializable> initSteps = new CopyOnWriteArrayList<>();
    private boolean initialized = false;

    public PluginInitializationManager(Syncmoney plugin) {
        this.plugin = plugin;
    }

    /**
     * Register initialization steps in dependency order.
     */
    public void registerStep(Initializable step) {
        initSteps.add(step);
    }

    /**
     * Execute all initialization steps in order.
     */
    public void initialize() {
        if (initialized) {
            plugin.getLogger().warning("PluginInitializationManager already initialized");
            return;
        }

        for (Initializable step : initSteps) {
            try {
                plugin.getLogger().fine("Initializing: " + step.getClass().getSimpleName());
                step.initialize();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize " + step.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        initialized = true;
        plugin.getLogger().fine("PluginInitializationManager initialized successfully");
    }

    /**
     * Execute shutdown in reverse order.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down PluginInitializationManager...");

        for (int i = initSteps.size() - 1; i >= 0; i--) {
            try {
                Initializable step = initSteps.get(i);
                plugin.getLogger().fine("Shutting down: " + step.getClass().getSimpleName());
                step.shutdown();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error during shutdown", e);
            }
        }

        initialized = false;
        plugin.getLogger().fine("PluginInitializationManager shutdown complete");
    }

    /**
     * Check if initialization is complete.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
