package noietime.syncmoney.expansion;

import java.text.DecimalFormat;

/**
 * [SYNC-PAPI-031] Local formatting utilities for PAPI Expansion.
 * Provides number formatting methods to avoid scattered String.format calls.
 */
public final class ExpansionFormatUtil {

    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,##0.00");
    private static final DecimalFormat COMPACT_FORMAT = new DecimalFormat("#,##0.#");

    private ExpansionFormatUtil() {}

    /**
     * [SYNC-PAPI-032] Formats a number with thousand separators.
     */
    public static String formatCurrency(double value) {
        return CURRENCY_FORMAT.format(value);
    }

    /**
     * [SYNC-PAPI-033] Formats a number as percentage.
     */
    public static String formatPercent(double value) {
        return COMPACT_FORMAT.format(value) + "%";
    }

    /**
     * [SYNC-PAPI-034] Formats a number in compact form (K, M, B).
     */
    public static String formatCompact(double value) {
        if (value >= 1_000_000_000) {
            return String.format("%.1fB", value / 1_000_000_000);
        } else if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000);
        }
        return formatCurrency(value);
    }

    /**
     * [SYNC-PAPI-035] Formats a number in abbreviated form (K, M, B) with 2 decimal places.
     */
    public static String formatAbbreviated(double value) {
        if (value >= 1_000_000_000) {
            return String.format("%.2fB", value / 1_000_000_000);
        } else if (value >= 1_000_000) {
            return String.format("%.2fM", value / 1_000_000);
        } else if (value >= 1_000) {
            return String.format("%.2fK", value / 1_000);
        }
        return formatCurrency(value);
    }
}
