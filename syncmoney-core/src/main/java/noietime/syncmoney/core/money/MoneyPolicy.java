package noietime.syncmoney.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared currency normalization and account-balance invariants. */
public final class MoneyPolicy {

    public static final BigDecimal ZERO = BigDecimal.ZERO;
    public static final BigDecimal MAX_BALANCE =
            new BigDecimal("999999999999999999999999999999999999.99");

    private MoneyPolicy() { }

    /** Normalizes currency to two decimal places using the existing HALF_UP rule. */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static boolean isValidAmount(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    public static boolean isNonNegative(BigDecimal amount) {
        return amount != null && amount.signum() >= 0;
    }

    public static boolean isValidBalance(BigDecimal balance) {
        return balance != null && balance.signum() >= 0 && balance.compareTo(MAX_BALANCE) <= 0;
    }

    public static boolean hasSufficientBalance(BigDecimal balance, BigDecimal amount) {
        return balance != null && amount != null && balance.compareTo(amount) >= 0;
    }

    /** Returns whether adding the amount keeps the result within the existing balance bound. */
    public static boolean canAdd(BigDecimal balance, BigDecimal amount) {
        return balance != null && amount != null
                && balance.add(amount).compareTo(MAX_BALANCE) <= 0;
    }
}
