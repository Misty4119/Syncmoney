package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Cross-server notifications configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class CrossServerConfig {

    private final FileConfiguration config;

    public CrossServerConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-156] Whether cross-server notifications are enabled.
     */
    public boolean isCrossServerNotificationsEnabled() {
        return config.getBoolean("cross-server-notifications.enabled", true);
    }

    /**
     * [SYNC-CONFIG-156] Gets the notification type (all/deposit-only/withdraw-only/none).
     */
    public String getCrossServerNotifyType() {
        return config.getString("cross-server-notifications.notify-type", "all");
    }

    /**
     * [SYNC-CONFIG-156] Whether to show cross-server notifications in actionbar.
     */
    public boolean showCrossServerActionbar() {
        return config.getBoolean("cross-server-notifications.show-actionbar", true);
    }

    /**
     * [SYNC-CONFIG-156] Gets the actionbar notification duration in milliseconds.
     */
    public int getActionbarDuration() {
        return config.getInt("cross-server-notifications.actionbar-duration", 80);
    }
}
