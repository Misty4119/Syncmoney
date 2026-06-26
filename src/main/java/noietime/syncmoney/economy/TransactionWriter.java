package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.breaker.PlayerTransactionGuard.CheckResult;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.util.Constants;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [SYNC-ECO-090] Optimistic-locking write path for the economy.
 */
final class TransactionWriter {

    private final EconomyFacade facade;
    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final LocalEconomyHandler localEconomyHandler;
    private final EconomyWriteQueue writeQueue;
    private final FallbackEconomyWrapper fallbackWrapper;
    private final OverflowLogInterface overflowLog;
    private final MemoryStateManager stateManager;
    private final AtomicLong droppedEventCount;
    private final boolean isLocalMode;

    TransactionWriter(EconomyFacade facade, Plugin plugin, SyncmoneyConfig config,
            LocalEconomyHandler localEconomyHandler, EconomyWriteQueue writeQueue,
            FallbackEconomyWrapper fallbackWrapper, OverflowLogInterface overflowLog,
            MemoryStateManager stateManager, AtomicLong droppedEventCount, boolean isLocalMode) {
        this.facade = facade;
        this.plugin = plugin;
        this.config = config;
        this.localEconomyHandler = localEconomyHandler;
        this.writeQueue = writeQueue;
        this.fallbackWrapper = fallbackWrapper;
        this.overflowLog = overflowLog;
        this.stateManager = stateManager;
        this.droppedEventCount = droppedEventCount;
        this.isLocalMode = isLocalMode;
    }

    /**
     * [SYNC-ECO-102] Shared pre-transaction gate for deposit/withdraw: pre-transaction
     * event, circuit breaker, and amount-bounds validation. (The migration lock is checked
     * by each caller before {@code ensureLoaded}, because its handling differs and must run
     * before any state is loaded.) Returns true when the transaction may proceed.
     */
    private boolean preTransactionChecks(UUID uuid, String playerName, BigDecimal amount,
            BigDecimal currentBalance, AsyncPreTransactionEvent.TransactionType type,
            BigDecimal circuitBreakerDelta, EconomyEvent.EventSource source, String opLabel) {
        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, type,
                    amount, currentBalance, source.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning(opLabel + " rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason() + " for " + playerName);
                return false;
            }
        }

