package noietime.syncmoney.expansion;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * [SYNC-PAPI-024] Handles all placeholder requests.
 */
public final class PlaceholderHandler {

    private final PluginCache pluginCache;
    private final boolean debugMode;

    public PlaceholderHandler(PluginCache pluginCache, boolean debugMode) {
        this.pluginCache = pluginCache;
        this.debugMode = debugMode;
    }

    /**
     * [SYNC-PAPI-025] Main entry point for placeholder requests.
     */
    public String handle(OfflinePlayer player, @NotNull String params) {
        if (debugMode) {
            log("handle: params=" + params + ", player=" + (player != null ? player.getName() : "null"));
        }

        try {
            if (isGlobalPlaceholder(params)) {
                return handleGlobal(params);
            }

            if (player == null) {
                return "N/A";
            }

            return handlePlayer(player, params);
        } catch (Exception e) {
            log("handle exception: " + e.getMessage());
            return "N/A";
        }
    }

    /**
     * [SYNC-PAPI-026] Check if placeholder is global (doesn't need player).
     */
    private boolean isGlobalPlaceholder(String params) {
        String lower = params.toLowerCase();
        return lower.equals("total_supply") ||
               lower.equals("version") ||
               lower.equals("online_players") ||
               lower.startsWith("top_") ||
               (lower.startsWith("balance_") && !lower.equals("balance") &&
                !lower.equals("balance_formatted") && !lower.equals("balance_abbreviated"));
    }

    /**
     * [SYNC-PAPI-027] Handle global placeholder.
     */
    private String handleGlobal(String params) {
        String lower = params.toLowerCase();

        if (lower.equals("total_supply")) {
            Object baltopManager = pluginCache.getBaltopManager();
            if (baltopManager != null) {
                Object totalSupply = ReflectionHelper.invokeMethod(baltopManager, "getTotalSupply");
                if (totalSupply instanceof Number) {
                    return ExpansionFormatUtil.formatCurrency(((Number) totalSupply).doubleValue());
                }
            }
            return "0.00";
        }

        if (lower.equals("version")) {
            return pluginCache.getVersion();
        }

        if (lower.equals("online_players")) {
            return String.valueOf(Bukkit.getOnlinePlayers().size());
        }

        if (lower.startsWith("top_")) {
            return handleTop(params);
        }

        if (lower.startsWith("balance_")) {
            return handleOtherPlayerBalance(params);
        }

        return null;
    }

    /**
     * [SYNC-PAPI-028] Handle player-specific placeholder.
     */
    private String handlePlayer(OfflinePlayer player, String params) {
        Object economyFacade = pluginCache.getEconomyFacade();
        Object baltopManager = pluginCache.getBaltopManager();

        if (economyFacade == null || baltopManager == null) {
            return "N/A";
        }

        UUID uuid = player.getUniqueId();
        String lower = params.toLowerCase();

        if (lower.equals("balance")) {
            Object balance = ReflectionHelper.invokeMethod(economyFacade, "getBalanceAsDouble", uuid);
            if (balance instanceof Number) {
                return ExpansionFormatUtil.formatCurrency(((Number) balance).doubleValue());
            }
            return "0.00";
        }

        if (lower.equals("balance_formatted")) {
            Object balance = ReflectionHelper.invokeMethod(economyFacade, "getBalanceAsDouble", uuid);
            if (balance instanceof Number) {
                return ExpansionFormatUtil.formatCompact(((Number) balance).doubleValue());
            }
            return "0.00";
        }

        if (lower.equals("balance_abbreviated")) {
            Object balance = ReflectionHelper.invokeMethod(economyFacade, "getBalanceAsDouble", uuid);
            if (balance instanceof Number) {
                return ExpansionFormatUtil.formatAbbreviated(((Number) balance).doubleValue());
            }
            return "0.00";
        }

        if (lower.equals("rank") || lower.equals("my_rank")) {
            Object rankObj = ReflectionHelper.invokeMethod(baltopManager, "getPlayerRank", uuid);
            if (rankObj instanceof Integer) {
                int rank = (Integer) rankObj;
                return rank > 0 ? String.valueOf(rank) : "N/A";
            }
            return "N/A";
        }

        if (lower.equals("total_players")) {
            Object totalObj = ReflectionHelper.invokeMethod(baltopManager, "getTotalPlayers");
            if (totalObj instanceof Integer) {
                return String.valueOf(totalObj);
            }
            return "0";
        }

        if (lower.equals("online_players")) {
            return String.valueOf(Bukkit.getOnlinePlayers().size());
        }

        if (lower.startsWith("top_")) {
            return handleTop(params);
        }

        if (lower.startsWith("balance_") && !lower.equals("balance")) {
            return handleOtherPlayerBalance(params);
        }

        return null;
    }

    /**
     * [SYNC-PAPI-029] Handle top N placeholder.
     */
    private String handleTop(String params) {
        Object baltopManager = pluginCache.getBaltopManager();
        if (baltopManager == null) {
            return "N/A";
        }

        String rankStr = params.substring(4).trim();
        if (rankStr.isEmpty()) {
            return "N/A";
        }

        int rank;
        try {
            rank = Integer.parseInt(rankStr.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return "N/A";
        }

        if (rank < 1 || rank > 10000) {
            return "N/A";
        }

        Object topObj = ReflectionHelper.invokeMethod(baltopManager, "getTopRank", rank);
        if (topObj == null) {
            return "N/A";
        }

        @SuppressWarnings("unchecked")
        List<Object> top = (List<Object>) topObj;
        if (top.isEmpty() || top.size() < rank) {
            return "N/A";
        }

        Object entry = top.get(rank - 1);
        if (entry == null) {
            return "N/A";
        }

        double balance = ReflectionHelper.getRecordBalance(entry);
        if (balance >= 0) {
            return ExpansionFormatUtil.formatCurrency(balance);
        }

        return "N/A";
    }

    /**
     * [SYNC-PAPI-030] Handle balance for another player.
     */
    private String handleOtherPlayerBalance(String params) {
        String playerName = params.substring(8).trim();
        if (playerName.isEmpty()) {
            return "N/A";
        }

        UUID targetUuid = null;

        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayerIfCached(playerName);
        if (targetPlayer != null) {
            targetUuid = targetPlayer.getUniqueId();
        } else {
            Object nameResolver = pluginCache.getNameResolver();
            if (nameResolver != null) {
                Object uuid = ReflectionHelper.invokeMethod(nameResolver, "resolveUUID", playerName);
                if (uuid instanceof UUID) {
                    targetUuid = (UUID) uuid;
                }
            }
        }

        if (targetUuid == null) {
            return "N/A";
        }

        Object economyFacade = pluginCache.getEconomyFacade();
        if (economyFacade == null) {
            return "N/A";
        }

        Object balance = ReflectionHelper.invokeMethod(economyFacade, "getBalanceAsDouble", targetUuid);
        if (balance instanceof Number) {
            return ExpansionFormatUtil.formatCurrency(((Number) balance).doubleValue());
        }

        return "0.00";
    }

    private void log(String message) {
        if (debugMode) {
            org.bukkit.Bukkit.getLogger().fine("[Syncmoney-PAPI-Debug] PlaceholderHandler: " + message);
        }
    }
}
