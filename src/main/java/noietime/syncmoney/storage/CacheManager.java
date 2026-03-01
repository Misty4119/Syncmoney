package noietime.syncmoney.storage;

import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

/**
 * [SYNC-CACHE-001] In-memory cache layer with Redis backend.
 * Provides getBalance, getVersion, atomicSetBalance, atomicAddBalance operations.
 * Read priority: Memory -> Redis
 *
 * [AsyncScheduler] All Redis operations should be called from async threads.
 */
public final class CacheManager {

    private static final String KEY_PREFIX_BALANCE = "syncmoney:balance:";
    private static final String KEY_PREFIX_VERSION = "syncmoney:version:";
    private static final long MIN_RELOAD_INTERVAL_MS = 60000; // 60 seconds

    private final RedisManager redisManager;
    private final Plugin plugin;
    private final ConcurrentMap<UUID, CachedBalance> memoryCache;
    private final boolean redisRequired;

    private String atomicSetBalanceSha;
    private String atomicAddBalanceSha;
    private volatile boolean setScriptBlocked = false;
    private volatile boolean addScriptBlocked = false;

    private volatile long lastReloadTime = 0;

    private volatile boolean isReloading = false;

    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong(0);

    private final int expirationMinutes = 30;

    public CacheManager(Plugin plugin, RedisManager redisManager, boolean redisRequired) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.redisRequired = redisRequired;
        this.memoryCache = new ConcurrentHashMap<>();

        if (!redisRequired) {
            plugin.getLogger().fine("CacheManager initialized in LOCAL mode - skipping Redis operations");
            return;
        }

        loadLuaScripts();
    }

    /**
     * Load Lua scripts and cache SHA.
     */
    private void loadLuaScripts() {
        try {
            String atomicSetScript = loadScript("atomic_set_balance.lua");
            String atomicAddScript = loadScript("atomic_add_balance.lua");

            try (var jedis = redisManager.getResource()) {
                atomicSetBalanceSha = jedis.scriptLoad(atomicSetScript);
                atomicAddBalanceSha = jedis.scriptLoad(atomicAddScript);
                plugin.getLogger().fine("Lua scripts loaded successfully.");
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
        if (cached != null) {
            cacheHits.incrementAndGet();
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
            plugin.getLogger().warning("Failed to get balance for " + uuid + ": " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get version number.
     */
    public long getVersion(UUID uuid) {
        CachedBalance cached = memoryCache.get(uuid);
        if (cached != null) {
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
     * Atomic set balance.
     * @return New version number
     */
    public long atomicSetBalance(UUID uuid, BigDecimal newBalance) {
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
     * Remove player data from memory cache.
     */
    public void invalidateCache(UUID uuid) {
        memoryCache.remove(uuid);
    }

    /**
     * Cache record - uses BigDecimal to avoid floating point errors.
     */
    public record CachedBalance(BigDecimal balance, long version) {}


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
}
