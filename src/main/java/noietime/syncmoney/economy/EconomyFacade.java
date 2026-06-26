package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [SYNC-ECO-037] EconomyFacade - Core economic operations with optimistic locking.
 * Reads: Memory first (O(1)), then Redis -> Database -> LocalSQLite.
 * Writes: Memory update -> immediate return -> event queue for async persistence.
 *
 * <p>This is a thin facade. The heavy lifting is delegated to focused collaborators:
 * <ul>
 *   <li>{@link MemoryStateManager} - in-memory state and transfer bookkeeping maps.</li>
 *   <li>{@link TransactionWriter} - the optimistic-locking deposit/withdraw/setBalance
 *       write path (and the plugin-direct deposit/withdraw wrappers).</li>
 *   <li>{@link TransferOrchestrator} - player-to-player transfers and rollback.</li>
 * </ul>
 * The read path ({@link #getBalance}) and the public API surface live here so external
 * callers and Vault mapping keep their exact method signatures. The LMAX-style timing and
 * {@code stateManager.compute + version+1} optimistic locking are unchanged.
 */
public final class EconomyFacade {

    public record EconomyState(BigDecimal balance, long version, long lastAccessTime) {
    }

    /**
     * Transfer context for linking withdraw + deposit as a single transaction.
     */
    public record TransferContext(
        UUID fromUuid,
        UUID toUuid,
        BigDecimal amount,
        long timestamp,
        boolean completed,
        boolean rolledBack
    ) {}

    /**
     * Pending rollback info for failed deposits.
     */
    public record PendingRollback(
        UUID fromUuid,
        BigDecimal amount,
        String transferId,
        long timestamp
    ) {}

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final DatabaseManager databaseManager;
    private final LocalEconomyHandler localEconomyHandler;
    private final FallbackEconomyWrapper fallbackWrapper;
    private final OverflowLogInterface overflowLog;
    private PlayerTransactionGuard playerTransactionGuard;
    private EconomicCircuitBreaker circuitBreaker;
    private NameResolver nameResolver;

    private final boolean isLocalMode;

    private final AtomicLong droppedEventCount = new AtomicLong(0);

    private final MemoryStateManager stateManager;
    private final TransactionWriter transactionWriter;
    private final TransferOrchestrator transferOrchestrator;

    private final ScheduledExecutorService cleanupExecutor;

    public EconomyFacade(Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, LocalEconomyHandler localEconomyHandler,
            EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper) {
        this(plugin, config, cacheManager, redisManager, databaseManager, localEconomyHandler, writeQueue,
                fallbackWrapper, null);
    }

    public EconomyFacade(Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, LocalEconomyHandler localEconomyHandler,
            EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper,
            PlayerTransactionGuard playerTransactionGuard) {
        this(plugin, config, cacheManager, redisManager, databaseManager, localEconomyHandler,
             writeQueue, fallbackWrapper, playerTransactionGuard, null);
    }

    public EconomyFacade(Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, LocalEconomyHandler localEconomyHandler,
            EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper,
            PlayerTransactionGuard playerTransactionGuard,
            OverflowLogInterface customOverflowLog) {
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
        this.localEconomyHandler = localEconomyHandler;
        this.fallbackWrapper = fallbackWrapper;
        this.playerTransactionGuard = playerTransactionGuard;

        if (customOverflowLog != null) {
            this.overflowLog = customOverflowLog;
        } else {
            this.overflowLog = new OverflowLog(plugin, plugin.getDataFolder().toPath());
        }

        this.isLocalMode = (localEconomyHandler != null);

        this.stateManager = new MemoryStateManager(plugin);
        this.transactionWriter = new TransactionWriter(this, plugin, config, localEconomyHandler,
                writeQueue, fallbackWrapper, overflowLog, stateManager, droppedEventCount, isLocalMode);
        this.transferOrchestrator = new TransferOrchestrator(this, plugin, config, cacheManager, stateManager);

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EconomyFacade-Cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredEntries, 5, 5, TimeUnit.MINUTES);
        this.cleanupExecutor.scheduleAtFixedRate(stateManager::cleanupTransferData, 1, 1, TimeUnit.MINUTES);
    }

    /** [SYNC-ECO-039] Check if running in local mode. */
    public boolean isLocalMode() {
        return isLocalMode;
    }

    /**
     * [SYNC-ECO-040] Get the overflow log instance.
     */
    public OverflowLogInterface getOverflowLog() {
        return overflowLog;
    }

    /**
     * [SYNC-ECO-109] Set the overflow log instance.
     *
     * @deprecated overflowLog is a final field assigned in the constructor and cannot be
     *     reassigned. Use the constructor to supply a custom {@link OverflowLogInterface}.
     *     Kept as a no-op for backwards compatibility.
     */
    @Deprecated
    public void setOverflowLog(OverflowLogInterface log) {
    }

    /**
     * [SYNC-ECO-041] Set the player transaction guard after initialization.
     * Resolves circular initialization dependency with Syncmoney main class.
     */
    public void setPlayerTransactionGuard(PlayerTransactionGuard guard) {
        this.playerTransactionGuard = guard;
    }

    /**
     * [SYNC-ECO-042] Set the circuit breaker after initialization.
     * Resolves circular initialization dependency with Syncmoney main class.
     */
    public void setCircuitBreaker(EconomicCircuitBreaker breaker) {
        this.circuitBreaker = breaker;
    }

    EconomicCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    PlayerTransactionGuard getPlayerTransactionGuard() {
        return playerTransactionGuard;
    }

    /**
     * [SYNC-ECO-100] Set the NameResolver for player name resolution.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * [SYNC-ECO-101] Resolve player name from UUID, with fallback to UUID string.
     */
    String resolvePlayerName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (nameResolver != null) {
            String name = nameResolver.getName(uuid);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return uuid.toString();
    }

    /**
     * [SYNC-ECO-043] Get balance with fallback chain: Memory -> Redis -> Database -> LocalSQLite.
     * Core read path for player balances.
     */
    public BigDecimal getBalance(UUID uuid) {
        EconomyState state = stateManager.get(uuid);
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
        stateManager.computeIfAbsent(uuid, key -> new EconomyState(finalBalance, finalVersion, now));
        return balance;
    }

    /**
     * [SYNC-ECO-044] Get balance as double (Vault API compatibility only).
     */
    public double getBalanceAsDouble(UUID uuid) {
        return getBalance(uuid).doubleValue();
    }

    /**
     * [SYNC-ECO-045] Synchronously gets player balance.
     * Intended for Vault API compatibility mapping.
     */
    public BigDecimal getBalanceSync(UUID uuid) {
        EconomyState state = stateManager.get(uuid);
        if (state != null) {
            return state.balance();
        }

        if (cacheManager != null) {
            try {
                BigDecimal redisBalance = cacheManager.getBalance(uuid);
                if (redisBalance != null) {
                    return redisBalance;
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Redis balance lookup failed for " + uuid + ": " + e.getMessage());
            }
        }

        if (databaseManager != null) {
            try {
                var record = databaseManager.getPlayer(uuid);
                if (record.isPresent()) {
                    return record.get().balance();
                }
            } catch (Exception e) {
                plugin.getLogger().fine("DB balance lookup failed for " + uuid + ": " + e.getMessage());
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * [SYNC-ECO-046] Ensure data is loaded into memory. If not exists, trigger getBalance.
     */
    void ensureLoaded(UUID uuid) {
        if (!stateManager.containsKey(uuid)) {
            getBalance(uuid);
        }
    }

    /**
     * [SYNC-ECO-047] Deposit with optimistic locking - immediate memory update.
     * Events are queued for async persistence to Redis/DB.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return transactionWriter.deposit(uuid, amount, source);
    }

    /**
     * [SYNC-ECO-048] Deposit - optimistic update flow (double-compatible version).
     */
    public double deposit(UUID uuid, double amount, EconomyEvent.EventSource source) {
        return deposit(uuid, NumericUtil.normalize(amount), source).doubleValue();
    }

    /**
     * [SYNC-ECO-049] Withdraw with optimistic locking - validates sufficient balance.
     * Events are queued for async persistence to Redis/DB.
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return transactionWriter.withdraw(uuid, amount, source);
    }

    /**
     * [SYNC-ECO-050] Withdraw - optimistic update flow (double-compatible version).
     */
    public double withdraw(UUID uuid, double amount, EconomyEvent.EventSource source) {
        return withdraw(uuid, NumericUtil.normalize(amount), source).doubleValue();
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     * This method bypasses the memory state and directly uses Redis for true atomicity.
     */
    public CacheManager.TransferResult executeAtomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount) {
        return transferOrchestrator.executeAtomicTransfer(fromUuid, toUuid, amount);
    }

    /**
     * [SYNC-ECO-104] Atomic transfer between two players.
     * Ensures both withdraw and deposit succeed or both fail.
     *
     * @param fromUuid Player who will lose money
     * @param toUuid Player who will receive money
     * @param amount Amount to transfer
     * @param source Event source
     * @return new balance of fromUuid, or -1 if transfer failed
     */
    public BigDecimal atomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return transferOrchestrator.atomicTransfer(fromUuid, toUuid, amount, source);
    }

    /**
     * [SYNC-ECO-106] Check if there's a pending rollback for a player.
     * Used to detect and handle incoming transfers to locked accounts.
     */
    public PendingRollback getPendingRollback(UUID uuid) {
        return stateManager.getPendingRollback(uuid);
    }

    /**
     * [SYNC-ECO-110] Plugin deposit - third-party plugins directly call this, bypassing Vault pairing.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param uuid Player UUID
     * @param amount Deposit amount (positive)
     * @param pluginName Calling plugin name (for audit log)
     * @return New balance after transaction, -1 on failure
     */
    public BigDecimal pluginDeposit(UUID uuid, BigDecimal amount, String pluginName) {
        return transactionWriter.pluginDeposit(uuid, amount, pluginName);
    }

    /**
     * [SYNC-ECO-111] Plugin withdraw - third-party plugins directly call this, bypassing Vault pairing.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param uuid Player UUID
     * @param amount Withdrawal amount (positive)
     * @param pluginName Calling plugin name (for audit log)
     * @return New balance after transaction, -1 on failure
     */
    public BigDecimal pluginWithdraw(UUID uuid, BigDecimal amount, String pluginName) {
        return transactionWriter.pluginWithdraw(uuid, amount, pluginName);
    }

    /**
     * [SYNC-ECO-112] Plugin atomic transfer - direct transfer between two players for third-party plugins.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param fromUuid Sender UUID
     * @param toUuid Receiver UUID
     * @param amount Transfer amount
     * @param pluginName Calling plugin name
     * @return TransferResult (with both new balances and versions), null on failure
     */
    public CacheManager.TransferResult pluginAtomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount, String pluginName) {
        return transferOrchestrator.pluginAtomicTransfer(fromUuid, toUuid, amount, pluginName);
    }

    /**
     * [SYNC-ECO-051] Set balance directly (admin operation) with optimistic locking.
     * Events are queued for async persistence to Redis/DB.
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        return transactionWriter.setBalance(uuid, newBalance, source);
    }

    /**
     * [SYNC-ECO-052] Set balance - optimistic update flow (double-compatible version).
     */
    public double setBalance(UUID uuid, double newBalance, EconomyEvent.EventSource source) {
        return setBalance(uuid, NumericUtil.normalize(newBalance), source).doubleValue();
    }

    /**
     * [SYNC-ECO-053] Update memory state (called by Pub/Sub subscriber).
     * Only updates when new version > current version.
     */
    public boolean updateMemoryState(UUID uuid, BigDecimal balance, long version) {
        return stateManager.applyRemoteUpdate(uuid, balance, version);
    }

    /**
     * [SYNC-ECO-054] Update memory state (double-compatible version).
     * Only updates when new version > current version.
     */
    public boolean updateMemoryState(UUID uuid, double balance, long version) {
        return updateMemoryState(uuid, NumericUtil.normalize(balance), version);
    }

    /**
     * [SYNC-ECO-055] Remove from memory.
     */
    public void invalidate(UUID uuid) {
        stateManager.clearEntry(uuid);
    }

    /**
     * [SYNC-ECO-056] Get memory state (for debugging purposes).
     */
    public EconomyState getMemoryState(UUID uuid) {
        return stateManager.get(uuid);
    }

    /**
     * [SYNC-ECO-057] Force update memory state from external source.
     * Used by periodic version check to sync data from Redis.
     */
    public void forceUpdateMemoryState(UUID uuid, BigDecimal balance, long version) {
        stateManager.forceUpdate(uuid, balance, version);
    }

    /**
     * [SYNC-ECO-058] Get all online player UUIDs from memory state.
     * Used by periodic version check to identify valid players.
     */
    public Set<UUID> getOnlinePlayerUuids() {
        return stateManager.keySet();
    }

    /**
     * [SYNC-ECO-059] Check if player data exists in memory.
     */
    public boolean hasInMemory(UUID uuid) {
        return stateManager.containsKey(uuid);
    }

    /**
     * [SYNC-ECO-060] Clean up expired memory state (called by scheduled task).
     * Removes player data not accessed for more than 30 minutes.
     * Also enforces maximum entry limit to prevent memory leaks.
     */
    public int cleanupExpiredEntries() {
        return stateManager.cleanupExpiredEntries();
    }

    /**
     * [SYNC-ECO-061] Get number of players in memory.
     */
    public int getMemorySize() {
        return getCachedPlayerCount();
    }

    /**
     * [SYNC-ECO-062] Get number of cached players (for monitoring).
     */
    public int getCachedPlayerCount() {
        return stateManager.size();
    }

    /**
     * [SYNC-ECO-063] Record successful transaction for unlock testing.
     */
    public void recordSuccessfulTransaction(UUID uuid) {
        if (playerTransactionGuard != null) {
            playerTransactionGuard.recordSuccessfulTransaction(uuid);
        }
    }

    /**
     * [SYNC-ECO-064] Manually unlock a player.
     */
    public boolean unlockPlayer(UUID uuid) {
        if (playerTransactionGuard != null) {
            return playerTransactionGuard.unlockPlayer(uuid);
        }
        return false;
    }

    /**
     * [SYNC-ECO-065] Get player protection state.
     */
    public PlayerTransactionGuard.ProtectionState getPlayerProtectionState(UUID uuid) {
        if (playerTransactionGuard != null) {
            return playerTransactionGuard.getPlayerState(uuid);
        }
        return PlayerTransactionGuard.ProtectionState.NORMAL;
    }

    /**
     * [SYNC-ECO-066] Returns true if the player's account is currently locked by PlayerTransactionGuard.
     * Used by transfer executors to distinguish guard rejections from other errors.
     */
    public boolean isPlayerLocked(UUID uuid) {
        if (playerTransactionGuard == null) return false;
        return playerTransactionGuard.getPlayerState(uuid) == PlayerTransactionGuard.ProtectionState.LOCKED;
    }

    /**
     * [SYNC-ECO-067] Get count of dropped events for monitoring.
     */
    public long getDroppedEventCount() {
        return droppedEventCount.get();
    }

    /**
     * [SYNC-ECO-069] Shutdown the EconomyFacade and release resources.
     */
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().fine("EconomyFacade cleanup executor shutdown complete");
        }
    }
}
