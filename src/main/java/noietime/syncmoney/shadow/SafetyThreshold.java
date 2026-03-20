package noietime.syncmoney.shadow;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Safety-Threshold emergency lock mechanism.
 * Prevents abnormal balance writes to CMI database.
 *
 * [ThreadSafe] This class is thread-safe utility class.
 */
public final class SafetyThreshold {

    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000");

    private static final BigDecimal MIN_BALANCE = BigDecimal.ZERO;

    private static final double INFLATION_THRESHOLD = 100.0;

    private SafetyThreshold() {
    }

    /**
     * Checks if balance passes Safety-Threshold.
     *
     * @param currentBalance current balance (Syncmoney)
     * @param previousBalance previous balance (CMI)
     * @return true if check passes
     */
    public static boolean check(BigDecimal currentBalance, BigDecimal previousBalance) {
        if (currentBalance.compareTo(MIN_BALANCE) <= 0) {
            return false;
        }

        if (currentBalance.compareTo(MAX_BALANCE) > 0) {
            return false;
        }

        if (previousBalance != null && previousBalance.compareTo(BigDecimal.ZERO) > 0) {
            double ratio = currentBalance.divide(previousBalance, 2, RoundingMode.HALF_UP).doubleValue();
            if (ratio > INFLATION_THRESHOLD) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if balance exceeds maximum.
     *
     * @param balance balance
     * @return true if exceeds maximum
     */
    public static boolean isOverMax(BigDecimal balance) {
        return balance.compareTo(MAX_BALANCE) > 0;
    }

    /**
     * Checks if balance is below minimum.
     *
     * @param balance balance
     * @return true if below minimum
     */
    public static boolean isUnderMin(BigDecimal balance) {
        return balance.compareTo(MIN_BALANCE) <= 0;
    }

    /**
     * Calculates balance inflation ratio.
     *
     * @param currentBalance current balance
     * @param previousBalance previous balance
     * @return ratio (returns -1 if cannot be calculated)
     */
    public static double calculateInflationRatio(BigDecimal currentBalance, BigDecimal previousBalance) {
        if (previousBalance == null || previousBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return -1;
        }
        return currentBalance.divide(previousBalance, 2, RoundingMode.HALF_UP).doubleValue();
    }
}
