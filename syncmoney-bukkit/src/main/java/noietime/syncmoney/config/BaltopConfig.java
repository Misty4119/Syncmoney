package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * [SYNC-CONFIG] Leaderboard (baltop) configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class BaltopConfig {

    private final FileConfiguration config;

    public BaltopConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-111] Gets leaderboard cache seconds.
     */
    public int getBaltopCacheSeconds() {
        return config.getInt("baltop.cache-seconds", 30);
    }

    /**
     * [SYNC-CONFIG-112] Gets items per page.
     */
    public int getBaltopEntriesPerPage() {
        return config.getInt("baltop.entries-per-page", 10);
    }

    /**
     * [SYNC-CONFIG-113] Gets balance format (full/smart/abbreviated).
     */
    public String getBaltopFormat() {
        return config.getString("baltop.format", "smart");
    }

    /**
     * [SYNC-CONFIG-114] Gets number format configuration list.
     * Each entry is a list: [threshold, label]
     * Example: [[1000000000000, "T"], [1000000000, "B"], [1000000, "M"], [1000, "K"]]
     */
    @SuppressWarnings("unchecked")
    public List<Object> getBaltopNumberFormat() {
        return (List<Object>) config.getList("baltop.number-format");
    }
}
