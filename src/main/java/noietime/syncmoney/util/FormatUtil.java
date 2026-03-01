package noietime.syncmoney.util;

import java.math.BigDecimal;

/**
 * Utility class for formatting operations.
 * Provides centralized formatting to avoid scattered String.format calls.
 */
public final class FormatUtil {

    private FormatUtil() {}

    /**
     * Formats amount as currency with thousand separators.
     * Example: 1234567.89 -> "1,234,567.89"
     */
    public static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return String.format("%,.2f", amount);
    }

    /**
     * Formats amount as currency (double overload).
     */
    public static String formatCurrency(double amount) {
        return String.format("%,.2f", amount);
    }

    /**
     * Formats percentage with 1 decimal place and % symbol.
     * Example: 45.678 -> "45.7%"
     */
    public static String formatPercent(double value) {
        return String.format("%.1f%%", value);
    }

    /**
     * Formats percentage without % symbol.
     * Example: 45.678 -> "45.7"
     */
    public static String formatPercentRaw(double value) {
        return String.format("%.1f", value);
    }

    /**
     * Formats integer with thousand separators.
     */
    public static String formatNumber(long number) {
        return String.format("%,d", number);
    }

    /**
     * Formats ratio (e.g., 0.5 -> "50.0%")
     */
    public static String formatRatio(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    /**
     * Formats memory size in appropriate unit.
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        } else {
            return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
        }
    }

    /**
     * Formats hit rate with 2 decimal places.
     * Example: 0.9567 -> "95.67"
     */
    public static String formatHitRate(double rate) {
        return String.format("%.2f", rate * 100);
    }
}
