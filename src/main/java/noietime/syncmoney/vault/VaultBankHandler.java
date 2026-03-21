package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SYNC-VAULT-013] Handles bank operations.
 * Bank creation, deletion, balance queries, and transactions using Redis Lua scripts.
 */
public class VaultBankHandler {

    private static final String BANK_KEY_PREFIX = "syncmoney:bank:";
    private static final String BANK_OWNER_PREFIX = "syncmoney:bank:owner:";
    private static final String BANK_VERSION_PREFIX = "syncmoney:bank:version:";
    private static final long BANK_CACHE_DURATION_MS = 60000;

    private final ConcurrentHashMap<String, CacheEntry> bankBalanceCache = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final RedisManager redisManager;
    private final VaultLuaScriptManager luaScriptManager;

    /**
     * Bank balance cache entry.
     */
    public record CacheEntry(double balance, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > BANK_CACHE_DURATION_MS;
        }
    }

    public VaultBankHandler(Plugin plugin, RedisManager redisManager, VaultLuaScriptManager luaScriptManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.luaScriptManager = luaScriptManager;
    }

    /**
     * Checks if bank system is available.
     */
    public boolean isBankAvailable() {
        return redisManager != null && !redisManager.isDegraded();
    }

    /**
     * [SYNC-VAULT-003] Load bank Lua scripts if not already loaded.
     */
    public void ensureBankScriptsLoaded(Jedis jedis) {
        try {
            if (luaScriptManager.getBankWithdrawScriptSha() == null) {
                String script = luaScriptManager.loadScript("atomic_bank_withdraw.lua");
                if (script != null) {
                    luaScriptManager.setBankWithdrawScriptSha(jedis.scriptLoad(script));
                }
            }
            if (luaScriptManager.getBankDepositScriptSha() == null) {
                String script = luaScriptManager.loadScript("atomic_bank_deposit.lua");
                if (script != null) {
                    luaScriptManager.setBankDepositScriptSha(jedis.scriptLoad(script));
                }
            }
            if (luaScriptManager.getBankTransferScriptSha() == null) {
                String script = luaScriptManager.loadScript("atomic_bank_transfer.lua");
                if (script != null) {
                    luaScriptManager.setBankTransferScriptSha(jedis.scriptLoad(script));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load bank Lua scripts: " + e.getMessage());
        }
    }

    /**
     * Creates a bank account.
     */
    public EconomyResponse createBank(String name, String player) {
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(player);
        if (offlinePlayer == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "Player not found in cache. Use /spawn or wait for player to load first.");
        }
        return createBank(name, offlinePlayer);
    }

    /**
     * Creates a bank account.
     */
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

    /**
     * Deletes a bank account.
     */
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

    /**
     * Gets bank balance.
     */
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

    /**
     * Checks if bank has a specific amount.
     */
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

    /**
     * Withdraws from a bank.
     */
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
                        luaScriptManager.getBankWithdrawScriptSha(),
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
    public EconomyResponse bankWithdrawFallback(Jedis jedis, String name, double amount) {
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

    /**
     * Deposits to a bank.
     */
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
                        luaScriptManager.getBankDepositScriptSha(),
                        2,
                        bankKey, versionKey,
                        String.valueOf(amount)
                );

                double newBalance = Double.parseDouble(result.get(0));

                bankBalanceCache.remove(cacheKey);

                return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "");
            } catch ( Exception e) {
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
    public EconomyResponse bankDepositFallback(Jedis jedis, String name, double amount) {
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
                        luaScriptManager.getBankTransferScriptSha(),
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
    public EconomyResponse bankTransferFallback(Jedis jedis, String fromBankName, String toBankName, double amount) {
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

    /**
     * Checks if player is bank owner.
     */
    public EconomyResponse isBankOwner(String name, String playerName) {
        return isBankOwner(name, plugin.getServer().getOfflinePlayer(playerName));
    }

    /**
     * Checks if player is bank owner.
     */
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

    /**
     * Checks if player is bank member.
     */
    public EconomyResponse isBankMember(String name, String playerName) {
        return isBankOwner(name, playerName);
    }

    /**
     * Checks if player is bank member.
     */
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return isBankOwner(name, player);
    }

    /**
     * Gets list of all banks.
     */
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

    /**
     * Clears the bank balance cache.
     */
    public void clearCache() {
        bankBalanceCache.clear();
    }
}
