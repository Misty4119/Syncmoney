package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.breaker.PlayerTransactionGuard.CheckResult;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.core.state.AccountStateStore;
import noietime.syncmoney.util.Constants;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
        this.stateManager.attachWriteQueue(writeQueue);
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
            BigDecimal circuitBreakerDelta, EconomyEvent.EventSource source, String opLabel,
            String attribution) {
        if (SyncmoneyEventBus.isInitialized()) {
            AsyncPreTransactionEvent preEvent = new AsyncPreTransactionEvent(
                    uuid, playerName, type,
                    amount, currentBalance, source.name(),
                    null, null, attribution);
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

    private boolean rejectIfRedisUnavailable(String operation) {
        if (isLocalMode || !fallbackWrapper.isDegraded()) {
            return false;
        }
        plugin.getLogger().warning(operation + " rejected: Redis authority is unavailable");
        return true;
    }

    private boolean commitAcceptedWrite(EconomyWriteQueue.Reservation reservation, EconomyEvent event) {
        if (!isLocalMode) {
            return reservation.commit(event);
        }
        if (MigrationLock.isLocked()) {
            // Reaching this branch requires the migration worker's already-consumed
            // one-shot ADMIN_SET permit. Do not try to consume a second permit for
            // the worker's own migration write.
            return reservation.commit(event);
        }

        // Keep migration from snapshotting LOCAL SQLite until the accepted event has
        // actually committed. The producer's own lease ends when the Vault call returns.
        MigrationLock.WriteLease persistenceLease = MigrationLock.tryAcquireWrite();
        if (persistenceLease == null) {
            return false;
        }
        boolean accepted = false;
        try {
            accepted = reservation.commit(event, persistenceLease);
            return accepted;
        } finally {
            if (!accepted) {
                persistenceLease.close();
            }
        }
    }

    private boolean ensureAccountLoaded(UUID uuid) {
        if (stateManager.isUnavailable(uuid)) {
            plugin.getLogger().warning("Economy mutation rejected while account authority is unresolved for " + uuid);
            return false;
        }
        if (stateManager.get(uuid) != null) {
            return !isLocalMode || localEconomyHandler == null || localEconomyHandler.isRecoveryReady();
        }
        if (isLocalMode && localEconomyHandler != null && !localEconomyHandler.isRecoveryReady()) {
            plugin.getLogger().warning("LOCAL mutation rejected while accepted-write recovery is unresolved for " + uuid);
            return false;
        }
        return facade.ensureLoaded(uuid);
    }

    /**
     * Accepts a player transfer as one paired in-memory/queue operation. Policy callbacks
     * run before account locks and reservations; balances are re-read under UUID-ordered
     * locks before either leg is committed.
     */
    BigDecimal transfer(UUID fromUuid, UUID toUuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return transfer(fromUuid, toUuid, amount, source, null);
    }

    private BigDecimal transfer(UUID fromUuid, UUID toUuid, BigDecimal amount, EconomyEvent.EventSource source,
            String attribution) {
        if (fromUuid == null || toUuid == null || fromUuid.equals(toUuid)
                || amount == null || source == null) {
            return BigDecimal.valueOf(-1);
        }
        BigDecimal normalizedAmount = NumericUtil.normalize(amount);
        if (normalizedAmount.signum() <= 0 || normalizedAmount.compareTo(Constants.MAX_DECIMAL_BALANCE) > 0
                || rejectIfRedisUnavailable("Transfer")) {
            return BigDecimal.valueOf(-1);
        }

        if (!ensureAccountLoaded(fromUuid) || !ensureAccountLoaded(toUuid)) {
            plugin.getLogger().fine("Transfer rejected because one or both accounts are not ready");
            return BigDecimal.valueOf(-1);
        }

        AccountState initialFrom = stateManager.get(fromUuid);
        AccountState initialTo = stateManager.get(toUuid);
        if (initialFrom == null || initialTo == null || !canAdvanceLocalVersion(fromUuid, initialFrom)
                || !canAdvanceLocalVersion(toUuid, initialTo)) {
            return BigDecimal.valueOf(-1);
        }
        if (initialFrom.balance().compareTo(normalizedAmount) < 0
                || initialTo.balance().add(normalizedAmount).compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
            return BigDecimal.valueOf(-1);
        }

        String fromName = facade.resolvePlayerName(fromUuid);
        String toName = facade.resolvePlayerName(toUuid);
        if (!preTransactionChecks(fromUuid, fromName, normalizedAmount, initialFrom.balance(),
                    AsyncPreTransactionEvent.TransactionType.WITHDRAW, normalizedAmount.negate(),
                    source, "Transfer withdrawal", attribution)
                || !preTransactionChecks(toUuid, toName, normalizedAmount, initialTo.balance(),
                    AsyncPreTransactionEvent.TransactionType.DEPOSIT, normalizedAmount,
                    source, "Transfer deposit", attribution)) {
            return BigDecimal.valueOf(-1);
        }
        // Guard checks can dispatch notifications (including Web Admin SSE writes), so
        // run those policy callbacks before acquiring either account mutation lock.
        if (!transferGuardAllows(fromUuid, initialFrom.balance(), normalizedAmount.negate(),
                    EconomyEvent.EventType.WITHDRAW, source)
                || !transferGuardAllows(toUuid, initialTo.balance(), normalizedAmount,
                    EconomyEvent.EventType.DEPOSIT, source)) {
            return BigDecimal.valueOf(-1);
        }

        String operationId = TransferRequestIds.newOperationId();
        long timestamp = System.currentTimeMillis();
        BigDecimal[] acceptedBalances = new BigDecimal[2];
        AccountState[] acceptedStates = new AccountState[2];
        EconomyEvent[] acceptedEvents = new EconomyEvent[2];
        boolean accepted = false;

        try (AccountStateStore.MutationLease accountLocks = stateManager.lockAccounts(fromUuid, toUuid)) {
            AccountState fromState = stateManager.get(fromUuid);
            AccountState toState = stateManager.get(toUuid);
            if (fromState == null || toState == null || !canAdvanceLocalVersion(fromUuid, fromState)
                    || !canAdvanceLocalVersion(toUuid, toState)
                    || fromState.balance().compareTo(normalizedAmount) < 0
                    || toState.balance().add(normalizedAmount).compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
                return BigDecimal.valueOf(-1);
            }

            if (!transferGuardStillAllows(fromUuid, toUuid, source)) {
                return BigDecimal.valueOf(-1);
            }

            EconomyWriteQueue.Reservation fromReservation = stateManager.tryReserveWrite(fromUuid, fromState);
            if (fromReservation == null) {
                plugin.getLogger().warning("Transfer rejected before mutation: write admission is full for " + fromUuid);
                return BigDecimal.valueOf(-1);
            }
            try (fromReservation) {
                EconomyWriteQueue.Reservation toReservation = stateManager.tryReserveWrite(toUuid, toState);
                if (toReservation == null) {
                    plugin.getLogger().warning("Transfer rejected before mutation: write admission is full for " + toUuid);
                    return BigDecimal.valueOf(-1);
                }
                try (toReservation) {
                    long fromVersion;
                    long toVersion;
                    try {
                        fromVersion = Math.addExact(fromState.version(), 1L);
                        toVersion = Math.addExact(toState.version(), 1L);
                    } catch (ArithmeticException e) {
                        plugin.getLogger().severe("Transfer rejected before mutation because an account version overflowed");
                        return BigDecimal.valueOf(-1);
                    }

                    AccountState nextFrom = new AccountState(fromState.balance().subtract(normalizedAmount),
                            fromVersion, timestamp);
                    AccountState nextTo = new AccountState(toState.balance().add(normalizedAmount),
                            toVersion, timestamp);
                    EconomyEvent fromEvent = new EconomyEvent(fromUuid, normalizedAmount.negate(),
                            nextFrom.balance(), fromVersion, EconomyEvent.EventType.TRANSFER_OUT, source,
                            TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), timestamp,
                            attribution);
                    EconomyEvent toEvent = new EconomyEvent(toUuid, normalizedAmount,
                            nextTo.balance(), toVersion, EconomyEvent.EventType.TRANSFER_IN, source,
                            TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), timestamp,
                            attribution);

                    AutoCloseable persistenceLease = null;
                    if (isLocalMode) {
                        persistenceLease = MigrationLock.tryAcquireWrite();
                        if (persistenceLease == null) {
                            plugin.getLogger().warning("Transfer rejected before mutation: LOCAL migration is locked");
                            return BigDecimal.valueOf(-1);
                        }
                    }

                    boolean committed;
                    try {
                        committed = writeQueue.commitPair(fromReservation, fromEvent,
                                toReservation, toEvent, persistenceLease);
                    } catch (RuntimeException | Error failure) {
                        closeQuietly(persistenceLease);
                        throw failure;
                    }
                    if (!committed) {
                        closeQuietly(persistenceLease);
                        plugin.getLogger().severe("Transfer rejected because its paired write reservation could not be committed");
                        return BigDecimal.valueOf(-1);
                    }

                    // Queue admission is now complete and cannot be rejected. The held
                    // account locks keep ordinary writers from interleaving between legs.
                    stateManager.put(fromUuid, nextFrom);
                    stateManager.put(toUuid, nextTo);
                    acceptedBalances[0] = nextFrom.balance();
                    acceptedBalances[1] = nextTo.balance();
                    acceptedStates[0] = nextFrom;
                    acceptedStates[1] = nextTo;
                    acceptedEvents[0] = fromEvent;
                    acceptedEvents[1] = toEvent;
                    accepted = true;
                }
            }
        }

        if (!accepted) return BigDecimal.valueOf(-1);
        writeQueue.reportUsage();
        if (isLocalMode) {
            postTransactionEvent(fromUuid, fromName, AsyncPreTransactionEvent.TransactionType.WITHDRAW,
                    normalizedAmount, acceptedStates[0].balance().add(normalizedAmount), acceptedBalances[0],
                    source.name(), toUuid, toName, operationId, true, null);
            postTransactionEvent(toUuid, toName, AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                    normalizedAmount, acceptedStates[1].balance().subtract(normalizedAmount), acceptedBalances[1],
                    source.name(), fromUuid, fromName, operationId, true, null);
        }
        return acceptedBalances[0];
    }

    private boolean transferGuardAllows(UUID uuid, BigDecimal balance, BigDecimal delta,
            EconomyEvent.EventType type, EconomyEvent.EventSource source) {
        PlayerTransactionGuard guard = facade.getPlayerTransactionGuard();
        if (isTransferGuardBypassed(source) || guard == null) return true;
        CheckResult result = guard.checkTransaction(uuid, balance, delta, type, source);
        if (!result.allowed()) {
            plugin.getLogger().warning("Transfer rejected by PlayerTransactionGuard: " + result.reason()
                    + " for " + uuid);
        }
        return result.allowed();
    }

    private boolean transferGuardStillAllows(UUID sender, UUID receiver, EconomyEvent.EventSource source) {
        PlayerTransactionGuard guard = facade.getPlayerTransactionGuard();
        if (isTransferGuardBypassed(source) || guard == null) return true;
        // These checks only read the guard state. The stateful policy check and all
        // notifications already ran before taking the account locks.
        return !guard.isGlobalLocked()
                && !facade.isPlayerLocked(sender)
                && !facade.isPlayerLocked(receiver)
                && !guard.isTransferLocked(sender);
    }

    private boolean isTransferGuardBypassed(EconomyEvent.EventSource source) {
        if (!isLocalMode) return false;
        if (!config.playerProtection().isPlayerProtectionEnabledInLocalMode()) return true;
        boolean isVaultSource = source == EconomyEvent.EventSource.VAULT_DEPOSIT
                || source == EconomyEvent.EventSource.VAULT_WITHDRAW;
        if (isVaultSource && config.playerProtection().isPlayerProtectionVaultRelaxedThreshold()) return true;
        List<String> whitelist = config.playerProtection().getPlayerProtectionVaultBypassWhitelist();
        return whitelist != null && whitelist.contains(source.name());
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // The failed reservation is already rejected; the migration lease is best-effort released here.
        }
    }

    private boolean canAdvanceLocalVersion(UUID uuid, AccountState currentState) {
        if (!isLocalMode || localEconomyHandler == null) {
            return true;
        }
        if (currentState == null || currentState.version() >= Integer.MAX_VALUE - 1L) {
            plugin.getLogger().severe("LOCAL mutation rejected before SQLite version overflow for " + uuid);
            return false;
        }
        return true;
    }

    /**
     * [SYNC-ECO-047] Deposit with optimistic locking - immediate memory update.
     */
    BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return deposit(uuid, amount, source, null);
    }

    BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source, String attribution) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            plugin.getLogger().warning("Deposit rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            return depositAdmitted(uuid, amount, source, attribution);
        }
    }

    private BigDecimal depositAdmitted(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source,
            String attribution) {
        if (rejectIfRedisUnavailable("Deposit")) {
            return BigDecimal.valueOf(-1);
        }

        String playerName = facade.resolvePlayerName(uuid);
        if (!ensureAccountLoaded(uuid)) {
            plugin.getLogger().warning("Deposit rejected because the account could not be loaded for " + uuid);
            return BigDecimal.valueOf(-1);
        }
        AccountState currentState = stateManager.get(uuid);
        if (!canAdvanceLocalVersion(uuid, currentState)) {
            return BigDecimal.valueOf(-1);
        }
        BigDecimal currentBalance = (currentState != null) ? currentState.balance() : BigDecimal.ZERO;

        if (!preTransactionChecks(uuid, playerName, amount, currentBalance,
                AsyncPreTransactionEvent.TransactionType.DEPOSIT, amount, source, "Deposit", attribution)) {
            return BigDecimal.valueOf(-1);
        }

        final EconomyWriteQueue.Reservation reservation = stateManager.tryReserveWrite(uuid, currentState);
        if (reservation == null) {
            plugin.getLogger().warning("Deposit rejected before balance mutation: write admission is full for " + uuid);
            return BigDecimal.valueOf(-1);
        }

        final PlayerTransactionGuard playerTransactionGuard = facade.getPlayerTransactionGuard();
        final AtomicReference<CheckResult> guardResultRef = new AtomicReference<>();
        AtomicBoolean mutationAccepted = new AtomicBoolean();

        try (reservation) {
            long now = System.currentTimeMillis();
            AccountState newState = stateManager.compute(uuid, (key, existing) -> {
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
                    var result = playerTransactionGuard.checkTransaction(uuid, existingBalance, amount, EconomyEvent.EventType.DEPOSIT, source);
                    guardResultRef.set(result);
                    if (!result.allowed()) {
                        plugin.getLogger().warning(
                                "Deposit rejected by PlayerTransactionGuard: " + result.reason() + " for " + uuid);
                        return existing;
                    }
                }

                AccountState next = new AccountState(existingBalance.add(amount), currentVersion + 1, now);
                EconomyEvent event = new EconomyEvent(
                        uuid, amount, next.balance(), next.version(),
                        EconomyEvent.EventType.DEPOSIT, source,
                        UUID.randomUUID().toString(), System.currentTimeMillis(), attribution);
                if (!commitAcceptedWrite(reservation, event)) {
                    plugin.getLogger().severe("Deposit rejected because its reserved write slot could not be committed for " + uuid);
                    return existing;
                }
                mutationAccepted.set(true);
                return next;
            });

            var guardResult = guardResultRef.get();
            if (guardResult != null && !guardResult.allowed()) {
                return BigDecimal.valueOf(-1);
            }
            if (!mutationAccepted.get() || newState == null) {
                return BigDecimal.valueOf(-1);
            }

            writeQueue.reportUsage();

            BigDecimal newBalance = newState.balance();

            BigDecimal balanceBefore = newBalance.subtract(amount);
            postTransactionEvent(uuid, playerName, AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                    amount, balanceBefore, newBalance, source.name(), null, null, attribution, true, null);

            if (isLocalMode) {
                return newBalance;
            }

            var circuitBreaker = facade.getCircuitBreaker();
            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
                circuitBreaker.onTransactionComplete(uuid, balanceBefore, newBalance);
            }

            return newBalance;
        }
    }

    /**
     * [SYNC-ECO-049] Withdraw with optimistic locking - validates sufficient balance.
     */
    BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return withdraw(uuid, amount, source, null);
    }

    BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source, String attribution) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            plugin.getLogger().warning("Withdraw rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            return withdrawAdmitted(uuid, amount, source, attribution);
        }
    }

    private BigDecimal withdrawAdmitted(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source,
            String attribution) {
        if (rejectIfRedisUnavailable("Withdraw")) {
            return BigDecimal.valueOf(-1);
        }

        String playerName = facade.resolvePlayerName(uuid);
        if (!ensureAccountLoaded(uuid)) {
            plugin.getLogger().warning("Withdraw rejected because the account could not be loaded for " + uuid);
            return BigDecimal.valueOf(-1);
        }
        AccountState currentState = stateManager.get(uuid);
        if (!canAdvanceLocalVersion(uuid, currentState)) {
            return BigDecimal.valueOf(-1);
        }
        BigDecimal currentBalance = (currentState != null) ? currentState.balance() : BigDecimal.ZERO;

        if (!preTransactionChecks(uuid, playerName, amount, currentBalance,
                AsyncPreTransactionEvent.TransactionType.WITHDRAW, amount.negate(), source, "Withdraw", attribution)) {
            return BigDecimal.valueOf(-1);
        }

        final EconomyWriteQueue.Reservation reservation = stateManager.tryReserveWrite(uuid, currentState);
        if (reservation == null) {
            plugin.getLogger().warning("Withdraw rejected before balance mutation: write admission is full for " + uuid);
            return BigDecimal.valueOf(-1);
        }

        final PlayerTransactionGuard playerTransactionGuard = facade.getPlayerTransactionGuard();
        final AtomicReference<CheckResult> guardResultRef = new AtomicReference<>();

        try (reservation) {
            long now = System.currentTimeMillis();
            AtomicBoolean mutationAccepted = new AtomicBoolean();
            AccountState newState = stateManager.compute(uuid, (key, existing) -> {
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

                AccountState next = new AccountState(existingBalance.subtract(amount), currentVersion + 1, now);
                EconomyEvent event = new EconomyEvent(
                        uuid, amount.negate(), next.balance(), next.version(),
                        EconomyEvent.EventType.WITHDRAW, source,
                        UUID.randomUUID().toString(), System.currentTimeMillis(), attribution);
                if (!commitAcceptedWrite(reservation, event)) {
                    plugin.getLogger().severe("Withdraw rejected because its reserved write slot could not be committed for " + uuid);
                    return existing;
                }
                mutationAccepted.set(true);
                return next;
            });

            var guardResult = guardResultRef.get();
            if (guardResult != null && !guardResult.allowed()) {
                return BigDecimal.valueOf(-1);
            }

            if (!mutationAccepted.get() || newState == null) {
                return BigDecimal.valueOf(-1);
            }

            writeQueue.reportUsage();

            BigDecimal newBalance = newState.balance();

            BigDecimal balanceBefore = newBalance.add(amount);
            postTransactionEvent(uuid, playerName, AsyncPreTransactionEvent.TransactionType.WITHDRAW,
                    amount, balanceBefore, newBalance, source.name(), null, null, attribution, true, null);

            if (isLocalMode) {
                return newBalance;
            }

            var circuitBreaker = facade.getCircuitBreaker();
            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
                circuitBreaker.onTransactionComplete(uuid, balanceBefore, newBalance);
            }

            return newBalance;
        }
    }

    /**
     * [SYNC-ECO-051] Set balance directly (admin operation) with optimistic locking.
     */
    BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            plugin.getLogger().warning("SetBalance rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            return setBalanceAdmitted(uuid, newBalance, source, writeLease);
        }
    }

    private BigDecimal setBalanceAdmitted(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source,
                                          MigrationLock.WriteLease writeLease) {
        if (newBalance.compareTo(BigDecimal.ZERO) < 0 || newBalance.compareTo(Constants.MAX_DECIMAL_BALANCE) > 0) {
            plugin.getLogger().warning("SetBalance rejected: Amount out of bounds (" + newBalance.toPlainString() + ")");
            return BigDecimal.valueOf(-1);
        }

        newBalance = NumericUtil.normalize(newBalance);
        final BigDecimal finalNewBalance = newBalance;

        if (rejectIfRedisUnavailable("SetBalance")) {
            return BigDecimal.valueOf(-1);
        }

        if (!ensureAccountLoaded(uuid)) {
            plugin.getLogger().warning("SetBalance rejected because the account could not be loaded for " + uuid);
            return BigDecimal.valueOf(-1);
        }

        AccountState existingState = stateManager.get(uuid);
        if (!canAdvanceLocalVersion(uuid, existingState)) {
            return BigDecimal.valueOf(-1);
        }
        BigDecimal oldBalance = (existingState != null) ? existingState.balance() : BigDecimal.ZERO;

        boolean migrationWorkerWrite = isLocalMode && source == EconomyEvent.EventSource.ADMIN_SET
                && MigrationLock.isLocked();
        final EconomyWriteQueue.Reservation reservation = migrationWorkerWrite
                ? null : stateManager.tryReserveWrite(uuid, existingState);
        if (!migrationWorkerWrite && reservation == null) {
            plugin.getLogger().warning("SetBalance rejected before balance mutation: write admission is full for " + uuid);
            return BigDecimal.valueOf(-1);
        }

        try (reservation) {
            long now = System.currentTimeMillis();
            AtomicBoolean mutationAccepted = new AtomicBoolean();
            boolean committedAtSourceBoundary = writeLease.commitMutationIfSourceStable(() -> {
                stateManager.compute(uuid, (key, existing) -> {
                    long currentVersion = (existing != null) ? existing.version() : 0L;
                    AccountState next = new AccountState(finalNewBalance, currentVersion + 1, now);
                    if (reservation != null) {
                        BigDecimal delta = finalNewBalance.subtract(oldBalance);
                        EconomyEvent event = new EconomyEvent(
                                uuid, delta, finalNewBalance, next.version(),
                                EconomyEvent.EventType.SET_BALANCE, source,
                                UUID.randomUUID().toString(), System.currentTimeMillis());
                        if (!commitAcceptedWrite(reservation, event)) {
                            plugin.getLogger().severe("SetBalance rejected because its reserved write slot could not be committed for " + uuid);
                            return existing;
                        }
                    }
                    mutationAccepted.set(true);
                    return next;
                });
                return mutationAccepted.get();
            });

            if (!committedAtSourceBoundary || !mutationAccepted.get()) {
                return BigDecimal.valueOf(-1);
            }

            if (reservation != null) {
                writeQueue.reportUsage();
            }

            String playerName = facade.resolvePlayerName(uuid);
            postTransactionEvent(uuid, playerName, AsyncPreTransactionEvent.TransactionType.SET_BALANCE,
                    newBalance.subtract(oldBalance), oldBalance, newBalance, source.name(), null, null, null, true, null);

            if (isLocalMode) {
                if (migrationWorkerWrite && localEconomyHandler != null) {
                    localEconomyHandler.setBalance(uuid, null, newBalance, source.name());
                }
                return newBalance;
            }

            return newBalance;
        }
    }

    /**
     * [SYNC-ECO-110] Plugin deposit - third-party plugins directly call this with explicit attribution.
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

        BigDecimal newBalance = deposit(uuid, amount, EconomyEvent.EventSource.PLUGIN_DEPOSIT, pluginName);
        if (isLocalMode && localEconomyHandler != null && newBalance.compareTo(BigDecimal.ZERO) > 0) {
            completeLocalPluginTransaction(uuid, newBalance.subtract(amount), newBalance);
        }
        return newBalance;
    }

    /**
     * [SYNC-ECO-111] Plugin withdraw - third-party plugins directly call this with explicit attribution.
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

        BigDecimal newBalance = withdraw(uuid, amount, EconomyEvent.EventSource.PLUGIN_WITHDRAW, pluginName);
        if (isLocalMode && localEconomyHandler != null && newBalance.compareTo(BigDecimal.ZERO) >= 0) {
            completeLocalPluginTransaction(uuid, newBalance.add(amount), newBalance);
        }
        return newBalance;
    }

    private void completeLocalPluginTransaction(UUID uuid, BigDecimal balanceBefore, BigDecimal balanceAfter) {
        var circuitBreaker = facade.getCircuitBreaker();
        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
            circuitBreaker.onTransactionComplete(uuid, balanceBefore, balanceAfter);
        }
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
