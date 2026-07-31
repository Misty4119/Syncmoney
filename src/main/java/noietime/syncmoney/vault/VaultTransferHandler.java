package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.sync.CMIVersioning;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
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
    private final CrossServerSyncManager syncManager;
    private final VaultPluginDetector pluginDetector;
    private final SyncmoneyConfig config;
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

    /**
     * [FIX-CMI-VAULT-PUBLISH] Emit a cross-server balance update for the Vault transfer handler.
     *
     * <p>Routes the publish to the right channel for the active economy mode:
     * <ul>
     *   <li>SYNC / LOCAL_REDIS → {@code publishAndNotify} on the SYNC channel (legacy behavior).</li>
     *   <li>CMI → {@code publishCMIUpdate} on the CMI channel with a CMI versioning counter.</li>
     *   <li>LOCAL → no-op (no cross-server transport).</li>
     * </ul>
     *
     * @param uuid             player whose balance changed
     * @param newBalance       authoritative post-transaction balance
     * @param syncModeEventType event type used when publishing to {@code publishAndNotify}
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
            syncManager.publishAndNotify(uuid, newBalance, syncModeEventType, amount, resolvedPlugin, sourcePlayerName);
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
        if (amount == 0) {
            BigDecimal currentBalance = economyFacade.getBalance(player.getUniqueId());
            return new EconomyResponse(0, currentBalance.doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, "Zero withdraw ignored");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);

        UUID uuid = player.getUniqueId();

        EconomyResponse locked = LockingHelper.requireNotLocked(economyFacade, uuid,
                "Account is locked due to suspicious activity");
        if (locked != null) {
            return locked;
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

        String sourcePlugin = pluginDetector.detectCallingPlugin();

        CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.withdrawn", amountBd, newBalance);

        publishCrossServerUpdate(uuid, newBalance, "VAULT_WITHDRAW", -amount, sourcePlugin, null);

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
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

            CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.withdrawn", amountBd, result.fromNewBalance);

            Player receiverPlayer = plugin.getServer().getPlayer(toUuid);
            CrossServerNotifier.notifyBalanceChange(plugin, receiverPlayer, "vault.deposited", amountBd, result.toNewBalance);

            String sourcePlugin = pluginDetector.detectCallingPlugin();

            String senderName = nameResolver != null ? nameResolver.getName(fromUuid) : null;
            if (senderName == null) {
                senderName = fromUuid.toString();
            }
            publishCrossServerUpdate(fromUuid, result.fromNewBalance, "VAULT_WITHDRAW", -amountBd.doubleValue(), sourcePlugin, senderName);
            publishCrossServerUpdate(toUuid, result.toNewBalance, "VAULT_DEPOSIT", amountBd.doubleValue(), sourcePlugin, senderName);

            plugin.getLogger().info("Atomic transfer completed (Lua): " + fromUuid + " -> " + toUuid + " : " + amountBd);

            return new EconomyResponse(amountBd.doubleValue(), result.fromNewBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, "");

        } catch (Exception e) {
            plugin.getLogger().severe("Atomic transfer exception: " + e.getMessage());
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Transfer error: " + e.getMessage());
        }
    }

}
