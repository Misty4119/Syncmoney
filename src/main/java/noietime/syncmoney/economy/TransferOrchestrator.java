package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.economy.EconomyFacade.PendingRollback;
import noietime.syncmoney.economy.EconomyFacade.TransferContext;
import noietime.syncmoney.storage.CacheManager;
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

    TransferOrchestrator(EconomyFacade facade, Plugin plugin, SyncmoneyConfig config,
            CacheManager cacheManager, MemoryStateManager stateManager) {
        this.facade = facade;
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.stateManager = stateManager;
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     * This method bypasses the memory state and directly uses Redis for true atomicity.
     */
    CacheManager.TransferResult executeAtomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount) {
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
    BigDecimal atomicTransfer(UUID fromUuid, UUID toUuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (facade.isPlayerLocked(fromUuid) || facade.isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Atomic transfer rejected: one or both players are locked");
            return BigDecimal.valueOf(-1);
        }

        var circuitBreaker = facade.getCircuitBreaker();
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

        BigDecimal withdrawResult = facade.withdraw(fromUuid, amount, source);

        if (withdrawResult.compareTo(BigDecimal.ZERO) < 0) {
            plugin.getLogger().warning("Atomic transfer failed at withdraw stage: " + fromUuid + " -> " + toUuid);
            return BigDecimal.valueOf(-1);
        }

        PendingRollback rollbackInfo = new PendingRollback(fromUuid, amount, transferId, now);
        stateManager.putPendingRollback(toUuid, rollbackInfo);

        try {
            BigDecimal depositResult = facade.deposit(toUuid, amount, source);

            if (depositResult.compareTo(BigDecimal.ZERO) < 0) {
                plugin.getLogger().warning("Atomic transfer failed at deposit stage, initiating rollback: " + fromUuid + " -> " + toUuid);
                rollbackWithdraw(fromUuid, amount, source, transferId);
                return BigDecimal.valueOf(-1);
            }

            stateManager.putTransferContext(transferId, new TransferContext(
                fromUuid, toUuid, amount, now, true, false
            ));

            stateManager.removePendingRollback(toUuid);

            var playerTransactionGuard = facade.getPlayerTransactionGuard();
            if (playerTransactionGuard != null) {
                playerTransactionGuard.checkAndLockReceiverForTransfer(toUuid, amount);
            }

            if (circuitBreaker != null && config.circuitBreaker().isCircuitBreakerEnabled() && source != EconomyEvent.EventSource.TEST) {
                BigDecimal fromBalance = facade.getBalance(fromUuid);
                BigDecimal toBalance = facade.getBalance(toUuid);
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
            facade.getBalance(uuid);
            long now = System.currentTimeMillis();

            stateManager.compute(uuid, (key, existing) -> {
                BigDecimal current = (existing != null) ? existing.balance() : BigDecimal.ZERO;
                long version = (existing != null) ? existing.version() : 0L;
                return new EconomyState(current.add(amount), version + 1, now);
            });

            plugin.getLogger().info("Rollback successful: restored " + amount + " to " + uuid + " (transferId: " + transferId + ")");

            stateManager.computeTransferContext(transferId, (k, ctx) -> {
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
        if (facade.isPlayerLocked(fromUuid) || facade.isPlayerLocked(toUuid)) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: one or both players are locked");
            return null;
        }
        if (noietime.syncmoney.migration.MigrationLock.isLocked()) {
            plugin.getLogger().warning("Plugin atomic transfer rejected: migration in progress");
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
}
