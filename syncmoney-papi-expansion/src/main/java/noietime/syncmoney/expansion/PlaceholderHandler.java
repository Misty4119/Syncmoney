package noietime.syncmoney.expansion;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SYNC-PAPI-024] Handles all placeholder requests.
 *
 * This class merges the former PluginCache (cached plugin service references) and
 * ReflectionHelper/ReflectionCache (cached reflective access) responsibilities so the
 * expansion ships as a small set of classes while keeping every placeholder ID and
 * output format unchanged.
 *
 * [ThreadSafe] Reflective method lookups are cached in a ConcurrentHashMap.
 */
public final class PlaceholderHandler {

    private static final long CACHE_EXPIRY_MS = 5000;
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private final Object plugin;
    private final boolean debugMode;

    private Object cachedEconomyFacade;
    private Object cachedBaltopManager;
    private Object cachedNameResolver;
    private long cacheTimestamp = 0;

    public PlaceholderHandler(Object plugin, boolean debugMode) {
        this.plugin = plugin;
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
            Object baltopManager = getBaltopManager();
            if (baltopManager != null) {
                Object totalSupply = invokeMethod(baltopManager, "getTotalSupply");
                if (totalSupply instanceof Number) {
                    return ExpansionFormatUtil.formatCurrency(((Number) totalSupply).doubleValue());
                }
            }
            return "0.00";
        }

        if (lower.equals("version")) {
            return getVersion();
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
        Object economyFacade = getEconomyFacade();
        Object baltopManager = getBaltopManager();

        if (economyFacade == null || baltopManager == null) {
            return "N/A";
        }

        UUID uuid = player.getUniqueId();
        String lower = params.toLowerCase();

        if (lower.equals("balance")) {
            Object balance = invokeMethod(economyFacade, "getBalanceAsDouble", uuid);
            if (balance instanceof Number) {
                return ExpansionFormatUtil.formatCurrency(((Number) balance).doubleValue());
            }
            return "0.00";
        }

        if (lower.equals("balance_formatted")) {
            Object balance = invokeMethod(economyFacade, "getBalanceAsDouble", uuid);
            if (balance instanceof Number) {
                return ExpansionFormatUtil.formatCompact(((Number) balance).doubleValue());
            }
            return "0.00";
        }

        if (lower.equals("balance_abbreviated")) {
            Object balance = invokeMethod(economyFacade, "getBalanceAsDouble", uuid);
            if (balance instanceof Number) {
                return ExpansionFormatUtil.formatAbbreviated(((Number) balance).doubleValue());
            }
            return "0.00";
        }

        if (lower.equals("rank") || lower.equals("my_rank")) {
            Object rankObj = invokeMethod(baltopManager, "getPlayerRank", uuid);
            if (rankObj instanceof Integer) {
                int rank = (Integer) rankObj;
                return rank > 0 ? String.valueOf(rank) : "N/A";
            }
            return "N/A";
        }

        if (lower.equals("total_players")) {
            Object totalObj = invokeMethod(baltopManager, "getTotalRegisteredPlayers");
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
        Object baltopManager = getBaltopManager();
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

        Object topObj = invokeMethod(baltopManager, "getTopRank", rank);
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

        double balance = getRecordBalance(entry);
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
            Object nameResolver = getNameResolver();
            if (nameResolver != null) {
                Object uuid = invokeMethod(nameResolver, "resolveUUID", playerName);
                if (uuid instanceof UUID) {
                    targetUuid = (UUID) uuid;
                }
            }
        }

        if (targetUuid == null) {
            return "N/A";
        }

        Object economyFacade = getEconomyFacade();
        if (economyFacade == null) {
            return "N/A";
        }

        Object balance = invokeMethod(economyFacade, "getBalanceAsDouble", targetUuid);
        if (balance instanceof Number) {
            return ExpansionFormatUtil.formatCurrency(((Number) balance).doubleValue());
        }

        return "0.00";
    }

    private Object getEconomyFacade() {
        refreshIfNeeded();
        return cachedEconomyFacade;
    }

