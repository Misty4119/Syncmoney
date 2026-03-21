package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SYNC-VAULT-011] Handles player-to-player transfer operations.
 * Withdraw, deposit, atomic transfers, rollback, and correlation logic.
 */
public class VaultTransferHandler {

    private final ConcurrentHashMap<UUID, TransferContext> pendingTransfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<RecentWithdrawal>> recentWithdrawals = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final CrossServerSyncManager syncManager;
    private final VaultPluginDetector pluginDetector;
    private final SyncmoneyConfig config;
    private final VaultPlayerHandler playerHandler;
    private final NameResolver nameResolver;

    /**
     * Recent withdrawal for transfer correlation.
     */
    public record RecentWithdrawal(
        UUID fromUuid,
        BigDecimal amount,
        String sourcePlugin,
        long timestamp
    ) {}

    /**
     * Transfer context for linking withdraw + deposit as a single transaction.
     */
    public record TransferContext(
        UUID fromUuid,
        UUID toUuid,
        BigDecimal amount,
        String sourcePlugin,
        long timestamp
    ) {}

    public VaultTransferHandler(Plugin plugin, EconomyFacade economyFacade, CrossServerSyncManager syncManager,
                               VaultPluginDetector pluginDetector, SyncmoneyConfig config, VaultPlayerHandler playerHandler,
                               NameResolver nameResolver) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.syncManager = syncManager;
        this.pluginDetector = pluginDetector;
        this.config = config;
        this.playerHandler = playerHandler;
        this.nameResolver = nameResolver;
    }

    /**
     * [SYNC-VAULT-011] Withdraw with optional transfer context for rollback support.
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdrawPlayer(player, amount, null);
    }

    /**
     * [SYNC-VAULT-011] Withdraw with optional transfer context for rollback support.
     *
     * @param player The player to withdraw from
     * @param amount Amount to withdraw
     * @param toUuid Optional target UUID for transfer tracking (used for rollback)
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount, UUID toUuid) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amount");
        }
        if (amount == 0) {
            BigDecimal currentBalance = economyFacade.getBalance(player.getUniqueId());
            return new EconomyResponse(0, currentBalance.doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, "Zero withdraw ignored");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);

        UUID uuid = player.getUniqueId();

        if (economyFacade.isPlayerLocked(uuid)) {
            return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE,
                    "Account is locked due to suspicious activity");
        }

        if (toUuid != null) {
            return executeAtomicTransfer(player, uuid, toUuid, amountBd);
        }

        BigDecimal newBalance = economyFacade.withdraw(uuid, amountBd, EconomyEvent.EventSource.VAULT_WITHDRAW);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal currentBalance = economyFacade.getBalance(uuid);
            return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Insufficient funds");
        }

        if (toUuid != null) {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            pendingTransfers.put(toUuid, new TransferContext(
                uuid, toUuid, amountBd, sourcePlugin, System.currentTimeMillis()
            ));
        } else {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            RecentWithdrawal withdrawal = new RecentWithdrawal(uuid, amountBd, sourcePlugin, System.currentTimeMillis());
            recentWithdrawals.compute(uuid, (k, list) -> {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(withdrawal);
                long cutoff = System.currentTimeMillis() - 30000;
                list.removeIf(w -> w.timestamp() < cutoff);
                return list;
            });
        }

        if (player.isOnline()) {
            final Player finalOnlinePlayer = (Player) player;
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String message = syncMoneyPlugin.getMessage("vault.withdrawn");
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                    message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                    MessageHelper.sendMessage(finalOnlinePlayer, message);
                }
            }
        }

        if (syncManager != null && config != null && config.isSyncMode()) {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "VAULT_WITHDRAW",
                    -amount,
                    sourcePlugin,
                    null);
        }

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    /**
     * [SYNC-VAULT-004] Find a correlated withdrawal that matches this deposit.
     */
    TransferContext findCorrelatedTransfer(UUID toUuid, BigDecimal amount) {
        long now = System.currentTimeMillis();
        long windowStart = now - 30000;

        for (var entry : recentWithdrawals.entrySet()) {
            List<RecentWithdrawal> list = entry.getValue();
            if (list == null) continue;

            list.removeIf(w -> w.timestamp() < windowStart);

            if (list.isEmpty()) {
                recentWithdrawals.remove(entry.getKey());
                continue;
            }

            for (RecentWithdrawal withdrawal : list) {
                if (withdrawal.amount().compareTo(amount) == 0 &&
                    withdrawal.timestamp() >= windowStart) {

                    TransferContext ctx = new TransferContext(
                        withdrawal.fromUuid(),
                        toUuid,
                        withdrawal.amount(),
                        withdrawal.sourcePlugin(),
                        withdrawal.timestamp()
                    );

                    plugin.getLogger().fine("Correlated transfer found: " +
                        withdrawal.fromUuid() + " -> " + toUuid + " : " + amount);

                    return ctx;
                }
            }
        }

        return null;
    }

    /**
     * [SYNC-VAULT-012] Rollback a transfer when deposit fails.
     */
    void rollbackTransfer(TransferContext transfer) {
        try {
            BigDecimal rollbackAmount = transfer.amount();
            UUID fromUuid = transfer.fromUuid();

            BigDecimal newBalance = economyFacade.deposit(fromUuid, rollbackAmount,
                EconomyEvent.EventSource.ADMIN_GIVE);

            if (newBalance.compareTo(BigDecimal.ZERO) >= 0) {
                plugin.getLogger().info("Rollback successful: restored " + rollbackAmount +
                    " to " + fromUuid + " (from failed transfer to " + transfer.toUuid() + ")");
            } else {
                plugin.getLogger().severe("Rollback FAILED: could not restore " + rollbackAmount +
                    " to " + fromUuid);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Rollback exception: " + e.getMessage());
        }
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     */
    private EconomyResponse executeAtomicTransfer(OfflinePlayer player, UUID fromUuid, UUID toUuid, BigDecimal amountBd) {
        try {
            CacheManager.TransferResult result = economyFacade.executeAtomicTransfer(fromUuid, toUuid, amountBd);

            if (result == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Atomic transfer failed - try again");
            }

            if (result == CacheManager.TransferResult.insufficientFunds()) {
                BigDecimal currentBalance = economyFacade.getBalance(fromUuid);
                return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Insufficient funds");
            }

            if (player.isOnline()) {
                final Player finalOnlinePlayer = (Player) player;
                if (plugin instanceof noietime.syncmoney.Syncmoney) {
                    noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;

                    String withdrawMsg = syncMoneyPlugin.getMessage("vault.withdrawn");
                    if (withdrawMsg != null) {
                        withdrawMsg = withdrawMsg.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                        withdrawMsg = withdrawMsg.replace("{balance}", FormatUtil.formatCurrency(result.fromNewBalance));
                        MessageHelper.sendMessage(finalOnlinePlayer, withdrawMsg);
                    }
                }
            }

            Player receiverPlayer = plugin.getServer().getPlayer(toUuid);
            if (receiverPlayer != null && receiverPlayer.isOnline()) {
                if (plugin instanceof noietime.syncmoney.Syncmoney) {
                    noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                    String depositMsg = syncMoneyPlugin.getMessage("vault.deposited");
                    if (depositMsg != null) {
                        depositMsg = depositMsg.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                        depositMsg = depositMsg.replace("{balance}", FormatUtil.formatCurrency(result.toNewBalance));
                        MessageHelper.sendMessage(receiverPlayer, depositMsg);
                    }
                }
            }

            if (syncManager != null && config != null && config.isSyncMode()) {
                String sourcePlugin = pluginDetector.detectCallingPlugin();

                String senderName = nameResolver != null ? nameResolver.getName(fromUuid) : null;
                if (senderName == null) {
                    senderName = fromUuid.toString();
                }
                syncManager.publishAndNotify(fromUuid, result.fromNewBalance, "VAULT_WITHDRAW", -amountBd.doubleValue(), sourcePlugin, senderName);
                syncManager.publishAndNotify(toUuid, result.toNewBalance, "VAULT_DEPOSIT", amountBd.doubleValue(), sourcePlugin, senderName);
            }

            plugin.getLogger().info("Atomic transfer completed (Lua): " + fromUuid + " -> " + toUuid + " : " + amountBd);

            return new EconomyResponse(amountBd.doubleValue(), result.fromNewBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, "");

        } catch (Exception e) {
            plugin.getLogger().severe("Atomic transfer exception: " + e.getMessage());
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Transfer error: " + e.getMessage());
        }
    }

    /**
     * Gets a pending transfer for a player.
     */
    TransferContext getPendingTransfer(UUID toUuid) {
        return pendingTransfers.get(toUuid);
    }

    /**
     * Removes a pending transfer for a player.
     */
    void removePendingTransfer(UUID toUuid) {
        pendingTransfers.remove(toUuid);
    }

    /**
     * Removes a recent withdrawal for a player.
     */
    void removeRecentWithdrawal(UUID fromUuid, BigDecimal amount) {
        recentWithdrawals.compute(fromUuid, (k, list) -> {
            if (list != null) {
                list.removeIf(w -> w.amount().compareTo(amount) == 0);
            }
            return list;
        });
    }
}
