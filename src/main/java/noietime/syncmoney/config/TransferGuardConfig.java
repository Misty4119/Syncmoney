package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG-158] Transfer guard configuration settings.
 * Contains transfer-guard.* settings from config.yml for cross-server portal protection.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class TransferGuardConfig {

    private final FileConfiguration config;

    public TransferGuardConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-153] Gets whether transfer guard is enabled.
     * Protects players during cross-server transfers.
     * Default: true
     */
    public boolean isTransferGuardEnabled() {
        return config.getBoolean("transfer-guard.enabled", true);
    }

    /**
     * [SYNC-CONFIG-159] Gets the maximum wait time in milliseconds for transfer guard.
     * Default: 500
     */
    public int getTransferGuardMaxWaitMs() {
        return config.getInt("transfer-guard.max-wait-ms", 500);
    }
}
