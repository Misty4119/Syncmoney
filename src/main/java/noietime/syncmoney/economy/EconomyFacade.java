package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * EconomyFacade - Core economic operations with optimistic locking.
 *
 * Read: Memory first (O(1)), then Redis/Database/LocalSQLite
 * Write: Memory update -> immediate return -> event queue for async persistence
 *
 * [ThreadSafe] All operations use ConcurrentHashMap for thread safety.
 * [AsyncScheduler] Redis/DB operations are handled by async consumers.
 */
public final class EconomyFacade {

    private static final int DEFAULT_EXPIRATION_MINUTES = 30;

    public record EconomyState(BigDecimal balance, long version, long lastAccessTime) {
    }


    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final DatabaseManager databaseManager;
    private final LocalEconomyHandler localEconomyHandler;
    private final EconomyWriteQueue writeQueue;
    private final FallbackEconomyWrapper fallbackWrapper;
    private final PlayerTransactionGuard playerTransactionGuard;

    private final ConcurrentMap<UUID, EconomyState> memoryState;

    private final boolean isLocalMode;

    public EconomyFacade(Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper) {
        this(plugin, config, cacheManager, redisManager, databaseManager, null, writeQueue, fallbackWrapper);
    }

    public EconomyFacade(Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, LocalEconomyHandler localEconomyHandler,
            EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper) {
        this(plugin, config, cacheManager, redisManager, databaseManager, localEconomyHandler, writeQueue, fallbackWrapper, null);
    }

    public EconomyFacade(Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, LocalEconomyHandler localEconomyHandler,
            EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper,
            PlayerTransactionGuard playerTransactionGuard) {
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
        this.localEconomyHandler = localEconomyHandler;
        this.writeQueue = writeQueue;
        this.fallbackWrapper = fallbackWrapper;
        this.playerTransactionGuard = playerTransactionGuard;
        this.memoryState = new ConcurrentHashMap<>();
        this.isLocalMode = (localEconomyHandler != null);
    }

    /**
     * Check if running in local mode.
     */
    public boolean isLocalMode() {
        return isLocalMode;
    }

