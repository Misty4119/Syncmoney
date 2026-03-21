package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Local economy mode configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class LocalConfig {

    private final FileConfiguration config;

    public LocalConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-155] Gets the SQLite database path for local economy mode.
     */
    public String getLocalSQLitePath() {
        return config.getString("economy.local.sqlite-path", "plugins/Syncmoney/syncmoney.db");
    }

    /**
     * [SYNC-CONFIG-155] Gets the automatic save interval in seconds.
     */
    public int getLocalAutoSaveInterval() {
        return config.getInt("economy.local.auto-save-interval", 60);
    }
}
