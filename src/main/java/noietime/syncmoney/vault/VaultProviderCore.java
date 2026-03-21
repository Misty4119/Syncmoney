package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.Economy;
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
import java.util.UUID;

/**
 * [SYNC-VAULT-001] Core Vault Economy API wrapper for Syncmoney.
 * Delegates to specialized handlers for different operations.
 * Provides BigDecimal conversion layer to avoid floating-point errors.
 *
 * [MainThread] Vault API is synchronous, all methods execute on main thread.
 */
public class VaultProviderCore implements Economy {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final String currencyName;

    private volatile boolean enabled = false;

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

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the enabled state.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getName() {
        return "Syncmoney";
    }

    @Override
    public boolean hasBankSupport() {
        return true;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        BigDecimal bd = NumericUtil.normalize(amount);
        return FormatUtil.formatCurrency(bd) + " " + currencyName;
    }

    @Override
    public String currencyNamePlural() {
        return currencyName;
    }

    @Override
    public String currencyNameSingular() {
        return currencyName;
    }

    @Override
    public boolean hasAccount(String playerName) {
        return playerHandler.hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return playerHandler.hasAccount(player);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return playerHandler.hasAccount(playerName, worldName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return playerHandler.hasAccount(player, worldName);
    }

    @Override
    public double getBalance(String playerName) {
        return playerHandler.getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return playerHandler.getBalance(player);
    }

    /**
     * Gets balance as BigDecimal (internal use).
     */
    public BigDecimal getBalanceAsBigDecimal(OfflinePlayer player) {
        return playerHandler.getBalanceAsBigDecimal(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return playerHandler.getBalance(playerName, world);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return playerHandler.getBalance(player, world);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return playerHandler.has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return playerHandler.has(player, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return playerHandler.has(playerName, worldName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return playerHandler.has(player, worldName, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // =========================================================================
    // Deposit/Withdraw delegation to TransferHandler
    // =========================================================================

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return transferHandler.withdrawPlayer(playerHandler.getOfflinePlayerSafe(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return transferHandler.withdrawPlayer(player, amount);
    }

    /**
     * [SYNC-VAULT-011] Withdraw with optional transfer context for rollback support.
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount, UUID toUuid) {
        return transferHandler.withdrawPlayer(player, amount, toUuid);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(playerHandler.getOfflinePlayerSafe(playerName), amount);
    }

    @Override
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
        }

        if (pendingTransfer == null) {
            // [FIX-VAULT-ORPHAN] No correlated withdrawal found.
            // Instead of rejecting, process as PLUGIN_DEPOSIT to prevent money loss
            // when high-frequency transactions cause race conditions in the pairing logic.
            plugin.getLogger().warning("No corresponding withdrawal found for VAULT_DEPOSIT: " + uuid +
                " amount " + amountBd + ". Processing as PLUGIN_DEPOSIT to prevent money loss.");

            if (economyFacade.isPlayerLocked(uuid)) {
                return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE,
                        "Target account is locked");
            }

            BigDecimal newBalance = economyFacade.pluginDeposit(uuid, amountBd, "Vault-Orphan-Recovery");
            if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                return new EconomyResponse(0, economyFacade.getBalance(uuid).doubleValue(),
                        EconomyResponse.ResponseType.FAILURE, "Failed to deposit");
            }

            if (player.isOnline()) {
                final org.bukkit.entity.Player finalOnlinePlayer = (org.bukkit.entity.Player) player;
                if (plugin instanceof noietime.syncmoney.Syncmoney) {
                    noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                    String message = syncMoneyPlugin.getMessage("vault.deposited");
                    if (message != null) {
                        message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                        message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                        noietime.syncmoney.util.MessageHelper.sendMessage(finalOnlinePlayer, message);
                    }
                }
            }

            if (syncManager != null && config != null && config.isSyncMode()) {
                String sourcePlugin = pluginDetector.detectCallingPlugin();
                syncManager.publishAndNotify(uuid, newBalance, "PLUGIN_DEPOSIT", amount, sourcePlugin, null);
            }

            return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
        }

        if (economyFacade.isPlayerLocked(uuid)) {
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

        if (player.isOnline()) {
            final org.bukkit.entity.Player finalOnlinePlayer = (org.bukkit.entity.Player) player;
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String message = syncMoneyPlugin.getMessage("vault.deposited");
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                    message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                    noietime.syncmoney.util.MessageHelper.sendMessage(finalOnlinePlayer, message);
                }
            }
        }

        if (syncManager != null && config != null && config.isSyncMode()) {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "VAULT_DEPOSIT",
                    amount,
                    sourcePlugin,
                    null);
        }

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
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

        if (economyFacade.isPlayerLocked(uuid)) {
            return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE,
                    "Target account is locked");
        }

        BigDecimal newBalance = economyFacade.pluginDeposit(uuid, amountBd, pluginName);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            return new EconomyResponse(0, economyFacade.getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Failed to deposit");
        }

        if (player.isOnline()) {
            final org.bukkit.entity.Player finalOnlinePlayer = (org.bukkit.entity.Player) player;
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String message = syncMoneyPlugin.getMessage("vault.deposited");
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                    message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                    noietime.syncmoney.util.MessageHelper.sendMessage(finalOnlinePlayer, message);
                }
            }
        }

        if (syncManager != null && config != null && config.isSyncMode()) {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "PLUGIN_DEPOSIT",
                    amount,
                    pluginName,
                    null);
        }

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

        if (economyFacade.isPlayerLocked(uuid)) {
            return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE,
                    "Account is locked due to suspicious activity");
        }

        BigDecimal newBalance = economyFacade.pluginWithdraw(uuid, amountBd, pluginName);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal currentBalance = economyFacade.getBalance(uuid);
            return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Insufficient funds");
        }

        if (player.isOnline()) {
            final Player finalOnlinePlayer = (Player) player;
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String message = syncMoneyPlugin.getMessage("vault.withdrawn");
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                    message = message.replace("{balance}", FormatUtil.formatCurrency(newBalance));
                    noietime.syncmoney.util.MessageHelper.sendMessage(finalOnlinePlayer, message);
                }
            }
        }

        if (syncManager != null && config != null && config.isSyncMode()) {
            String sourcePlugin = pluginDetector.detectCallingPlugin();
            syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "PLUGIN_WITHDRAW",
                    -amount,
                    pluginName,
                    null);
        }

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

        if (economyFacade.isPlayerLocked(fromUuid) || economyFacade.isPlayerLocked(toUuid)) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "One or both accounts are locked");
        }

        CacheManager.TransferResult result = economyFacade.pluginAtomicTransfer(fromUuid, toUuid, amountBd, pluginName);

        if (result == null) {
            BigDecimal currentBalance = economyFacade.getBalance(fromUuid);
            return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Atomic transfer failed - insufficient funds or locked account");
        }

        if (from.isOnline()) {
            final Player finalFromPlayer = (Player) from;
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String message = syncMoneyPlugin.getMessage("vault.withdrawn");
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                    message = message.replace("{balance}", FormatUtil.formatCurrency(result.fromNewBalance));
                    noietime.syncmoney.util.MessageHelper.sendMessage(finalFromPlayer, message);
                }
            }
        }

        if (to.isOnline()) {
            final Player finalToPlayer = (Player) to;
            if (plugin instanceof noietime.syncmoney.Syncmoney) {
                noietime.syncmoney.Syncmoney syncMoneyPlugin = (noietime.syncmoney.Syncmoney) plugin;
                String message = syncMoneyPlugin.getMessage("vault.deposited");
                if (message != null) {
                    message = message.replace("{amount}", FormatUtil.formatCurrency(amountBd));
                    message = message.replace("{balance}", FormatUtil.formatCurrency(result.toNewBalance));
                    noietime.syncmoney.util.MessageHelper.sendMessage(finalToPlayer, message);
                }
            }
        }

        if (syncManager != null && config != null && config.isSyncMode()) {
            String senderName = nameResolver != null ? nameResolver.getName(fromUuid) : null;
            if (senderName == null) {
                senderName = fromUuid.toString();
            }
            syncManager.publishAndNotify(fromUuid, result.fromNewBalance, "PLUGIN_WITHDRAW", -amountBd.doubleValue(), pluginName, senderName);
            syncManager.publishAndNotify(toUuid, result.toNewBalance, "PLUGIN_DEPOSIT", amountBd.doubleValue(), pluginName, senderName);
        }

        plugin.getLogger().info("Plugin atomic transfer completed: " + fromUuid + " -> " + toUuid + " : " + amountBd + " by " + pluginName);

        return new EconomyResponse(amountBd.doubleValue(), result.fromNewBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, "");
    }

    // =========================================================================
    // Bank delegation to BankHandler
    // =========================================================================

    @Override
    public EconomyResponse createBank(String name, String player) {
        return bankHandler.createBank(name, player);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return bankHandler.createBank(name, player);
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return bankHandler.deleteBank(name);
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return bankHandler.bankBalance(name);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return bankHandler.bankHas(name, amount);
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return bankHandler.bankWithdraw(name, amount);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return bankHandler.bankDeposit(name, amount);
    }

    /**
     * [SYNC-VAULT-008] Atomic bank-to-bank transfer using Lua script.
     */
    public EconomyResponse bankTransfer(String fromBankName, String toBankName, double amount) {
        return bankHandler.bankTransfer(fromBankName, toBankName, amount);
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return bankHandler.isBankOwner(name, playerName);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bankHandler.isBankOwner(name, player);
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return bankHandler.isBankMember(name, playerName);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bankHandler.isBankMember(name, player);
    }

    @Override
    public java.util.List<String> getBanks() {
        return bankHandler.getBanks();
    }
}
