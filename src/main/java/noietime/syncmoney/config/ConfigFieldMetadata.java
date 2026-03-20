package noietime.syncmoney.config;

import java.util.Arrays;
import java.util.List;

/**
 * Metadata definitions for all configurable fields.
 * Defines which fields are editable, their types, validation rules, and i18n keys.
 */
public final class ConfigFieldMetadata {

    public enum FieldType {
        STRING, NUMBER, BOOLEAN, ARRAY, OBJECT
    }

    public record ConfigFieldDefinition(
        String section,
        String key,
        String path,
        FieldType type,
        boolean editable,
        Object defaultValue,
        Object min,
        Object max,
        List<String> allowedValues,
        String i18nKey
    ) {}

    private ConfigFieldMetadata() {}

    /**
     * Get all field definitions for the configuration.
     */
    public static List<ConfigFieldDefinition> getAllFields() {
        return Arrays.asList(

            create("core", "server-name", FieldType.STRING, false, "", null, null, null),
            create("core", "queue-capacity", FieldType.NUMBER, false, 50000, null, null, null),
            create("core", "debug", FieldType.BOOLEAN, true, false, null, null, null),


            create("economy", "mode", FieldType.STRING, true, "auto", null, null, 
                Arrays.asList("auto", "local", "local_redis", "sync", "cmi")),
            create("economy.sync", "vault-intercept", FieldType.BOOLEAN, true, true, null, null, null),
            create("economy.sync", "vault-intercept-deposit", FieldType.BOOLEAN, true, true, null, null, null),
            create("economy.sync", "vault-intercept-withdraw", FieldType.BOOLEAN, true, true, null, null, null),
            create("economy.cmi", "detect-interval-ms", FieldType.NUMBER, true, 500, 100, 10000, null),
            create("economy.cmi", "cross-server-sync", FieldType.BOOLEAN, true, true, null, null, null),
            create("economy.cmi", "balance-mode", FieldType.STRING, true, "internal", null, null, 
                Arrays.asList("api", "internal")),


            create("display", "currency-name", FieldType.STRING, true, "$", null, null, null),
            create("display", "decimal-places", FieldType.NUMBER, true, 2, 0, 10, null),


            create("pay", "cooldown-seconds", FieldType.NUMBER, true, 30, 0, 3600, null),
            create("pay", "min-amount", FieldType.NUMBER, true, 1.0, null, null, null),
            create("pay", "max-amount", FieldType.NUMBER, true, 1000000.0, null, null, null),
            create("pay", "confirm-threshold", FieldType.NUMBER, true, 100000.0, null, null, null),
            create("pay", "allow-in-degraded", FieldType.BOOLEAN, true, false, null, null, null),


            create("baltop", "cache-seconds", FieldType.NUMBER, true, 30, 5, 3600, null),
            create("baltop", "entries-per-page", FieldType.NUMBER, true, 10, 1, 100, null),
            create("baltop", "format", FieldType.STRING, true, "smart", null, null, 
                Arrays.asList("full", "smart", "abbreviated")),


            create("circuit-breaker", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("circuit-breaker", "max-single-transaction", FieldType.NUMBER, true, 100000000L, null, null, null),
            create("circuit-breaker", "max-transactions-per-second", FieldType.NUMBER, true, 10, 1, 100, null),
            create("circuit-breaker", "rapid-inflation-threshold", FieldType.NUMBER, true, 0.2, 0.0, 1.0, null),
            create("circuit-breaker", "inflation-check-interval-minutes", FieldType.NUMBER, true, 5, 1, 60, null),
            create("circuit-breaker", "sudden-change-threshold", FieldType.NUMBER, true, 100.0, null, null, null),
            create("circuit-breaker", "redis-disconnect-lock-seconds", FieldType.NUMBER, true, 5, 1, 300, null),
            create("circuit-breaker", "memory-warning-threshold", FieldType.NUMBER, true, 80, 50, 95, null),
            create("circuit-breaker", "pool-exhausted-warning", FieldType.NUMBER, true, 2, 0, 10, null),


            create("player-protection", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("player-protection.rate-limit", "max-transactions-per-second", FieldType.NUMBER, true, 5, 1, 100, null),
            create("player-protection.rate-limit", "max-transactions-per-minute", FieldType.NUMBER, true, 50, 1, 1000, null),
            create("player-protection.rate-limit", "max-amount-per-minute", FieldType.NUMBER, true, 1000000.0, null, null, null),
            create("player-protection.anomaly-detection", "warning-window-seconds", FieldType.NUMBER, true, 30, 5, 300, null),
            create("player-protection.anomaly-detection", "warning-threshold", FieldType.NUMBER, true, 30, 5, 500, null),
            create("player-protection.anomaly-detection", "balance-change-threshold", FieldType.NUMBER, true, 50.0, null, null, null),
            create("player-protection.auto-unlock", "lock-duration-minutes", FieldType.NUMBER, true, 5, 1, 60, null),
            create("player-protection.auto-unlock", "max-lock-extensions", FieldType.NUMBER, true, 3, 0, 20, null),
            create("player-protection.auto-unlock", "unlock-test-transactions", FieldType.NUMBER, true, 3, 1, 20, null),
            create("player-protection.global-lock", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("player-protection.global-lock", "total-inflation-threshold", FieldType.NUMBER, true, 0.2, 0.0, 1.0, null),
            create("player-protection.transfer-protection", "lock-receiver", FieldType.BOOLEAN, true, true, null, null, null),
            create("player-protection.transfer-protection", "receiver-lock-threshold", FieldType.NUMBER, true, 0.0, null, null, null),


            create("discord-webhook", "enabled", FieldType.BOOLEAN, true, false, null, null, null),


            create("audit", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("audit", "batch-size", FieldType.NUMBER, true, 100, 10, 1000, null),
            create("audit", "flush-interval-ms", FieldType.NUMBER, true, 5000, 0, 60000, null),
            create("audit", "retention-days", FieldType.NUMBER, true, 90, 0, 3650, null),
            create("audit.cleanup", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("audit.cleanup", "interval-hours", FieldType.NUMBER, true, 24, 1, 168, null),
            create("audit.export", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("audit.redis", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("audit.redis", "window-size", FieldType.NUMBER, true, 200, 50, 1000, null),


            create("admin-permissions", "enforce-daily-limit", FieldType.BOOLEAN, true, true, null, null, null),
            create("admin-permissions", "confirm-threshold", FieldType.NUMBER, true, 500000.0, null, null, null),


            create("messages", "legacy-color-support", FieldType.BOOLEAN, true, true, null, null, null),
            create("messages", "minimessage-support", FieldType.BOOLEAN, true, true, null, null, null),
            create("messages", "hex-colors", FieldType.BOOLEAN, true, true, null, null, null),


            create("cross-server-notifications", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("cross-server-notifications", "show-actionbar", FieldType.BOOLEAN, true, true, null, null, null),
            create("cross-server-notifications", "actionbar-duration", FieldType.NUMBER, true, 80, 20, 200, null),


            create("shadow-sync", "enabled", FieldType.BOOLEAN, true, true, null, null, null),
            create("shadow-sync.trigger", "batch-size", FieldType.NUMBER, true, 10, 1, 1000, null),
            create("shadow-sync.trigger", "max-delay-ms", FieldType.NUMBER, true, 1000, 100, 60000, null),


            create("web-admin.ui", "theme", FieldType.STRING, true, "dark", null, null, 
                Arrays.asList("dark", "light")),
            create("web-admin.ui", "language", FieldType.STRING, true, "zh-TW", null, null, 
                Arrays.asList("zh-TW", "en-US")),
            create("web-admin.ui", "timezone", FieldType.STRING, true, "UTC", null, null, null)
        );
    }

    private static ConfigFieldDefinition create(String section, String key, FieldType type,
                                                 boolean editable, Object defaultValue,
                                                 Object min, Object max, List<String> allowedValues) {
        String path = section + "." + key;
        return new ConfigFieldDefinition(section, key, path, type, editable, defaultValue, min, max, allowedValues, null);
    }

    /**
     * Get editable fields only.
     */
    public static List<ConfigFieldDefinition> getEditableFields() {
        return getAllFields().stream()
            .filter(ConfigFieldDefinition::editable)
            .toList();
    }

    /**
     * Get fields by section.
     */
    public static List<ConfigFieldDefinition> getFieldsBySection(String section) {
        return getAllFields().stream()
            .filter(f -> f.section().equals(section))
            .toList();
    }
}
