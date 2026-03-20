package noietime.syncmoney.storage;

import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

/**
 * [SYNC-CACHE-001] In-memory cache layer with Redis backend.
 * Provides getBalance, getVersion, atomicSetBalance, atomicAddBalance operations.
 * Read priority: Memory -> Redis
 *
 * [AsyncScheduler] All Redis operations should be called from async threads.
 *
 * [TASK-444 FIX] Uses LinkedHashMap with LRU eviction policy.
 */
public final class CacheManager {

    private static final String KEY_PREFIX_BALANCE = "syncmoney:balance:";
    private static final String KEY_PREFIX_VERSION = "syncmoney:version:";
    private static final long MIN_RELOAD_INTERVAL_MS = 60000;

    /**
     * [TASK-444 FIX] Maximum cache size for LRU eviction.
     */
    private static final int MAX_CACHE_SIZE = 10000;

    private final RedisManager redisManager;
    private final Plugin plugin;

    /**
     * [TASK-444 FIX] LRU cache using LinkedHashMap with synchronized wrapper.
     * Access order is maintained for LRU behavior.
     */
    private final Map<UUID, CachedBalance> memoryCache;

    private final boolean redisRequired;

    private String atomicSetBalanceSha;
    private String atomicAddBalanceSha;
    private String atomicTransferSha;
    private volatile boolean setScriptBlocked = false;
    private volatile boolean addScriptBlocked = false;
    private volatile boolean transferScriptBlocked = false;

    private volatile long lastReloadTime = 0;

    private volatile boolean isReloading = false;

    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong(0);

    private final int expirationMinutes = 30;

    public CacheManager(Plugin plugin, RedisManager redisManager, boolean redisRequired) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.redisRequired = redisRequired;


