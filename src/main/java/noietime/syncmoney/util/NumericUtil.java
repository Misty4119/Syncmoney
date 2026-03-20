package noietime.syncmoney.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility class for numeric operations, especially BigDecimal handling.
 * Provides centralized normalization to avoid scattered setScale calls.
 */
public final class NumericUtil {

    public static final BigDecimal ZERO = BigDecimal.ZERO;
    public static final BigDecimal ONE = BigDecimal.ONE;
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final BigDecimal NEGATIVE_ONE = BigDecimal.valueOf(-1);

    private NumericUtil() {}

    /**
     * Normalizes BigDecimal to 2 decimal places with HALF_UP rounding.
     * This is the standard for currency operations.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return ZERO;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Converts double to normalized BigDecimal.
     */
    public static BigDecimal normalize(double amount) {
        return normalize(BigDecimal.valueOf(amount));
    }

    /**
     * Converts String to normalized BigDecimal.
     */
    public static BigDecimal normalize(String amount) {
        if (amount == null || amount.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return normalize(new BigDecimal(amount));
    }

    /**
     * Checks if amount is valid (positive and not zero).
     */
    public static boolean isValidAmount(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Checks if amount is non-negative (zero or positive).
     */
    public static boolean isNonNegative(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) >= 0;
    }

    /**
     * Checks if balance is sufficient for withdrawal.
     */
    public static boolean hasSufficientBalance(BigDecimal balance, BigDecimal amount) {
        return balance != null && amount != null && balance.compareTo(amount) >= 0;
    }
}