    private Object getBaltopManager() {
        refreshIfNeeded();
        return cachedBaltopManager;
    }

    private Object getNameResolver() {
        refreshIfNeeded();
        return cachedNameResolver;
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (cachedEconomyFacade == null || now - cacheTimestamp > CACHE_EXPIRY_MS) {
            cachedEconomyFacade = invokeMethod(plugin, "getEconomyFacade");
            cachedBaltopManager = invokeMethod(plugin, "getBaltopManager");
            cachedNameResolver = invokeMethod(plugin, "getNameResolver");
            cacheTimestamp = now;

            if (debugMode) {
                log("service cache refreshed: economyFacade=" + (cachedEconomyFacade != null) +
                    ", baltopManager=" + (cachedBaltopManager != null));
            }
        }
    }

    private String getVersion() {
        Object desc = invokeMethod(plugin, "getDescription");
        if (desc != null) {
            Object version = invokeMethod(desc, "getVersion");
            if (version != null) {
                return version.toString();
            }
        }
        return "1.0.0";
    }

    private static Object invokeMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;

        String key = generateKey(obj, methodName, args);

        Method cachedMethod = METHOD_CACHE.get(key);
        if (cachedMethod != null) {
            try {
                return cachedMethod.invoke(obj, args);
            } catch (Exception ignored) {
                METHOD_CACHE.remove(key);
            }
        }

        Method method = findMethod(obj.getClass(), methodName, args);
        if (method != null) {
            METHOD_CACHE.put(key, method);
            try {
                return method.invoke(obj, args);
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static String generateKey(Object obj, String methodName, Object... args) {
        StringBuilder sb = new StringBuilder();
        sb.append(obj.getClass().getName()).append(':').append(methodName);
        for (Object arg : args) {
            sb.append(':').append(arg != null ? arg.getClass().getName() : "null");
        }
        return sb.toString();
    }

    private static Method findMethod(Class<?> clazz, String methodName, Object[] args) {
        Class<?>[] argClasses = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null) {
                argClasses[i] = args[i].getClass();
            }
        }

        try {
            return clazz.getMethod(methodName, argClasses);
        } catch (NoSuchMethodException ignored) {}

        Class<?>[] primitiveClasses = new Class[args.length];
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
            return clazz.getMethod(methodName, primitiveClasses);
        } catch (NoSuchMethodException ignored) {}

        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)) {
                if (args.length == 0) {
                    return m;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == args.length) {
                    boolean match = true;
                    for (int i = 0; i < params.length; i++) {
                        if (args[i] != null) {
                            Class<?> paramClass = params[i];
                            Class<?> argClass = args[i].getClass();
                            boolean primitiveMatch = isPrimitiveMatch(paramClass, argClass);
                            boolean uuidMatch = isUuidMatch(paramClass, argClass);
                            if (!primitiveMatch && !uuidMatch && !paramClass.isAssignableFrom(argClass)) {
                                match = false;
                                break;
                            }
                        }
                    }
                    if (match) {
                        return m;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isUuidMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == UUID.class || "java.util.UUID".equals(paramClass.getName())) {
            return argClass == UUID.class || "java.util.UUID".equals(argClass.getName());
        }
        return false;
    }

    private static boolean isPrimitiveMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == int.class) return argClass == Integer.class;
        if (paramClass == long.class) return argClass == Long.class;
        if (paramClass == double.class) return argClass == Double.class;
        if (paramClass == boolean.class) return argClass == Boolean.class;
        return false;
    }

    /**
     * [SYNC-PAPI-006] Get balance from RankEntry record (supports Java records).
     */
    private static double getRecordBalance(Object entry) {
        if (entry == null) return -1;

        try {
            Method m = entry.getClass().getMethod("balance");
            Object result = m.invoke(entry);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception ignored) {}

        try {
            var field = entry.getClass().getField("balance");
            Object result = field.get(entry);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception ignored) {}

        return -1;
    }

    private void log(String message) {
        if (debugMode) {
            Bukkit.getLogger().fine("[Syncmoney-PAPI-Debug] PlaceholderHandler: " + message);
        }
    }
}
