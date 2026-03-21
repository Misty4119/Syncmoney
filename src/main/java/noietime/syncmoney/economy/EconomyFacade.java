package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.util.Constants;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * [SYNC-ECO-037] EconomyFacade - Core economic operations with optimistic locking.
 * Reads: Memory first (O(1)), then Redis -> Database -> LocalSQLite.
 * Writes: Memory update -> immediate return -> event queue for async persistence.
 */
public final class EconomyFacade {

    private static final int DEFAULT_EXPIRATION_MINUTES = 30;
    /** [SYNC-ECO-038] Maximum number of entries allowed in memory state to prevent memory leaks. */
    private static final int MAX_MEMORY_ENTRIES = 10000;

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
    private final OverflowLogInterface overflowLog;
    private PlayerTransactionGuard playerTransactionGuard;
    private EconomicCircuitBreaker circuitBreaker;
    private NameResolver nameResolver;

    private final ConcurrentMap<UUID, EconomyState> memoryState;

    private final boolean isLocalMode;

    private final java.util.concurrent.atomic.AtomicLong droppedEventCount = new java.util.concurrent.atomic.AtomicLong(
            0);



    private final ConcurrentMap<UUID, PendingRollback> pendingRollbacks = new ConcurrentHashMap<>();


    private final ConcurrentMap<String, TransferContext> transferContexts = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupExecutor;

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
        this.writeQueue = writeQueue;
        this.fallbackWrapper = fallbackWrapper;
        this.playerTransactionGuard = playerTransactionGuard;
        

        if (customOverflowLog != null) {
            this.overflowLog = customOverflowLog;
        } else {
            this.overflowLog = new OverflowLog(plugin, plugin.getDataFolder().toPath());
        }
        
