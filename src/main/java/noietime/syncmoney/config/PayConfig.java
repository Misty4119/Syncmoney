package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG-151] Pay command configuration settings.
 * Contains pay.* settings from config.yml including cooldown, limits, and confirmation threshold.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class PayConfig {

    private final FileConfiguration config;

    public PayConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-151] Gets the pay command cooldown in seconds.
     * Players must wait this many seconds between pay transactions.
     * Default: 30 seconds
     */
    public int getPayCooldownSeconds() {
        return config.getInt("pay.cooldown-seconds", 30);
    }

    /**
     * [SYNC-CONFIG-152] Gets the minimum amount allowed for a single pay transaction.
     * Default: 1
     */
    public double getPayMinAmount() {
        return config.getDouble("pay.min-amount", 1);
    }

    /**
     * [SYNC-CONFIG-153] Gets the maximum amount allowed for a single pay transaction.
     * Default: 1000000
     */
    public double getPayMaxAmount() {
        return config.getDouble("pay.max-amount", 1000000);
    }

    /**
     * [SYNC-CONFIG-154] Gets the confirmation threshold for large pay transactions.
     * Transactions above this amount require confirmation.
     * Default: 100000
     */
    public double getPayConfirmThreshold() {
        return config.getDouble("pay.confirm-threshold", 100000);
    }

    /**
     * [SYNC-CONFIG-155] Gets whether pay is allowed when economy is in degraded mode.
     * Default: false
     */
    public boolean isPayAllowedInDegraded() {
        return config.getBoolean("pay.allow-in-degraded", false);
    }
}