        this.memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<UUID, CachedBalance>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedBalance> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
        );

        if (!redisRequired) {
            plugin.getLogger().fine("CacheManager initialized in LOCAL mode - skipping Redis operations");
            return;
        }

        loadLuaScripts();


        startCleanupTask();
    }
    
    /**
     * [MEM-01 FIX] Start periodic cleanup task for expired cache entries.
     * Runs every 5 minutes to remove expired entries.
     */
    private void startCleanupTask() {

        long cleanupIntervalTicks = 5 * 60 * 20;
        
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            cleanupExpiredEntries();
        }, cleanupIntervalTicks, cleanupIntervalTicks);
        
        plugin.getLogger().fine("Cache cleanup task started (interval: 5 minutes)");
    }
    
    /**
     * [MEM-01 FIX] Remove expired entries from memory cache.
     * Only removes entries that have exceeded the expiration time.
     */
    public void cleanupExpiredEntries() {
        if (memoryCache.isEmpty()) {
            return;
        }

        final int[] removedCount = {0};


        memoryCache.entrySet().removeIf(entry -> {
            CachedBalance cached = entry.getValue();
            if (cached.isExpired(expirationMinutes)) {
                removedCount[0]++;
                return true;
            }
            return false;
        });

        if (removedCount[0] > 0) {
            plugin.getLogger().fine("Cleaned up " + removedCount[0] + " expired cache entries");
        }
    }

    /**
     * Load Lua scripts and cache SHA.
     */
    private void loadLuaScripts() {
        try {
            String atomicSetScript = loadScript("atomic_set_balance.lua");
            String atomicAddScript = loadScript("atomic_add_balance.lua");
            String atomicTransferScript = loadScript("atomic_transfer.lua");

            try (var jedis = redisManager.getResource()) {
                atomicSetBalanceSha = jedis.scriptLoad(atomicSetScript);
                atomicAddBalanceSha = jedis.scriptLoad(atomicAddScript);
                atomicTransferSha = jedis.scriptLoad(atomicTransferScript);
                plugin.getLogger().fine("Lua scripts loaded successfully (including atomic_transfer).");
            }
        } catch (Exception e) {
            if (redisRequired) {
                plugin.getLogger().severe("Failed to load Lua scripts: " + e.getMessage());
            } else {
                plugin.getLogger().fine("Redis not available, skipping Lua script loading.");
            }
        }
    }

    /**
     * Load script content from resource.
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

    /**
     * Get balance (memory first, fallback to Redis on failure).
     */
    public BigDecimal getBalance(UUID uuid) {
        CachedBalance cached = memoryCache.get(uuid);
        if (cached != null && !cached.isExpired(expirationMinutes)) {
            cacheHits.incrementAndGet();

            memoryCache.compute(uuid, (k, v) -> new CachedBalance(cached.balance(), cached.version()));
            return cached.balance();
        }

        cacheMisses.incrementAndGet();

        try (var jedis = redisManager.getResource()) {
            String balanceStr = jedis.get(KEY_PREFIX_BALANCE + uuid.toString());
            BigDecimal balance = balanceStr != null ?
                NumericUtil.normalize(balanceStr) :
                BigDecimal.ZERO;

            String versionStr = jedis.get(KEY_PREFIX_VERSION + uuid.toString());
            long version = versionStr != null ? Long.parseLong(versionStr) : 0L;
            memoryCache.compute(uuid, (k, v) -> new CachedBalance(balance, version));

            return balance;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get version number.
     */
    public long getVersion(UUID uuid) {
        CachedBalance cached = memoryCache.get(uuid);
        if (cached != null && !cached.isExpired(expirationMinutes)) {

            memoryCache.compute(uuid, (k, v) -> new CachedBalance(cached.balance(), cached.version()));
            return cached.version();
        }

        try (var jedis = redisManager.getResource()) {
            String versionStr = jedis.get(KEY_PREFIX_VERSION + uuid.toString());
            return versionStr != null ? Long.parseLong(versionStr) : 0L;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get version for " + uuid + ": " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Batch get versions for multiple players.
     * [ASYNC] This method performs Redis MGET for efficiency.
     *
     * @param uuids set of player UUIDs
     * @return map of UUID to version number (missing keys not included)
     */
    public Map<UUID, Long> getAllVersions(Set<UUID> uuids) {
        Map<UUID, Long> result = new java.util.HashMap<>();
        if (uuids == null || uuids.isEmpty()) {
            return result;
        }

        try (var jedis = redisManager.getResource()) {
            String[] keys = uuids.stream()
                    .map(uuid -> KEY_PREFIX_VERSION + uuid.toString())
                    .toArray(String[]::new);

            List<String> values = jedis.mget(keys);

            int i = 0;
            for (UUID uuid : uuids) {
                if (i < values.size() && values.get(i) != null) {
                    try {
                        result.put(uuid, Long.parseLong(values.get(i)));
                    } catch (NumberFormatException ignored) {

                    }
                }
                i++;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get all versions: " + e.getMessage());
        }

        return result;
    }

    /**
     * Atomic set balance.
     * @return New version number
     */
    public long atomicSetBalance(UUID uuid, BigDecimal newBalance) {
        if (atomicSetBalanceSha == null) {
            plugin.getLogger().severe("Lua script atomicSetBalance not loaded! Mode: " + (redisRequired ? "CROSS_SERVER" : "LOCAL"));
            throw new IllegalStateException("Lua script not available. Please check economy mode configuration.");
        }

        String keyBalance = KEY_PREFIX_BALANCE + uuid.toString();
        String keyVersion = KEY_PREFIX_VERSION + uuid.toString();

        newBalance = NumericUtil.normalize(newBalance);

        if (setScriptBlocked) {
            reloadLuaScripts();
        }

        try (var jedis = redisManager.getResource()) {
            Object result = jedis.evalsha(
                    atomicSetBalanceSha,
                    2,
                    keyVersion,
                    keyBalance,
                    newBalance.toPlainString()
            );

            long newVersion = Long.parseLong(result.toString());

            final BigDecimal finalBalance = newBalance;
            final long finalVersion = newVersion;
            memoryCache.compute(uuid, (k, v) -> new CachedBalance(finalBalance, finalVersion));

            return newVersion;
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (handleLuaScriptError(errorMsg)) {
                plugin.getLogger().severe("Failed to atomic set balance for " + uuid + ": " + errorMsg + " (script reloaded, please retry)");
            } else {
                plugin.getLogger().severe("Failed to atomic set balance for " + uuid + ": " + errorMsg);
            }
            return -1;
        }
    }

    /**
     * Reload Lua scripts (called when script is flagged as dangerous by Redis).
     * Prevents infinite loop from repeated reloads in short time.
     */
    public synchronized void reloadLuaScripts() {
        if (isReloading) {
            plugin.getLogger().warning("Lua script reload already in progress, skipping");
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastReloadTime < MIN_RELOAD_INTERVAL_MS) {
            plugin.getLogger().warning("Skipping Lua script reload - too soon since last reload");
            return;
        }

        isReloading = true;
        lastReloadTime = now;

        try {
            plugin.getLogger().warning("Reloading Lua scripts due to previous blocking...");

            String atomicSetScript = loadScript("atomic_set_balance.lua");
            String atomicAddScript = loadScript("atomic_add_balance.lua");

            try (var jedis = redisManager.getResource()) {
                atomicSetBalanceSha = jedis.scriptLoad(atomicSetScript);
                atomicAddBalanceSha = jedis.scriptLoad(atomicAddScript);
                setScriptBlocked = false;
                addScriptBlocked = false;
                plugin.getLogger().fine("Lua scripts reloaded successfully.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload Lua scripts: " + e.getMessage());
        } finally {
            isReloading = false;
        }
    }

    /**
     * Check and handle Lua script error.
     * @param errorMessage Error message
     * @return true if retry is needed, false if error is unrecoverable
     */
    private boolean handleLuaScriptError(String errorMessage) {
        if (errorMessage != null && errorMessage.contains("not allowed from script")) {
            plugin.getLogger().severe("Lua script blocked by Redis, will attempt to reload...");
            addScriptBlocked = true;
            reloadLuaScripts();
            return true;
        }
        return false;
    }

    /**
     * Atomic add/subtract balance.
     * @param amount Positive to add, negative to subtract
     * @return New balance, or -1 if failed
     */
    public BigDecimal atomicAddBalance(UUID uuid, BigDecimal amount) {
        if (atomicAddBalanceSha == null) {
            plugin.getLogger().severe("Lua script atomicAddBalance not loaded! Mode: " + (redisRequired ? "CROSS_SERVER" : "LOCAL"));
            throw new IllegalStateException("Lua script not available. Please check economy mode configuration.");
        }

        String keyBalance = KEY_PREFIX_BALANCE + uuid.toString();
        String keyVersion = KEY_PREFIX_VERSION + uuid.toString();

        amount = NumericUtil.normalize(amount);

        if (addScriptBlocked) {
            reloadLuaScripts();
        }

        try (var jedis = redisManager.getResource()) {
            Object result = jedis.evalsha(
                    atomicAddBalanceSha,
                    2,
                    keyBalance,
                    keyVersion,
                    amount.toPlainString()
            );

            if (result instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                var list = (java.util.List<?>) result;
                if (list.size() < 2) {
                    plugin.getLogger().severe("Lua script returned insufficient elements: " + list.size());
                    return BigDecimal.valueOf(-1);
                }
                BigDecimal newBalance = NumericUtil.normalize(list.get(0).toString());
                long newVersion = Long.parseLong(list.get(1).toString());

                memoryCache.compute(uuid, (k, v) -> new CachedBalance(newBalance, newVersion));

                return newBalance;
            } else if (result instanceof String) {
                String errorMsg = result.toString();
                if (handleLuaScriptError(errorMsg)) {
                    return BigDecimal.valueOf(-1);
                }
                throw new RuntimeException("Lua error: " + errorMsg);
            }

            return BigDecimal.valueOf(-1);
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (handleLuaScriptError(errorMsg)) {
                plugin.getLogger().severe("Failed to atomic add balance for " + uuid + ": " + errorMsg + " (script reloaded, please retry)");
            } else {
                plugin.getLogger().severe("Failed to atomic add balance for " + uuid + ": " + errorMsg);
            }
            return BigDecimal.valueOf(-1);
        }
    }

    /**
     * Atomic add/subtract balance (double-compatible version).
     * @param amount Positive to add, negative to subtract
     * @return New balance, or -1 if failed
     */
    public double atomicAddBalance(UUID uuid, double amount) {
        return atomicAddBalance(uuid, NumericUtil.normalize(amount)).doubleValue();
    }

    /**
     * Atomic set balance (double-compatible version).
     * @return New version number
     */
    public long atomicSetBalance(UUID uuid, double newBalance) {
        return atomicSetBalance(uuid, NumericUtil.normalize(newBalance));
    }

    /**
     * [FIX-003] Atomic transfer between two players using Redis Lua script.
     * This ensures the transfer is atomic - both withdraw and deposit happen together,
     * or neither happens.
     * 
     * @param fromUuid Sender's UUID
     * @param toUuid Receiver's UUID
     * @param amount Amount to transfer
     * @return TransferResult with new balances and versions, or null if failed
     */
    public TransferResult atomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount) {
        if (atomicTransferSha == null) {
            plugin.getLogger().severe("Lua script atomicTransfer not loaded!");
            throw new IllegalStateException("Lua script not available. Please check economy mode configuration.");
        }

        if (transferScriptBlocked) {
            reloadLuaScripts();
        }

        String fromBalanceKey = KEY_PREFIX_BALANCE + fromUuid.toString();
        String fromVersionKey = KEY_PREFIX_VERSION + fromUuid.toString();
        String toBalanceKey = KEY_PREFIX_BALANCE + toUuid.toString();
        String toVersionKey = KEY_PREFIX_VERSION + toUuid.toString();

        amount = NumericUtil.normalize(amount);

        try (var jedis = redisManager.getResource()) {
            Object result = jedis.evalsha(
                    atomicTransferSha,
                    4,
                    fromBalanceKey, fromVersionKey, toBalanceKey, toVersionKey,
                    amount.toPlainString()
            );

            if (result instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                var list = (java.util.List<?>) result;
                if (list.size() < 4) {
                    plugin.getLogger().severe("Lua transfer script returned insufficient elements: " + list.size());
                    return null;
                }

                BigDecimal fromNewBalance = NumericUtil.normalize(list.get(0).toString());
                BigDecimal toNewBalance = NumericUtil.normalize(list.get(1).toString());
                long fromNewVersion = Long.parseLong(list.get(2).toString());
                long toNewVersion = Long.parseLong(list.get(3).toString());

                memoryCache.compute(fromUuid, (k, v) -> new CachedBalance(fromNewBalance, fromNewVersion));
                memoryCache.compute(toUuid, (k, v) -> new CachedBalance(toNewBalance, toNewVersion));

                return new TransferResult(fromNewBalance, toNewBalance, fromNewVersion, toNewVersion);
            } else if (result instanceof String) {
                String errorMsg = result.toString();
                handleLuaScriptError(errorMsg);
                if (errorMsg.contains("INSUFFICIENT_FUNDS")) {
                    return TransferResult.insufficientFunds();
                }
                plugin.getLogger().severe("Lua transfer error: " + errorMsg);
                return null;
            }

            return null;
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (handleLuaScriptError(errorMsg)) {
                plugin.getLogger().severe("Failed to atomic transfer: " + errorMsg + " (script reloaded, please retry)");
            } else {
                plugin.getLogger().severe("Failed to atomic transfer: " + errorMsg);
            }
            return null;
        }
    }

    /**
     * Result of atomic transfer operation.
     */
    public static class TransferResult {
        public final BigDecimal fromNewBalance;
        public final BigDecimal toNewBalance;
        public final long fromNewVersion;
        public final long toNewVersion;

        private static final TransferResult INSUFFICIENT_FUNDS_INSTANCE = new TransferResult(null, null, -1, -1);

        public static TransferResult insufficientFunds() {
            return INSUFFICIENT_FUNDS_INSTANCE;
        }

        public TransferResult(BigDecimal fromBalance, BigDecimal toBalance, long fromVersion, long toVersion) {
            this.fromNewBalance = fromBalance;
            this.toNewBalance = toBalance;
            this.fromNewVersion = fromVersion;
            this.toNewVersion = toVersion;
        }
    }

    /**
     * Update memory cache (for Pub/Sub usage).
     * Only updates when new version > current version.
     */
    public boolean updateMemoryCache(UUID uuid, BigDecimal balance, long version) {
        final boolean[] updated = {false};
        memoryCache.compute(uuid, (key, existing) -> {
            if (existing == null || version > existing.version()) {
                updated[0] = true;
                return new CachedBalance(balance, version);
            }
            return existing;
        });
        return updated[0];
    }

    /**
     * Update memory cache (double-compatible version).
     */
    public boolean updateMemoryCache(UUID uuid, double balance, long version) {
        return updateMemoryCache(uuid, NumericUtil.normalize(balance), version);
    }

    /**
     * Force sync player's balance to Redis directly, bypassing the event queue.
     * This method writes immediately to Redis without going through the async queue.
     *
     * @param uuid Player's UUID
     * @param balance Balance to sync
     * @return true if sync was successful
     */
    public boolean forceSyncToRedis(UUID uuid, BigDecimal balance) {
        if (redisRequired && (atomicSetBalanceSha == null || redisManager.isDegraded())) {
            plugin.getLogger().warning("Force sync skipped: Redis not available or Lua scripts not loaded");
            return false;
        }

        try (var jedis = redisManager.getResource()) {
            String keyBalance = KEY_PREFIX_BALANCE + uuid.toString();
            String keyVersion = KEY_PREFIX_VERSION + uuid.toString();


            Object result = jedis.evalsha(atomicSetBalanceSha, 2,
                    keyVersion, keyBalance, balance.toPlainString());

            long newVersion = Long.parseLong(result.toString());


            memoryCache.put(uuid, new CachedBalance(balance, newVersion));

            plugin.getLogger().info("Force sync to Redis successful for " + uuid + ": " + balance + " (version: " + newVersion + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to force sync to Redis: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove player data from memory cache.
     */
    public void invalidateCache(UUID uuid) {
        memoryCache.remove(uuid);
    }

    /**
     * Cache record - uses BigDecimal to avoid floating point errors.
     * Includes lastAccessTime for expiration-based cleanup.
     */
    public record CachedBalance(BigDecimal balance, long version, long lastAccessTime) {
        public CachedBalance(BigDecimal balance, long version) {
            this(balance, version, System.currentTimeMillis());
        }

        public boolean isExpired(int expirationMinutes) {
            return System.currentTimeMillis() - lastAccessTime > expirationMinutes * 60 * 1000;
        }
    }

    /**
     * Get cache hit count.
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Get cache miss count.
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Get cache expiration minutes.
     */
    public int getExpirationMinutes() {
        return expirationMinutes;
    }

    /**
     * Reset cache statistics.
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /**
     * Check if Redis is required for current mode.
     */
    public boolean isRedisRequired() {
        return redisRequired;
    }

    /**
     * Get current economy mode description.
     */
    public String getModeDescription() {
        return redisRequired ? "CROSS_SERVER" : "LOCAL";
    }

    /**
     * Check if the add/subtract Lua script is loaded.
     * [ThreadSafe]
     */
    public boolean isAddScriptLoaded() {
        return atomicAddBalanceSha != null;
    }

    /**
     * Check if the set balance Lua script is loaded.
     * [ThreadSafe]
     */
    public boolean isSetScriptLoaded() {
        return atomicSetBalanceSha != null;
    }

    /**
     * Check if the transfer Lua script is loaded.
     * [ThreadSafe]
     */
    public boolean isTransferScriptLoaded() {
        return atomicTransferSha != null;
    }

    /**
     * Check if all Lua scripts are loaded.
     * [ThreadSafe]
     */
    public boolean areAllScriptsLoaded() {
        return atomicAddBalanceSha != null && atomicSetBalanceSha != null && atomicTransferSha != null;
    }
}