        this.memoryState = new ConcurrentHashMap<>();
        this.isLocalMode = (localEconomyHandler != null);

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EconomyFacade-Cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredEntries, 5, 5, TimeUnit.MINUTES);
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupTransferData, 1, 1, TimeUnit.MINUTES);
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
     * [SYNC-ECO-109] Set the overflow log instance (for Redis-based implementation).
     * Must be called before any economic operations.
     * @throws UnsupportedOperationException always, as overflowLog is final and immutable after construction
     */
    public void setOverflowLog(OverflowLogInterface log) {
        throw new UnsupportedOperationException(
                "overflowLog is a final field assigned in the constructor and cannot be reassigned. " +
                "Use the constructor to set the OverflowLog implementation at initialization time.");
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

    /**
     * [SYNC-ECO-100] Set the NameResolver for player name resolution.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * [SYNC-ECO-101] Resolve player name from UUID, with fallback to UUID string.
     */
    private String resolvePlayerName(UUID uuid) {
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
        memoryState.computeIfAbsent(uuid, key -> new EconomyState(finalBalance, finalVersion, now));
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
        EconomyState state = memoryState.get(uuid);
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
    private void ensureLoaded(UUID uuid) {
        if (!memoryState.containsKey(uuid)) {
            getBalance(uuid);
        }
    }

    /**
     * [SYNC-ECO-047] Deposit with optimistic locking - immediate memory update.
     * Events are queued for async persistence to Redis/DB.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Deposit rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        String playerName = resolvePlayerName(uuid);
        ensureLoaded(uuid);
        EconomyState currentState = memoryState.get(uuid);
        BigDecimal currentBalance = (currentState != null) ? currentState.balance() : BigDecimal.ZERO;

        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                    amount, currentBalance, source.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning("Deposit rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason() + " for " + playerName);
                return BigDecimal.valueOf(-1);
            }
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            var cbResult = circuitBreaker.checkTransaction(uuid, amount, source);
            if (!cbResult.allowed()) {
                plugin.getLogger().warning("Deposit rejected by CircuitBreaker: " + cbResult.reason() + " for " + uuid);
                return BigDecimal.valueOf(-1);
            }
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
            plugin.getLogger().warning("Deposit rejected: Amount out of bounds (" + amount.toPlainString() + ")");
            return BigDecimal.valueOf(-1);
        }

        final java.util.concurrent.atomic.AtomicReference<noietime.syncmoney.breaker.PlayerTransactionGuard.CheckResult> guardResultRef =
            new java.util.concurrent.atomic.AtomicReference<>();

        long now = System.currentTimeMillis();
        EconomyState newState = memoryState.compute(uuid, (key, existing) -> {
            BigDecimal existingBalance = (existing != null) ? existing.balance() : BigDecimal.ZERO;
            long currentVersion = (existing != null) ? existing.version() : 0L;

            boolean skipGuardForLocalMode = isLocalMode && !config.playerProtection().isPlayerProtectionEnabledInLocalMode();
            boolean skipGuard = skipGuardForLocalMode;

            boolean isVaultSource = source == EconomyEvent.EventSource.VAULT_DEPOSIT
                    || source == EconomyEvent.EventSource.VAULT_WITHDRAW;
            if (isLocalMode && isVaultSource && config.playerProtection().isPlayerProtectionVaultRelaxedThreshold()) {
                skipGuard = true;
            }

            if (isLocalMode && !skipGuard) {
                List<String> whitelist = config.playerProtection().getPlayerProtectionVaultBypassWhitelist();
                if (whitelist != null && !whitelist.isEmpty() && whitelist.contains(source.name())) {
                    skipGuard = true;
                }
            }

            if (!skipGuard && playerTransactionGuard != null) {
                ensureLoaded(uuid);
                var result = playerTransactionGuard.checkTransaction(uuid, existingBalance, amount, EconomyEvent.EventType.DEPOSIT, source);
                guardResultRef.set(result);
                if (!result.allowed()) {
                    plugin.getLogger().warning(
                            "Deposit rejected by PlayerTransactionGuard: " + result.reason() + " for " + uuid);
                    return existing;
                }
            }

            return new EconomyState(existingBalance.add(amount), currentVersion + 1, now);
        });

        var guardResult = guardResultRef.get();
        if (guardResult != null && !guardResult.allowed()) {
            return BigDecimal.valueOf(-1);
        }

        BigDecimal newBalance = newState.balance();
        long newVersion = newState.version();

        BigDecimal balanceBefore = newBalance.subtract(amount);
        postTransactionEvent(uuid, playerName, AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                amount, balanceBefore, newBalance, source.name(), null, null, null, true, null);

        if (isLocalMode && localEconomyHandler != null) {
            try {
                localEconomyHandler.deposit(uuid, null, amount, source.name());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write to local SQLite: " + e.getMessage());
            }
            return newBalance;
        }

        boolean isDegraded = fallbackWrapper.isDegraded();
        if (isDegraded) {
            fallbackWrapper.logDegradedOperation("deposit - falling back to database only");
        }

        EconomyEvent event = new EconomyEvent(
                uuid, amount, newBalance, newVersion,
                EconomyEvent.EventType.DEPOSIT, source,
                UUID.randomUUID().toString(), System.currentTimeMillis());


        boolean queued = writeQueue.offer(event);
        if (!queued) {

            queued = writeQueue.offerWithTimeout(event);
        }
        
        if (!queued) {

            overflowLog.log(event);
            droppedEventCount.incrementAndGet();
            plugin.getLogger().warning("EconomyWriteQueue full, event dropped for " + uuid
                    + ". Total dropped: " + droppedEventCount.get());
        }


        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            circuitBreaker.onTransactionComplete(uuid, balanceBefore, newBalance);
        }

        return newBalance;
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
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Withdraw rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        String playerName = resolvePlayerName(uuid);
        ensureLoaded(uuid);
        EconomyState currentState = memoryState.get(uuid);
        BigDecimal currentBalance = (currentState != null) ? currentState.balance() : BigDecimal.ZERO;

        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, AsyncPreTransactionEvent.TransactionType.WITHDRAW,
                    amount, currentBalance, source.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning("Withdraw rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason() + " for " + playerName);
                return BigDecimal.valueOf(-1);
            }
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            var cbResult = circuitBreaker.checkTransaction(uuid, amount.negate(), source);
            if (!cbResult.allowed()) {
                plugin.getLogger().warning("Withdraw rejected by CircuitBreaker: " + cbResult.reason() + " for " + uuid);
                return BigDecimal.valueOf(-1);
            }
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
            plugin.getLogger().warning("Withdraw rejected: Amount out of bounds (" + amount.toPlainString() + ")");
            return BigDecimal.valueOf(-1);
        }

        final java.util.concurrent.atomic.AtomicReference<noietime.syncmoney.breaker.PlayerTransactionGuard.CheckResult> guardResultRef =
            new java.util.concurrent.atomic.AtomicReference<>();

        EconomyState stateBefore = memoryState.get(uuid);
        long versionBefore = (stateBefore != null) ? stateBefore.version() : 0L;

        long now = System.currentTimeMillis();
        EconomyState newState = memoryState.compute(uuid, (key, existing) -> {
            BigDecimal existingBalance = (existing != null) ? existing.balance() : BigDecimal.ZERO;
            long currentVersion = (existing != null) ? existing.version() : 0L;

            boolean skipGuardForLocalMode = isLocalMode && !config.playerProtection().isPlayerProtectionEnabledInLocalMode();
            boolean skipGuard = skipGuardForLocalMode
                    || source == EconomyEvent.EventSource.ADMIN_GIVE
                    || source == EconomyEvent.EventSource.ADMIN_SET
                    || source == EconomyEvent.EventSource.ADMIN_TAKE;

            boolean isVaultSource = source == EconomyEvent.EventSource.VAULT_DEPOSIT
                    || source == EconomyEvent.EventSource.VAULT_WITHDRAW;
            if (isLocalMode && isVaultSource && config.playerProtection().isPlayerProtectionVaultRelaxedThreshold()) {
                skipGuard = true;
            }

            if (isLocalMode && !skipGuard) {
                List<String> whitelist = config.playerProtection().getPlayerProtectionVaultBypassWhitelist();
                if (whitelist != null && !whitelist.isEmpty() && whitelist.contains(source.name())) {
                    skipGuard = true;
                }
            }

            if (!skipGuard && playerTransactionGuard != null) {
                ensureLoaded(uuid);
                var result = playerTransactionGuard.checkTransaction(uuid, existingBalance, amount.negate(),
                        EconomyEvent.EventType.WITHDRAW, source);
                guardResultRef.set(result);
                if (!result.allowed()) {
                    plugin.getLogger().warning(
                            "Withdraw rejected by PlayerTransactionGuard: " + result.reason() + " for " + uuid);
                    return existing;
                }
            }

            if (existingBalance.compareTo(amount) < 0) {
                return existing;
            }

            return new EconomyState(existingBalance.subtract(amount), currentVersion + 1, now);
        });

        var guardResult = guardResultRef.get();
        if (guardResult != null && !guardResult.allowed()) {
            return BigDecimal.valueOf(-1);
        }

        /** Insufficient funds: version was not incremented by the compute lambda. */
        if (newState == null || newState.version() == versionBefore) {
            return BigDecimal.valueOf(-1);
        }

        BigDecimal newBalance = newState.balance();
        long newVersion = newState.version();

        BigDecimal balanceBefore = newBalance.add(amount);
        postTransactionEvent(uuid, playerName, AsyncPreTransactionEvent.TransactionType.WITHDRAW,
                amount, balanceBefore, newBalance, source.name(), null, null, null, true, null);

        if (isLocalMode && localEconomyHandler != null) {
            try {
                localEconomyHandler.withdraw(uuid, null, amount, source.name());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write to local SQLite: " + e.getMessage());
            }
            return newBalance;
        }

        boolean isDegraded = fallbackWrapper.isDegraded();
        if (isDegraded) {
            fallbackWrapper.logDegradedOperation("withdraw - falling back to database only");
        }

        BigDecimal delta = amount.negate();
        EconomyEvent event = new EconomyEvent(
                uuid, delta, newBalance, newVersion,
                EconomyEvent.EventType.WITHDRAW, source,
                UUID.randomUUID().toString(), System.currentTimeMillis());


        boolean queued = writeQueue.offer(event);
        if (!queued) {

            queued = writeQueue.offerWithTimeout(event);
        }
        
        if (!queued) {

            overflowLog.log(event);
            droppedEventCount.incrementAndGet();
            plugin.getLogger().warning("EconomyWriteQueue full, event dropped for " + uuid
                    + ". Total dropped: " + droppedEventCount.get());
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            circuitBreaker.onTransactionComplete(uuid, balanceBefore, newBalance);
        }

        return newBalance;
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     * This method bypasses the memory state and directly uses Redis for true atomicity.
     */
    public CacheManager.TransferResult executeAtomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount) {
        if (isPlayerLocked(fromUuid) || isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected: one or both players are locked");
            return null;
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            var cbFrom = circuitBreaker.checkTransaction(fromUuid, amount.negate(), EconomyEvent.EventSource.PLAYER_TRANSFER);
            if (!cbFrom.allowed()) {
                plugin.getLogger().warning("Atomic transfer rejected by CircuitBreaker (from): " + cbFrom.reason());
                return null;
            }
            var cbTo = circuitBreaker.checkTransaction(toUuid, amount, EconomyEvent.EventSource.PLAYER_TRANSFER);
            if (!cbTo.allowed()) {
                plugin.getLogger().warning("Atomic transfer rejected by CircuitBreaker (to): " + cbTo.reason());
                return null;
            }
        }

        CacheManager.TransferResult result = cacheManager.atomicTransfer(fromUuid, toUuid, amount);

        if (result != null) {
            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
                circuitBreaker.onTransactionComplete(fromUuid, result.fromNewBalance.add(amount), result.fromNewBalance);
                circuitBreaker.onTransactionComplete(toUuid, result.toNewBalance.subtract(amount), result.toNewBalance);
            }
        }

        return result;
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
        if (isPlayerLocked(fromUuid) || isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected: one or both players are locked");
            return BigDecimal.valueOf(-1);
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            var cbFrom = circuitBreaker.checkTransaction(fromUuid, amount.negate(), source);
            if (!cbFrom.allowed()) {
                plugin.getLogger().warning("Atomic transfer rejected by CircuitBreaker (from): " + cbFrom.reason());
                return BigDecimal.valueOf(-1);
            }
            var cbTo = circuitBreaker.checkTransaction(toUuid, amount, source);
            if (!cbTo.allowed()) {
                plugin.getLogger().warning("Atomic transfer rejected by CircuitBreaker (to): " + cbTo.reason());
                return BigDecimal.valueOf(-1);
            }
        }

        String transferId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        BigDecimal withdrawResult = withdraw(fromUuid, amount, source);

        if (withdrawResult.compareTo(BigDecimal.ZERO) < 0) {
            plugin.getLogger().warning("Atomic transfer failed at withdraw stage: " + fromUuid + " -> " + toUuid);
            return BigDecimal.valueOf(-1);
        }

        PendingRollback rollbackInfo = new PendingRollback(fromUuid, amount, transferId, now);
        pendingRollbacks.put(toUuid, rollbackInfo);

        try {
            BigDecimal depositResult = deposit(toUuid, amount, source);

            if (depositResult.compareTo(BigDecimal.ZERO) < 0) {
                plugin.getLogger().warning("Atomic transfer failed at deposit stage, initiating rollback: " + fromUuid + " -> " + toUuid);
                rollbackWithdraw(fromUuid, amount, source, transferId);
                return BigDecimal.valueOf(-1);
            }

            transferContexts.put(transferId, new TransferContext(
                fromUuid, toUuid, amount, now, true, false
            ));

            pendingRollbacks.remove(toUuid);

            if (playerTransactionGuard != null) {
                playerTransactionGuard.checkAndLockReceiverForTransfer(toUuid, amount);
            }

            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
                BigDecimal fromBalance = getBalance(fromUuid);
                BigDecimal toBalance = getBalance(toUuid);
                circuitBreaker.onTransactionComplete(fromUuid, fromBalance.add(amount), fromBalance);
                circuitBreaker.onTransactionComplete(toUuid, toBalance.subtract(amount), toBalance);
            }

            return withdrawResult;

        } catch (Exception e) {

            plugin.getLogger().severe("Atomic transfer failed with exception, initiating rollback: " + e.getMessage());
            rollbackWithdraw(fromUuid, amount, source, transferId);
            return BigDecimal.valueOf(-1);
        }
    }

    /**
     * [SYNC-ECO-105] Rollback a withdraw that was made as part of a failed transfer.
     */
    private void rollbackWithdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source, String transferId) {
        try {
            BigDecimal currentBalance = getBalance(uuid);
            long now = System.currentTimeMillis();

            memoryState.compute(uuid, (key, existing) -> {
                BigDecimal current = (existing != null) ? existing.balance() : BigDecimal.ZERO;
                long version = (existing != null) ? existing.version() : 0L;
                return new EconomyState(current.add(amount), version + 1, now);
            });

            plugin.getLogger().info("Rollback successful: restored " + amount + " to " + uuid + " (transferId: " + transferId + ")");

            transferContexts.compute(transferId, (k, ctx) -> {
                if (ctx != null) {
                    return new TransferContext(
                        ctx.fromUuid(), ctx.toUuid(), ctx.amount(), ctx.timestamp(), false, true
                    );
                }
                return null;
            });

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to rollback withdraw for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-106] Check if there's a pending rollback for a player.
     * Used to detect and handle incoming transfers to locked accounts.
     */
    public PendingRollback getPendingRollback(UUID uuid) {
        return pendingRollbacks.get(uuid);
    }

    /**
     * [SYNC-ECO-107] Clean up old transfer contexts and pending rollbacks.
     */
    private void cleanupTransferData() {
        long now = System.currentTimeMillis();
        long expirationTime = now - (5 * 60 * 1000);

        transferContexts.entrySet().removeIf(entry -> {
            TransferContext ctx = entry.getValue();
            return ctx.timestamp() < expirationTime;
        });

        pendingRollbacks.entrySet().removeIf(entry -> {
            PendingRollback rb = entry.getValue();
            return rb.timestamp() < expirationTime;
        });
    }

    /**
     * [SYNC-ECO-050] Withdraw - optimistic update flow (double-compatible version).
     */
    public double withdraw(UUID uuid, double amount, EconomyEvent.EventSource source) {
        return withdraw(uuid, NumericUtil.normalize(amount), source).doubleValue();
    }

    /**
     * [SYNC-ECO-110] Plugin deposit - third-party plugins directly call this, bypassing Vault pairing.
     * Uses atomic_transfer.lua for atomicity and skips PlayerTransactionGuard VAULT relaxation thresholds.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param uuid Player UUID
     * @param amount Deposit amount (positive)
     * @param pluginName Calling plugin name (for audit log)
     * @return New balance after transaction, -1 on failure
     */
    public BigDecimal pluginDeposit(UUID uuid, BigDecimal amount, String pluginName) {
        if (uuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            plugin.getLogger().warning("Plugin deposit rejected: invalid parameters");
            return BigDecimal.valueOf(-1);
        }
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Plugin deposit rejected: migration in progress");
            return BigDecimal.valueOf(-1);
        }

        String playerName = resolvePlayerName(uuid);
        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                    amount, getBalance(uuid), EconomyEvent.EventSource.PLUGIN_DEPOSIT.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning("Plugin deposit rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason());
                return BigDecimal.valueOf(-1);
            }
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            var cbResult = circuitBreaker.checkTransaction(uuid, amount, EconomyEvent.EventSource.PLUGIN_DEPOSIT);
            if (!cbResult.allowed()) {
                plugin.getLogger().warning("Plugin deposit rejected by CircuitBreaker: " + cbResult.reason());
                return BigDecimal.valueOf(-1);
            }
        }

        BigDecimal newBalance = deposit(uuid, amount, EconomyEvent.EventSource.PLUGIN_DEPOSIT);

        if (newBalance.compareTo(BigDecimal.ZERO) > 0 && circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            circuitBreaker.onTransactionComplete(uuid, newBalance.subtract(amount), newBalance);
        }

        return newBalance;
    }

    /**
     * [SYNC-ECO-111] Plugin withdraw - third-party plugins directly call this, bypassing Vault pairing.
     * Uses atomic_transfer.lua for atomicity and skips PlayerTransactionGuard VAULT relaxation thresholds.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param uuid Player UUID
     * @param amount Withdrawal amount (positive)
     * @param pluginName Calling plugin name (for audit log)
     * @return New balance after transaction, -1 on failure
     */
    public BigDecimal pluginWithdraw(UUID uuid, BigDecimal amount, String pluginName) {
        if (uuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            plugin.getLogger().warning("Plugin withdraw rejected: invalid parameters");
            return BigDecimal.valueOf(-1);
        }
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Plugin withdraw rejected: migration in progress");
            return BigDecimal.valueOf(-1);
        }

        String playerName = resolvePlayerName(uuid);
        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, AsyncPreTransactionEvent.TransactionType.WITHDRAW,
                    amount, getBalance(uuid), EconomyEvent.EventSource.PLUGIN_WITHDRAW.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning("Plugin withdraw rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason());
                return BigDecimal.valueOf(-1);
            }
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            var cbResult = circuitBreaker.checkTransaction(uuid, amount.negate(), EconomyEvent.EventSource.PLUGIN_WITHDRAW);
            if (!cbResult.allowed()) {
                plugin.getLogger().warning("Plugin withdraw rejected by CircuitBreaker: " + cbResult.reason());
                return BigDecimal.valueOf(-1);
            }
        }

        BigDecimal newBalance = withdraw(uuid, amount, EconomyEvent.EventSource.PLUGIN_WITHDRAW);

        if (newBalance.compareTo(BigDecimal.ZERO) >= 0 && circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            circuitBreaker.onTransactionComplete(uuid, newBalance.add(amount), newBalance);
        }

        return newBalance;
    }

    /**
     * [SYNC-ECO-112] Plugin atomic transfer - direct transfer between two players for third-party plugins.
     * Calls CacheManager.atomicTransfer directly, does not report PLAYER_TRANSFER event to circuitBreaker limits.
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
        if (fromUuid == null || toUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: invalid parameters");
            return null;
        }
        if (isPlayerLocked(fromUuid) || isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: one or both players are locked");
            return null;
        }
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: migration in progress");
            return null;
        }

        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            var cbFrom = circuitBreaker.checkTransaction(fromUuid, amount.negate(), EconomyEvent.EventSource.PLUGIN_WITHDRAW);
            if (!cbFrom.allowed()) {
                plugin.getLogger().warning("Plugin atomic transfer rejected by CircuitBreaker (from): " + cbFrom.reason());
                return null;
            }
            var cbTo = circuitBreaker.checkTransaction(toUuid, amount, EconomyEvent.EventSource.PLUGIN_DEPOSIT);
            if (!cbTo.allowed()) {
                plugin.getLogger().warning("Plugin atomic transfer rejected by CircuitBreaker (to): " + cbTo.reason());
                return null;
            }
        }

        CacheManager.TransferResult result = cacheManager.atomicTransfer(fromUuid, toUuid, amount);

        if (result != null) {
            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
                circuitBreaker.onTransactionComplete(fromUuid, result.fromNewBalance.add(amount), result.fromNewBalance);
                circuitBreaker.onTransactionComplete(toUuid, result.toNewBalance.subtract(amount), result.toNewBalance);
            }
            plugin.getLogger().info("Plugin atomic transfer completed: " + fromUuid + " -> " + toUuid + " : " + amount + " by " + pluginName);
        }

        return result;
    }

    /**
     * [SYNC-ECO-051] Set balance directly (admin operation) with optimistic locking.
     * Events are queued for async persistence to Redis/DB.
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        if (noietime.syncmoney.migration.MigrationLock.isLocked() &&
                source != EconomyEvent.EventSource.ADMIN_SET &&
                source != EconomyEvent.EventSource.COMMAND_ADMIN) {
            plugin.getLogger().warning("SetBalance rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        if (newBalance.compareTo(BigDecimal.ZERO) < 0 || newBalance.compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
            plugin.getLogger().warning("SetBalance rejected: Amount out of bounds (" + newBalance.toPlainString() + ")");
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

        String playerName = resolvePlayerName(uuid);
        postTransactionEvent(uuid, playerName, AsyncPreTransactionEvent.TransactionType.SET_BALANCE,
                newBalance.subtract(oldBalance), oldBalance, newBalance, source.name(), null, null, null, true, null);

        if (isLocalMode && localEconomyHandler != null) {
            try {
                localEconomyHandler.setBalance(uuid, null, newBalance, source.name());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write to local SQLite: " + e.getMessage());
            }
            return newBalance;
        }

        boolean isDegraded = fallbackWrapper.isDegraded();
        if (isDegraded) {
            fallbackWrapper.logDegradedOperation("setBalance - falling back to database only");
        }

        BigDecimal delta = newBalance.subtract(oldBalance);
        EconomyEvent event = new EconomyEvent(
                uuid, delta, newBalance, newVersion,
                EconomyEvent.EventType.SET_BALANCE, source,
                UUID.randomUUID().toString(), System.currentTimeMillis());


        boolean queued = writeQueue.offer(event);
        if (!queued) {

            queued = writeQueue.offerWithTimeout(event);
        }
        
        if (!queued) {

            overflowLog.log(event);
            droppedEventCount.incrementAndGet();
            plugin.getLogger().warning("EconomyWriteQueue full, event dropped for " + uuid
                    + ". Total dropped: " + droppedEventCount.get());
        }

        return newBalance;
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
        memoryState.remove(uuid);
    }

    /**
     * [SYNC-ECO-056] Get memory state (for debugging purposes).
     */
    public EconomyState getMemoryState(UUID uuid) {
        return memoryState.get(uuid);
    }

    /**
     * [SYNC-ECO-057] Force update memory state from external source.
     * Used by periodic version check to sync data from Redis.
     */
    public void forceUpdateMemoryState(UUID uuid, BigDecimal balance, long version) {
        long now = System.currentTimeMillis();
        memoryState.put(uuid, new EconomyState(balance, version, now));
    }

    /**
     * [SYNC-ECO-058] Get all online player UUIDs from memory state.
     * Used by periodic version check to identify valid players.
     */
    public Set<UUID> getOnlinePlayerUuids() {
        return memoryState.keySet();
    }

    /**
     * [SYNC-ECO-059] Check if player data exists in memory.
     */
    public boolean hasInMemory(UUID uuid) {
        return memoryState.containsKey(uuid);
    }

    /**
     * [SYNC-ECO-060] Clean up expired memory state (called by scheduled task).
     * Removes player data not accessed for more than 30 minutes.
     * Also enforces maximum entry limit to prevent memory leaks.
     */
    public int cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        long expirationTime = now - (DEFAULT_EXPIRATION_MINUTES * 60 * 1000);

        int removedCount = 0;
        Iterator<Map.Entry<UUID, EconomyState>> iterator = memoryState.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EconomyState> entry = iterator.next();
            EconomyState state = entry.getValue();
            if (state != null && state.lastAccessTime() < expirationTime) {
                if (memoryState.remove(entry.getKey(), state)) {
                    removedCount++;
                }
            }
        }

        if (memoryState.size() > MAX_MEMORY_ENTRIES) {
            int entriesToRemove = memoryState.size() - MAX_MEMORY_ENTRIES;
            java.util.List<UUID> oldestEntries = memoryState.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(
                            e1.getValue() != null ? e1.getValue().lastAccessTime() : 0,
                            e2.getValue() != null ? e2.getValue().lastAccessTime() : 0))
                    .limit(entriesToRemove)
                    .map(java.util.Map.Entry::getKey)
                    .toList();

            for (UUID uuid : oldestEntries) {
                if (memoryState.remove(uuid) != null)
                    removedCount++;
            }

            plugin.getLogger().warning("Memory cleanup: removed " + entriesToRemove + " oldest entries due to exceeding max limit of " + MAX_MEMORY_ENTRIES);
        }

        if (removedCount > 0) {
            plugin.getLogger().fine("Memory cleanup: removed " + removedCount + " expired entries, " + memoryState.size() + " entries remaining");
        }
        return removedCount;
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
        return memoryState.size();
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
     * [SYNC-ECO-068] Post a transaction event to the event bus for WebSocket clients.
     */
    private void postTransactionEvent(UUID playerUuid, String playerName,
            AsyncPreTransactionEvent.TransactionType type, BigDecimal amount,
            BigDecimal balanceBefore, BigDecimal balanceAfter,
            String source, UUID targetUuid, String targetName, String reason,
            boolean success, String errorMessage) {
        if (SyncmoneyEventBus.isInitialized()) {
            PostTransactionEvent event = new PostTransactionEvent(
                    playerUuid, playerName, type, amount, balanceBefore, balanceAfter,
                    source, targetUuid, targetName, reason, success, errorMessage);
            SyncmoneyEventBus.getInstance().callEvent(event);
        }
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