        var circuitBreaker = facade.getCircuitBreaker();
        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            var cbResult = circuitBreaker.checkTransaction(uuid, circuitBreakerDelta, source);
            if (!cbResult.allowed()) {
                plugin.getLogger().warning(opLabel + " rejected by CircuitBreaker: " + cbResult.reason() + " for " + uuid);
                return false;
            }
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
            plugin.getLogger().warning(opLabel + " rejected: Amount out of bounds (" + amount.toPlainString() + ")");
            return false;
        }

        return true;
    }

    /**
     * [SYNC-ECO-108] Enqueue an event for async persistence, falling back to the overflow
     * WAL log if the write queue cannot accept it. Shared by deposit/withdraw/setBalance.
     */
    private void enqueueOrOverflow(UUID uuid, EconomyEvent event) {
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
    }

    /**
     * [SYNC-ECO-047] Deposit with optimistic locking - immediate memory update.
     */
    BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (MigrationLock.isLocked()) {
            plugin.getLogger().warning("Deposit rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        String playerName = facade.resolvePlayerName(uuid);
        facade.ensureLoaded(uuid);
        EconomyState currentState = stateManager.get(uuid);
        BigDecimal currentBalance = (currentState != null) ? currentState.balance() : BigDecimal.ZERO;

        if (!preTransactionChecks(uuid, playerName, amount, currentBalance,
                AsyncPreTransactionEvent.TransactionType.DEPOSIT, amount, source, "Deposit")) {
            return BigDecimal.valueOf(-1);
        }

        final PlayerTransactionGuard playerTransactionGuard = facade.getPlayerTransactionGuard();
        final AtomicReference<CheckResult> guardResultRef = new AtomicReference<>();

        long now = System.currentTimeMillis();
        EconomyState newState = stateManager.compute(uuid, (key, existing) -> {
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
                facade.ensureLoaded(uuid);
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

        enqueueOrOverflow(uuid, event);

        var circuitBreaker = facade.getCircuitBreaker();
        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            circuitBreaker.onTransactionComplete(uuid, balanceBefore, newBalance);
        }

        return newBalance;
    }

    /**
     * [SYNC-ECO-049] Withdraw with optimistic locking - validates sufficient balance.
     */
    BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (MigrationLock.isLocked()) {
            plugin.getLogger().warning("Withdraw rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }

        String playerName = facade.resolvePlayerName(uuid);
        facade.ensureLoaded(uuid);
        EconomyState currentState = stateManager.get(uuid);
        BigDecimal currentBalance = (currentState != null) ? currentState.balance() : BigDecimal.ZERO;

        if (!preTransactionChecks(uuid, playerName, amount, currentBalance,
                AsyncPreTransactionEvent.TransactionType.WITHDRAW, amount.negate(), source, "Withdraw")) {
            return BigDecimal.valueOf(-1);
        }

        final PlayerTransactionGuard playerTransactionGuard = facade.getPlayerTransactionGuard();
        final AtomicReference<CheckResult> guardResultRef = new AtomicReference<>();

        EconomyState stateBefore = stateManager.get(uuid);
        long versionBefore = (stateBefore != null) ? stateBefore.version() : 0L;

        long now = System.currentTimeMillis();
        EconomyState newState = stateManager.compute(uuid, (key, existing) -> {
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
                facade.ensureLoaded(uuid);
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

        enqueueOrOverflow(uuid, event);

        var circuitBreaker = facade.getCircuitBreaker();
        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
            circuitBreaker.onTransactionComplete(uuid, balanceBefore, newBalance);
        }

        return newBalance;
    }

    /**
     * [SYNC-ECO-051] Set balance directly (admin operation) with optimistic locking.
     */
    BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        if (MigrationLock.isLocked() &&
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

        facade.ensureLoaded(uuid);

        EconomyState existingState = stateManager.get(uuid);
        BigDecimal oldBalance = (existingState != null) ? existingState.balance() : BigDecimal.ZERO;

        long now = System.currentTimeMillis();
        EconomyState newState = stateManager.compute(uuid, (key, existing) -> {
            long currentVersion = (existing != null) ? existing.version() : 0L;
            return new EconomyState(finalNewBalance, currentVersion + 1, now);
        });

        long newVersion = newState.version();

        String playerName = facade.resolvePlayerName(uuid);
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

        enqueueOrOverflow(uuid, event);

        return newBalance;
    }

    /**
     * [SYNC-ECO-110] Plugin deposit - third-party plugins directly call this, bypassing Vault pairing.
     */
    BigDecimal pluginDeposit(UUID uuid, BigDecimal amount, String pluginName) {
        if (uuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            plugin.getLogger().warning("Plugin deposit rejected: invalid parameters");
            return BigDecimal.valueOf(-1);
        }
        if (MigrationLock.isLocked()) {
            plugin.getLogger().warning("Plugin deposit rejected: migration in progress");
            return BigDecimal.valueOf(-1);
        }

        String playerName = facade.resolvePlayerName(uuid);
        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                    amount, facade.getBalance(uuid), EconomyEvent.EventSource.PLUGIN_DEPOSIT.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning("Plugin deposit rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason());
                return BigDecimal.valueOf(-1);
            }
        }

        var circuitBreaker = facade.getCircuitBreaker();
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
     */
    BigDecimal pluginWithdraw(UUID uuid, BigDecimal amount, String pluginName) {
        if (uuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            plugin.getLogger().warning("Plugin withdraw rejected: invalid parameters");
            return BigDecimal.valueOf(-1);
        }
        if (MigrationLock.isLocked()) {
            plugin.getLogger().warning("Plugin withdraw rejected: migration in progress");
            return BigDecimal.valueOf(-1);
        }

        String playerName = facade.resolvePlayerName(uuid);
        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, AsyncPreTransactionEvent.TransactionType.WITHDRAW,
                    amount, facade.getBalance(uuid), EconomyEvent.EventSource.PLUGIN_WITHDRAW.name(),
                    null, null, null);
            SyncmoneyEventBus.getInstance().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                plugin.getLogger().warning("Plugin withdraw rejected by AsyncPreTransactionEvent: " + preEvent.getCancelReason());
                return BigDecimal.valueOf(-1);
            }
        }

        var circuitBreaker = facade.getCircuitBreaker();
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
}
