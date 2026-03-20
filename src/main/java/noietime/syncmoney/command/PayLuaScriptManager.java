package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.storage.RedisManager;

/**
 * Manages the Redis Lua transfer script lifecycle.
 * Handles loading, reloading, and error recovery for the atomic_transfer.lua script.
 */
final class PayLuaScriptManager {

    private final Syncmoney plugin;
    private final RedisManager redisManager;

    private String transferScriptSha;
    private volatile boolean transferScriptBlocked = false;

    PayLuaScriptManager(Syncmoney plugin, RedisManager redisManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
    }

    void load() {
        if (redisManager == null || redisManager.isDegraded()) {
            plugin.getLogger().warning("Redis not available, skipping Lua script loading.");
            return;
        }
        try {
            String script = loadScript("atomic_transfer.lua");
            try (var jedis = redisManager.getResource()) {
                transferScriptSha = jedis.scriptLoad(script);
                transferScriptBlocked = false;
                plugin.getLogger().fine("Transfer Lua script loaded.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load transfer script: " + e.getMessage());
        }
    }

    synchronized void reload() {
        try {
            plugin.getLogger().warning("Reloading transfer Lua script due to previous blocking...");
            String script = loadScript("atomic_transfer.lua");
            try (var jedis = redisManager.getResource()) {
                transferScriptSha = jedis.scriptLoad(script);
                transferScriptBlocked = false;
                plugin.getLogger().fine("Transfer Lua script reloaded successfully.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload transfer script: " + e.getMessage());
        }
    }

    /**
     * Inspect a script error and attempt recovery if the script was blocked by Redis.
     *
     * @param errorMessage the exception message from the evalsha call
     * @return true if the script was reloaded and the caller should ask the player to retry
     */
    boolean handleError(String errorMessage) {
        if (errorMessage != null && errorMessage.contains("not allowed from script")) {
            plugin.getLogger().severe("Transfer Lua script blocked by Redis, will attempt to reload...");
            transferScriptBlocked = true;
            reload();
            return true;
        }
        return false;
    }

    String getTransferScriptSha() {
        return transferScriptSha;
    }

    boolean isTransferScriptBlocked() {
        return transferScriptBlocked;
    }

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
