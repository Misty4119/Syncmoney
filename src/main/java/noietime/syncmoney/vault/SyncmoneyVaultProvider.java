package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SYNC-VAULT-001] Vault Economy API wrapper for Syncmoney.
 * Implements Vault Economy interface, internally delegates to EconomyFacade.
 * Provides BigDecimal conversion layer to avoid floating-point errors.
 *
 * [MainThread] Vault API is synchronous, all methods execute on main thread.
 */
public class SyncmoneyVaultProvider implements Economy {

    private static final String BANK_KEY_PREFIX = "syncmoney:bank:";
    private static final String BANK_OWNER_PREFIX = "syncmoney:bank:owner:";
    private static final String BANK_VERSION_PREFIX = "syncmoney:bank:version:";
    private static final long BANK_CACHE_DURATION_MS = 60000;

    private volatile String bankWithdrawScriptSha;
    private volatile String bankDepositScriptSha;
    private volatile String bankTransferScriptSha;

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    private final NameResolver nameResolver;
    private final String currencyName;
    private volatile boolean enabled = false;

    private CrossServerSyncManager syncManager;

    private SyncmoneyConfig config;

    private final ConcurrentHashMap<UUID, TransferContext> pendingTransfers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, List<RecentWithdrawal>> recentWithdrawals = new ConcurrentHashMap<>();

    /**
     * Recent withdrawal for transfer correlation.
     */
    private record RecentWithdrawal(
        UUID fromUuid,
        BigDecimal amount,
        String sourcePlugin,
        long timestamp
    ) {}

    /**
     * Transfer context for linking withdraw + deposit as a single transaction.
     */
    private record TransferContext(
        UUID fromUuid,
        UUID toUuid,
        BigDecimal amount,
        String sourcePlugin,
        long timestamp
    ) {}

    private final ConcurrentHashMap<String, CacheEntry> bankBalanceCache = new ConcurrentHashMap<>();

