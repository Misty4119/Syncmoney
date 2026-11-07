package noietime.syncmoney.initialization;

import noietime.syncmoney.Syncmoney;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private List<Initializable> activeSteps = List.of();
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
    public synchronized void initialize() throws Initializable.InitializationException {
        if (initialized) {
            plugin.getLogger().warning("PluginInitializationManager already initialized");
            return;
        }

        List<Initializable> attemptedSteps = new ArrayList<>();
        List<Initializable> completedSteps = new ArrayList<>();
        for (Initializable step : initSteps) {
            attemptedSteps.add(step);
            try {
                plugin.getLogger().fine("Initializing: " + step.getClass().getSimpleName());
                step.initialize();
                completedSteps.add(step);
            } catch (Exception | LinkageError failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Failed to initialize " + step.getClass().getSimpleName(), failure);
                rollback(attemptedSteps);
                if (failure instanceof Initializable.InitializationException initializationFailure) {
                    throw initializationFailure;
                }
                throw new Initializable.InitializationException(
                        "Failed to initialize " + step.getClass().getSimpleName(), failure);
            }
        }

        activeSteps = List.copyOf(completedSteps);
        initialized = true;
        plugin.getLogger().fine("PluginInitializationManager initialized successfully");
    }

    /**
     * Execute shutdown in reverse order.
     */
    public synchronized void shutdown() {
        plugin.getLogger().fine("Shutting down PluginInitializationManager...");
        List<Initializable> toClose = activeSteps;
        activeSteps = List.of();
        initialized = false;
        rollback(toClose);
        plugin.getLogger().fine("PluginInitializationManager shutdown complete");
    }

    private void rollback(List<Initializable> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            Initializable step = steps.get(i);
            try {
                plugin.getLogger().fine("Shutting down: " + step.getClass().getSimpleName());
                step.shutdown();
            } catch (Throwable failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Error shutting down " + step.getClass().getSimpleName(), failure);
            }
        }
    }

    /**
     * Check if initialization is complete.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
