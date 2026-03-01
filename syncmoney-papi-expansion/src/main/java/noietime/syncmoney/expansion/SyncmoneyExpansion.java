package noietime.syncmoney.expansion;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Server platform detection utility (embedded version to avoid module dependencies).
 */
final class PlatformDetector {
    static boolean isFolia() {
        Server server = Bukkit.getServer();
        return server != null && server.getName().contains("Folia");
    }
}

/**
 * Syncmoney PlaceholderAPI Expansion.
 * A standalone expansion that accesses Syncmoney main plugin data via reflection.
 *
 * Available placeholders:
 * - %syncmoney_balance% - Get player's own balance
 * - %syncmoney_balance_formatted% - Get player's own balance (smart formatting)
 * - %syncmoney_balance_abbreviated% - Get player's own balance (abbreviated)
 * - %syncmoney_rank% - Get player's own rank
 * - %syncmoney_my_rank% - Get player's own rank (alias)
 * - %syncmoney_total_supply% - Get total currency supply
 * - %syncmoney_total_players% - Get total players in leaderboard
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

    private Object syncMoneyPlugin;

    public SyncmoneyExpansion() {
        this.syncMoneyPlugin = Bukkit.getPluginManager().getPlugin("Syncmoney");
        if (DEBUG_MODE) {
            logDebug("SyncmoneyExpansion initialized, plugin: " + (syncMoneyPlugin != null ? "found" : "NOT FOUND"));
        }
    }

    /**
     * Log debug message if debug mode is enabled.
     */
    private void logDebug(String message) {
        if (DEBUG_MODE) {
            Bukkit.getLogger().info("[Syncmoney-PAPI-Debug] " + message);
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
        return "1.0.0";
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
            logDebug("onRequest: syncMoneyPlugin is null, params=" + params);
            return "N/A";
        }

        try {
            if (params.equalsIgnoreCase("total_supply") ||
                params.equalsIgnoreCase("version")) {
                return handleGlobalRequest(params);
            }

            if (player == null) {
                return null;
            }

            if (requiresBukkitApi(params)) {
                return runOnMainThread(() -> handlePlayerRequest(player, params));
            }

            return handlePlayerRequest(player, params);
        } catch (Exception e) {
            logDebug("onRequest exception: params=" + params + ", error=" + e.getMessage());
            return "N/A";
        }
    }

    /**
     * Handles global placeholders.
     */
    private String handleGlobalRequest(String params) {
        try {
            Object baltopManager = getMethod(syncMoneyPlugin, "getBaltopManager");

            if (params.equalsIgnoreCase("total_supply")) {
                Object totalSupply = getMethod(baltopManager, "getTotalSupply");
                if (totalSupply instanceof Number) {
                    return formatNumber(((Number) totalSupply).doubleValue());
                }
                return "0.00";
            }

            if (params.equalsIgnoreCase("version")) {
                Object desc = getMethod(syncMoneyPlugin, "getDescription");
                if (desc != null) {
                    return (String) getMethod(desc, "getVersion");
                }
                return "1.0.0";
            }
        } catch (Exception e) {
        }
        return "N/A";
    }

    /**
     * Checks if the parameter requires Bukkit API.
     */
    private boolean requiresBukkitApi(String params) {
        if (params.equalsIgnoreCase("balance_formatted") ||
            params.equalsIgnoreCase("balance_abbreviated")) {
            return false;
        }
        return params.startsWith("top_") ||
               params.equalsIgnoreCase("online_players") ||
               params.startsWith("balance_");
    }

    /**
     * Executes task on main thread.
     */
    private String runOnMainThread(java.util.function.Supplier<String> task) {
        if (PlatformDetector.isFolia()) {
            AtomicReference<String> result = new AtomicReference<>("N/A");
            CountDownLatch latch = new CountDownLatch(1);

            Bukkit.getScheduler().runTask((org.bukkit.plugin.Plugin) syncMoneyPlugin, () -> {
                try {
                    result.set(task.get());
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return result.get();
        }

        return task.get();
    }

    /**
     * Handles player-related placeholders.
     */
    private String handlePlayerRequest(OfflinePlayer player, String params) {
        try {
            logDebug("handlePlayerRequest: params=" + params);

            Object economyFacade = getMethod(syncMoneyPlugin, "getEconomyFacade");
            Object baltopManager = getMethod(syncMoneyPlugin, "getBaltopManager");

            logDebug("handlePlayerRequest: economyFacade=" + (economyFacade != null ? "found" : "null") +
                     ", baltopManager=" + (baltopManager != null ? "found" : "null"));

            if (economyFacade == null || baltopManager == null) {
                return "N/A";
            }

            String[] parts = params.split("_");

            if (params.equalsIgnoreCase("online_players")) {
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            }

            if (params.startsWith("top_")) {
                return handleTop(baltopManager, params);
            }

            if (params.startsWith("balance_") &&
                !params.equalsIgnoreCase("balance") &&
                !params.equalsIgnoreCase("balance_formatted") &&
                !params.equalsIgnoreCase("balance_abbreviated")) {
                logDebug("handlePlayerRequest: calling handleOtherPlayerBalance for " + params);
                return handleOtherPlayerBalance(params);
            }

            if (params.equalsIgnoreCase("total_players")) {
                Object totalObj = getMethod(baltopManager, "getTotalPlayers");
                if (totalObj instanceof Integer) {
                    return String.valueOf(totalObj);
                }
                return "0";
            }

            UUID uuid = player.getUniqueId();

            if (params.equalsIgnoreCase("balance")) {
                logDebug("handlePlayerRequest: processing balance for uuid=" + uuid);
                Object balance = getMethod(economyFacade, "getBalanceAsDouble", uuid);
                logDebug("handlePlayerRequest: balance result=" + balance);
                if (balance instanceof Number) {
                    return formatNumber(((Number) balance).doubleValue());
                }
                return "0.00";
            }

            if (params.equalsIgnoreCase("balance_formatted")) {
                logDebug("handlePlayerRequest: processing balance_formatted for uuid=" + uuid);
                Object balance = getMethod(economyFacade, "getBalanceAsDouble", uuid);
                logDebug("handlePlayerRequest: balance_formatted raw balance=" + balance);
                if (balance instanceof Number) {
                    String result = formatNumberSmart(((Number) balance).doubleValue(), baltopManager);
                    logDebug("handlePlayerRequest: balance_formatted result=" + result);
                    return result;
                }
                return "0.00";
            }

            if (params.equalsIgnoreCase("balance_abbreviated")) {
                logDebug("handlePlayerRequest: processing balance_abbreviated for uuid=" + uuid);
                Object balance = getMethod(economyFacade, "getBalanceAsDouble", uuid);
                logDebug("handlePlayerRequest: balance_abbreviated raw balance=" + balance);
                if (balance instanceof Number) {
                    String result = formatNumberAbbreviated(((Number) balance).doubleValue(), baltopManager);
                    logDebug("handlePlayerRequest: balance_abbreviated result=" + result);
                    return result;
                }
                return "0.00";
            }

            if (params.equalsIgnoreCase("rank") || params.equalsIgnoreCase("my_rank")) {
                Object rankObj = getMethod(baltopManager, "getPlayerRank", uuid);
                if (rankObj instanceof Integer) {
                    int rank = (Integer) rankObj;
                    return rank > 0 ? String.valueOf(rank) : "N/A";
                }
                return "N/A";
            }

            return null;
        } catch (Exception e) {
            logDebug("handlePlayerRequest exception: params=" + params + ", error=" + e.getMessage());
            return "N/A";
        }
    }

    /**
     * Handles leaderboard placeholders.
     */
    private String handleTop(Object baltopManager, String params) {
        try {
            String rankStr = params.substring(4).trim();
            if (rankStr.isEmpty()) {
                return "N/A";
            }
            int rank = Integer.parseInt(rankStr.replaceAll("\\D+", ""));
            if (rank < 1 || rank > 10000) {
                return "N/A";
            }

            logDebug("handleTop: requesting rank " + rank);

            Object topObj = getMethod(baltopManager, "getTopRank", rank);
            if (topObj == null) {
                logDebug("handleTop: getTopRank returned null");
                return "N/A";
            }

            @SuppressWarnings("unchecked")
            List<Object> top = (List<Object>) topObj;
            logDebug("handleTop: got " + top.size() + " entries");

            if (top.isEmpty() || top.size() < rank) {
                logDebug("handleTop: not enough entries");
                return "N/A";
            }

            Object entry = top.get(rank - 1);
            if (entry == null) {
                return "N/A";
            }

            logDebug("handleTop: entry class=" + entry.getClass().getName());
            try {
                var rankMethod = entry.getClass().getMethod("rank");
                Object rankVal = rankMethod.invoke(entry);
                logDebug("handleTop: entry rank=" + rankVal);
            } catch (Exception ignored) {}

            try {
                var nameMethod = entry.getClass().getMethod("name");
                Object nameVal = nameMethod.invoke(entry);
                logDebug("handleTop: entry name=" + nameVal);
            } catch (Exception ignored) {}

            double balance = getRecordBalance(entry);
            logDebug("handleTop: entry balance=" + balance);

            if (balance >= 0) {
                return formatNumber(balance);
            }
            return "N/A";
        } catch (Exception e) {
            logDebug("handleTop exception: " + e.getMessage());
            return "N/A";
        }
    }

    /**
     * Gets balance from RankEntry record using accessor method.
     * Java records generate accessor methods with the field name.
     */
    private double getRecordBalance(Object entry) {
        try {
            var method = entry.getClass().getMethod("balance");
            Object result = method.invoke(entry);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception ignored) {
        }
        try {
            var field = entry.getClass().getField("balance");
            Object result = field.get(entry);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Handles querying other player's balance.
     */
    private String handleOtherPlayerBalance(String params) {
        try {
            String playerName = params.substring(8).trim();
            if (playerName.isEmpty()) {
                return "N/A";
            }

            logDebug("handleOtherPlayerBalance: looking up player=" + playerName);

            UUID targetUuid = null;

            org.bukkit.OfflinePlayer targetPlayer = Bukkit.getOfflinePlayerIfCached(playerName);
            if (targetPlayer != null) {
                targetUuid = targetPlayer.getUniqueId();
                logDebug("handleOtherPlayerBalance: found player in Bukkit cache, uuid=" + targetUuid);
            } else {
                logDebug("handleOtherPlayerBalance: player not in cache, trying NameResolver");
                Object nameResolver = getMethod(syncMoneyPlugin, "getNameResolver");
                if (nameResolver != null) {
                    Object uuid = getMethod(nameResolver, "resolveUUID", playerName);
                    if (uuid instanceof UUID) {
                        targetUuid = (UUID) uuid;
                        logDebug("handleOtherPlayerBalance: found player in database, uuid=" + targetUuid);
                    }
                }
            }

            if (targetUuid == null) {
                logDebug("handleOtherPlayerBalance: player not found anywhere");
                return "N/A";
            }

            Object economyFacade = getMethod(syncMoneyPlugin, "getEconomyFacade");
            if (economyFacade == null) {
                return "N/A";
            }

            Object balance = getMethod(economyFacade, "getBalanceAsDouble", targetUuid);
            if (balance instanceof Number) {
                return formatNumber(((Number) balance).doubleValue());
            }
            return "0.00";
        } catch (Exception e) {
            logDebug("handleOtherPlayerBalance exception: " + e.getMessage());
            return "N/A";
        }
    }

    /**
     * Formats number with thousand separators.
     */
    private String formatNumber(double value) {
        return ExpansionFormatUtil.formatCurrency(value);
    }

    /**
     * Smart number formatting.
     * First tries to use BaltopManager's formatNumberSmart method,
     * falls back to local implementation if reflection fails.
     */
    private String formatNumberSmart(double value, Object baltopManager) {
        try {
            if (baltopManager != null) {
                Object result = getMethod(baltopManager, "formatNumberSmart", value);
                if (result != null) {
                    return result.toString();
                }
                result = getMethod(baltopManager, "formatNumberSmart", Double.valueOf(value));
                if (result != null) {
                    return result.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return formatNumberSmartLocal(value);
    }

    /**
     * Local implementation of smart number formatting.
     */
    private String formatNumberSmartLocal(double value) {
        return ExpansionFormatUtil.formatCompact(value);
    }

    /**
     * Abbreviated number formatting.
     * First tries to use BaltopManager's formatNumberAbbreviated method,
     * falls back to local implementation if reflection fails.
     */
    private String formatNumberAbbreviated(double value, Object baltopManager) {
        try {
            if (baltopManager != null) {
                Object result = getMethod(baltopManager, "formatNumberAbbreviated", value);
                if (result != null) {
                    return result.toString();
                }
                result = getMethod(baltopManager, "formatNumberAbbreviated", Double.valueOf(value));
                if (result != null) {
                    return result.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return formatNumberAbbreviatedLocal(value);
    }

    /**
     * Local implementation of abbreviated number formatting.
     */
    private String formatNumberAbbreviatedLocal(double value) {
        return ExpansionFormatUtil.formatAbbreviated(value);
    }

    /**
     * Gets method from object.
     * Enhanced to handle UUID types and common Java record patterns.
     */
    private Object getMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;
        try {
            Class<?>[] argClasses = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null) {
                    argClasses[i] = args[i].getClass();
                }
            }

            try {
                var method = obj.getClass().getMethod(methodName, argClasses);
                return method.invoke(obj, args);
            } catch (NoSuchMethodException ignored) {
            }

            Class<?>[] primitiveClasses = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null) {
                    Class<?> argClass = args[i].getClass();
                    if (argClass == Integer.class) primitiveClasses[i] = int.class;
                    else if (argClass == Long.class) primitiveClasses[i] = long.class;
                    else if (argClass == Double.class) primitiveClasses[i] = double.class;
                    else if (argClass == Boolean.class) primitiveClasses[i] = boolean.class;
                    else primitiveClasses[i] = argClass;
                }
            }

            try {
                var method = obj.getClass().getMethod(methodName, primitiveClasses);
                return method.invoke(obj, args);
            } catch (NoSuchMethodException ignored) {
            }

            for (var method : obj.getClass().getMethods()) {
                if (method.getName().equals(methodName)) {
                    if (args.length == 0) {
                        return method.invoke(obj);
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == args.length) {
                        boolean match = true;
                        for (int i = 0; i < params.length; i++) {
                            if (args[i] != null) {
                                Class<?> paramClass = params[i];
                                Class<?> argClass = args[i].getClass();
                                boolean isPrimitiveMatch = false;
                                if (paramClass.isPrimitive()) {
                                    if (paramClass == int.class && argClass == Integer.class) isPrimitiveMatch = true;
                                    else if (paramClass == long.class && argClass == Long.class) isPrimitiveMatch = true;
                                    else if (paramClass == double.class && argClass == Double.class) isPrimitiveMatch = true;
                                    else if (paramClass == boolean.class && argClass == Boolean.class) isPrimitiveMatch = true;
                                }
                                boolean isUuidMatch = isUuidMatch(paramClass, argClass);
                                if (!isPrimitiveMatch && !isUuidMatch && !paramClass.isAssignableFrom(argClass)) {
                                    match = false;
                                    break;
                                }
                            }
                        }
                        if (match) {
                            return method.invoke(obj, args);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Checks if two classes are both UUID types.
     */
    private boolean isUuidMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == UUID.class || "java.util.UUID".equals(paramClass.getName())) {
            return argClass == UUID.class || "java.util.UUID".equals(argClass.getName());
        }
        return false;
    }

    /**
     * Gets field from object.
     */
    private Object getField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            var field = obj.getClass().getField(fieldName);
            return field.get(obj);
        } catch (Exception ignored) {
        }
        return null;
    }
}
