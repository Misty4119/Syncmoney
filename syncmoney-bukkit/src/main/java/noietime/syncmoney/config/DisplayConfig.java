package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG-156] Display configuration settings.
 * Contains display.* settings from config.yml for currency formatting.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class DisplayConfig {

    private final FileConfiguration config;

    public DisplayConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-152] Gets the currency name/symbol for display.
     * Default: "$"
     */
    public String getCurrencyName() {
        return config.getString("display.currency-name", "$");
    }

    /**
     * [SYNC-CONFIG-157] Gets the number of decimal places for currency display.
     * Default: 2
     */
    public int getDecimalPlaces() {
        return config.getInt("display.decimal-places", 2);
    }
}
