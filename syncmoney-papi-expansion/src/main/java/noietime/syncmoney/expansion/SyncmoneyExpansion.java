package noietime.syncmoney.expansion;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * [SYNC-PAPI-001] Syncmoney PlaceholderAPI Expansion.
 *
 * Available placeholders:
 * - %syncmoney_balance% - Get player's own balance
 * - %syncmoney_balance_formatted% - Get player's own balance (smart formatting)
 * - %syncmoney_balance_abbreviated% - Get player's own balance (abbreviated)
 * - %syncmoney_rank% - Get player's own rank
 * - %syncmoney_my_rank% - Get player's own rank (alias)
 * - %syncmoney_total_supply% - Get total currency supply
 * - %syncmoney_total_players% - Get total registered players from database
 * - %syncmoney_version% - Get plugin version
 * - %syncmoney_online_players% - Get online player count
 * - %syncmoney_top_<n>% - Get balance of rank n in leaderboard
 * - %syncmoney_balance_<player>% - Get specified player's balance
 *
 * [ThreadSafe] This class is safe for concurrent usage.
 *
 * Debug Mode:
 * Set system property "syncmoney.papi.debug=true" to enable debug logging.
 */
public class SyncmoneyExpansion extends PlaceholderExpansion {

    private static final boolean DEBUG_MODE = Boolean.getBoolean("syncmoney.papi.debug");

    private final Object syncMoneyPlugin;
    private final PluginCache pluginCache;
    private final PlaceholderHandler placeholderHandler;

    public SyncmoneyExpansion() {
        this.syncMoneyPlugin = Bukkit.getPluginManager().getPlugin("Syncmoney");
        this.pluginCache = new PluginCache(syncMoneyPlugin, DEBUG_MODE);
        this.placeholderHandler = new PlaceholderHandler(pluginCache, DEBUG_MODE);

        if (DEBUG_MODE) {
            log("SyncmoneyExpansion initialized, plugin: " + (syncMoneyPlugin != null ? "found" : "NOT FOUND"));
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "syncmoney";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Syncmoney";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.1.2";
    }

    @Override
    public boolean canRegister() {
        return syncMoneyPlugin != null;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (syncMoneyPlugin == null) {
            return "N/A";
        }

        try {
            return placeholderHandler.handle(player, params);
        } catch (Exception e) {
            if (DEBUG_MODE) {
                log("onRequest exception: params=" + params + ", error=" + e.getMessage());
            }
            return "N/A";
        }
    }

    private void log(String message) {
        if (DEBUG_MODE) {
            Bukkit.getLogger().fine("[Syncmoney-PAPI-Debug] " + message);
        }
    }
}
