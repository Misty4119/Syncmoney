package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade.TransferContext;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.RedisMutationException;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * [SYNC-ECO-103] Orchestrates player-to-player transfers and their rollback.
 */
final class TransferOrchestrator {

    private final EconomyFacade facade;
    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final CacheManager cacheManager;
    private final MemoryStateManager stateManager;
    private final TransactionWriter transactionWriter;

    TransferOrchestrator(EconomyFacade facade, Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, MemoryStateManager stateManager, TransactionWriter transactionWriter) {
        this.facade = facade;
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.stateManager = stateManager;
        this.transactionWriter = transactionWriter;
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     * This method bypasses the memory state and directly uses Redis for true atomicity.
     */
    CacheManager.TransferResult executeAtomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            plugin.getLogger().warning("Atomic transfer rejected: migration in progress and economy is locked");
            return null;
        }
        try (writeLease) {
            return executeAtomicTransferAdmitted(fromUuid, toUuid, amount);
        }
    }

    private CacheManager.TransferResult executeAtomicTransferAdmitted(UUID fromUuid, UUID toUuid, BigDecimal amount) {
        if (!facade.ensureAccountsLoaded(fromUuid, toUuid)) {
            plugin.getLogger().fine("Atomic transfer rejected while one or both accounts are loading");
            return null;
        }
        if (stateManager.isUnavailable(fromUuid) || stateManager.isUnavailable(toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected while an account authority outcome is unresolved");
            return null;
        }
        if (facade.isPlayerLocked(fromUuid) || facade.isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected: one or both players are locked");
            return null;
        }

        var circuitBreaker = facade.getCircuitBreaker();
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

        String operationId = TransferRequestIds.newOperationId();
        CacheManager.TransferResult result = executeRedisTransfer(fromUuid, toUuid, amount, operationId);

        if (result != null && result != CacheManager.TransferResult.insufficientFunds()) {
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
    BigDecimal atomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount, EconomyEvent.EventSource source) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            plugin.getLogger().warning("Atomic transfer rejected: migration in progress and economy is locked");
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            return atomicTransferAdmitted(fromUuid, toUuid, amount, source);
        }
    }

    private BigDecimal atomicTransferAdmitted(UUID fromUuid, UUID toUuid, BigDecimal amount,
            EconomyEvent.EventSource source) {
        if (fromUuid == null || toUuid == null || fromUuid.equals(toUuid) || amount == null || source == null) {
            return BigDecimal.valueOf(-1);
        }
        if (!facade.ensureAccountsLoaded(fromUuid, toUuid)) {
            plugin.getLogger().fine("Atomic transfer rejected while one or both accounts are loading");
            return BigDecimal.valueOf(-1);
        }
        if (facade.isPlayerLocked(fromUuid) || facade.isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected: one or both players are locked");
            return BigDecimal.valueOf(-1);
        }

        String transferId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        BigDecimal result = transactionWriter.transfer(fromUuid, toUuid, amount, source);
        if (result.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.valueOf(-1);
        }

        stateManager.putTransferContext(transferId, new TransferContext(
                fromUuid, toUuid, amount, now, true, false));

        var playerTransactionGuard = facade.getPlayerTransactionGuard();
        if (playerTransactionGuard != null) {
            try {
                playerTransactionGuard.checkAndLockReceiverForTransfer(toUuid, amount);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Transfer accepted but receiver guard notification failed: " + e.getMessage());
            }
        }

        var circuitBreaker = facade.getCircuitBreaker();
        if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()
                && source != EconomyEvent.EventSource.TEST) {
            try {
                BigDecimal fromBalance = result;
                BigDecimal toBalance = facade.getBalance(toUuid);
                circuitBreaker.onTransactionComplete(fromUuid, fromBalance.add(amount), fromBalance);
                circuitBreaker.onTransactionComplete(toUuid, toBalance.subtract(amount), toBalance);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Transfer accepted but circuit-breaker completion failed: " + e.getMessage());
            }
        }
        return result;
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
    CacheManager.TransferResult pluginAtomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount, String pluginName) {
        if (fromUuid == null || toUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: invalid parameters");
            return null;
        }
        if (fromUuid.equals(toUuid)) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: accounts must be distinct");
            return null;
        }
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: migration in progress");
            return null;
        }
        try (writeLease) {
            return pluginAtomicTransferAdmitted(fromUuid, toUuid, amount, pluginName);
        }
    }

    private CacheManager.TransferResult pluginAtomicTransferAdmitted(UUID fromUuid, UUID toUuid,
            BigDecimal amount, String pluginName) {
        if (!facade.ensureAccountsLoaded(fromUuid, toUuid)) {
            plugin.getLogger().fine("Plugin atomic transfer rejected while one or both accounts are loading");
            return null;
        }
        if (stateManager.isUnavailable(fromUuid) || stateManager.isUnavailable(toUuid)
                || stateManager.hasPendingWrite(fromUuid) || stateManager.hasPendingWrite(toUuid)) {
            plugin.getLogger().warning("Plugin atomic transfer rejected while either account is unavailable or has accepted writes pending");
            return null;
        }
        if (facade.isPlayerLocked(fromUuid) || facade.isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: one or both players are locked");
            return null;
        }

        var circuitBreaker = facade.getCircuitBreaker();
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

        String operationId = TransferRequestIds.newOperationId();
        CacheManager.TransferResult result = executeRedisTransfer(fromUuid, toUuid, amount, operationId);

        if (result != null && result != CacheManager.TransferResult.insufficientFunds()) {
            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled()) {
                circuitBreaker.onTransactionComplete(fromUuid, result.fromNewBalance.add(amount), result.fromNewBalance);
                circuitBreaker.onTransactionComplete(toUuid, result.toNewBalance.subtract(amount), result.toNewBalance);
            }
            plugin.getLogger().info("Plugin atomic transfer completed: " + fromUuid + " -> " + toUuid + " : " + amount + " by " + pluginName);
        }

        return result;
    }

    private CacheManager.TransferResult executeRedisTransfer(UUID fromUuid, UUID toUuid,
            BigDecimal amount, String operationId) {
        if (!stateManager.beginDirectTransferMutation(operationId, fromUuid, toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected because an account is unavailable or has accepted writes pending: "
                    + fromUuid + " -> " + toUuid);
            return null;
        }
        try {
            CacheManager.TransferResult result = cacheManager.atomicTransfer(fromUuid, toUuid, amount);
            if (result == CacheManager.TransferResult.insufficientFunds()) {
                reconcileRejectedTransfer(operationId, fromUuid, toUuid);
            } else if (hasConfirmedReceipt(result)) {
                stateManager.completeDirectTransferMutation(operationId, fromUuid,
                        result.fromNewBalance, result.fromNewVersion, toUuid,
                        result.toNewBalance, result.toNewVersion);
            } else {
                plugin.getLogger().severe("Atomic transfer returned an invalid receipt; both accounts remain unavailable: "
                        + fromUuid + " -> " + toUuid);
            }
            return result;
        } catch (RedisMutationException e) {
            if (e.executionState() == RedisMutationException.ExecutionState.SENT_UNKNOWN) {
                plugin.getLogger().severe("Atomic transfer outcome is unknown; both accounts are unavailable pending reconciliation: "
                        + fromUuid + " -> " + toUuid);
            } else if (e.executionState() == RedisMutationException.ExecutionState.REJECTED) {
                reconcileRejectedTransfer(operationId, fromUuid, toUuid);
            } else {
                stateManager.cancelDirectTransferMutation(operationId, fromUuid, toUuid);
            }
            plugin.getLogger().warning("Atomic transfer did not return a confirmed receipt: " + e.getMessage());
            return null;
        }
    }

    private boolean hasConfirmedReceipt(CacheManager.TransferResult result) {
        return result != null && result.fromAccountPresent && result.toAccountPresent
                && result.fromNewBalance != null && result.toNewBalance != null
                && result.fromNewVersion >= 0L && result.toNewVersion >= 0L;
    }

    private void reconcileRejectedTransfer(String operationId, UUID fromUuid, UUID toUuid) {
        try {
            CacheManager.TransferResult snapshot = cacheManager.readRejectedTransferSnapshotStrict(fromUuid, toUuid);
            if (snapshot == null
                    || !hasValidAuthoritySnapshotAccount(snapshot.fromAccountPresent,
                            snapshot.fromNewBalance, snapshot.fromNewVersion)
                    || !hasValidAuthoritySnapshotAccount(snapshot.toAccountPresent,
                            snapshot.toNewBalance, snapshot.toNewVersion)) {
                throw new IllegalStateException("Redis returned an invalid transfer authority snapshot");
            }
            long now = System.currentTimeMillis();
            EconomyFacade.EconomyState fromSnapshot = snapshot.fromAccountPresent
                    ? new EconomyFacade.EconomyState(snapshot.fromNewBalance, snapshot.fromNewVersion, now) : null;
            EconomyFacade.EconomyState toSnapshot = snapshot.toAccountPresent
                    ? new EconomyFacade.EconomyState(snapshot.toNewBalance, snapshot.toNewVersion, now) : null;
            stateManager.completeRejectedDirectTransferMutation(
                    operationId, fromUuid, fromSnapshot, toUuid, toSnapshot);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Rejected transfer could not refresh its authority pair; both accounts are unavailable: "
                    + e.getMessage());
        }
    }

    private boolean hasValidAuthoritySnapshotAccount(boolean present, BigDecimal balance, long version) {
        return present ? balance != null && version >= 0L : balance == null && version == -1L;
    }
}
