package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Admin permissions configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class AdminPermissionConfig {

    private final FileConfiguration config;

    public AdminPermissionConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-115] Whether to enable daily limit.
     */
    public boolean isAdminDailyLimitEnabled() {
        return config.getBoolean("admin-permissions.enforce-daily-limit", true);
    }

    /**
     * [SYNC-CONFIG-116] Gets large operation confirmation threshold.
     */
    public double getAdminConfirmThreshold() {
        return config.getDouble("admin-permissions.confirm-threshold", 500000);
    }

    /**
     * [SYNC-CONFIG-117] Gets REWARD tier daily give limit.
     */
    public double getAdminRewardDailyGiveLimit() {
        return config.getDouble("admin-permissions.levels.reward.daily-give-limit", 100000);
    }

    /**
     * [SYNC-CONFIG-118] Gets GENERAL tier daily give limit.
     */
    public double getAdminGeneralDailyGiveLimit() {
        return config.getDouble("admin-permissions.levels.general.daily-give-limit", 1000000);
    }

    /**
     * [SYNC-CONFIG-119] Gets GENERAL tier daily take limit.
     */
    public double getAdminGeneralDailyTakeLimit() {
        return config.getDouble("admin-permissions.levels.general.daily-take-limit", 1000000);
    }
}
