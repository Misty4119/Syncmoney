package noietime.syncmoney.expansion;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * [SYNC-PAPI-014] Manages cached plugin references and provides database access.
 */
public final class PluginCache {

    private static final long CACHE_EXPIRY_MS = 5000;

    private final Object plugin;
    private final boolean debugMode;

    private Object cachedEconomyFacade;
    private Object cachedBaltopManager;
    private Object cachedNameResolver;
    private long cacheTimestamp = 0;

    public PluginCache(Object plugin, boolean debugMode) {
        this.plugin = plugin;
        this.debugMode = debugMode;
    }

    /**
     * [SYNC-PAPI-015] Get cached economy facade (with periodic refresh).
     */
    public Object getEconomyFacade() {
        refreshIfNeeded();
        return cachedEconomyFacade;
    }

    /**
     * [SYNC-PAPI-016] Get cached baltop manager (with periodic refresh).
     */
    public Object getBaltopManager() {
        refreshIfNeeded();
        return cachedBaltopManager;
    }

    /**
     * [SYNC-PAPI-017] Get cached name resolver (with periodic refresh).
     */
    public Object getNameResolver() {
        refreshIfNeeded();
        return cachedNameResolver;
    }

    /**
     * [SYNC-PAPI-018] Refresh cache if expired.
     */
    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (cachedEconomyFacade == null || now - cacheTimestamp > CACHE_EXPIRY_MS) {
            cachedEconomyFacade = ReflectionHelper.invokeMethod(plugin, "getEconomyFacade");
            cachedBaltopManager = ReflectionHelper.invokeMethod(plugin, "getBaltopManager");
            cachedNameResolver = ReflectionHelper.invokeMethod(plugin, "getNameResolver");
            cacheTimestamp = now;

            if (debugMode) {
                log("PluginCache refreshed: economyFacade=" + (cachedEconomyFacade != null) +
                    ", baltopManager=" + (cachedBaltopManager != null));
            }
        }
    }

    /**
     * [SYNC-PAPI-019] Get plugin description for version.
     */
    public String getVersion() {
        Object desc = ReflectionHelper.invokeMethod(plugin, "getDescription");
        if (desc != null) {
            Object version = ReflectionHelper.invokeMethod(desc, "getVersion");
            if (version != null) {
                return version.toString();
            }
        }
        return "1.0.0";
    }

    /**
     * [SYNC-PAPI-020] Get DataSource from DatabaseManager.
     */
    public DataSource getDataSource() {
        Object databaseManager = ReflectionHelper.invokeMethod(plugin, "getDatabaseManager");
        if (databaseManager != null) {
            return (DataSource) ReflectionHelper.invokeMethod(databaseManager, "getDataSource");
        }
        return null;
    }

    /**
     * [SYNC-PAPI-021] Get player name from database by UUID.
     */
    public String getPlayerNameFromDatabase(UUID uuid) {
        DataSource ds = getDataSource();
        if (ds == null || uuid == null) return null;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT player_name FROM players WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("player_name");
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void log(String message) {
        org.bukkit.Bukkit.getLogger().fine("[Syncmoney-PAPI-Debug] " + message);
    }
}
