package noietime.syncmoney.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyPolicyTest {

    @Test
    void normalizesCurrencyWithHalfUpAtTwoDecimalPlaces() {
        assertEquals(new BigDecimal("1.01"), MoneyPolicy.normalize(new BigDecimal("1.005")));
    }

    @Test
    void rejectsInvalidAmountsAndBalancesBeyondTheExistingBound() {
        assertFalse(MoneyPolicy.isValidAmount(null));
        assertFalse(MoneyPolicy.isValidAmount(BigDecimal.ZERO));
        assertFalse(MoneyPolicy.isValidAmount(new BigDecimal("-0.01")));
        assertTrue(MoneyPolicy.isValidAmount(new BigDecimal("0.01")));
        assertTrue(MoneyPolicy.isValidBalance(MoneyPolicy.MAX_BALANCE));
        assertFalse(MoneyPolicy.isValidBalance(MoneyPolicy.MAX_BALANCE.add(new BigDecimal("0.01"))));
    }

    @Test
    void allowsZeroButRejectsNegativeBalances() {
        assertTrue(MoneyPolicy.isNonNegative(BigDecimal.ZERO));
        assertTrue(MoneyPolicy.isNonNegative(new BigDecimal("0.01")));
        assertFalse(MoneyPolicy.isNonNegative(new BigDecimal("-0.01")));
        assertFalse(MoneyPolicy.isNonNegative(null));
    }

    @Test
    void checksWithdrawalAndDepositInvariants() {
        assertTrue(MoneyPolicy.hasSufficientBalance(new BigDecimal("10.00"), new BigDecimal("10.00")));
        assertFalse(MoneyPolicy.hasSufficientBalance(new BigDecimal("9.99"), new BigDecimal("10.00")));
        assertTrue(MoneyPolicy.canAdd(new BigDecimal("1.00"), new BigDecimal("2.00")));
        assertFalse(MoneyPolicy.canAdd(MoneyPolicy.MAX_BALANCE, new BigDecimal("0.01")));
    }

    @Test
    void enforcesSharedSqlDecimal20_2CapacityWithoutChangingLocalPolicy() {
        BigDecimal sharedMaximum = new BigDecimal("999999999999999999.99");
        assertEquals(sharedMaximum, MoneyPolicy.MAX_SHARED_BALANCE);
        assertTrue(MoneyPolicy.canAddWithin(
                sharedMaximum.subtract(new BigDecimal("0.01")), new BigDecimal("0.01"), sharedMaximum));
        assertFalse(MoneyPolicy.canAddWithin(sharedMaximum, new BigDecimal("0.01"), sharedMaximum));
        assertTrue(MoneyPolicy.isValidBalance(MoneyPolicy.MAX_BALANCE),
                "the shared storage bound must not narrow the existing LOCAL policy ceiling");
    }
}
