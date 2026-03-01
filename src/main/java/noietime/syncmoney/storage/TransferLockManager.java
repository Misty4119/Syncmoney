package noietime.syncmoney.storage;

import noietime.syncmoney.util.Constants;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.params.SetParams;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Distributed lock manager.
 * Used to lock both accounts during /pay transfers to avoid concurrency issues.
 * In LOCAL mode, uses in-memory locks instead of Redis.
 *
 * [AsyncScheduler] All Redis operations should be executed on non-main threads.
 */
public final class TransferLockManager {

    private static final String LOCK_PREFIX = Constants.LOCK_PREFIX;

    // Lua script for safe lock release (only delete if value matches)
    private static final String SAFE_UNLOCK_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('del', KEYS[1]) " +
        "else " +
        "    return 0 " +
        "end";

    private final RedisManager redisManager;
    private final Plugin plugin;
    private final boolean redisRequired;
    private final boolean localMode;
    private String unlockScriptSha;

    private final ConcurrentMap<UUID, String> memoryLocks = new ConcurrentHashMap<>();

    private final ConcurrentMap<UUID, String> lockValues;

    public TransferLockManager(Plugin plugin, RedisManager redisManager, boolean redisRequired) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.redisRequired = redisRequired;
        this.localMode = !redisRequired;
        this.lockValues = new ConcurrentHashMap<>();
        
        if (!localMode) {
            loadUnlockScript();
        } else {
            plugin.getLogger().fine("TransferLockManager initialized in LOCAL mode (in-memory locks)");
        }
    }

    /**
     * Loads unlock Lua script.
     */
    private void loadUnlockScript() {
        try (var jedis = redisManager.getResource()) {
            unlockScriptSha = jedis.scriptLoad(SAFE_UNLOCK_SCRIPT);
            plugin.getLogger().fine("Unlock Lua script loaded for TransferLockManager.");
        } catch (Exception e) {
            if (redisRequired) {
                plugin.getLogger().severe("Failed to load unlock script: " + e.getMessage());
            } else {
                plugin.getLogger().fine("Redis not available, skipping unlock script loading.");
            }
        }
    }

    /**
     * Acquires dual lock (fixed order to avoid deadlocks).
     * @param uuid1 First UUID
     * @param uuid2 Second UUID
     * @return true if lock acquired successfully
     */
    public boolean acquireDualLock(UUID uuid1, UUID uuid2) {
        String s1 = uuid1.toString();
        String s2 = uuid2.toString();
        UUID first = s1.compareTo(s2) < 0 ? uuid1 : uuid2;
        UUID second = s1.compareTo(s2) < 0 ? uuid2 : uuid1;

        if (!acquireLock(first)) {
            return false;
        }

        if (!acquireLock(second)) {
            releaseLock(first);
            plugin.getLogger().warning("acquireDualLock: second lock failed, released first lock for " + first);
            return false;
        }

        return true;
    }

    /**
     * Releases dual lock.
     */
    public void releaseDualLock(UUID uuid1, UUID uuid2) {
        releaseLock(uuid1);
        releaseLock(uuid2);
    }

    /**
     * Acquires single lock.
     */
    private boolean acquireLock(UUID uuid) {
        if (localMode) {
            return acquireMemoryLock(uuid);
        }
        
        String lockKey = LOCK_PREFIX + uuid.toString();
        String lockValue = UUID.randomUUID().toString();

        for (int i = 0; i < Constants.LOCK_MAX_RETRIES; i++) {
            try (var jedis = redisManager.getResource()) {
                String result = jedis.set(lockKey,
                    lockValue,
                    SetParams.setParams().nx().ex(Constants.LOCK_TIMEOUT_SECONDS));

                if ("OK".equals(result)) {
                    lockValues.put(uuid, lockValue);
                    return true;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Lock acquisition error: " + e.getMessage());
            }

            try {
                    Thread.sleep(Constants.LOCK_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        plugin.getLogger().warning("Failed to acquire lock for " + uuid);
        return false;
    }

    /**
     * Acquires in-memory lock (LOCAL mode).
     */
    private boolean acquireMemoryLock(UUID uuid) {
        String lockValue = UUID.randomUUID().toString();
        
        String existing = memoryLocks.putIfAbsent(uuid, lockValue);
        if (existing == null) {
            lockValues.put(uuid, lockValue);
            return true;
        }
        
        for (int i = 0; i < Constants.LOCK_MAX_RETRIES; i++) {
            try {
                    Thread.sleep(Constants.LOCK_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            
            existing = memoryLocks.putIfAbsent(uuid, lockValue);
            if (existing == null) {
                lockValues.put(uuid, lockValue);
                return true;
            }
        }
        
        return false;
    }

    /**
     * Releases single lock (validates with Lua script before deletion).
     */
    private void releaseLock(UUID uuid) {
        if (localMode) {
            releaseMemoryLock(uuid);
            return;
        }
        
        String lockKey = LOCK_PREFIX + uuid.toString();
        String lockValue = lockValues.remove(uuid);

        if (lockValue == null) {
            plugin.getLogger().warning("Attempted to release lock but no value found for " + uuid);
            return;
        }

        try (var jedis = redisManager.getResource()) {
            if (unlockScriptSha != null) {
                jedis.evalsha(unlockScriptSha, 1, lockKey, lockValue);
            } else {
                jedis.del(lockKey);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Lock release error: " + e.getMessage());
        }
    }

    /**
     * Releases in-memory lock (LOCAL mode).
     */
    private void releaseMemoryLock(UUID uuid) {
        lockValues.remove(uuid);
        memoryLocks.remove(uuid);
    }
}
