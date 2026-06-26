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
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [SYNC-VAULT-001] Internal core implementation of the Syncmoney Vault economy logic.
 * Delegates to specialized handlers for different operations and provides a
 * BigDecimal conversion layer to avoid floating-point errors.
 *
 * <p>This class is <b>not</b> the object registered with Vault; it is an internal
 * delegate owned by {@link SyncmoneyVaultProvider}, which is the public
 * {@link Economy} contract. Method signatures mirror the {@link Economy} interface
 * one-to-one so {@code SyncmoneyVaultProvider} can forward calls verbatim.
 *
 * [MainThread] Vault API is synchronous, all methods execute on main thread.
 */
public class VaultProviderCore {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final String currencyName;

    private volatile boolean enabled = false;

    private final AtomicInteger orphanDepositCount = new AtomicInteger(0);
    private static final int ORPHAN_LOG_INTERVAL = 100;

    private final AtomicLong cmiVersionCounter = new AtomicLong(0L);

    private volatile CMIEconomyHandler cmiHandler;

    private final VaultPlayerHandler playerHandler;
    private final VaultTransferHandler transferHandler;
    private final VaultBankHandler bankHandler;
    private final VaultPluginDetector pluginDetector;
    private final VaultLuaScriptManager luaScriptManager;

    private CrossServerSyncManager syncManager;
    private SyncmoneyConfig config;
    private NameResolver nameResolver;

    public VaultProviderCore(Plugin plugin, EconomyFacade economyFacade,
                           VaultPlayerHandler playerHandler, VaultTransferHandler transferHandler,
                           VaultBankHandler bankHandler, VaultPluginDetector pluginDetector,
                           VaultLuaScriptManager luaScriptManager) {
        this(plugin, economyFacade, playerHandler, transferHandler, bankHandler,
             pluginDetector, luaScriptManager, null);
    }

