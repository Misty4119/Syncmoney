package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [SYNC-VAULT-001] Vault Economy API wrapper for Syncmoney.
 * Thin facade that delegates to VaultProviderCore.
 * Implements Vault Economy interface, internally delegates to EconomyFacade.
 * Provides BigDecimal conversion layer to avoid floating-point errors.
 *
 * [MainThread] Vault API is synchronous, all methods execute on main thread.
 */
public class SyncmoneyVaultProvider implements Economy {

    private final Plugin plugin;
    private final VaultProviderCore core;

    public SyncmoneyVaultProvider(Plugin plugin, EconomyFacade economyFacade, NameResolver nameResolver) {
        this(plugin, economyFacade, null, nameResolver);
    }

    public SyncmoneyVaultProvider(Plugin plugin, EconomyFacade economyFacade, RedisManager redisManager,
            NameResolver nameResolver) {
        this.plugin = plugin;

        VaultLuaScriptManager luaScriptManager = new VaultLuaScriptManager(plugin);
        VaultPluginDetector pluginDetector = new VaultPluginDetector(null);
        VaultPlayerHandler playerHandler = new VaultPlayerHandler(economyFacade, nameResolver, plugin);
        VaultTransferHandler transferHandler = new VaultTransferHandler(
                plugin, economyFacade, null, pluginDetector, null, playerHandler, nameResolver);
        VaultBankHandler bankHandler = new VaultBankHandler(plugin, redisManager, luaScriptManager);

        this.core = new VaultProviderCore(
                plugin, economyFacade,
                playerHandler, transferHandler, bankHandler,
                pluginDetector, luaScriptManager, nameResolver);
    }

    /**
     * Sets the sync manager.
     */
    public void setSyncManager(CrossServerSyncManager syncManager) {
        core.setSyncManager(syncManager);
    }

    /**
     * Sets the configuration.
     */
    public void setConfig(SyncmoneyConfig config) {
        core.setConfig(config);
    }

