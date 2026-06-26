package noietime.syncmoney.expansion;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the numeric output formats backing the Syncmoney placeholders.
 *
 * <p>The locale is pinned to {@link Locale#US} so the grouping/decimal separators
 * are deterministic. These formats feed the placeholders:
 * <ul>
 *   <li>{@code %syncmoney_balance%} / {@code %syncmoney_top_<n>%} -> formatCurrency</li>
 *   <li>{@code %syncmoney_balance_formatted%} -> formatCompact</li>
 *   <li>{@code %syncmoney_balance_abbreviated%} -> formatAbbreviated</li>
 * </ul>
 */
class SyncmoneyExpansionTest {

    private static Locale originalLocale;

    @BeforeAll
    static void pinLocale() {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void formatCurrency_usesThousandSeparatorAndTwoDecimals() {
        assertEquals("0.00", ExpansionFormatUtil.formatCurrency(0));
        assertEquals("1,234.50", ExpansionFormatUtil.formatCurrency(1234.5));
        assertEquals("1,000,000.00", ExpansionFormatUtil.formatCurrency(1_000_000));
    }

    @Test
    void formatCompact_usesSingleDecimalSuffixes() {
        assertEquals("1.5K", ExpansionFormatUtil.formatCompact(1_500));
        assertEquals("2.5M", ExpansionFormatUtil.formatCompact(2_500_000));
        assertEquals("3.2B", ExpansionFormatUtil.formatCompact(3_200_000_000.0));
    }

    @Test
    void formatCompact_belowThousandFallsBackToCurrency() {
        assertEquals("999.00", ExpansionFormatUtil.formatCompact(999));
    }

    @Test
    void formatAbbreviated_usesTwoDecimalSuffixes() {
        assertEquals("1.50K", ExpansionFormatUtil.formatAbbreviated(1_500));
        assertEquals("1.23M", ExpansionFormatUtil.formatAbbreviated(1_234_567));
        assertEquals("2.00B", ExpansionFormatUtil.formatAbbreviated(2_000_000_000.0));
    }

    @Test
    void formatAbbreviated_belowThousandFallsBackToCurrency() {
        assertEquals("12.00", ExpansionFormatUtil.formatAbbreviated(12));
    }
}
