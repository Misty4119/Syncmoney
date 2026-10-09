package noietime.syncmoney.storage;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.util.Constants;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisNoScriptException;

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

    private volatile String atomicSetBalanceSha;
    private volatile String atomicAddBalanceSha;
    private volatile String atomicTransferSha;
    private volatile String atomicSetBalanceScript;
    private volatile String atomicAddBalanceScript;
    private volatile String atomicTransferScript;

    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong(0);

    private final int expirationMinutes = Constants.DEFAULT_EXPIRATION_MINUTES;

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
            atomicSetBalanceScript = loadScript("atomic_set_balance.lua");
            atomicAddBalanceScript = loadScript("atomic_add_balance.lua");
            atomicTransferScript = loadScript("atomic_transfer.lua");

            try (var jedis = redisManager.getResource()) {
                atomicSetBalanceSha = jedis.scriptLoad(atomicSetBalanceScript);
                atomicAddBalanceSha = jedis.scriptLoad(atomicAddBalanceScript);
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

            memoryCache.compute(uuid, (k, v) -> new CachedBalance(
                    cached.balance(), cached.version(), System.currentTimeMillis(), cached.accountPresent()));
            return cached.balance();
        }

        cacheMisses.incrementAndGet();

        try (var jedis = redisManager.getResource()) {
            List<String> values = jedis.mget(RedisKeys.balance(uuid), RedisKeys.version(uuid));
            if (values == null || values.size() != 2) {
                throw new IllegalStateException("Redis returned an invalid balance/version pair");
            }
            String balanceStr = values.get(0);
            String versionStr = values.get(1);
            if (balanceStr == null && versionStr != null) {
                throw new IllegalStateException("Redis account version exists without a balance");
            }
            BigDecimal balance = balanceStr != null ?
                NumericUtil.normalize(balanceStr) :
                BigDecimal.ZERO;
            long version = versionStr != null ? Long.parseLong(versionStr) : 0L;
            memoryCache.compute(uuid, (k, v) -> new CachedBalance(
                    balance, version, System.currentTimeMillis(), balanceStr != null));

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

            memoryCache.compute(uuid, (k, v) -> new CachedBalance(
                    cached.balance(), cached.version(), System.currentTimeMillis(), cached.accountPresent()));
            return cached.version();
        }

        try (var jedis = redisManager.getResource()) {
            String versionStr = jedis.get(RedisKeys.version(uuid));
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
                    .map(RedisKeys::version)
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
        try {
            ConfirmedAccount receipt = atomicSetBalanceReceipt(uuid, newBalance);
            return receipt == null ? -1L : receipt.version();
        } catch (RedisMutationException e) {
            plugin.getLogger().warning("Atomic set did not return a confirmed receipt: " + e.getMessage());
            return -1L;
        }
    }

    /** Apply a set and return the exact balance/version pair returned by this mutation. */
    public ConfirmedAccount atomicSetBalanceReceipt(UUID uuid, BigDecimal newBalance) {
        if (atomicSetBalanceSha == null) {
            plugin.getLogger().severe("Lua script atomicSetBalance not loaded! Mode: "
                    + (redisRequired ? "CROSS_SERVER" : "LOCAL"));
            throw new IllegalStateException("Lua script not available. Please check economy mode configuration.");
        }

        String keyBalance = RedisKeys.balance(uuid);
        String keyVersion = RedisKeys.version(uuid);
        BigDecimal confirmedBalance = NumericUtil.normalize(newBalance);

        Jedis resource = acquireRedisResource("atomic set");
        if (resource == null) return null;
        try (var jedis = resource) {
            Object result = evalScriptWithNoscriptReload(jedis, atomicSetBalanceSha, atomicSetBalanceScript,
                    2, new String[] {keyVersion, keyBalance, confirmedBalance.toPlainString()},
                    sha -> atomicSetBalanceSha = sha);
            long confirmedVersion = Long.parseLong(result.toString());
            ConfirmedAccount confirmed = new ConfirmedAccount(uuid, confirmedBalance, confirmedVersion);
            rememberConfirmedAccount(confirmed);
            return confirmed;
        } catch (RedisMutationException e) {
            throw e;
        } catch (JedisDataException e) {
            if (isKnownNoMutationError(e.getMessage())) {
                plugin.getLogger().warning("Redis rejected atomic set for " + uuid + ": " + e.getMessage());
                throw rejected("Redis explicitly rejected atomic set without mutation", e);
            }
            throw sentUnknown("Redis atomic set outcome is unknown for " + uuid, e);
        } catch (Exception e) {
            throw sentUnknown("Redis atomic set outcome is unknown for " + uuid, e);
        }
    }

    private Jedis acquireRedisResource(String operation) {
        try {
            return redisManager.getResource();
        } catch (Exception e) {
            markDegradedOnRedisTransportFailure(e);
            plugin.getLogger().warning("Redis " + operation + " was not sent: " + e.getMessage());
            return null;
        }
    }

    private Object evalScriptWithNoscriptReload(Jedis jedis, String sha, String script,
            int keyCount, String[] parameters, java.util.function.Consumer<String> shaUpdater) {
        try {
            return jedis.evalsha(sha, keyCount, parameters);
        } catch (JedisNoScriptException missingScript) {
            final String reloadedSha;
            try {
                reloadedSha = jedis.scriptLoad(script);
                shaUpdater.accept(reloadedSha);
            } catch (Exception reloadFailure) {
                throw new RedisMutationException(RedisMutationException.ExecutionState.NOT_SENT,
                        "Redis script reload failed before mutation", reloadFailure);
            }

            try {
                return jedis.evalsha(reloadedSha, keyCount, parameters);
            } catch (JedisNoScriptException stillMissing) {
                throw new RedisMutationException(RedisMutationException.ExecutionState.NOT_SENT,
                        "Redis script remained unavailable after reload", stillMissing);
            } catch (JedisDataException rejected) {
                if (isKnownNoMutationError(rejected.getMessage())) throw rejected;
                throw sentUnknown("Redis script response was an unexpected error", rejected);
            } catch (Exception failure) {
                throw sentUnknown("Redis script retry outcome is unknown", failure);
            }
        } catch (JedisDataException rejected) {
            if (isKnownNoMutationError(rejected.getMessage())) throw rejected;
            throw sentUnknown("Redis script response was an unexpected error", rejected);
        } catch (Exception failure) {
            throw sentUnknown("Redis script execution outcome is unknown", failure);
        }
    }

    private static boolean isKnownNoMutationError(String message) {
        if (message == null) return false;
        return message.contains("INVALID_AMOUNT")
                || message.contains("INSUFFICIENT_FUNDS")
                || message.contains("NEGATIVE_BALANCE")
                || message.contains("INVALID_KEY_TYPE")
                || message.contains("INVALID_BALANCE")
                || message.contains("INVALID_VERSION")
                || message.contains("VERSION_OVERFLOW")
                || message.contains("SELF_TRANSFER");
    }

    private RedisMutationException sentUnknown(String message, Throwable cause) {
        markDegradedOnRedisTransportFailure(cause);
        return new RedisMutationException(RedisMutationException.ExecutionState.SENT_UNKNOWN, message, cause);
    }

    private static RedisMutationException rejected(String message, Throwable cause) {
        return new RedisMutationException(RedisMutationException.ExecutionState.REJECTED, message, cause);
    }
    public synchronized void reloadLuaScripts() {
        loadLuaScripts();
    }

    /**
     * Atomic add/subtract balance.
     * @param amount Positive to add, negative to subtract
     * @return New balance, or -1 if failed
     */
    public BigDecimal atomicAddBalance(UUID uuid, BigDecimal amount) {
        try {
            ConfirmedAccount receipt = atomicAddBalanceReceipt(uuid, amount);
            return receipt == null ? BigDecimal.valueOf(-1) : receipt.balance();
        } catch (RedisMutationException e) {
            plugin.getLogger().warning("Atomic add did not return a confirmed receipt: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        }
    }

    /** Apply an add and retain the balance/version pair returned by the same Lua execution. */
    public ConfirmedAccount atomicAddBalanceReceipt(UUID uuid, BigDecimal amount) {
        if (atomicAddBalanceSha == null) {
            plugin.getLogger().severe("Lua script atomicAddBalance not loaded! Mode: "
                    + (redisRequired ? "CROSS_SERVER" : "LOCAL"));
            throw new IllegalStateException("Lua script not available. Please check economy mode configuration.");
        }

        String keyBalance = RedisKeys.balance(uuid);
        String keyVersion = RedisKeys.version(uuid);
        BigDecimal normalizedAmount = NumericUtil.normalize(amount);

        Jedis resource = acquireRedisResource("atomic add");
        if (resource == null) return null;
        try (var jedis = resource) {
            Object result = evalScriptWithNoscriptReload(jedis, atomicAddBalanceSha, atomicAddBalanceScript,
                    2, new String[] {keyBalance, keyVersion, normalizedAmount.toPlainString()},
                    sha -> atomicAddBalanceSha = sha);
            if (result instanceof List<?> values) {
                if (values.size() < 2) {
                    throw sentUnknown("Lua add returned an incomplete authority receipt", null);
                }
                BigDecimal confirmedBalance = NumericUtil.normalize(values.get(0).toString());
                long confirmedVersion = Long.parseLong(values.get(1).toString());
                ConfirmedAccount confirmed = new ConfirmedAccount(uuid, confirmedBalance, confirmedVersion);
                rememberConfirmedAccount(confirmed);
                return confirmed;
            }
            if (result instanceof String errorMessage) {
                if (isKnownNoMutationError(errorMessage)) {
                    throw rejected("Redis explicitly rejected atomic add without mutation: " + errorMessage, null);
                }
                throw sentUnknown("Lua add returned an unrecognized error response", null);
            }
            throw sentUnknown("Lua add returned an invalid authority receipt", null);
        } catch (RedisMutationException e) {
            throw e;
        } catch (JedisDataException e) {
            if (isKnownNoMutationError(e.getMessage())) {
                plugin.getLogger().warning("Redis rejected atomic add for " + uuid + ": " + e.getMessage());
                throw rejected("Redis explicitly rejected atomic add without mutation", e);
            }
            throw sentUnknown("Redis atomic add outcome is unknown for " + uuid, e);
        } catch (Exception e) {
            throw sentUnknown("Redis atomic add outcome is unknown for " + uuid, e);
        }
    }
    public java.util.Optional<CachedBalance> getBalanceSnapshotStrict(UUID uuid) {
        CachedBalance cached = memoryCache.get(uuid);
        if (cached != null && cached.accountPresent() && !cached.isExpired(expirationMinutes)) {
            cacheHits.incrementAndGet();
            memoryCache.compute(uuid, (k, v) -> new CachedBalance(
                    cached.balance(), cached.version(), System.currentTimeMillis(), cached.accountPresent()));
            return java.util.Optional.of(cached);
        }

        cacheMisses.incrementAndGet();
        try (var jedis = redisManager.getResource()) {
            List<String> values = jedis.mget(RedisKeys.balance(uuid), RedisKeys.version(uuid));
            if (values == null || values.size() != 2) {
                throw new IllegalStateException("Redis returned an invalid cold account snapshot");
            }
            String balanceStr = values.get(0);
            String versionStr = values.get(1);
            if (balanceStr == null && versionStr == null) {
                return java.util.Optional.empty();
            }
            if (balanceStr == null) {
                throw new IllegalStateException("Redis account version exists without a balance");
            }
            BigDecimal balance = NumericUtil.normalize(balanceStr);
            long version = versionStr != null ? Long.parseLong(versionStr) : 0L;
            CachedBalance snapshot = new CachedBalance(balance, version);
            memoryCache.compute(uuid, (k, v) -> snapshot);
            return java.util.Optional.of(snapshot);
        }
    }

    /**
     * Read the current Redis authority balance/version pair without consulting the local cache.
     * This is reserved for reconciliation after Redis has explicitly rejected a mutation.
     */
    public java.util.Optional<CachedBalance> readAuthorityBalanceSnapshotStrict(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("An authority snapshot requires an account UUID");
        }

        CachedBalance cachedAtReadStart = memoryCache.get(uuid);
        try (var jedis = redisManager.getResource()) {
            if (jedis == null) {
                throw new IllegalStateException("Redis resource is unavailable for authority reconciliation");
            }
            List<String> values = jedis.mget(RedisKeys.balance(uuid), RedisKeys.version(uuid));
            if (values == null || values.size() != 2) {
                throw new IllegalStateException("Redis returned an invalid account authority snapshot");
            }
            if (values.get(0) == null && values.get(1) == null) {
                clearCachedAccountIfUnchanged(uuid, cachedAtReadStart);
                return java.util.Optional.empty();
            }

            CachedBalance snapshot = parseStrictSnapshot(uuid, values.get(0), values.get(1));
            rememberConfirmedAccount(new ConfirmedAccount(uuid, snapshot.balance(), snapshot.version()));
            return java.util.Optional.of(snapshot);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Redis account authority snapshot", e);
        }
    }

    /**
     * Read one unexpired balance/version pair from the local cache without Redis I/O.
     * Legacy publishers use this to avoid attaching a version read separately from
     * the balance they were given.
     */
    public Optional<CachedBalance> getCachedBalanceSnapshot(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        CachedBalance cached = memoryCache.get(uuid);
        return cached != null && !cached.isExpired(expirationMinutes)
                ? Optional.of(cached)
                : Optional.empty();
    }

    private void markDegradedOnRedisTransportFailure(Throwable failure) {
        if (!redisRequired) {
            return;
        }

        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JedisConnectionException) {
                redisManager.setDegraded(true);
                return;
            }
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
     * @return TransferResult with new balances and versions
     */
    public TransferResult atomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount) {
        if (atomicTransferSha == null) {
            plugin.getLogger().severe("Lua script atomicTransfer not loaded!");
            throw new IllegalStateException("Lua script not available. Please check economy mode configuration.");
        }

        String fromBalanceKey = RedisKeys.balance(fromUuid);
        String fromVersionKey = RedisKeys.version(fromUuid);
        String toBalanceKey = RedisKeys.balance(toUuid);
        String toVersionKey = RedisKeys.version(toUuid);

        amount = NumericUtil.normalize(amount);

        Jedis resource = acquireRedisResource("atomic transfer");
        if (resource == null) {
            throw new RedisMutationException(RedisMutationException.ExecutionState.NOT_SENT,
                    "Redis transfer was not sent because no connection was available", null);
        }
        try (var jedis = resource) {
            Object result = evalScriptWithNoscriptReload(jedis, atomicTransferSha, atomicTransferScript,
                    4, new String[] {fromBalanceKey, fromVersionKey, toBalanceKey, toVersionKey,
                            amount.toPlainString()},
                    sha -> atomicTransferSha = sha);

            if (result instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                var list = (java.util.List<?>) result;
                if (list.size() < 4) {
                    throw sentUnknown("Lua transfer returned an incomplete authority receipt", null);
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
                if (isKnownNoMutationError(errorMsg)) {
                    if (errorMsg.contains("INSUFFICIENT_FUNDS")) return TransferResult.insufficientFunds();
                    throw rejected("Redis explicitly rejected atomic transfer without mutation: " + errorMsg,
                            null);
                }
                throw sentUnknown("Lua transfer returned an unrecognized error response", null);
            }

            throw sentUnknown("Lua transfer returned an invalid authority receipt", null);
        } catch (RedisMutationException e) {
            throw e;
        } catch (JedisDataException e) {
            if (isKnownNoMutationError(e.getMessage())) {
                if (e.getMessage().contains("INSUFFICIENT_FUNDS")) return TransferResult.insufficientFunds();
                plugin.getLogger().warning("Redis rejected atomic transfer: " + e.getMessage());
                throw rejected("Redis explicitly rejected atomic transfer without mutation", e);
            }
            throw sentUnknown("Redis atomic transfer outcome is unknown", e);
        } catch (Exception e) {
            throw sentUnknown("Redis atomic transfer outcome is unknown", e);
        }
    }

    /** Read both Redis account snapshots, requiring both accounts to exist. */
    public TransferResult readTransferSnapshotStrict(UUID fromUuid, UUID toUuid) {
        return readTransferSnapshot(fromUuid, toUuid, false);
    }

    /**
     * Read the authority pair after Redis has explicitly rejected a transfer without mutation.
     * Confirmed absence is preserved per account so the caller can release this rejected
     * operation without manufacturing a zero-balance account.
     */
    public TransferResult readRejectedTransferSnapshotStrict(UUID fromUuid, UUID toUuid) {
        return readTransferSnapshot(fromUuid, toUuid, true);
    }

    private TransferResult readTransferSnapshot(UUID fromUuid, UUID toUuid, boolean allowAbsent) {
        if (fromUuid == null || toUuid == null || fromUuid.equals(toUuid)) {
            throw new IllegalArgumentException("A transfer snapshot requires two distinct accounts");
        }
        CachedBalance fromCachedAtReadStart = memoryCache.get(fromUuid);
        CachedBalance toCachedAtReadStart = memoryCache.get(toUuid);
        try (var jedis = redisManager.getResource()) {
            List<String> values = jedis.mget(
                    RedisKeys.balance(fromUuid), RedisKeys.version(fromUuid),
                    RedisKeys.balance(toUuid), RedisKeys.version(toUuid));
            if (values == null || values.size() != 4) {
                throw new IllegalStateException("Redis returned an invalid transfer authority snapshot");
            }
            CachedBalance from = parseStrictSnapshot(fromUuid, values.get(0), values.get(1));
            CachedBalance to = parseStrictSnapshot(toUuid, values.get(2), values.get(3));
            if (!from.accountPresent()) {
                clearCachedAccountIfUnchanged(fromUuid, fromCachedAtReadStart);
            }
            if (!to.accountPresent()) {
                clearCachedAccountIfUnchanged(toUuid, toCachedAtReadStart);
            }
            if (!allowAbsent && (!from.accountPresent() || !to.accountPresent())) {
                throw new IllegalStateException("Redis transfer authority snapshot is missing an account balance");
            }
            if (from.accountPresent()) {
                rememberConfirmedAccount(new ConfirmedAccount(fromUuid, from.balance(), from.version()));
            }
            if (to.accountPresent()) {
                rememberConfirmedAccount(new ConfirmedAccount(toUuid, to.balance(), to.version()));
            }
            return new TransferResult(
                    from.accountPresent() ? from.balance() : null,
                    to.accountPresent() ? to.balance() : null,
                    from.accountPresent() ? from.version() : -1L,
                    to.accountPresent() ? to.version() : -1L,
                    from.accountPresent(), to.accountPresent());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Redis transfer authority snapshot", e);
        }
    }

    private static CachedBalance parseStrictSnapshot(UUID uuid, String balanceValue, String versionValue) {
        if (balanceValue == null && versionValue != null) {
            throw new IllegalStateException("Redis account version exists without a balance for " + uuid);
        }
        if (balanceValue == null) {
            return new CachedBalance(BigDecimal.ZERO, 0L, System.currentTimeMillis(), false);
        }
        BigDecimal balance = NumericUtil.normalize(balanceValue);
        long version = versionValue == null ? 0L : Long.parseLong(versionValue);
        if (version < 0L) {
            throw new IllegalStateException("Redis account version is negative for " + uuid);
        }
        return new CachedBalance(balance, version);
    }

    /**
     * Result of atomic transfer operation.
     */
    public static class TransferResult {
        public final BigDecimal fromNewBalance;
        public final BigDecimal toNewBalance;
        public final long fromNewVersion;
        public final long toNewVersion;
        public final boolean fromAccountPresent;
        public final boolean toAccountPresent;

        private static final TransferResult INSUFFICIENT_FUNDS_INSTANCE =
                new TransferResult(null, null, -1, -1, false, false);

        public static TransferResult insufficientFunds() {
            return INSUFFICIENT_FUNDS_INSTANCE;
        }

        public TransferResult(BigDecimal fromBalance, BigDecimal toBalance, long fromVersion, long toVersion) {
            this(fromBalance, toBalance, fromVersion, toVersion,
                    fromBalance != null && fromVersion >= 0L,
                    toBalance != null && toVersion >= 0L);
        }

        public TransferResult(BigDecimal fromBalance, BigDecimal toBalance, long fromVersion, long toVersion,
                boolean fromAccountPresent, boolean toAccountPresent) {
            this.fromNewBalance = fromBalance;
            this.toNewBalance = toBalance;
            this.fromNewVersion = fromVersion;
            this.toNewVersion = toVersion;
            this.fromAccountPresent = fromAccountPresent;
            this.toAccountPresent = toAccountPresent;
        }
    }

    /**
     * Update memory cache (for Pub/Sub usage).
     * Only updates when new version > current version.
     */
    public boolean updateMemoryCache(UUID uuid, BigDecimal balance, long version) {
        final boolean[] updated = {false};
        memoryCache.compute(uuid, (key, existing) -> {
            if (existing == null || !existing.accountPresent() || version > existing.version()) {
                updated[0] = true;
                return new CachedBalance(balance, version);
            }
            return existing;
        });
        return updated[0];
    }

    /** Remember a confirmed Redis pair without letting a delayed response regress this node's cache. */
    private void rememberConfirmedAccount(ConfirmedAccount confirmed) {
        memoryCache.compute(confirmed.playerUuid(), (key, existing) -> {
            if (existing != null && existing.version() > confirmed.version()) {
                return existing;
            }
            if (existing != null && existing.accountPresent() && existing.version() == confirmed.version()
                    && existing.balance().compareTo(confirmed.balance()) != 0) {
                plugin.getLogger().severe("Conflicting Redis receipt for " + confirmed.playerUuid()
                        + " at version " + confirmed.version() + "; retaining the cached balance");
                return existing;
            }
            return new CachedBalance(confirmed.balance(), confirmed.version());
        });
    }

    /**
     * Remove a cached authority value only when it still represents the same account state observed
     * before the Redis read. A newer concurrent cache value must not be erased by a delayed absence.
     */
    private void clearCachedAccountIfUnchanged(UUID uuid, CachedBalance cachedAtReadStart) {
        if (cachedAtReadStart == null) {
            return;
        }
        memoryCache.computeIfPresent(uuid, (key, current) -> sameAccountState(current, cachedAtReadStart)
                ? null
                : current);
    }

    private static boolean sameAccountState(CachedBalance left, CachedBalance right) {
        return left.accountPresent() == right.accountPresent()
                && left.version() == right.version()
                && left.balance().compareTo(right.balance()) == 0;
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
            String keyBalance = RedisKeys.balance(uuid);
            String keyVersion = RedisKeys.version(uuid);


            Object result = evalScriptWithNoscriptReload(jedis, atomicSetBalanceSha, atomicSetBalanceScript,
                    2, new String[] {keyVersion, keyBalance, balance.toPlainString()},
                    sha -> atomicSetBalanceSha = sha);

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
    public record CachedBalance(BigDecimal balance, long version, long lastAccessTime, boolean accountPresent) {
        public CachedBalance(BigDecimal balance, long version) {
            this(balance, version, System.currentTimeMillis(), true);
        }

        public CachedBalance(BigDecimal balance, long version, long lastAccessTime) {
            this(balance, version, lastAccessTime, true);
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