    public VaultProviderCore(Plugin plugin, EconomyFacade economyFacade,
                           VaultPlayerHandler playerHandler, VaultTransferHandler transferHandler,
                           VaultBankHandler bankHandler, VaultPluginDetector pluginDetector,
                           VaultLuaScriptManager luaScriptManager, NameResolver nameResolver) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.playerHandler = playerHandler;
        this.transferHandler = transferHandler;
        this.bankHandler = bankHandler;
        this.pluginDetector = pluginDetector;
        this.luaScriptManager = luaScriptManager;
        this.currencyName = "Syncmoney";
        this.nameResolver = nameResolver;
    }

    /**
     * Sets the sync manager.
     */
    public void setSyncManager(CrossServerSyncManager syncManager) {
        this.syncManager = syncManager;
    }

    /**
     * Sets the configuration.
     */
    public void setConfig(SyncmoneyConfig config) {
        this.config = config;
    }

    /**
     * Sets the name resolver.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    public void setCmiHandler(CMIEconomyHandler cmiHandler) {
        this.cmiHandler = cmiHandler;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the enabled state.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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
                this.plugin.getLogger().fine("[FIX-CMI-VAULT-PUBLISH] CMI mode publish uuid=" + uuid
                        + " balance=" + newBalance + " v" + version
                        + " amount=" + amount + " sourcePlugin=" + resolvedPlugin);
            }
        }
    }

    public String getName() {
        return "Syncmoney";
    }

    public boolean hasBankSupport() {
        return true;
    }

    public int fractionalDigits() {
        return 2;
    }

    public String format(double amount) {
        BigDecimal bd = NumericUtil.normalize(amount);
        return FormatUtil.formatCurrency(bd) + " " + currencyName;
    }

    public String currencyNamePlural() {
        return currencyName;
    }

    public String currencyNameSingular() {
        return currencyName;
    }

    public boolean hasAccount(String playerName) {
        return playerHandler.hasAccount(playerName);
    }

    public boolean hasAccount(OfflinePlayer player) {
        return playerHandler.hasAccount(player);
    }

    public boolean hasAccount(String playerName, String worldName) {
        return playerHandler.hasAccount(playerName, worldName);
    }

    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return playerHandler.hasAccount(player, worldName);
    }

    public double getBalance(String playerName) {
        return playerHandler.getBalance(playerName);
    }

    public double getBalance(OfflinePlayer player) {
        return playerHandler.getBalance(player);
    }

    /**
     * Gets balance as BigDecimal (internal use).
     */
    public BigDecimal getBalanceAsBigDecimal(OfflinePlayer player) {
        return playerHandler.getBalanceAsBigDecimal(player);
    }

    public double getBalance(String playerName, String world) {
        return playerHandler.getBalance(playerName, world);
    }

    public double getBalance(OfflinePlayer player, String world) {
        return playerHandler.getBalance(player, world);
    }

    public boolean has(String playerName, double amount) {
        return playerHandler.has(playerName, amount);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return playerHandler.has(player, amount);
    }

    public boolean has(String playerName, String worldName, double amount) {
        return playerHandler.has(playerName, worldName, amount);
    }

    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return playerHandler.has(player, worldName, amount);
    }

    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null;
    }

    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // =========================================================================
    // Deposit/Withdraw delegation to TransferHandler
    // =========================================================================

    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return transferHandler.withdrawPlayer(playerHandler.getOfflinePlayerSafe(playerName), amount);
    }

    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return transferHandler.withdrawPlayer(player, amount);
    }

    /**
     * [SYNC-VAULT-011] Withdraw with optional transfer context for rollback support.
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount, UUID toUuid) {
        return transferHandler.withdrawPlayer(player, amount, toUuid);
    }

    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(playerHandler.getOfflinePlayerSafe(playerName), amount);
    }

    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amount");
        }
        if (amount == 0) {
            BigDecimal currentBalance = economyFacade.getBalance(player.getUniqueId());
            return new EconomyResponse(0, currentBalance.doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, "Zero deposit ignored");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);
        UUID uuid = player.getUniqueId();

        VaultTransferHandler.TransferContext pendingTransfer = transferHandler.getPendingTransfer(uuid);

        if (pendingTransfer == null) {
            pendingTransfer = transferHandler.findCorrelatedTransfer(uuid, amountBd);
            transferHandler.purgeExpiredWithdrawals();
        }

        if (pendingTransfer == null) {

            int count = orphanDepositCount.incrementAndGet();
            plugin.getLogger().fine("No corresponding withdrawal found for VAULT_DEPOSIT: " + uuid +
                " amount " + amountBd + ". Processing as PLUGIN_DEPOSIT.");
            if (count > 0 && count % ORPHAN_LOG_INTERVAL == 0) {
                plugin.getLogger().info("[Vault-Orphan-Recovery] " + count + " orphan deposits processed since startup (cross-server expected behavior).");
            }

            EconomyResponse locked = LockingHelper.requireNotLocked(economyFacade, uuid, "Target account is locked");
            if (locked != null) {
                return locked;
            }

            BigDecimal newBalance = economyFacade.pluginDeposit(uuid, amountBd, "Vault-Orphan-Recovery");
            if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                return new EconomyResponse(0, economyFacade.getBalance(uuid).doubleValue(),
                        EconomyResponse.ResponseType.FAILURE, "Failed to deposit");
            }

            CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.deposited", amountBd, newBalance);

            publishCrossServerUpdate(uuid, newBalance, "PLUGIN_DEPOSIT", amount, "Vault-Orphan-Recovery", null);

            return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
        }

        if (LockingHelper.isLocked(economyFacade, uuid)) {
            if (pendingTransfer != null) {
                plugin.getLogger().warning("Deposit failed: target account locked. Rolling back transfer: " +
                    pendingTransfer.fromUuid() + " -> " + pendingTransfer.toUuid());
                transferHandler.rollbackTransfer(pendingTransfer);
                transferHandler.removePendingTransfer(uuid);
            }
            return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE,
                    "Target account is locked");
        }

        BigDecimal newBalance = economyFacade.deposit(uuid, amountBd, EconomyEvent.EventSource.VAULT_DEPOSIT);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            if (pendingTransfer != null) {
                plugin.getLogger().warning("Deposit failed: deposit rejected. Rolling back transfer: " +
                    pendingTransfer.fromUuid() + " -> " + pendingTransfer.toUuid());
                transferHandler.rollbackTransfer(pendingTransfer);
                transferHandler.removePendingTransfer(uuid);
            }
            return new EconomyResponse(0, economyFacade.getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Failed to deposit");
        }

        if (pendingTransfer != null) {
            transferHandler.removePendingTransfer(uuid);
            transferHandler.removeRecentWithdrawal(pendingTransfer.fromUuid(), amountBd);
            plugin.getLogger().info("Atomic transfer completed: " +
                pendingTransfer.fromUuid() + " -> " + pendingTransfer.toUuid() + " : " + amountBd);
        }

        CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.deposited", amountBd, newBalance);

        publishCrossServerUpdate(uuid, newBalance, "VAULT_DEPOSIT", amount, null, null);

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // =========================================================================
    // Plugin API - bypasses Vault pairing for third-party plugins
    // =========================================================================

    /**
     * [SYNC-VAULT-015] Deposit for plugin use - bypasses Vault pairing logic.
     * Third-party plugins (e.g., chest shops) should call this instead of the standard
     * Vault Economy API when they need plugin-level attribution.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param player Target player
     * @param amount Amount to deposit (positive)
     * @param pluginName Calling plugin name (for audit trail)
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse depositPlayerForPlugin(OfflinePlayer player, double amount, String pluginName) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amount");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);
        UUID uuid = player.getUniqueId();

        EconomyResponse locked = LockingHelper.requireNotLocked(economyFacade, uuid, "Target account is locked");
        if (locked != null) {
            return locked;
        }

        BigDecimal newBalance = economyFacade.pluginDeposit(uuid, amountBd, pluginName);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            return new EconomyResponse(0, economyFacade.getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Failed to deposit");
        }

        CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.deposited", amountBd, newBalance);

        publishCrossServerUpdate(uuid, newBalance, "PLUGIN_DEPOSIT", amount, pluginName, null);

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    /**
     * [SYNC-VAULT-016] Withdraw for plugin use - bypasses Vault pairing logic.
     * Third-party plugins (e.g., chest shops) should call this instead of the standard
     * Vault Economy API when they need plugin-level attribution.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param player Target player
     * @param amount Amount to withdraw (positive)
     * @param pluginName Calling plugin name (for audit trail)
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse withdrawPlayerForPlugin(OfflinePlayer player, double amount, String pluginName) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amount");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);
        UUID uuid = player.getUniqueId();

        EconomyResponse locked = LockingHelper.requireNotLocked(economyFacade, uuid,
                "Account is locked due to suspicious activity");
        if (locked != null) {
            return locked;
        }

        BigDecimal newBalance = economyFacade.pluginWithdraw(uuid, amountBd, pluginName);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal currentBalance = economyFacade.getBalance(uuid);
            return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Insufficient funds");
        }

        CrossServerNotifier.notifyBalanceChange(plugin, player, "vault.withdrawn", amountBd, newBalance);

        publishCrossServerUpdate(uuid, newBalance, "PLUGIN_WITHDRAW", -amount, pluginName, null);

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    /**
     * [SYNC-VAULT-017] Atomic transfer for plugin use.
     * Ensures both withdraw and deposit succeed atomically without Vault pairing.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param from Player who pays
     * @param to Player who receives
     * @param amount Transfer amount
     * @param pluginName Calling plugin name
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse pluginTransfer(OfflinePlayer from, OfflinePlayer to, double amount, String pluginName) {
        if (from == null || to == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot transfer negative amount");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);
        UUID fromUuid = from.getUniqueId();
        UUID toUuid = to.getUniqueId();

        if (LockingHelper.isLocked(economyFacade, fromUuid) || LockingHelper.isLocked(economyFacade, toUuid)) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "One or both accounts are locked");
        }

        CacheManager.TransferResult result = economyFacade.pluginAtomicTransfer(fromUuid, toUuid, amountBd, pluginName);

        if (result == null) {
            BigDecimal currentBalance = economyFacade.getBalance(fromUuid);
            return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Atomic transfer failed - insufficient funds or locked account");
        }

        CrossServerNotifier.notifyBalanceChange(plugin, from, "vault.withdrawn", amountBd, result.fromNewBalance);
        CrossServerNotifier.notifyBalanceChange(plugin, to, "vault.deposited", amountBd, result.toNewBalance);

        String senderName = nameResolver != null ? nameResolver.getName(fromUuid) : null;
        if (senderName == null) {
            senderName = fromUuid.toString();
        }
        publishCrossServerUpdate(fromUuid, result.fromNewBalance, "PLUGIN_WITHDRAW", -amountBd.doubleValue(), pluginName, senderName);
        publishCrossServerUpdate(toUuid, result.toNewBalance, "PLUGIN_DEPOSIT", amountBd.doubleValue(), pluginName, senderName);

        plugin.getLogger().info("Plugin atomic transfer completed: " + fromUuid + " -> " + toUuid + " : " + amountBd + " by " + pluginName);

        return new EconomyResponse(amountBd.doubleValue(), result.fromNewBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, "");
    }

    // =========================================================================
    // Bank delegation to BankHandler
    // =========================================================================

    public EconomyResponse createBank(String name, String player) {
        return bankHandler.createBank(name, player);
    }

    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return bankHandler.createBank(name, player);
    }

    public EconomyResponse deleteBank(String name) {
        return bankHandler.deleteBank(name);
    }

    public EconomyResponse bankBalance(String name) {
        return bankHandler.bankBalance(name);
    }

    public EconomyResponse bankHas(String name, double amount) {
        return bankHandler.bankHas(name, amount);
    }

    public EconomyResponse bankWithdraw(String name, double amount) {
        return bankHandler.bankWithdraw(name, amount);
    }

    public EconomyResponse bankDeposit(String name, double amount) {
        return bankHandler.bankDeposit(name, amount);
    }

    /**
     * [SYNC-VAULT-008] Atomic bank-to-bank transfer using Lua script.
     */
    public EconomyResponse bankTransfer(String fromBankName, String toBankName, double amount) {
        return bankHandler.bankTransfer(fromBankName, toBankName, amount);
    }

    public EconomyResponse isBankOwner(String name, String playerName) {
        return bankHandler.isBankOwner(name, playerName);
    }

    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bankHandler.isBankOwner(name, player);
    }

    public EconomyResponse isBankMember(String name, String playerName) {
        return bankHandler.isBankMember(name, playerName);
    }

    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bankHandler.isBankMember(name, player);
    }

    public java.util.List<String> getBanks() {
        return bankHandler.getBanks();
    }
}
