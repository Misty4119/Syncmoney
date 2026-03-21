package noietime.syncmoney.vault;

import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * [SYNC-VAULT-010] Handles player account operations.
 * Player account existence checks, balance queries, and balance validation.
 */
public class VaultPlayerHandler {

    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;
    private final Plugin plugin;

    public VaultPlayerHandler(EconomyFacade economyFacade, NameResolver nameResolver, Plugin plugin) {
        this.economyFacade = economyFacade;
        this.nameResolver = nameResolver;
        this.plugin = plugin;
    }

    /**
     * Safely obtains OfflinePlayer, avoiding synchronous IO.
     * Prioritizes cache lookup from NameResolver, falls back to
     * getOfflinePlayerIfCached.
     */
    public OfflinePlayer getOfflinePlayerSafe(String playerName) {
        return plugin.getServer().getOfflinePlayerIfCached(playerName);
    }

    /**
     * Checks if player has an account (is not locked).
     */
    public boolean hasAccount(String playerName) {
        return hasAccount(getOfflinePlayerSafe(playerName));
    }

    /**
     * Checks if player has an account (is not locked).
     */
    public boolean hasAccount(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        return !economyFacade.isPlayerLocked(player.getUniqueId());
    }

    /**
     * Checks if player has an account (world variant).
     */
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    /**
     * Checks if player has an account (world variant).
     */
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    /**
     * Gets player balance by name.
     */
    public double getBalance(String playerName) {
        return getBalance(getOfflinePlayerSafe(playerName));
    }

    /**
     * Gets player balance.
     */
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

    /**
     * Gets player balance with world name.
     */
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    /**
     * Gets player balance with world name.
     */
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    /**
     * Checks if player has a specific amount.
     */
    public boolean has(String playerName, double amount) {
        return has(getOfflinePlayerSafe(playerName), amount);
    }

    /**
     * Checks if player has a specific amount.
     */
    public boolean has(OfflinePlayer player, double amount) {
        if (player == null)
            return false;
        UUID uuid = player.getUniqueId();
        BigDecimal amountBd = NumericUtil.normalize(amount);

        return getBalance(player) >= amountBd.doubleValue();
    }

    /**
     * Checks if player has a specific amount (world variant).
     */
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    /**
     * Checks if player has a specific amount (world variant).
     */
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }
}