    /**
     * Initializes and registers Economy with Vault.
     *
     * @return true if registration succeeded
     */
    public boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault plugin not found. Economy integration disabled.");
            return false;
        }

        if (registerEconomy()) {
            plugin.getLogger().fine("Syncmoney registered as Vault Economy provider immediately.");
            scheduleDelayedRegistration();
            return true;
        }

        int maxRetries = 5;
        int retryDelayMs = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                plugin.getLogger().fine("Vault registration attempt " + attempt + "...");
                if (registerEconomy()) {
                    plugin.getLogger().fine("Syncmoney registered as Vault Economy provider on attempt " + attempt);
                    return true;
                }
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        plugin.getLogger().severe("Failed to register Vault Economy after " + maxRetries + " attempts.");
        return false;
    }

    /**
     * Register the economy system to Vault.
     */
    private boolean registerEconomy() {
        try {
            var existingProvider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (existingProvider != null && existingProvider.getProvider() instanceof SyncmoneyVaultProvider) {
                plugin.getLogger().fine("Syncmoney Vault Economy already registered.");
                core.setEnabled(true);
                return true;
            }

            if (existingProvider != null) {
                plugin.getServer().getServicesManager().unregister(Economy.class, existingProvider.getProvider());
                plugin.getLogger()
                        .fine("Unregistered existing economy provider: " + existingProvider.getProvider().getName());
            }

            plugin.getServer().getServicesManager().register(
                    Economy.class,
                    this,
                    plugin,
                    org.bukkit.plugin.ServicePriority.Highest);

            core.setEnabled(true);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Registration error: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Vault registration stacktrace", e);
            return false;
        }
    }

    /**
     * Delay registration to ensure other plugins can see the Syncmoney economy
     * system.
     * Uses Folia-compatible scheduling or falls back to immediate execution.
     */
    private void scheduleDelayedRegistration() {
        try {
            var server = plugin.getServer();

            if (hasFoliaScheduler(server)) {
                scheduleDelayedRegistrationFolia(server);
            } else {
                server.getScheduler().runTaskLater(plugin, () -> {
                    var existingProvider = server.getServicesManager().getRegistration(Economy.class);

                    if (existingProvider != null
                            && !(existingProvider.getProvider() instanceof SyncmoneyVaultProvider)) {
                        server.getServicesManager().unregister(Economy.class, existingProvider.getProvider());
                        server.getServicesManager().register(
                                Economy.class,
                                this,
                                plugin,
                                org.bukkit.plugin.ServicePriority.Highest);
                        plugin.getLogger().fine("Syncmoney Vault Economy re-registered for compatibility.");
                    }
                }, 20L);
            }
        } catch (UnsupportedOperationException e) {
            plugin.getLogger().fine("Deferred registration skipped, using initial registration.");
        }
    }

    /**
     * Check if server has Folia-style entity scheduler.
     */
    private boolean hasFoliaScheduler(org.bukkit.Server server) {
        try {
            server.getClass().getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodError | NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Folia-compatible delayed registration using global region scheduler.
     */
    private void scheduleDelayedRegistrationFolia(org.bukkit.Server server) {
        try {
            server.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                var existingProvider = server.getServicesManager().getRegistration(Economy.class);

                if (existingProvider != null && !(existingProvider.getProvider() instanceof SyncmoneyVaultProvider)) {
                    server.getServicesManager().unregister(Economy.class, existingProvider.getProvider());
                    server.getServicesManager().register(
                            Economy.class,
                            this,
                            plugin,
                            org.bukkit.plugin.ServicePriority.Highest);
                    plugin.getLogger().fine("Syncmoney Vault Economy re-registered for compatibility (Folia).");
                }
            }, 20L);
        } catch (Exception e) {
            plugin.getLogger().fine("Folia scheduling failed, using immediate registration.");
        }
    }

    // =========================================================================
    // Economy interface delegation
    // =========================================================================

    @Override
    public boolean isEnabled() {
        return core.isEnabled();
    }

    @Override
    public String getName() {
        return core.getName();
    }

    @Override
    public boolean hasBankSupport() {
        return core.hasBankSupport();
    }

    @Override
    public int fractionalDigits() {
        return core.fractionalDigits();
    }

    @Override
    public String format(double amount) {
        return core.format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return core.currencyNamePlural();
    }

    @Override
    public String currencyNameSingular() {
        return core.currencyNameSingular();
    }

    @Override
    public boolean hasAccount(String playerName) {
        return core.hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return core.hasAccount(player);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return core.hasAccount(playerName, worldName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return core.hasAccount(player, worldName);
    }

    @Override
    public double getBalance(String playerName) {
        return core.getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return core.getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return core.getBalance(playerName, world);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return core.getBalance(player, world);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return core.has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return core.has(player, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return core.has(playerName, worldName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return core.has(player, worldName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return core.withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return core.withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return core.withdrawPlayer(playerName, worldName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return core.withdrawPlayer(player, worldName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return core.depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return core.depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return core.depositPlayer(playerName, worldName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return core.depositPlayer(player, worldName, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return core.createBank(name, player);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return core.createBank(name, player);
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return core.deleteBank(name);
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return core.bankBalance(name);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return core.bankHas(name, amount);
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return core.bankWithdraw(name, amount);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return core.bankDeposit(name, amount);
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return core.isBankOwner(name, playerName);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return core.isBankOwner(name, player);
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return core.isBankMember(name, playerName);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return core.isBankMember(name, player);
    }

    @Override
    public List<String> getBanks() {
        return core.getBanks();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return core.createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return core.createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return core.createPlayerAccount(playerName, worldName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return core.createPlayerAccount(player, worldName);
    }

    // =========================================================================
    // Extended API methods (not in Economy interface)
    // =========================================================================

    /**
     * [SYNC-VAULT-011] Withdraw with optional transfer context for rollback support.
     *
     * @param player The player to withdraw from
     * @param amount Amount to withdraw
     * @param toUuid Optional target UUID for transfer tracking (used for rollback)
     */
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount, UUID toUuid) {
        return core.withdrawPlayer(player, amount, toUuid);
    }

    /**
     * Gets balance as BigDecimal (internal use).
     */
    public java.math.BigDecimal getBalanceAsBigDecimal(OfflinePlayer player) {
        return core.getBalanceAsBigDecimal(player);
    }

    /**
     * [SYNC-VAULT-008] Atomic bank-to-bank transfer using Lua script.
     *
     * @param fromBankName source bank name
     * @param toBankName   destination bank name
     * @param amount       transfer amount
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse bankTransfer(String fromBankName, String toBankName, double amount) {
        return core.bankTransfer(fromBankName, toBankName, amount);
    }

    /**
     * [SYNC-VAULT-015] Deposit for plugin use - bypasses Vault pairing logic.
     * Third-party plugins (e.g., chest shops) should call this directly instead
     * of the Vault Economy API when they need to ensure plugin-level attribution.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param player Target player
     * @param amount Amount to deposit (positive)
     * @param pluginName Calling plugin's name (for audit trail)
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse depositPlayerForPlugin(OfflinePlayer player, double amount, String pluginName) {
        return core.depositPlayerForPlugin(player, amount, pluginName);
    }

    /**
     * [SYNC-VAULT-016] Withdraw for plugin use - bypasses Vault pairing logic.
     * Third-party plugins (e.g., chest shops) should call this directly instead
     * of the Vault Economy API when they need to ensure plugin-level attribution.
     *
     * [AsyncScheduler] Must be called from async thread.
     *
     * @param player Target player
     * @param amount Amount to withdraw (positive)
     * @param pluginName Calling plugin's name (for audit trail)
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse withdrawPlayerForPlugin(OfflinePlayer player, double amount, String pluginName) {
        return core.withdrawPlayerForPlugin(player, amount, pluginName);
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
     * @param pluginName Calling plugin's name
     * @return EconomyResponse with transaction result
     */
    public EconomyResponse pluginTransfer(OfflinePlayer from, OfflinePlayer to, double amount, String pluginName) {
        return core.pluginTransfer(from, to, amount, pluginName);
    }
}
