package noietime.syncmoney.config;

import noietime.syncmoney.Syncmoney;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * [SYNC-CONFIG-002] Configuration manager for reading, writing, validating, and hot-reloading config.
 * Provides API for the web admin interface to manage plugin settings.
 */
public class ConfigManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final File configFile;
    private YamlConfiguration yamlConfig;

    public record ValidationResult(boolean valid, String message) {}

    public record ConfigChange(String section, String key, Object value) {}

    public ConfigManager(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        reloadYaml();
    }

    private void reloadYaml() {
        this.yamlConfig = YamlConfiguration.loadConfiguration(configFile);
    }

    /**
     * [SYNC-CONFIG-003] Get full configuration with editable markers for the web UI.
     */
    public Map<String, Object> getFullConfig() {
        reloadYaml();
        Map<String, Object> result = new LinkedHashMap<>();


        result.put("redis", getRedisInfo());
        result.put("database", getDatabaseInfo());


        result.put("core", getCoreSection());
        result.put("economy", getEconomySection());
        result.put("display", getDisplaySection());
        result.put("pay", getPaySection());
        result.put("baltop", getBaltopSection());
        result.put("circuit-breaker", getCircuitBreakerSection());
        result.put("discord-webhook", getDiscordWebhookSection());
        result.put("audit", getAuditSection());
        result.put("admin-permissions", getAdminPermissionsSection());
        result.put("messages", getMessagesSection());
        result.put("cross-server-notifications", getCrossServerNotificationsSection());
        result.put("shadow-sync", getShadowSyncSection());
        result.put("web-admin.ui", getWebAdminUiSection());

        return result;
    }

    private Map<String, Object> getCoreSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("server-name", createField(false, config.getServerName()));
        section.put("queue-capacity", createField(false, config.getQueueCapacity()));
        section.put("debug", createField(true, yamlConfig.getBoolean("debug", false)));
        return section;
    }

    private Map<String, Object> getEconomySection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("mode", createField(true, yamlConfig.getString("economy.mode", "auto"),
            Arrays.asList("auto", "local", "local_redis", "sync", "cmi")));


        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("vault-intercept", createField(true, yamlConfig.getBoolean("economy.sync.vault-intercept", true)));
        sync.put("vault-intercept-deposit", createField(true, yamlConfig.getBoolean("economy.sync.vault-intercept-deposit", true)));
        sync.put("vault-intercept-withdraw", createField(true, yamlConfig.getBoolean("economy.sync.vault-intercept-withdraw", true)));
        section.put("sync", sync);


        Map<String, Object> cmi = new LinkedHashMap<>();
        cmi.put("detect-interval-ms", createField(true, yamlConfig.getInt("economy.cmi.detect-interval-ms", 500), 100, 10000));
        cmi.put("cross-server-sync", createField(true, yamlConfig.getBoolean("economy.cmi.cross-server-sync", true)));
        cmi.put("balance-mode", createField(true, yamlConfig.getString("economy.cmi.balance-mode", "internal"),
            Arrays.asList("api", "internal")));
        section.put("cmi", cmi);

        return section;
    }

    private Map<String, Object> getDisplaySection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("currency-name", createField(true, yamlConfig.getString("display.currency-name", "Coins")));
        section.put("decimal-places", createField(true, yamlConfig.getInt("display.decimal-places", 2), 0, 10));
        return section;
    }

    private Map<String, Object> getPaySection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("cooldown-seconds", createField(true, yamlConfig.getInt("pay.cooldown-seconds", 30), 0, 3600));
        section.put("min-amount", createField(true, yamlConfig.getDouble("pay.min-amount", 0.0)));
        section.put("max-amount", createField(true, yamlConfig.getDouble("pay.max-amount", 1000000.0)));
        section.put("confirm-threshold", createField(true, yamlConfig.getDouble("pay.confirm-threshold", 10000.0)));
        section.put("allow-in-degraded", createField(true, yamlConfig.getBoolean("pay.allow-in-degraded", false)));
        return section;
    }

    private Map<String, Object> getBaltopSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("cache-seconds", createField(true, yamlConfig.getInt("baltop.cache-seconds", 60), 5, 3600));
        section.put("entries-per-page", createField(true, yamlConfig.getInt("baltop.entries-per-page", 10), 1, 100));
        section.put("format", createField(true, yamlConfig.getString("baltop.format", "abbreviated"),
            Arrays.asList("full", "smart", "abbreviated")));
        return section;
    }

    private Map<String, Object> getCircuitBreakerSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enabled", createField(true, yamlConfig.getBoolean("circuit-breaker.enabled", true)));
        section.put("max-single-transaction", createField(true, yamlConfig.getDouble("circuit-breaker.max-single-transaction", 100000000.0)));
        section.put("max-transactions-per-second", createField(true, yamlConfig.getInt("circuit-breaker.max-transactions-per-second", 5), 1, 100));
        section.put("rapid-inflation-threshold", createField(true, yamlConfig.getDouble("circuit-breaker.rapid-inflation-threshold", 0.2), 0.0, 1.0));
        section.put("inflation-check-interval-minutes", createField(true, yamlConfig.getInt("circuit-breaker.inflation-check-interval-minutes", 5), 1, 60));
        section.put("sudden-change-threshold", createField(true, yamlConfig.getDouble("circuit-breaker.sudden-change-threshold", 100.0)));
        section.put("redis-disconnect-lock-seconds", createField(true, yamlConfig.getInt("circuit-breaker.redis-disconnect-lock-seconds", 5), 1, 300));
        section.put("memory-warning-threshold", createField(true, yamlConfig.getInt("circuit-breaker.memory-warning-threshold", 80), 50, 95));
        section.put("pool-exhausted-warning", createField(true, yamlConfig.getInt("circuit-breaker.pool-exhausted-warning", 2), 0, 10));


        Map<String, Object> playerProtection = new LinkedHashMap<>();
        playerProtection.put("enabled", createField(true, yamlConfig.getBoolean("circuit-breaker.player-protection.enabled", true)));


        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("max-transactions-per-second", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.rate-limit.max-transactions-per-second", 5), 1, 100));
        rateLimit.put("max-transactions-per-minute", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.rate-limit.max-transactions-per-minute", 50), 1, 1000));
        rateLimit.put("max-amount-per-minute", createField(true, yamlConfig.getDouble("circuit-breaker.player-protection.rate-limit.max-amount-per-minute", 1000000.0)));
        playerProtection.put("rate-limit", rateLimit);


        Map<String, Object> anomalyDetection = new LinkedHashMap<>();
        anomalyDetection.put("warning-window-seconds", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.anomaly-detection.warning-window-seconds", 30), 5, 300));
        anomalyDetection.put("warning-threshold", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.anomaly-detection.warning-threshold", 30), 5, 500));
        anomalyDetection.put("balance-change-threshold", createField(true, yamlConfig.getDouble("circuit-breaker.player-protection.anomaly-detection.balance-change-threshold", 50.0)));
        playerProtection.put("anomaly-detection", anomalyDetection);


        Map<String, Object> autoUnlock = new LinkedHashMap<>();
        autoUnlock.put("lock-duration-minutes", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.auto-unlock.lock-duration-minutes", 5), 1, 60));
        autoUnlock.put("max-lock-extensions", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.auto-unlock.max-lock-extensions", 3), 0, 20));
        autoUnlock.put("unlock-test-transactions", createField(true, yamlConfig.getInt("circuit-breaker.player-protection.auto-unlock.unlock-test-transactions", 3), 1, 20));
        playerProtection.put("auto-unlock", autoUnlock);

        section.put("player-protection", playerProtection);

        return section;
    }

    private Map<String, Object> getDiscordWebhookSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enabled", createField(true, yamlConfig.getBoolean("discord-webhook.enabled", false)));


        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("color", createField(true, yamlConfig.getString("discord-webhook.embed.color", "FF5555")));
        embed.put("show-player-name", createField(true, yamlConfig.getBoolean("discord-webhook.embed.show-player-name", true)));
        embed.put("show-timestamp", createField(true, yamlConfig.getBoolean("discord-webhook.embed.show-timestamp", true)));
        embed.put("username", createField(true, yamlConfig.getString("discord-webhook.embed.username", "Syncmoney Alert")));
        section.put("embed", embed);

        return section;
    }

    private Map<String, Object> getAuditSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enabled", createField(true, yamlConfig.getBoolean("audit.enabled", true)));
        section.put("batch-size", createField(true, yamlConfig.getInt("audit.batch-size", 100), 10, 1000));
        section.put("flush-interval-ms", createField(true, yamlConfig.getInt("audit.flush-interval-ms", 5000), 0, 60000));
        section.put("retention-days", createField(true, yamlConfig.getInt("audit.retention-days", 90), 0, 3650));


        Map<String, Object> cleanup = new LinkedHashMap<>();
        cleanup.put("enabled", createField(true, yamlConfig.getBoolean("audit.cleanup.enabled", true)));
        cleanup.put("interval-hours", createField(true, yamlConfig.getInt("audit.cleanup.interval-hours", 24), 1, 168));
        section.put("cleanup", cleanup);


        Map<String, Object> export = new LinkedHashMap<>();
        export.put("enabled", createField(true, yamlConfig.getBoolean("audit.export.enabled", true)));
        export.put("export-folder", createField(true, yamlConfig.getString("audit.export.export-folder", "./plugins/Syncmoney/logs/")));
        export.put("delete-after-export", createField(true, yamlConfig.getBoolean("audit.export.delete-after-export", true)));
        section.put("export", export);


        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("enabled", createField(true, yamlConfig.getBoolean("audit.redis.enabled", true)));
        redis.put("window-size", createField(true, yamlConfig.getInt("audit.redis.window-size", 200), 50, 1000));
        redis.put("migration-threshold", createField(true, yamlConfig.getInt("audit.redis.migration-threshold", 5), 1, 100));
        redis.put("migration-batch-size", createField(true, yamlConfig.getInt("audit.redis.migration-batch-size", 20), 1, 100));
        redis.put("migration-interval-ms", createField(true, yamlConfig.getInt("audit.redis.migration-interval-ms", 1000), 100, 10000));
        section.put("redis", redis);


        Map<String, Object> deduplication = new LinkedHashMap<>();
        deduplication.put("enabled", createField(true, yamlConfig.getBoolean("audit.deduplication.enabled", true)));
        deduplication.put("server-window-seconds", createField(true, yamlConfig.getInt("audit.deduplication.server-window-seconds", 3600), 60, 86400));
        section.put("deduplication", deduplication);

        return section;
    }

    private Map<String, Object> getAdminPermissionsSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enforce-daily-limit", createField(true, yamlConfig.getBoolean("admin-permissions.enforce-daily-limit", true)));
        section.put("confirm-threshold", createField(true, yamlConfig.getDouble("admin-permissions.confirm-threshold", 500000)));


        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put("observe", createField(true, yamlConfig.getString("admin-permissions.levels.observe.permission", "syncmoney.admin.observe")));
        levels.put("reward", createField(true, yamlConfig.getString("admin-permissions.levels.reward.permission", "syncmoney.admin.reward")));
        levels.put("general", createField(true, yamlConfig.getString("admin-permissions.levels.general.permission", "syncmoney.admin.general")));
        levels.put("full", createField(true, yamlConfig.getString("admin-permissions.levels.full.permission", "syncmoney.admin.full")));
        section.put("levels", levels);

        return section;
    }

    private Map<String, Object> getMessagesSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("legacy-color-support", createField(true, yamlConfig.getBoolean("messages.legacy-color-support", true)));
        section.put("legacy-character", createField(true, yamlConfig.getString("messages.legacy-character", "&")));
        section.put("minimessage-support", createField(true, yamlConfig.getBoolean("messages.minimessage-support", true)));
        section.put("hex-colors", createField(true, yamlConfig.getBoolean("messages.hex-colors", true)));
        return section;
    }

    private Map<String, Object> getCrossServerNotificationsSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enabled", createField(true, yamlConfig.getBoolean("cross-server-notifications.enabled", true)));
        section.put("notify-type", createField(true, yamlConfig.getString("cross-server-notifications.notify-type", "all"),
            Arrays.asList("all", "deposit-only", "withdraw-only", "none")));
        section.put("show-actionbar", createField(true, yamlConfig.getBoolean("cross-server-notifications.show-actionbar", true)));
        section.put("actionbar-duration", createField(true, yamlConfig.getInt("cross-server-notifications.actionbar-duration", 80), 20, 200));
        return section;
    }

    private Map<String, Object> getShadowSyncSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enabled", createField(true, yamlConfig.getBoolean("shadow-sync.enabled", true)));
        section.put("target", createField(true, yamlConfig.getString("shadow-sync.target", "local"),
            Arrays.asList("local", "cmi", "all")));


        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("batch-size", createField(true, yamlConfig.getInt("shadow-sync.trigger.batch-size", 10), 1, 1000));
        trigger.put("max-delay-ms", createField(true, yamlConfig.getInt("shadow-sync.trigger.max-delay-ms", 1000), 100, 60000));
        section.put("trigger", trigger);


        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", createField(true, yamlConfig.getString("shadow-sync.storage.type", "sqlite"),
            Arrays.asList("jsonl", "sqlite", "mysql", "postgresql")));
        section.put("storage", storage);


        Map<String, Object> features = new LinkedHashMap<>();
        features.put("history-enabled", createField(true, yamlConfig.getBoolean("shadow-sync.features.history-enabled", true)));
        features.put("history-retention-days", createField(true, yamlConfig.getInt("shadow-sync.features.history-retention-days", 90), 0, 3650));
        section.put("features", features);

        return section;
    }

    private Map<String, Object> getWebAdminUiSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("theme", createField(true, yamlConfig.getString("web-admin.ui.theme", "dark"),
            Arrays.asList("dark", "light")));
        section.put("language", createField(true, yamlConfig.getString("web-admin.ui.language", "zh-TW"),
            Arrays.asList("zh-TW", "en-US")));
        section.put("timezone", createField(true, yamlConfig.getString("web-admin.ui.timezone", "UTC")));
        return section;
    }

    private Map<String, Object> createField(boolean editable, Object value) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("value", value);
        field.put("editable", editable);
        if (value instanceof Number) {
            field.put("type", "number");
        } else if (value instanceof Boolean) {
            field.put("type", "boolean");
        } else {
            field.put("type", "string");
        }
        return field;
    }

    private Map<String, Object> createField(boolean editable, Object value, Number min, Number max) {
        Map<String, Object> field = createField(editable, value);
        field.put("min", min);
        field.put("max", max);
        return field;
    }

    private Map<String, Object> createField(boolean editable, Object value, List<String> allowedValues) {
        Map<String, Object> field = createField(editable, value);
        field.put("allowedValues", allowedValues);
        return field;
    }

    /**
     * [SYNC-CONFIG-004] Get masked Redis connection info.
     */
    private Map<String, Object> getRedisInfo() {
        Map<String, Object> redis = new HashMap<>();
        redis.put("enabled", config.redis().getRedisHost() != null && !config.redis().getRedisHost().isEmpty());
        redis.put("host", maskHost(config.redis().getRedisHost()));
        redis.put("port", "***");
        redis.put("password", "***HIDDEN***");
        redis.put("editable", false);
        return redis;
    }

    /**
     * [SYNC-CONFIG-005] Get masked database connection info.
     */
    private Map<String, Object> getDatabaseInfo() {
        Map<String, Object> database = new HashMap<>();
        database.put("type", config.database().getDatabaseType());
        database.put("host", maskHost(config.database().getDatabaseHost()));
        database.put("port", "***");
        database.put("database", config.database().getDatabaseName());
        database.put("editable", false);
        return database;
    }

    /**
     * [SYNC-CONFIG-006] Mask host information for security.
     */
    private String maskHost(String host) {
        if (host == null || host.isEmpty()) {
            return "***";
        }
        if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            String[] parts = host.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + ".*.*";
            }
        }
        if (host.contains(".")) {
            int firstDot = host.indexOf('.');
            int lastDot = host.lastIndexOf('.');
            if (firstDot != lastDot) {
                return host.substring(0, firstDot + 1) + "***" + host.substring(lastDot);
            }
        }
        return "***";
    }

    /**
     * [SYNC-CONFIG-007] Save a single configuration change.
     */
    public boolean saveConfig(String section, String key, Object value) {
        return saveConfigBatch(List.of(new ConfigChange(section, key, value)));
    }

    /**
     * [SYNC-CONFIG-008] Maps virtual section paths to real YAML paths.
     * The "core" section is a virtual grouping of root-level YAML keys.
     */
    private static final Map<String, String> VIRTUAL_SECTION_PATHS = Map.of(
        "core.server-name", "server-name",
        "core.queue-capacity", "queue-capacity",
        "core.debug", "debug"
    );

    /**
     * [SYNC-CONFIG-009] Save multiple configuration changes at once.
     */
    public boolean saveConfigBatch(List<ConfigChange> changes) {
        try {
            reloadYaml();

            for (ConfigChange change : changes) {
                String rawPath = change.section() + "." + change.key();
                String path = VIRTUAL_SECTION_PATHS.getOrDefault(rawPath, rawPath);
                Object value = change.value();


                if (value instanceof Number) {
                    if (value instanceof Double || value instanceof Float) {
                        yamlConfig.set(path, ((Number) value).doubleValue());
                    } else if (value instanceof Long) {
                        yamlConfig.set(path, ((Number) value).longValue());
                    } else {
                        yamlConfig.set(path, ((Number) value).intValue());
                    }
                } else {
                    yamlConfig.set(path, value);
                }
            }

            yamlConfig.save(configFile);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save config: " + e.getMessage());
            return false;
        }
    }

    /**
     * [SYNC-CONFIG-010] Validate a configuration value before saving.
     */
    public ValidationResult validate(String section, String key, Object value) {

        if (value instanceof Number) {

        } else if (value instanceof Boolean) {

        } else if (value instanceof String) {

        } else {
            return new ValidationResult(false, "Invalid value type");
        }

        return new ValidationResult(true, "Valid");
    }

    /**
     * [SYNC-CONFIG-011] Perform hot reload of configuration.
     */
    public void hotReload() {

        plugin.reloadConfig();
        

        plugin.reloadSyncmoneyConfig();
        

        plugin.reloadPermissionService();
        

        plugin.reloadEconomyFacade();
    }

    /**
     * [SYNC-CONFIG-012] Get the config file path.
     */
    public java.nio.file.Path getConfigPath() {
        return configFile.toPath();
    }
}
