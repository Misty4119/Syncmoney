package noietime.syncmoney.baltop;

import noietime.syncmoney.util.FormatUtil;

import java.util.UUID;

/**
 * Leaderboard entry.
 *
 * [ThreadSafe] This class is immutable and thread-safe.
 */
public record RankEntry(

        int rank,

        UUID uuid,

        String name,

        double balance
) {
    /**
     * Get formatted balance.
     */
    public String getFormattedBalance() {
        return FormatUtil.formatCurrency(balance);
    }

    /**
     * Check if valid (has name).
     */
    public boolean isValid() {
        return name != null && !name.isEmpty();
    }
}
