package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

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
    private static final long BANK_CACHE_DURATION_MS = 60000; // 60 seconds

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    private final NameResolver nameResolver;
    private final String currencyName;
    private volatile boolean enabled = false;

    private CrossServerSyncManager syncManager;

    private SyncmoneyConfig config;

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
     * Prioritizes cache lookup from NameResolver, falls back to getOfflinePlayerIfCached.
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
                plugin.getLogger().fine("Unregistered existing economy provider: " + existingProvider.getProvider().getName());
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
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Delay registration to ensure other plugins can see the Syncmoney economy system.
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

                    if (existingProvider != null && !(existingProvider.getProvider() instanceof SyncmoneyVaultProvider)) {
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
        return player != null;
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

        if (!economyFacade.hasInMemory(uuid)) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                economyFacade.getBalance(uuid);
            });
            return 0.0;
        }

        return economyFacade.getBalance(uuid).doubleValue();
    }

    /**
     * Gets balance as BigDecimal (internal use).
     */
    public BigDecimal getBalanceAsBigDecimal(OfflinePlayer player) {
        if (player == null)
            return BigDecimal.ZERO;
        return economyFacade.getBalance(player.getUniqueId());
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

        if (!economyFacade.hasInMemory(uuid)) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                economyFacade.getBalance(uuid);
            });
        }

        BigDecimal balance = economyFacade.getBalance(uuid);
        return balance.compareTo(amountBd) >= 0;
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
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amount");
        }

        BigDecimal amountBd = NumericUtil.normalize(amount);

        UUID uuid = player.getUniqueId();

        BigDecimal newBalance = economyFacade.withdraw(uuid, amountBd, EconomyEvent.EventSource.VAULT_WITHDRAW);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal currentBalance = economyFacade.getBalance(uuid);
            return new EconomyResponse(0, currentBalance.doubleValue(), EconomyResponse.ResponseType.FAILURE,
                    "Insufficient funds");
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
                sourcePlugin
            );
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

        BigDecimal newBalance = economyFacade.deposit(uuid, amountBd, EconomyEvent.EventSource.VAULT_DEPOSIT);

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
                sourcePlugin
            );
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
                .orElse("Unknown")
            );
    }

    /**
     * Checks if the class is a known plugin class.
     */
    private boolean isKnownPluginClass(String className) {
        return className.contains("QuickShop-Hikari") ||
               className.contains("PlayerPoints") ||
               className.contains("Jobs") ||
               className.contains("DailyShop") ||
               className.contains("CrateReloaded") ||
               className.contains("zAuctionHouse") ||
               className.contains("BankSystem") ||
               className.contains("TokenManager");
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

    @Override
    public EconomyResponse createBank(String name, String player) {
        return createBank(name, plugin.getServer().getOfflinePlayer(player));
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

        try (Jedis jedis = redisManager.getResource()) {
            if (jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank already exists");
            }

            jedis.set(bankKey, "0");
            jedis.set(ownerKey, player.getUniqueId().toString());

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
        String cacheKey = name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }

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
        String cacheKey = name.toLowerCase();

        try (Jedis jedis = redisManager.getResource()) {
            if (!jedis.exists(bankKey)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank does not exist");
            }

            double currentBalance = Double.parseDouble(jedis.get(bankKey));
            double newBalance = currentBalance + amount;
            jedis.set(bankKey, String.valueOf(newBalance));

            bankBalanceCache.remove(cacheKey);

            return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
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
            Set<String> keys = jedis.keys(BANK_KEY_PREFIX + "*");
            List<String> banks = new ArrayList<>();
            for (String key : keys) {
                String bankName = key.substring(BANK_KEY_PREFIX.length());
                banks.add(bankName);
            }
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