    /**
     * Bank balance cache entry.
     */
    private record CacheEntry(double balance, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > BANK_CACHE_DURATION_MS;
        }
    }

    public SyncmoneyVaultProvider(Plugin plugin, EconomyFacade economyFacade, NameResolver nameResolver) {
        this(plugin, economyFacade, null, nameResolver);
    }

    public SyncmoneyVaultProvider(Plugin plugin, EconomyFacade economyFacade, RedisManager redisManager,
            NameResolver nameResolver) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
        this.nameResolver = nameResolver;
        this.currencyName = "Syncmoney";
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
     * Safely obtains OfflinePlayer, avoiding synchronous IO.
     * Prioritizes cache lookup from NameResolver, falls back to
     * getOfflinePlayerIfCached.
     */
    private OfflinePlayer getOfflinePlayerSafe(String playerName) {
        return plugin.getServer().getOfflinePlayerIfCached(playerName);
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
                enabled = true;
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

            enabled = true;
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

    @Override
    public boolean isEnabled() {
        return enabled;
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
        return hasAccount(getOfflinePlayerSafe(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        return !economyFacade.isPlayerLocked(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(getOfflinePlayerSafe(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null)
            return 0.0;
        UUID uuid = player.getUniqueId();

        if (economyFacade.isPlayerLocked(uuid)) {
            return 0.0;
        }

        if (economyFacade.hasInMemory(uuid)) {
            return economyFacade.getBalance(uuid).doubleValue();
        }

        try {
            BigDecimal balance = economyFacade.getBalanceSync(uuid);
            return balance.doubleValue();
        } catch (Exception e) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                economyFacade.getBalance(uuid);
            });
            plugin.getLogger().warning("Failed to get balance sync for " + uuid + ", returning 0: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Gets balance as BigDecimal (internal use).
     */
    public BigDecimal getBalanceAsBigDecimal(OfflinePlayer player) {
        if (player == null)
            return BigDecimal.ZERO;

        UUID uuid = player.getUniqueId();
        if (economyFacade.isPlayerLocked(uuid)) {
            return BigDecimal.ZERO;
        }

        return economyFacade.getBalance(uuid);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(getOfflinePlayerSafe(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (player == null)
            return false;
        UUID uuid = player.getUniqueId();
        BigDecimal amountBd = NumericUtil.normalize(amount);

        return getBalance(player) >= amountBd.doubleValue();
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(getOfflinePlayerSafe(playerName), amount);
    }

    @Override
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
            String sourcePlugin = detectCallingPlugin();
            pendingTransfers.put(toUuid, new TransferContext(
                uuid, toUuid, amountBd, sourcePlugin, System.currentTimeMillis()
            ));
        } else {
            String sourcePlugin = detectCallingPlugin();
            RecentWithdrawal withdrawal = new RecentWithdrawal(uuid, amountBd, sourcePlugin, System.currentTimeMillis());
            recentWithdrawals.compute(uuid, (k, list) -> {
                if (list == null) {
                    list = new java.util.ArrayList<>();
                }
                list.add(withdrawal);
                long cutoff = System.currentTimeMillis() - 30000;
                list.removeIf(w -> w.timestamp() < cutoff);
                return list;
            });
        }

        if (player.isOnline()) {
            final org.bukkit.entity.Player finalOnlinePlayer = (org.bukkit.entity.Player) player;
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
            String sourcePlugin = detectCallingPlugin();
            syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "VAULT_WITHDRAW",
                    -amount,
                    sourcePlugin);
        }

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
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
        return depositPlayer(getOfflinePlayerSafe(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amount");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);

        UUID uuid = player.getUniqueId();

        TransferContext pendingTransfer = pendingTransfers.get(uuid);

        if (pendingTransfer == null) {
            pendingTransfer = findCorrelatedTransfer(uuid, amountBd);
        }

        if (pendingTransfer == null) {
            plugin.getLogger().warning("Rejecting orphan VAULT_DEPOSIT: no corresponding withdrawal found for " + uuid + 
                " amount " + amountBd + ". This prevents potential money duplication.");
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, 
                "No corresponding withdrawal found - transaction rejected to prevent money duplication");
        }

        if (economyFacade.isPlayerLocked(uuid)) {
            if (pendingTransfer != null) {
                plugin.getLogger().warning("Deposit failed: target account locked. Rolling back transfer: " +
                    pendingTransfer.fromUuid() + " -> " + pendingTransfer.toUuid());
                rollbackTransfer(pendingTransfer);
                pendingTransfers.remove(uuid);
            }
            return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE,
                    "Target account is locked");
        }

        BigDecimal newBalance = economyFacade.deposit(uuid, amountBd, EconomyEvent.EventSource.VAULT_DEPOSIT);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            if (pendingTransfer != null) {
                plugin.getLogger().warning("Deposit failed: deposit rejected. Rolling back transfer: " +
                    pendingTransfer.fromUuid() + " -> " + pendingTransfer.toUuid());
                rollbackTransfer(pendingTransfer);
                pendingTransfers.remove(uuid);
            }
            return new EconomyResponse(0, economyFacade.getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Failed to deposit");
        }

        if (pendingTransfer != null) {
            pendingTransfers.remove(uuid);
            recentWithdrawals.compute(pendingTransfer.fromUuid(), (k, list) -> {
                if (list != null) {
                    list.removeIf(w -> w.amount().compareTo(amountBd) == 0);
                }
                return list;
            });
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
                    MessageHelper.sendMessage(finalOnlinePlayer, message);
                }
            }
        }

        if (syncManager != null && config != null && config.isSyncMode()) {
            String sourcePlugin = detectCallingPlugin();
            syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "VAULT_DEPOSIT",
                    amount,
                    sourcePlugin);
        }

        return new EconomyResponse(amount, newBalance.doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
    }

    /**
     * [FIX-003] Execute atomic transfer using Redis Lua script.
     * This ensures withdraw and deposit happen atomically - both succeed or both fail.
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
                final org.bukkit.entity.Player finalOnlinePlayer = (org.bukkit.entity.Player) player;
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

            org.bukkit.entity.Player receiverPlayer = plugin.getServer().getPlayer(toUuid);
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
                String sourcePlugin = detectCallingPlugin();
                syncManager.publishAndNotify(fromUuid, result.fromNewBalance, "VAULT_WITHDRAW", -amountBd.doubleValue(), sourcePlugin);
                syncManager.publishAndNotify(toUuid, result.toNewBalance, "VAULT_DEPOSIT", amountBd.doubleValue(), sourcePlugin);
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
     * [SYNC-VAULT-012] Rollback a transfer when deposit fails.
     * Returns the withdrawn amount back to the sender.
     */
    private void rollbackTransfer(TransferContext transfer) {
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
     * [SYNC-VAULT-004] Find a correlated withdrawal that matches this deposit.
     * Used for automatic rollback when third-party plugins call withdraw + deposit separately.
     *
     * @param toUuid The receiver's UUID
     * @param amount The deposit amount
     * @return TransferContext if found, null otherwise
     */
    private TransferContext findCorrelatedTransfer(UUID toUuid, BigDecimal amount) {
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

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    /**
     * Detects the calling plugin (using StackWalker).
     */
    private String detectCallingPlugin() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(stream -> stream
                        .map(StackWalker.StackFrame::getClassName)
                        .filter(this::isKnownPluginClass)
                        .findFirst()
                        .map(this::extractPluginName)
                        .orElse("Unknown"));
    }

    /**
     * [SYNC-VAULT-002] Checks if the class is a known plugin class.
     * Uses config for plugin class list.
     */
    private boolean isKnownPluginClass(String className) {
        List<String> knownClasses = config.getKnownPluginClasses();
        if (knownClasses == null || knownClasses.isEmpty()) {

            knownClasses = List.of(
                    "QuickShop-Hikari", "PlayerPoints", "Jobs", "DailyShop",
                    "CrateReloaded", "zAuctionHouse", "BankSystem", "TokenManager");
        }
        return knownClasses.stream().anyMatch(className::contains);
    }

    /**
     * Extracts plugin name from class name.
     */
    private String extractPluginName(String className) {
        String[] parts = className.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return "Unknown";
    }

    /**
     * Checks if bank system is available.
     */
    private boolean isBankAvailable() {
        return redisManager != null && !redisManager.isDegraded();
    }

    /**
     * [SYNC-VAULT-003] Load bank Lua scripts if not already loaded.
     */
    private void ensureBankScriptsLoaded(Jedis jedis) {
        try {
            if (bankWithdrawScriptSha == null) {
                String script = loadScript("atomic_bank_withdraw.lua");
                bankWithdrawScriptSha = jedis.scriptLoad(script);
            }
            if (bankDepositScriptSha == null) {
                String script = loadScript("atomic_bank_deposit.lua");
                bankDepositScriptSha = jedis.scriptLoad(script);
            }
            if (bankTransferScriptSha == null) {
                String script = loadScript("atomic_bank_transfer.lua");
                bankTransferScriptSha = jedis.scriptLoad(script);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load bank Lua scripts: " + e.getMessage());
        }
    }

    /**
     * [SYNC-VAULT-005] Load a Lua script from resources.
     */
    private String loadScript(String filename) {
        try (var is = plugin.getResource("lua/" + filename)) {
            if (is == null) {
                plugin.getLogger().severe("Lua script not found: " + filename);
                return null;
            }
            return new String(is.readAllBytes());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load Lua script " + filename + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(player);
        if (offlinePlayer == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "Player not found in cache. Use /spawn or wait for player to load first.");
        }
        return createBank(name, offlinePlayer);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        if (!isBankAvailable()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank system unavailable");
        }

        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
        }

        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();
        String ownerKey = BANK_OWNER_PREFIX + name.toLowerCase();
        String versionKey = BANK_VERSION_PREFIX + name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank already exists");
            }


            jedis.set(bankKey, "0");
            jedis.set(ownerKey, player.getUniqueId().toString());
            jedis.set(versionKey, "0");

            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to create bank: " + e.getMessage());
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        if (!isBankAvailable()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank system unavailable");
        }

        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }

            jedis.del(bankKey);
            jedis.del(BANK_OWNER_PREFIX + name.toLowerCase());
            jedis.del(BANK_VERSION_PREFIX + name.toLowerCase());

            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to delete bank: " + e.getMessage());
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        if (!isBankAvailable()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank system unavailable");
        }

        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();
        String cacheKey = name.toLowerCase();

        CacheEntry cached = bankBalanceCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return new EconomyResponse(cached.balance(), cached.balance(), EconomyResponse.ResponseType.SUCCESS, "");
        }

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }

            double balance = Double.parseDouble(jedis.get(bankKey));

            bankBalanceCache.put(cacheKey, new CacheEntry(balance, System.currentTimeMillis()));

            return new EconomyResponse(balance, balance, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        EconomyResponse response = bankBalance(name);
        if (response.type != EconomyResponse.ResponseType.SUCCESS) {
            return response;
        }

        if (response.amount >= amount) {
            return new EconomyResponse(amount, response.balance, EconomyResponse.ResponseType.SUCCESS, "");
        } else {
            return new EconomyResponse(0, response.balance, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        if (!isBankAvailable()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank system unavailable");
        }

        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amount");
        }

        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();
        String versionKey = BANK_VERSION_PREFIX + name.toLowerCase();
        String cacheKey = name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }


            ensureBankScriptsLoaded(jedis);


            try {
                @SuppressWarnings("unchecked")
                List<String> result = (List<String>) jedis.evalsha(
                        bankWithdrawScriptSha,
                        2,
                        bankKey, versionKey,
                        String.valueOf(amount)
                );


                double newBalance = Double.parseDouble(result.get(0));

                bankBalanceCache.remove(cacheKey);

                return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("INSUFFICIENT_FUNDS")) {
                    double currentBalance = Double.parseDouble(jedis.get(bankKey));
                    return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                            "Insufficient funds");
                }

                plugin.getLogger().warning(
                        "Bank withdrawal Lua script failed, falling back to non-atomic operation: " + e.getMessage());
                return bankWithdrawFallback(jedis, name, amount);
            }
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    /**
     * [SYNC-VAULT-006] Fallback method for bank withdrawal (non-atomic).
     */
    private EconomyResponse bankWithdrawFallback(Jedis jedis, String name, double amount) {
        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();
        String cacheKey = name.toLowerCase();

        try {
            double currentBalance = Double.parseDouble(jedis.get(bankKey));
            if (currentBalance < amount) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                        "Insufficient funds");
            }

            double newBalance = currentBalance - amount;
            jedis.set(bankKey, String.valueOf(newBalance));

            bankBalanceCache.remove(cacheKey);

            return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        if (!isBankAvailable()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank system unavailable");
        }

        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amount");
        }

        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();
        String versionKey = BANK_VERSION_PREFIX + name.toLowerCase();
        String cacheKey = name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }


            ensureBankScriptsLoaded(jedis);


            try {
                @SuppressWarnings("unchecked")
                List<String> result = (List<String>) jedis.evalsha(
                        bankDepositScriptSha,
                        2,
                        bankKey, versionKey,
                        String.valueOf(amount)
                );


                double newBalance = Double.parseDouble(result.get(0));

                bankBalanceCache.remove(cacheKey);

                return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
            } catch (Exception e) {

                plugin.getLogger().warning(
                        "Bank deposit Lua script failed, falling back to non-atomic operation: " + e.getMessage());
                return bankDepositFallback(jedis, name, amount);
            }
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    /**
     * [SYNC-VAULT-007] Fallback method for bank deposit (non-atomic).
     */
    private EconomyResponse bankDepositFallback(Jedis jedis, String name, double amount) {
        String bankKey = BANK_KEY_PREFIX + name.toLowerCase();
        String cacheKey = name.toLowerCase();

        try {
            double currentBalance = Double.parseDouble(jedis.get(bankKey));
            double newBalance = currentBalance + amount;
            jedis.set(bankKey, String.valueOf(newBalance));

            bankBalanceCache.remove(cacheKey);

            return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
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
        if (!isBankAvailable()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank system unavailable");
        }

        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot transfer negative amount");
        }

        if (fromBankName == null || toBankName == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank names cannot be null");
        }

        if (fromBankName.equalsIgnoreCase(toBankName)) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot transfer to the same bank");
        }

        String fromBankKey = BANK_KEY_PREFIX + fromBankName.toLowerCase();
        String toBankKey = BANK_KEY_PREFIX + toBankName.toLowerCase();
        String fromVersionKey = BANK_VERSION_PREFIX + fromBankName.toLowerCase();
        String toVersionKey = BANK_VERSION_PREFIX + toBankName.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {

            if (!jedis.exists(fromBankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Source bank does not exist");
            }
            if (!jedis.exists(toBankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                        "Destination bank does not exist");
            }


            ensureBankScriptsLoaded(jedis);


            try {
                @SuppressWarnings("unchecked")
                List<String> result = (List<String>) jedis.evalsha(
                        bankTransferScriptSha,
                        4,
                        fromBankKey, toBankKey, fromVersionKey, toVersionKey,
                        String.valueOf(amount)
                );


                double newSourceBalance = Double.parseDouble(result.get(0));
                double newDestBalance = Double.parseDouble(result.get(1));


                bankBalanceCache.remove(fromBankName.toLowerCase());
                bankBalanceCache.remove(toBankName.toLowerCase());

                return new EconomyResponse(amount, newSourceBalance, EconomyResponse.ResponseType.SUCCESS, "");
            } catch (Exception e) {

                plugin.getLogger().warning(
                        "Bank transfer Lua script failed, falling back to non-atomic operation: " + e.getMessage());
                return bankTransferFallback(jedis, fromBankName, toBankName, amount);
            }
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    /**
     * [SYNC-VAULT-009] Fallback method for bank transfer (non-atomic).
     */
    private EconomyResponse bankTransferFallback(Jedis jedis, String fromBankName, String toBankName, double amount) {
        String fromBankKey = BANK_KEY_PREFIX + fromBankName.toLowerCase();
        String toBankKey = BANK_KEY_PREFIX + toBankName.toLowerCase();

        try {
            double currentSourceBalance = Double.parseDouble(jedis.get(fromBankKey));
            double currentDestBalance = Double.parseDouble(jedis.get(toBankKey));

            if (currentSourceBalance < amount) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            }

            double newSourceBalance = currentSourceBalance - amount;
            double newDestBalance = currentDestBalance + amount;

            jedis.set(fromBankKey, String.valueOf(newSourceBalance));
            jedis.set(toBankKey, String.valueOf(newDestBalance));


            bankBalanceCache.remove(fromBankName.toLowerCase());
            bankBalanceCache.remove(toBankName.toLowerCase());

            return new EconomyResponse(amount, newSourceBalance, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return isBankOwner(name, plugin.getServer().getOfflinePlayer(playerName));
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        if (!isBankAvailable() || player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "Bank system unavailable or player not found");
        }

        String ownerKey = BANK_OWNER_PREFIX + name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(ownerKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }

            String ownerUuid = jedis.get(ownerKey);
            if (ownerUuid.equals(player.getUniqueId().toString())) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
            } else {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is not the bank owner");
            }
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return isBankOwner(name, playerName);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return isBankOwner(name, player);
    }

    @Override
    public List<String> getBanks() {
        if (!isBankAvailable()) {
            return List.of();
        }

        try (Jedis jedis = redisManager.getResource()) {
            List<String> banks = new ArrayList<>();
            ScanParams scanParams = new ScanParams().match(BANK_KEY_PREFIX + "*").count(100);

            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                List<String> keys = scanResult.getResult();

                for (String key : keys) {
                    String bankName = key.substring(BANK_KEY_PREFIX.length());
                    banks.add(bankName);
                }

                cursor = scanResult.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            return banks;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get banks: " + e.getMessage());
            return List.of();
        }
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
}
