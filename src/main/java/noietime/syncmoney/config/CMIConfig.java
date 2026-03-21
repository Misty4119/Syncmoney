package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] CMI-specific configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class CMIConfig {

    private final FileConfiguration config;

    public CMIConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-136] Gets CMI Redis prefix
     */
    public String getCMIRedisPrefix() {
        return config.getString("economy.cmi.redis-prefix", "syncmoney:cmi");
    }

    /**
     * [SYNC-CONFIG-137] Gets CMI detection interval (milliseconds)
     */
    public long getCMIDetectIntervalMs() {
        return config.getLong("economy.cmi.detect-interval-ms", 500);
    }

    /**
     * [SYNC-CONFIG-138] Whether CMI cross-server sync is enabled
     */
    public boolean isCMICrossServerSync() {
        return config.getBoolean("economy.cmi.cross-server-sync", true);
    }

    /**
     * [SYNC-CONFIG-140] Gets CMI balance operation mode (as string).
     * Returns "API" or "INTERNAL".
     */
    public String getCMIBalanceModeString() {
        return config.getString("economy.cmi.balance-mode", "internal");
    }

    /**
     * [SYNC-CONFIG-141] Gets CMI debounce ticks.
     */
    public int getCMIDebounceTicks() {
        return config.getInt("economy.cmi.debounce-ticks", 5);
    }
}
