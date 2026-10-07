package noietime.syncmoney.command;

import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Manages the Redis Lua transfer script lifecycle.
 * Handles loading, reloading, and error recovery for the atomic_transfer.lua script.
 */
final class PayLuaScriptManager {

    private final Plugin plugin;
    private final RedisManager redisManager;

    private volatile String transferScriptSha;
    private volatile String transferScript;
    private volatile boolean transferScriptBlocked = false;

    PayLuaScriptManager(Plugin plugin, RedisManager redisManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
    }

    void load() {
        try {
            transferScript = loadScript("atomic_transfer.lua");
        } catch (Exception sourceFailure) {
            plugin.getLogger().severe("Failed to read transfer script: " + sourceFailure.getMessage());
            return;
        }
        if (redisManager == null || redisManager.isDegraded()) {
            plugin.getLogger().warning("Redis not available; retaining transfer script for lazy load after recovery.");
            return;
        }
        try {
            try (var jedis = redisManager.getResource()) {
                transferScriptSha = jedis.scriptLoad(transferScript);
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
            transferScript = loadScript("atomic_transfer.lua");
            if (redisManager == null || redisManager.isDegraded()) {
                plugin.getLogger().warning("Redis is degraded; transfer script source is ready for lazy loading.");
                return;
            }
            try (var jedis = redisManager.getResource()) {
                transferScriptSha = jedis.scriptLoad(transferScript);
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

    Object evalTransfer(UUID senderUuid, UUID targetUuid, BigDecimal amount) throws Exception {
        String senderKey = "syncmoney:balance:" + senderUuid;
        String senderVersionKey = "syncmoney:version:" + senderUuid;
        String targetKey = "syncmoney:balance:" + targetUuid;
        String targetVersionKey = "syncmoney:version:" + targetUuid;
        String script = getTransferScriptSource();

        Jedis resource;
        try {
            resource = redisManager.getResource();
        } catch (Exception unavailable) {
            throw notSent("Redis transfer was not sent because no connection was available", unavailable);
        }

        try (Jedis jedis = resource) {
            String sha = transferScriptSha;
            if (sha == null) {
                try {
                    sha = jedis.scriptLoad(script);
                    transferScriptSha = sha;
                    transferScriptBlocked = false;
                } catch (Exception loadFailure) {
                    throw notSent("Redis transfer script could not be loaded before execution", loadFailure);
                }
            }
            try {
                return eval(jedis, sha, senderKey, senderVersionKey, targetKey, targetVersionKey, amount);
            } catch (JedisNoScriptException missingScript) {
                final String reloadedSha;
                try {
                    reloadedSha = jedis.scriptLoad(script);
                    transferScriptSha = reloadedSha;
                } catch (Exception reloadFailure) {
                    throw notSent("Redis transfer NOSCRIPT reload failed before execution", reloadFailure);
                }

                try {
                    return eval(jedis, reloadedSha, senderKey, senderVersionKey, targetKey, targetVersionKey,
                            amount);
                } catch (JedisNoScriptException stillMissing) {
                    throw notSent("Redis transfer script remained unavailable after NOSCRIPT reload", stillMissing);
                } catch (JedisDataException rejected) {
                    if (isKnownNoMutationError(rejected.getMessage())) {
                        throw rejected("Redis explicitly rejected transfer without mutation: "
                                + rejected.getMessage(), rejected);
                    }
                    throw sentUnknown("Redis transfer retry returned an unexpected error", rejected);
                } catch (RedisMutationException classified) {
                    throw classified;
                } catch (Exception failure) {
                    throw sentUnknown("Redis transfer retry outcome is unknown", failure);
                }
            } catch (JedisDataException rejected) {
                if (isKnownNoMutationError(rejected.getMessage())) {
                    throw rejected("Redis explicitly rejected transfer without mutation: "
                            + rejected.getMessage(), rejected);
                }
                throw sentUnknown("Redis transfer returned an unexpected error", rejected);
            } catch (RedisMutationException classified) {
                throw classified;
            } catch (Exception failure) {
                throw sentUnknown("Redis transfer outcome is unknown", failure);
            }
        } catch (RedisMutationException classified) {
            throw classified;
        } catch (Exception closeOrExecutionFailure) {
            throw sentUnknown("Redis transfer outcome is unknown", closeOrExecutionFailure);
        }
    }

    private static Object eval(Jedis jedis, String sha, String senderKey, String senderVersionKey,
            String targetKey, String targetVersionKey, BigDecimal amount) {
        Object result = jedis.evalsha(sha, 4, senderKey, senderVersionKey, targetKey, targetVersionKey,
                amount.toPlainString());
        return validateTransferReceipt(result);
    }

    static Object validateTransferReceipt(Object result) {
        if (!(result instanceof List<?> receipt) || receipt.size() < 4 || receipt.size() > 5) {
            throw sentUnknown("Redis transfer returned an invalid authority receipt", null);
        }
        try {
            BigDecimal senderBalance = new BigDecimal(receipt.get(0).toString());
            BigDecimal targetBalance = new BigDecimal(receipt.get(1).toString());
            long senderVersion = Long.parseLong(receipt.get(2).toString());
            long targetVersion = Long.parseLong(receipt.get(3).toString());
            if (senderBalance.signum() < 0 || targetBalance.signum() < 0
                    || senderVersion < 0 || targetVersion < 0) {
                throw new IllegalArgumentException("negative balance or version in authority receipt");
            }
            if (receipt.size() == 5) {
                UUID.fromString(receipt.get(4).toString());
            }
        } catch (RuntimeException invalidReceipt) {
            throw sentUnknown("Redis transfer returned an invalid authority receipt", invalidReceipt);
        }
        return result;
    }

    private static boolean isKnownNoMutationError(String message) {
        return message != null && (message.contains("INVALID_AMOUNT")
                || message.contains("INSUFFICIENT_FUNDS")
                || message.contains("INVALID_KEY_TYPE")
                || message.contains("INVALID_BALANCE")
                || message.contains("INVALID_VERSION")
                || message.contains("VERSION_OVERFLOW")
                || message.contains("NEGATIVE_BALANCE")
                || message.contains("SELF_TRANSFER"));
    }

    private static RedisMutationException notSent(String message, Throwable cause) {
        return new RedisMutationException(RedisMutationException.ExecutionState.NOT_SENT, message, cause);
    }

    private static RedisMutationException sentUnknown(String message, Throwable cause) {
        return new RedisMutationException(RedisMutationException.ExecutionState.SENT_UNKNOWN, message, cause);
    }

    private static RedisMutationException rejected(String message, Throwable cause) {
        return new RedisMutationException(RedisMutationException.ExecutionState.REJECTED, message, cause);
    }

    boolean isTransferScriptBlocked() {
        return transferScriptBlocked;
    }

    private String getTransferScriptSource() {
        String script = transferScript;
        if (script != null) {
            return script;
        }
        synchronized (this) {
            script = transferScript;
            if (script == null) {
                try {
                    script = loadScript("atomic_transfer.lua");
                    transferScript = script;
                } catch (RuntimeException sourceFailure) {
                    throw notSent("Redis transfer script source is unavailable", sourceFailure);
                }
            }
        }
        return script;
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