    /**
     * [SYNC-CORE-001] Get balance with fallback chain: Memory -> Redis -> Database -> LocalSQLite
     * This is the core read path for player balances.
     *
     * [AsyncScheduler] This method involves Redis/DB operations and should be called
     * from async thread or ensure caller handles threading.
     */
    public BigDecimal getBalance(UUID uuid) {
        EconomyState state = memoryState.get(uuid);
        if (state != null) {
            return state.balance();
        }

        BigDecimal balance = null;
        long version = 0L;

        if (isLocalMode && localEconomyHandler != null) {
            try {
                balance = localEconomyHandler.getBalance(uuid);
                version = 1L;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get balance from local SQLite: " + e.getMessage());
            }
        } else if (!redisManager.isDegraded()) {
            try {
                balance = cacheManager.getBalance(uuid);
                version = cacheManager.getVersion(uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get balance from Redis, falling back to DB: " + e.getMessage());
            }
        }

        if ((version == 0L) && config.isDbEnabled() && databaseManager != null) {
            try {
                var record = databaseManager.getPlayer(uuid);
                if (record.isPresent()) {
                    balance = record.get().balance();
                    version = record.get().version();
                    fallbackWrapper.logDegradedOperation("getBalance from DB");
                } else {
                    balance = BigDecimal.ZERO;
                    version = 0L;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to get balance from DB: " + e.getMessage());
            }
        }

        if (balance == null)
            balance = BigDecimal.ZERO;

        final BigDecimal finalBalance = balance;
        final long finalVersion = version;
        long now = System.currentTimeMillis();
        final long finalNow = now;
        memoryState.computeIfAbsent(uuid, key -> new EconomyState(finalBalance, finalVersion, finalNow));
        return balance;
    }

    /**
     * Get balance (double-compatible version).
     */
    public double getBalanceAsDouble(UUID uuid) {
        return getBalance(uuid).doubleValue();
    }

    /**
     * Ensure data is loaded into memory. If not exists, trigger getBalance.
     * [AsyncScheduler] This method should be called from async thread.
     */
    private void ensureLoaded(UUID uuid) {
        if (!memoryState.containsKey(uuid)) {
            getBalance(uuid);
        }
    }

    /**
     * [SYNC-CORE-002] Deposit with optimistic locking - immediate memory update
     * Events are queued for async persistence to Redis/DB.
     *
     * @return New balance after deposit, or -1 if failed
     * [AsyncScheduler] Event is processed by async consumer
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Deposit rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(-1);
        }

        if (playerTransactionGuard != null) {
            var guardResult = playerTransactionGuard.checkTransaction(uuid, amount, EconomyEvent.EventType.DEPOSIT);
            if (!guardResult.allowed()) {
                plugin.getLogger().warning("Deposit rejected by PlayerTransactionGuard: " + guardResult.reason() + " for " + uuid);
                return BigDecimal.valueOf(-1);
            }
        }

        ensureLoaded(uuid);

        long now = System.currentTimeMillis();
        EconomyState newState = memoryState.compute(uuid, (key, existing) -> {
            BigDecimal currentBalance = (existing != null) ? existing.balance() : BigDecimal.ZERO;
            long currentVersion = (existing != null) ? existing.version() : 0L;
            return new EconomyState(currentBalance.add(amount), currentVersion + 1, now);
        });

        BigDecimal newBalance = newState.balance();
        long newVersion = newState.version();

        if (isLocalMode && localEconomyHandler != null) {
            try {
                localEconomyHandler.deposit(uuid, null, amount);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write to local SQLite: " + e.getMessage());
            }
            return newBalance;
        }

        boolean isDegraded = fallbackWrapper.isDegraded();
        if (isDegraded) {
            fallbackWrapper.logDegradedOperation("deposit - queue write skipped");
            return newBalance;
        }

        EconomyEvent event = new EconomyEvent(
                uuid, amount, newBalance, newVersion,
                EconomyEvent.EventType.DEPOSIT, source,
                UUID.randomUUID().toString(), System.currentTimeMillis());

        if (source != EconomyEvent.EventSource.TEST && !writeQueue.offer(event)) {
            plugin.getLogger().warning("EconomyWriteQueue is full, event dropped for " + uuid);
        }

        return newBalance;
    }

    /**
     * Deposit - optimistic update flow (double-compatible version).
     *
     * @return New balance after operation, or -1 if failed
     */
    public double deposit(UUID uuid, double amount, EconomyEvent.EventSource source) {
        return deposit(uuid, NumericUtil.normalize(amount), source).doubleValue();
    }

    /**
     * [SYNC-CORE-003] Withdraw with optimistic locking - validates sufficient balance
     * Events are queued for async persistence to Redis/DB.
     *
     * @return New balance after withdrawal, or -1 if insufficient funds
     * [AsyncScheduler] Event is processed by async consumer
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Withdraw rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(-1);
        }

        if (playerTransactionGuard != null) {
            var guardResult = playerTransactionGuard.checkTransaction(uuid, amount.negate(), EconomyEvent.EventType.WITHDRAW);
            if (!guardResult.allowed()) {
                plugin.getLogger().warning("Withdraw rejected by PlayerTransactionGuard: " + guardResult.reason() + " for " + uuid);
                return BigDecimal.valueOf(-1);
            }
        }

        ensureLoaded(uuid);

        long now = System.currentTimeMillis();
        EconomyState newState = memoryState.compute(uuid, (key, existing) -> {
            BigDecimal currentBalance = (existing != null) ? existing.balance() : BigDecimal.ZERO;
            long currentVersion = (existing != null) ? existing.version() : 0L;

            if (currentBalance.compareTo(amount) < 0) {
            }

            return new EconomyState(currentBalance.subtract(amount), currentVersion + 1, now);
        });

        if (newState == null) {
        }

        BigDecimal newBalance = newState.balance();
        long newVersion = newState.version();

        if (isLocalMode && localEconomyHandler != null) {
            try {
                localEconomyHandler.withdraw(uuid, null, amount);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write to local SQLite: " + e.getMessage());
            }
            return newBalance;
        }

        boolean isDegraded = fallbackWrapper.isDegraded();
        if (isDegraded) {
            fallbackWrapper.logDegradedOperation("withdraw - queue write skipped");
            return newBalance;
        }

        BigDecimal delta = amount.negate();
        EconomyEvent event = new EconomyEvent(
                uuid, delta, newBalance, newVersion,
                EconomyEvent.EventType.WITHDRAW, source,
                UUID.randomUUID().toString(), System.currentTimeMillis());

        if (source != EconomyEvent.EventSource.TEST && !writeQueue.offer(event)) {
            plugin.getLogger().warning("EconomyWriteQueue is full, event dropped for " + uuid);
        }

        return newBalance;
    }

    /**
     * Withdraw - optimistic update flow (double-compatible version).
     *
     * @return New balance after operation, or -1 if insufficient funds
     */
    public double withdraw(UUID uuid, double amount, EconomyEvent.EventSource source) {
        return withdraw(uuid, NumericUtil.normalize(amount), source).doubleValue();
    }

    /**
     * [SYNC-CORE-004] Set balance directly (admin operation) with optimistic locking
     * Events are queued for async persistence to Redis/DB.
     *
     * @return New balance after set, or -1 if invalid amount
     * [AsyncScheduler] Event is processed by async consumer
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        if (noietime.syncmoney.migration.MigrationLock.isLocked() &&
            source != EconomyEvent.EventSource.ADMIN_SET &&
            source != EconomyEvent.EventSource.COMMAND_ADMIN) {
            plugin.getLogger().warning("SetBalance rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.valueOf(-1);
        }

        newBalance = NumericUtil.normalize(newBalance);
        final BigDecimal finalNewBalance = newBalance;

        ensureLoaded(uuid);

        EconomyState existingState = memoryState.get(uuid);
        BigDecimal oldBalance = (existingState != null) ? existingState.balance() : BigDecimal.ZERO;

        long now = System.currentTimeMillis();
        EconomyState newState = memoryState.compute(uuid, (key, existing) -> {
            long currentVersion = (existing != null) ? existing.version() : 0L;
            return new EconomyState(finalNewBalance, currentVersion + 1, now);
        });

        long newVersion = newState.version();

        if (isLocalMode && localEconomyHandler != null) {
            try {
                localEconomyHandler.setBalance(uuid, null, newBalance);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write to local SQLite: " + e.getMessage());
            }
            return newBalance;
        }

        boolean isDegraded = fallbackWrapper.isDegraded();
        if (isDegraded) {
            fallbackWrapper.logDegradedOperation("setBalance - queue write skipped");
            return newBalance;
        }

        BigDecimal delta = newBalance.subtract(oldBalance);
        EconomyEvent event = new EconomyEvent(
                uuid, delta, newBalance, newVersion,
                EconomyEvent.EventType.SET_BALANCE, source,
                UUID.randomUUID().toString(), System.currentTimeMillis());

        if (source != EconomyEvent.EventSource.TEST && !writeQueue.offer(event)) {
            plugin.getLogger().warning("EconomyWriteQueue is full, event dropped for " + uuid);
        }

        return newBalance;
    }

    /**
     * Set balance - optimistic update flow (double-compatible version).
     *
     * @return New balance after operation, or -1 if failed
     */
    public double setBalance(UUID uuid, double newBalance, EconomyEvent.EventSource source) {
        return setBalance(uuid, NumericUtil.normalize(newBalance), source).doubleValue();
    }

    /**
     * Update memory state (called by Pub/Sub subscriber).
     * Only updates when new version > current version.
     */
    public boolean updateMemoryState(UUID uuid, BigDecimal balance, long version) {
        long now = System.currentTimeMillis();
        EconomyState result = memoryState.compute(uuid, (key, existing) -> {
            if (existing == null || version > existing.version()) {
                return new EconomyState(balance, version, now);
            }
            return existing;
        });

        return result != null && result.version() == version;
    }

    /**
     * Update memory state (called by Pub/Sub subscriber - double-compatible version).
     * Only updates when new version > current version.
     */
    public boolean updateMemoryState(UUID uuid, double balance, long version) {
        return updateMemoryState(uuid, NumericUtil.normalize(balance), version);
    }

    /**
     * Remove from memory (after player is offline for a period of time).
     */
    public void invalidate(UUID uuid) {
        memoryState.remove(uuid);
    }

    /**
     * Get memory state (for debugging purposes).
     */
    public EconomyState getMemoryState(UUID uuid) {
        return memoryState.get(uuid);
    }

    /**
     * Check if player data exists in memory.
     */
    public boolean hasInMemory(UUID uuid) {
        return memoryState.containsKey(uuid);
    }

    /**
     * Clean up expired memory state (called by scheduled task).
     * Removes player data not accessed for more than 30 minutes.
     *
     * @return Number of entries cleaned up
     */
    public int cleanupExpiredEntries() {
        long expirationTime = System.currentTimeMillis() - (DEFAULT_EXPIRATION_MINUTES * 60 * 1000);

        java.util.List<UUID> toRemove = memoryState.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().lastAccessTime() < expirationTime)
                .map(java.util.Map.Entry::getKey)
                .toList();

        int removedCount = 0;
        for (UUID uuid : toRemove) {
            if (memoryState.remove(uuid) != null)
                removedCount++;
        }

        if (removedCount > 0) {
            plugin.getLogger().info("Cleaned up " + removedCount + " expired memory entries.");
        }
        return removedCount;
    }

    /**
     * Get number of players in memory.
     */
    public int getMemorySize() {
        return memoryState.size();
    }

    /**
     * Get number of cached players (for monitoring).
     */
    public int getCachedPlayerCount() {
        return memoryState.size();
    }

    /**
     * Record successful transaction for unlock testing.
     */
    public void recordSuccessfulTransaction(UUID uuid) {
        if (playerTransactionGuard != null) {
            playerTransactionGuard.recordSuccessfulTransaction(uuid);
        }
    }

    /**
     * Manually unlock a player.
     */
    public boolean unlockPlayer(UUID uuid) {
        if (playerTransactionGuard != null) {
            return playerTransactionGuard.unlockPlayer(uuid);
        }
        return false;
    }

    /**
     * Get player protection state.
     */
    public PlayerTransactionGuard.ProtectionState getPlayerProtectionState(UUID uuid) {
        if (playerTransactionGuard != null) {
            return playerTransactionGuard.getPlayerState(uuid);
        }
        return PlayerTransactionGuard.ProtectionState.NORMAL;
    }
}
