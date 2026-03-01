package noietime.syncmoney.economy;

import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;

/**
 * Fallback logic wrapper.
 * Logs and marks degraded status when Redis is unavailable.
 *
 * [MainThread] This class is primarily called from main thread, no Redis operations.
 */
public final class FallbackEconomyWrapper {

    private final Plugin plugin;
    private final RedisManager redisManager;
    private final boolean localMode;
    private volatile boolean degraded = false;

    public FallbackEconomyWrapper(Plugin plugin, RedisManager redisManager) {
        this(plugin, redisManager, false);
    }

    public FallbackEconomyWrapper(Plugin plugin, RedisManager redisManager, boolean localMode) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.localMode = localMode;
    }

    /**
     * Check if system is in degraded mode.
     * In LOCAL mode, this always returns false since Redis is not required.
     */
    public boolean isDegraded() {
        if (localMode) {
            return false;
        }
        return degraded || redisManager.isDegraded();
    }

    /**
     * Check if in local mode.
     */
    public boolean isLocalMode() {
        return localMode;
    }

    /**
     * Update degraded status.
     */
    public void updateDegradedState() {
        boolean redisDegraded = redisManager.isDegraded();

        if (redisDegraded && !degraded) {
            degraded = true;
            plugin.getLogger().warning("Economy system entered degraded mode - Redis unavailable");
        } else if (!redisDegraded && degraded) {
            degraded = false;
            plugin.getLogger().info("Economy system exited degraded mode - Redis recovered");
        }
    }

    /**
     * Log operation executed in degraded mode.
     */
    public void logDegradedOperation(String operation) {
        if (isDegraded()) {
            plugin.getLogger().warning("Operation '" + operation + "' executed in degraded mode");
        }
    }
}
