package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.sync.CMIVersioning;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [SYNC-VAULT-011] Handles player-to-player transfer operations.
 * Handles direct withdrawals and explicit atomic player-to-player transfers.
 */
public class VaultTransferHandler {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private volatile CrossServerSyncManager syncManager;
    private final VaultPluginDetector pluginDetector;
    private volatile SyncmoneyConfig config;
    private final VaultPlayerHandler playerHandler;
    private final NameResolver nameResolver;

    private final AtomicLong cmiVersionCounter = new AtomicLong(0L);

    private volatile CMIEconomyHandler cmiHandler;

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


    public void setCmiHandler(CMIEconomyHandler cmiHandler) {
        this.cmiHandler = cmiHandler;
    }

    public void setSyncManager(CrossServerSyncManager syncManager) {
        this.syncManager = syncManager;
    }

    public void setConfig(SyncmoneyConfig config) {
        this.config = config;
    }

    /**
     * [FIX-CMI-VAULT-PUBLISH] Emit a cross-server balance update for the Vault transfer handler.
     *
     * <p>Routes the publish to the right channel for the active economy mode:
     * <ul>
     *   <li>SYNC / LOCAL_REDIS → local notification; the accepted-write receipt publishes the shared update.</li>
     *   <li>CMI → {@code publishCMIUpdate} on the CMI channel with a CMI versioning counter.</li>
     *   <li>LOCAL → no-op (no cross-server transport).</li>
     * </ul>
     *
     * @param uuid             player whose balance changed
     * @param newBalance       authoritative post-transaction balance
     * @param syncModeEventType event type used for the local notification in SYNC mode
     *                          (e.g. {@code VAULT_WITHDRAW}, {@code VAULT_DEPOSIT})
     * @param amount           signed delta ({@code +amount} for deposit, {@code -amount} for withdraw)
     * @param sourcePlugin     name of the calling plugin (best-effort, {@code null} falls through
     *                          to {@code pluginDetector.detectCallingPlugin()})
     * @param sourcePlayerName optional counter-party display name (transfers only)
     */
    private void publishCrossServerUpdate(UUID uuid, BigDecimal newBalance,
                                          String syncModeEventType, double amount,
                                          String sourcePlugin, String sourcePlayerName) {
        if (syncManager == null || config == null) {
            return;
        }
        String resolvedPlugin = sourcePlugin != null ? sourcePlugin : pluginDetector.detectCallingPlugin();
        if (config.isSyncMode()) {
            syncManager.notifyLocalBalanceChange(uuid, syncModeEventType, amount, resolvedPlugin);
        } else if (config.isCMIMode()) {
            long version = (cmiHandler != null)
                    ? cmiHandler.mintCmiVersion()
                    : CMIVersioning.generateVersion(cmiVersionCounter);
            String cmiEventType = amount >= 0 ? "CMI_DEPOSIT" : "CMI_WITHDRAW";
            syncManager.publishCMIUpdate(uuid, newBalance, version, cmiEventType, amount, resolvedPlugin, sourcePlayerName);
            if (config.isDebug()) {
                plugin.getLogger().fine("[FIX-CMI-VAULT-PUBLISH] CMI mode publish uuid=" + uuid
                        + " balance=" + newBalance + " v" + version
                        + " amount=" + amount + " sourcePlugin=" + resolvedPlugin);
            }
        }
    }

    /**
     * [SYNC-VAULT-011] Withdraw with an explicit target for an atomic transfer.
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdrawPlayer(player, amount, null);
    }

    /**
     * [SYNC-VAULT-011] Withdraw with optional transfer context for rollback support.
     *
     * @param player The player to withdraw from
     * @param amount Amount to withdraw
     * @param toUuid Optional target UUID; when provided, the transfer is atomic
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount, UUID toUuid) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amount");
        }
        UUID uuid = player.getUniqueId();
        if (toUuid != null && config != null && config.isCMIMode()) {
            return pairedTransferUnavailable();
        }
        if (toUuid != null && uuid.equals(toUuid)) {
            return new EconomyResponse(0, readyBalance(uuid), EconomyResponse.ResponseType.FAILURE,
                    "Cannot transfer to the same account");
        }
        if (amount == 0) {
            double currentBalance = readyBalance(uuid);
            if (Double.isNaN(currentBalance)) {
                return accountNotReady();
            }
            return new EconomyResponse(0, currentBalance,
                    EconomyResponse.ResponseType.SUCCESS, "Zero withdraw ignored");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);

        EconomyResponse locked = LockingHelper.requireNotLocked(economyFacade, uuid,
                "Account is locked due to suspicious activity");
        if (locked != null) {
            return locked;
        }

        if (toUuid != null) {
            return executeAtomicTransfer(player, uuid, toUuid, amountBd);
        }

        String sourcePlugin = pluginDetector.detectCallingPlugin();
        BigDecimal newBalance = economyFacade.withdraw(uuid, amountBd,
                EconomyEvent.EventSource.VAULT_WITHDRAW, sourcePlugin);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            double currentBalance = readyBalance(uuid);
            return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                    Double.isNaN(currentBalance) ? notReadyMessage() : "Insufficient funds");
        }

        CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.withdrawn", amountBd, newBalance);

        publishCrossServerUpdate(uuid, newBalance, "VAULT_WITHDRAW", -amount, sourcePlugin, null);

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     */
    private EconomyResponse executeAtomicTransfer(OfflinePlayer player, UUID fromUuid, UUID toUuid, BigDecimal amountBd) {
        return executePairedMemoryTransfer(player, fromUuid, toUuid, amountBd);
    }

