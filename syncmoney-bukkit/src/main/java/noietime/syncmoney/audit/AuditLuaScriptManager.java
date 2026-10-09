package noietime.syncmoney.audit;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.storage.RedisManager;

/**
 * Manages the Redis Lua audit log script lifecycle.
 * Handles loading, reloading, and error recovery for the atomic_audit.lua script.
 * Provides atomic operations for sliding window audit log in Redis.
 */
final class AuditLuaScriptManager {

    private static final String AUDIT_SCRIPT_NAME = "atomic_audit.lua";

    private final Syncmoney plugin;
    private final RedisManager redisManager;

    private String auditScriptSha;
    private volatile boolean auditScriptBlocked = false;

    AuditLuaScriptManager(Syncmoney plugin, RedisManager redisManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
    }

    /**
     * Loads the audit Lua script into Redis.
     */
    void load() {
        if (redisManager == null || redisManager.isDegraded()) {
            plugin.getLogger().fine("Redis not available or degraded, skipping audit Lua script loading.");
            return;
        }
        try {
            String script = loadScript(AUDIT_SCRIPT_NAME);
            try (var jedis = redisManager.getResource()) {
                auditScriptSha = jedis.scriptLoad(script);
                auditScriptBlocked = false;
                plugin.getLogger().fine("Audit Lua script loaded.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load audit Lua script: " + e.getMessage());
        }
    }

    /**
     * Reloads the audit Lua script (e.g., after it was blocked by Redis).
     */
    synchronized void reload() {
        try {
            plugin.getLogger().warning("Reloading audit Lua script due to previous blocking...");
            String script = loadScript(AUDIT_SCRIPT_NAME);
            try (var jedis = redisManager.getResource()) {
                auditScriptSha = jedis.scriptLoad(script);
                auditScriptBlocked = false;
                plugin.getLogger().fine("Audit Lua script reloaded successfully.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload audit Lua script: " + e.getMessage());
        }
    }

    /**
     * Inspects a script error and attempts recovery if the script was blocked by Redis.
     *
     * @param errorMessage the exception message from the evalsha call
     * @return true if the script was reloaded and the caller should retry
     */
    boolean handleError(String errorMessage) {
        if (errorMessage != null && errorMessage.contains("not allowed from script")) {
            plugin.getLogger().severe("Audit Lua script blocked by Redis, will attempt to reload...");
            auditScriptBlocked = true;
            reload();
            return true;
        }
        return false;
    }

    /**
     * Gets the SHA of the loaded audit script.
     */
    String getAuditScriptSha() {
        return auditScriptSha;
    }

    /**
     * Checks if the audit script is currently blocked.
     */
    boolean isAuditScriptBlocked() {
        return auditScriptBlocked;
    }

    /**
     * Loads a Lua script from resources.
     */
    private String loadScript(String filename) {
        try (var is = plugin.getResource("lua/" + filename);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load script: " + filename, e);
        }
    }
}
