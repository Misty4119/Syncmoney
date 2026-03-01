package noietime.syncmoney.shadow;

import noietime.syncmoney.economy.EconomyFacade;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Rollback protection detection mechanism.
 * Prevents data loss when Syncmoney data overwrites CMI data.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class RollbackProtection {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final double threshold;


    private final java.util.concurrent.ConcurrentMap<UUID, BigDecimal> previousCMIBalances;

    public RollbackProtection(Plugin plugin, EconomyFacade economyFacade, double threshold) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.threshold = threshold;
        this.previousCMIBalances = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /**
     * Checks if writing Syncmoney balance to CMI is allowed.
     *
     * @param uuid player UUID
     * @param syncmoneyBalance Syncmoney current balance
     * @return true if write is allowed
     */
    public boolean canWriteToCMI(UUID uuid, BigDecimal syncmoneyBalance) {
        BigDecimal previousCMIBalance = previousCMIBalances.get(uuid);

        if (syncmoneyBalance.compareTo(BigDecimal.ZERO) <= 0) {
            if (previousCMIBalance != null && previousCMIBalance.compareTo(BigDecimal.ZERO) > 0) {
                plugin.getLogger().warning("RollbackProtection: Syncmoney balance is 0 but CMI has " + previousCMIBalance + ", blocking write for " + uuid);
                return false;
            }
        }

        if (previousCMIBalance != null && previousCMIBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = syncmoneyBalance.subtract(previousCMIBalance).abs();
            BigDecimal thresholdAmount = previousCMIBalance.multiply(BigDecimal.valueOf(threshold));

            if (diff.compareTo(thresholdAmount) > 0) {
                plugin.getLogger().warning("RollbackProtection: Difference " + diff + " exceeds threshold " + thresholdAmount + " for " + uuid + ", blocking write");
                return false;
            }
        }

        return true;
    }

    /**
     * Records CMI balance.
     *
     * @param uuid player UUID
     * @param balance CMI balance
     */
    public void recordCMIBalance(UUID uuid, BigDecimal balance) {
        previousCMIBalances.put(uuid, balance);
    }

    /**
     * Clears player's CMI balance record.
     *
     * @param uuid player UUID
     */
    public void clearCMIBalance(UUID uuid) {
        previousCMIBalances.remove(uuid);
    }

    /**
     * Clears all records.
     */
    public void clearAll() {
        previousCMIBalances.clear();
    }

    /**
     * Gets recorded CMI balance.
     *
     * @param uuid player UUID
     * @return previously recorded balance, or null if none
     */
    public BigDecimal getPreviousCMIBalance(UUID uuid) {
        return previousCMIBalances.get(uuid);
    }

    /**
     * Checks if sync is needed (balance mismatch).
     *
     * @param uuid player UUID
     * @param syncmoneyBalance Syncmoney current balance
     * @return true if sync is needed
     */
    public boolean needsSync(UUID uuid, BigDecimal syncmoneyBalance) {
        BigDecimal previousCMIBalance = previousCMIBalances.get(uuid);

        if (previousCMIBalance == null) {
            return true;
        }

        return syncmoneyBalance.compareTo(previousCMIBalance) != 0;
    }
}