    private EconomyResponse executePairedMemoryTransfer(OfflinePlayer player, UUID fromUuid,
            UUID toUuid, BigDecimal amountBd) {
        if (LockingHelper.isLocked(economyFacade, toUuid)) {
            return new EconomyResponse(0, readyBalance(fromUuid), EconomyResponse.ResponseType.FAILURE,
                    "One or both accounts are locked");
        }

        BigDecimal senderBalance;
        try {
            senderBalance = economyFacade.atomicTransfer(fromUuid, toUuid, amountBd,
                    EconomyEvent.EventSource.VAULT_WITHDRAW);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Paired Vault transfer was not accepted: " + failure.getMessage());
            return new EconomyResponse(0, readyBalance(fromUuid), EconomyResponse.ResponseType.FAILURE,
                    "Transfer was not confirmed; check the account balance before retrying");
        }

        if (senderBalance == null || senderBalance.signum() < 0) {
            double currentBalance = readyBalance(fromUuid);
            String message = Double.isNaN(currentBalance) ? notReadyMessage()
                    : senderBalance == null ? "Transfer was not accepted; one or both accounts are unavailable"
                            : currentBalance < amountBd.doubleValue() ? "Insufficient funds"
                                    : "Transfer was not accepted; one or both accounts may be locked";
            return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE, message);
        }

        try {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            String senderName = nameResolver != null ? nameResolver.getNameCachedOnly(fromUuid) : null;
            publishCrossServerUpdate(fromUuid, senderBalance, "VAULT_WITHDRAW", -amountBd.doubleValue(),
                    sourcePlugin, nameResolver != null ? nameResolver.getNameCachedOnly(toUuid) : null);

            BigDecimal receiverBalance = economyFacade.getBalanceForPlaceholder(toUuid);
            if (receiverBalance != null) {
                publishCrossServerUpdate(toUuid, receiverBalance, "VAULT_DEPOSIT", amountBd.doubleValue(),
                        sourcePlugin, senderName);
            }
            CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.withdrawn", amountBd, senderBalance);
            if (receiverBalance != null) {
                org.bukkit.Server server = plugin.getServer();
                CrossServerNotifier.notifyBalanceChange(plugin, server == null ? null : server.getPlayer(toUuid),
                        "vault.deposited", amountBd, receiverBalance);
            }
        } catch (RuntimeException followUpFailure) {
            plugin.getLogger().severe("Paired Vault transfer was accepted, but a post-accept notification failed; "
                    + "both transfer legs remain accepted: " + followUpFailure.getMessage());
        }
        plugin.getLogger().fine("Paired Vault transfer accepted: " + fromUuid + " -> " + toUuid
                + " : " + amountBd);
        return new EconomyResponse(amountBd.doubleValue(), senderBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, "");
    }

    private EconomyResponse pairedTransferUnavailable() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                "Paired transfers are unavailable in CMI mode");
    }

    private double readyBalance(UUID uuid) {
        BigDecimal balance = economyFacade.getBalanceForPlaceholder(uuid);
        return balance == null ? Double.NaN : balance.doubleValue();
    }

    private EconomyResponse accountNotReady() {
        return new EconomyResponse(0, Double.NaN, EconomyResponse.ResponseType.FAILURE, notReadyMessage());
    }

    private String notReadyMessage() {
        return "Account is not ready; retry after it finishes loading";
    }

}
