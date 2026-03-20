package noietime.syncmoney.config;

import noietime.syncmoney.economy.EconomyMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SYNC-CONFIG-001] Configuration loader for config.yml.
 * Provides getters for Redis/DB/server-name/queue-capacity settings.
 * Does not create connections; only parses config. Returns defaults for missing keys.
 */
public final class SyncmoneyConfig {

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final ServerIdentityManager serverIdentityManager;

    public SyncmoneyConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        String customServerName = config.getString("server-name", "");
        this.serverIdentityManager = new ServerIdentityManager(plugin, customServerName);
    }

    /**
     * [SYNC-CONFIG-013] Get the underlying FileConfiguration.
     * Used by WebAdminConfig to load web settings.
     */
    public FileConfiguration getConfig() {
        return config;
    }

    public String getRedisHost() {
        return config.getString("redis.host", "localhost");
    }

    public int getRedisPort() {
        return config.getInt("redis.port", 6379);
    }

    public String getRedisPassword() {
        return config.getString("redis.password", "");
    }

    public int getRedisDatabase() {
        return config.getInt("redis.database", 0);
    }

    public int getRedisPoolSize() {
        return config.getInt("redis.pool-size", 20);
    }

    public String getDatabaseHost() {
        return config.getString("database.host", "localhost");
    }

    public int getDatabasePort() {
        return config.getInt("database.port", 3306);
    }

    public String getDatabaseUsername() {
        return config.getString("database.username", "root");
    }

    public String getDatabasePassword() {
        return config.getString("database.password", "");
    }

    public String getDatabaseName() {
        return config.getString("database.database", "syncmoney");
    }

    public int getDatabasePoolSize() {
        return config.getInt("database.pool-size", 10);
    }

    public int getDatabaseMinimumIdle() {
        return config.getInt("database.minimum-idle", 2);
    }

    public int getDatabaseConnectionTimeout() {
        return config.getInt("database.connection-timeout", 10000);
    }

    public long getDatabaseMaxLifetime() {
        return config.getLong("database.max-lifetime", 600000L);
    }

    public long getDatabaseIdleTimeout() {
        return config.getLong("database.idle-timeout", 300000L);
    }

    /**
     * [SYNC-CONFIG-014] Gets database type (mysql or postgresql)
     */
    public String getDatabaseType() {
        return config.getString("database.type", "mysql").toLowerCase();
    }

    public String getServerName() {
        return serverIdentityManager.getServerName();
    }

    public int getQueueCapacity() {
        return config.getInt("queue-capacity", 10000);
    }

    public boolean isPubsubEnabled() {
        return config.getBoolean("pubsub-enabled", true);
    }

    public boolean isDbEnabled() {
        return config.getBoolean("db-enabled", true);
    }

    public String getCurrencyName() {
        return config.getString("display.currency-name", "$");
    }

    public int getDecimalPlaces() {
        return config.getInt("display.decimal-places", 2);
    }

    public int getPayCooldownSeconds() {
        return config.getInt("pay.cooldown-seconds", 30);
    }

    public boolean isDebug() {
        return config.getBoolean("debug", false);
    }

    public double getPayMinAmount() {
        return config.getDouble("pay.min-amount", 1);
    }

    public double getPayMaxAmount() {
        return config.getDouble("pay.max-amount", 1000000);
    }

    public double getPayConfirmThreshold() {
        return config.getDouble("pay.confirm-threshold", 100000);
    }

    public boolean isPayAllowedInDegraded() {
        return config.getBoolean("pay.allow-in-degraded", false);
    }

    public String getCMIDatabaseHost() {
        return config.getString("migration.cmi.host", "localhost");
    }

    public int getCMIDatabasePort() {
        return config.getInt("migration.cmi.port", 3306);
    }

    public String getCMIDatabaseUsername() {
        return config.getString("migration.cmi.username", "root");
    }

    public String getCMIDatabasePassword() {
        return config.getString("migration.cmi.password", "");
    }

    public String getCMIDatabaseName() {
        return config.getString("migration.cmi.database", "minecraft");
    }

    public int getMigrationBatchSize() {
        return config.getInt("migration.batch-size", 100);
    }

    public boolean isMigrationAutoBackup() {
        return config.getBoolean("migration.auto-backup", true);
    }

    /**
     * [SYNC-CONFIG-015a] Number of migration backups to keep (auto-cleanup old backups).
     */
    public int getMigrationBackupKeepCount() {
        return config.getInt("migration.backup.keep-count", 10);
    }

    /**
     * [SYNC-CONFIG-015b] Whether to populate Redis during migration
     * (strongly recommended for SYNC mode).
     */
    public boolean isMigrationPopulateRedis() {
        return config.getBoolean("migration.populate-redis", true);
    }

    /**
     * [SYNC-CONFIG-016] Whether to lock economy operations during migration.
     * When enabled, prevents data inconsistency during migration.
     */
    public boolean isMigrationLockEconomy() {
        return config.getBoolean("migration.lock-economy", false);
    }

    /**
     * [SYNC-CONFIG-017] Whether to automatically disable CMI economy commands after migration
     */
    public boolean isCMIAutoDisableCommands() {
        return config.getBoolean("migration.cmi.auto-disable-commands", true);
    }

    /**
     * [SYNC-CONFIG-018] Whether to automatically disable CMI economy module after migration
     */
    public boolean isCMIAutoDisableEconomy() {
        return config.getBoolean("migration.cmi.auto-disable-economy", false);
    }

    /**
     * [SYNC-CONFIG-019] Whether to enable CMI multi-server mode
     */
    public boolean isCMIMultiServerEnabled() {
        return config.getBoolean("migration.cmi.multi-server.enabled", false);
    }

    /**
     * [SYNC-CONFIG-020] Gets CMI merge strategy (latest/sum/max)
     */
    public String getCMIMergeStrategy() {
        return config.getString("migration.cmi.multi-server.merge-strategy", "latest");
    }

    /**
     * [SYNC-CONFIG-021] Gets CMI SQLite single path
     */
    public String getCMISqlitePath() {
        return config.getString("migration.cmi.sqlite-path", "plugins/CMI/cmi.sqlite.db");
    }

    /**
     * [SYNC-CONFIG-022] Gets CMI SQLite multiple paths list
     */
    public java.util.List<String> getCMISqlitePaths() {
        return config.getStringList("migration.cmi.multi-server.sqlite-paths");
    }

    /**
     * [SYNC-CONFIG-023] Whether to enable CMI auto-detection (from DataBaseInfo.yml)
     */
    public boolean isCMIAutoDetect() {
        return config.getBoolean("migration.cmi.auto-detect", true);
    }

    /**
     * [SYNC-CONFIG-024] Gets CMI table prefix
     */
    public String getCMITablePrefix() {
        return config.getString("migration.cmi.table-prefix", "CMI_");
    }

    public boolean isShadowSyncEnabled() {
        return config.getBoolean("shadow-sync.enabled", false);
    }

    /**
     * [SYNC-CONFIG-025] Gets shadow sync target (cmi/local/all)
     */
    public String getShadowSyncTarget() {
        return config.getString("shadow-sync.target", "cmi").toLowerCase();
    }

    public int getShadowSyncBatchSize() {
        return config.getInt("shadow-sync.batch-size", 10);
    }

    /**
     * [SYNC-CONFIG-026] Gets CMI sync maximum delay (milliseconds).
     */
    public long getShadowSyncMaxDelayMs() {
        return config.getLong("shadow-sync.max-delay-ms", 500);
    }

    public boolean isShadowSyncRollbackProtection() {
        return config.getBoolean("shadow-sync.rollback-protection", true);
    }

    public double getShadowSyncRollbackThreshold() {
        return config.getDouble("shadow-sync.rollback-threshold", 0.5);
    }

    /**
     * [SYNC-CONFIG-027] Gets CMI SQLite database path (for shadow sync).
     */
    public String getShadowSyncCMISQLitePath() {
        return config.getString("shadow-sync.cmi-sqlite-path", "plugins/CMI/cmi.sqlite.db");
    }

    /**
     * [SYNC-CONFIG-028] Gets CMI MySQL host (for shadow sync, when SQLite fails).
     */
    public String getShadowSyncCMIMySQLHost() {
        return config.getString("shadow-sync.cmi-mysql.host", "localhost");
    }

    /**
     * [SYNC-CONFIG-029] Gets CMI MySQL port (for shadow sync).
     */
    public int getShadowSyncCMIMySQLPort() {
        return config.getInt("shadow-sync.cmi-mysql.port", 3306);
    }

    /**
     * [SYNC-CONFIG-030] Gets CMI MySQL username (for shadow sync).
     */
    public String getShadowSyncCMIMySQLUsername() {
        return config.getString("shadow-sync.cmi-mysql.username", "root");
    }

    /**
     * [SYNC-CONFIG-031] Gets CMI MySQL password (for shadow sync).
     */
    public String getShadowSyncCMIMySQLPassword() {
        return config.getString("shadow-sync.cmi-mysql.password", "");
    }

    /**
     * [SYNC-CONFIG-032] Gets CMI MySQL database name (for shadow sync).
     */
    public String getShadowSyncCMIMySQLDatabase() {
        return config.getString("shadow-sync.cmi-mysql.database", "minecraft");
    }

    /**
     * [SYNC-CONFIG-033] Gets shadow sync storage type (jsonl/sqlite/mysql/postgresql)
     */
    public String getShadowSyncStorageType() {
        return config.getString("shadow-sync.storage.type", "sqlite").toLowerCase();
    }

    /**
     * [SYNC-CONFIG-034] Gets shadow sync SQLite path
     */
    public String getShadowSyncStorageSqlitePath() {
        return config.getString("shadow-sync.storage.sqlite.path", "plugins/Syncmoney/data/shadow-sync.db");
    }

    /**
     * [SYNC-CONFIG-035] Gets shadow sync JSONL path
     */
    public String getShadowSyncStorageJsonlPath() {
        return config.getString("shadow-sync.storage.jsonl.path", "plugins/Syncmoney/logs/shadow-sync/");
    }

    /**
     * [SYNC-CONFIG-036] Gets shadow sync MySQL host
     */
    public String getShadowSyncStorageMysqlHost() {
        return config.getString("shadow-sync.storage.mysql.host", "localhost");
    }

    /**
     * [SYNC-CONFIG-037] Gets shadow sync MySQL port
     */
    public int getShadowSyncStorageMysqlPort() {
        return config.getInt("shadow-sync.storage.mysql.port", 3306);
    }

    /**
     * [SYNC-CONFIG-038] Gets shadow sync MySQL username
     */
    public String getShadowSyncStorageMysqlUsername() {
        return config.getString("shadow-sync.storage.mysql.username", "root");
    }

    /**
     * [SYNC-CONFIG-039] Gets shadow sync MySQL password
     */
    public String getShadowSyncStorageMysqlPassword() {
        return config.getString("shadow-sync.storage.mysql.password", "");
    }

    /**
     * [SYNC-CONFIG-040] Gets shadow sync MySQL database name
     */
    public String getShadowSyncStorageMysqlDatabase() {
        return config.getString("shadow-sync.storage.mysql.database", "syncmoney_shadow");
    }

    /**
     * [SYNC-CONFIG-041] Gets shadow sync MySQL pool size
     */
    public int getShadowSyncStorageMysqlPoolSize() {
        return config.getInt("shadow-sync.storage.mysql.pool-size", 5);
    }

    /**
     * [SYNC-CONFIG-042] Gets shadow sync PostgreSQL host
     */
    public String getShadowSyncStoragePostgresHost() {
        return config.getString("shadow-sync.storage.postgresql.host", "localhost");
    }

    /**
     * [SYNC-CONFIG-043] Gets shadow sync PostgreSQL port
     */
    public int getShadowSyncStoragePostgresPort() {
        return config.getInt("shadow-sync.storage.postgresql.port", 5432);
    }

    /**
     * [SYNC-CONFIG-044] Gets shadow sync PostgreSQL username
     */
    public String getShadowSyncStoragePostgresUsername() {
        return config.getString("shadow-sync.storage.postgresql.username", "root");
    }

    /**
     * [SYNC-CONFIG-045] Gets shadow sync PostgreSQL password
     */
    public String getShadowSyncStoragePostgresPassword() {
        return config.getString("shadow-sync.storage.postgresql.password", "");
    }

    /**
     * [SYNC-CONFIG-046] Gets shadow sync PostgreSQL database name
     */
    public String getShadowSyncStoragePostgresDatabase() {
        return config.getString("shadow-sync.storage.postgresql.database", "syncmoney_shadow");
    }

    /**
     * [SYNC-CONFIG-047] Gets shadow sync PostgreSQL pool size
     */
    public int getShadowSyncStoragePostgresPoolSize() {
        return config.getInt("shadow-sync.storage.postgresql.pool-size", 5);
    }

    /**
     * [SYNC-CONFIG-048] Gets shadow sync CMI connection mode (sqlite/mysql)
     */
    public String getShadowSyncCMIConnection() {
        return config.getString("shadow-sync.cmi.connection", "sqlite").toLowerCase();
    }

    /**
     * [SYNC-CONFIG-049] Gets shadow sync CMI SQLite path
     */
    public String getShadowSyncCMIConnectionSqlitePath() {
        return config.getString("shadow-sync.cmi.sqlite-path", "plugins/CMI/cmi.sqlite.db");
    }

    /**
     * [SYNC-CONFIG-050] Gets shadow sync CMI MySQL host
     */
    public String getShadowSyncCMIConnectionMySQLHost() {
        return config.getString("shadow-sync.cmi.mysql.host", "localhost");
    }

    /**
     * [SYNC-CONFIG-051] Gets shadow sync CMI MySQL port
     */
    public int getShadowSyncCMIConnectionMySQLPort() {
        return config.getInt("shadow-sync.cmi.mysql.port", 3306);
    }

    /**
     * [SYNC-CONFIG-052] Gets shadow sync CMI MySQL username
     */
    public String getShadowSyncCMIConnectionMySQLUsername() {
        return config.getString("shadow-sync.cmi.mysql.username", "root");
    }

    /**
     * [SYNC-CONFIG-053] Gets shadow sync CMI MySQL password
     */
    public String getShadowSyncCMIConnectionMySQLPassword() {
        return config.getString("shadow-sync.cmi.mysql.password", "");
    }

    /**
     * [SYNC-CONFIG-054] Gets shadow sync CMI MySQL database name
     */
    public String getShadowSyncCMIConnectionMySQLDatabase() {
        return config.getString("shadow-sync.cmi.mysql.database", "minecraft");
    }

    /**
     * [SYNC-CONFIG-055] Gets shadow sync trigger batch size
     */
    public int getShadowSyncTriggerBatchSize() {
        return config.getInt("shadow-sync.trigger.batch-size", 10);
    }

    /**
     * [SYNC-CONFIG-056] Gets shadow sync trigger max delay milliseconds
     */
    public long getShadowSyncTriggerMaxDelayMs() {
        return config.getLong("shadow-sync.trigger.max-delay-ms", 1000);
    }

    /**
     * [SYNC-CONFIG-057] Whether shadow sync history feature is enabled
     */
    public boolean isShadowSyncHistoryEnabled() {
        return config.getBoolean("shadow-sync.features.history-enabled", true);
    }

    /**
     * [SYNC-CONFIG-058] Gets shadow sync history retention days
     */
    public int getShadowSyncHistoryRetentionDays() {
        return config.getInt("shadow-sync.features.history-retention-days", 90);
    }

    /**
     * [SYNC-CONFIG-059] Whether to enable cross-server portal protection.
     */
    public boolean isTransferGuardEnabled() {
        return config.getBoolean("transfer-guard.enabled", true);
    }

    /**
     * [SYNC-CONFIG-060] Gets portal protection maximum wait time (milliseconds).
     */
    public int getTransferGuardMaxWaitMs() {
        return config.getInt("transfer-guard.max-wait-ms", 500);
    }

    /**
     * [SYNC-CONFIG-061] Whether to enable economic circuit breaker mechanism.
     */
    public boolean isCircuitBreakerEnabled() {
        return config.getBoolean("circuit-breaker.enabled", true);
    }

    /**
     * [SYNC-CONFIG-062] Gets single transaction limit (blocks transactions exceeding this amount).
     */
    public long getCircuitBreakerMaxSingleTransaction() {
        return config.getLong("circuit-breaker.max-single-transaction", 100_000_000L);
    }

    /**
     * [SYNC-CONFIG-063] Gets transaction frequency limit (maximum transactions per second).
     */
    public int getCircuitBreakerMaxTransactionsPerSecond() {
        return config.getInt("circuit-breaker.max-transactions-per-second", 5);
    }

    /**
     * [SYNC-CONFIG-064] Gets rapid inflation threshold (total increase percentage within 5 minutes).
     */
    public double getCircuitBreakerRapidInflationThreshold() {
        return config.getDouble("circuit-breaker.rapid-inflation-threshold", 0.2);
    }

    /**
     * [SYNC-CONFIG-065] Gets rapid inflation check interval (minutes).
     */
    public int getCircuitBreakerInflationCheckIntervalMinutes() {
        return config.getInt("circuit-breaker.inflation-check-interval-minutes", 5);
    }

    /**
     * [SYNC-CONFIG-066] Gets abnormal large change detection threshold (balance change multiplier).
     */
    public double getCircuitBreakerSuddenChangeThreshold() {
        return config.getDouble("circuit-breaker.sudden-change-threshold", 100.0);
    }

    /**
     * [SYNC-CONFIG-067] Gets time before entering LOCKED mode after Redis disconnection (seconds).
     */
    public int getCircuitBreakerRedisDisconnectLockSeconds() {
        return config.getInt("circuit-breaker.redis-disconnect-lock-seconds", 5);
    }

    /**
     * [SYNC-CONFIG-068] Gets memory usage warning threshold (percentage).
     */
    public int getCircuitBreakerMemoryWarningThreshold() {
        return config.getInt("circuit-breaker.memory-warning-threshold", 80);
    }

    /**
     * [SYNC-CONFIG-069] Gets connection pool exhaustion warning threshold (remaining connections).
     */
    public int getCircuitBreakerPoolExhaustedWarning() {
        return config.getInt("circuit-breaker.pool-exhausted-warning", 2);
    }

    /**
     * [SYNC-CONFIG-070] Whether to enable per-player protection system.
     * Supports both new path and legacy path for backward compatibility.
     */
    public boolean isPlayerProtectionEnabled() {

        if (config.isSet("circuit-breaker.player-protection.enabled")) {
            return config.getBoolean("circuit-breaker.player-protection.enabled", true);
        }
        return config.getBoolean("player-protection.enabled", true);
    }

    /**
     * [SYNC-CONFIG-071] Gets maximum transactions per second per player.
     */
    public int getPlayerProtectionMaxTransactionsPerSecond() {
        if (config.isSet("circuit-breaker.player-protection.rate-limit.max-transactions-per-second")) {
            return config.getInt("circuit-breaker.player-protection.rate-limit.max-transactions-per-second", 5);
        }
        return config.getInt("player-protection.rate-limit.max-transactions-per-second", 5);
    }

    /**
     * [SYNC-CONFIG-072] Gets maximum transactions per minute per player.
     */
    public int getPlayerProtectionMaxTransactionsPerMinute() {
        if (config.isSet("circuit-breaker.player-protection.rate-limit.max-transactions-per-minute")) {
            return config.getInt("circuit-breaker.player-protection.rate-limit.max-transactions-per-minute", 50);
        }
        return config.getInt("player-protection.rate-limit.max-transactions-per-minute", 50);
    }

    /**
     * [SYNC-CONFIG-073] Gets maximum transaction amount per minute per player (0 = disabled).
     * Supports both new path and legacy path for backward compatibility.
     */
    public long getPlayerProtectionMaxAmountPerMinute() {
        if (config.isSet("circuit-breaker.player-protection.rate-limit.max-amount-per-minute")) {
            return config.getLong("circuit-breaker.player-protection.rate-limit.max-amount-per-minute", 1000000);
        }
        return config.getLong("player-protection.rate-limit.max-amount-per-minute", 1000000);
    }

    /**
     * [SYNC-CONFIG-074] Gets warning window in seconds.
     */
    public int getPlayerProtectionWarningWindowSeconds() {
        if (config.isSet("circuit-breaker.player-protection.anomaly-detection.warning-window-seconds")) {
            return config.getInt("circuit-breaker.player-protection.anomaly-detection.warning-window-seconds", 30);
        }
        return config.getInt("player-protection.anomaly-detection.warning-window-seconds", 30);
    }

    /**
     * [SYNC-CONFIG-075] Gets transaction count threshold to trigger WARNING.
     */
    public int getPlayerProtectionWarningThreshold() {
        if (config.isSet("circuit-breaker.player-protection.anomaly-detection.warning-threshold")) {
            return config.getInt("circuit-breaker.player-protection.anomaly-detection.warning-threshold", 30);
        }
        return config.getInt("player-protection.anomaly-detection.warning-threshold", 30);
    }

    /**
     * [SYNC-CONFIG-076] Gets balance change multiplier threshold.
     */
    public double getPlayerProtectionBalanceChangeThreshold() {
        if (config.isSet("circuit-breaker.player-protection.anomaly-detection.balance-change-threshold")) {
            return config.getDouble("circuit-breaker.player-protection.anomaly-detection.balance-change-threshold", 50.0);
        }
        return config.getDouble("player-protection.anomaly-detection.balance-change-threshold", 50.0);
    }

    /**
     * [SYNC-CONFIG-077] Gets lock duration in minutes before auto-unlock attempt.
     */
    public int getPlayerProtectionLockDurationMinutes() {
        if (config.isSet("circuit-breaker.player-protection.auto-unlock.lock-duration-minutes")) {
            return config.getInt("circuit-breaker.player-protection.auto-unlock.lock-duration-minutes", 5);
        }
        return config.getInt("player-protection.auto-unlock.lock-duration-minutes", 5);
    }

    /**
     * [SYNC-CONFIG-078] Gets maximum number of lock extensions.
     */
    public int getPlayerProtectionMaxLockExtensions() {
        if (config.isSet("circuit-breaker.player-protection.auto-unlock.max-lock-extensions")) {
            return config.getInt("circuit-breaker.player-protection.auto-unlock.max-lock-extensions", 3);
        }
        return config.getInt("player-protection.auto-unlock.max-lock-extensions", 3);
    }

    /**
     * [SYNC-CONFIG-079] Gets number of successful transactions required to confirm unlock.
     */
    public int getPlayerProtectionUnlockTestTransactions() {
        if (config.isSet("circuit-breaker.player-protection.auto-unlock.unlock-test-transactions")) {
            return config.getInt("circuit-breaker.player-protection.auto-unlock.unlock-test-transactions", 3);
        }
        return config.getInt("player-protection.auto-unlock.unlock-test-transactions", 3);
    }

    /**
     * [SYNC-CONFIG-080] Whether to enable global lock when total economy spikes.
     */
    public boolean isPlayerProtectionGlobalLockEnabled() {
        return config.getBoolean("player-protection.global-lock.enabled", true);
    }

    /**
     * [SYNC-CONFIG-081] Gets total inflation threshold for global lock.
     */
    public double getPlayerProtectionGlobalLockThreshold() {
        return config.getDouble("player-protection.global-lock.total-inflation-threshold", 0.2);
    }

    /**
     * [SYNC-CONFIG-082] Whether to enable player protection in LOCAL mode.
     */
    public boolean isPlayerProtectionEnabledInLocalMode() {
        return config.getBoolean("player-protection.local-mode.enabled-in-local-mode", false);
    }

    /**
     * [SYNC-CONFIG-083] Whether to apply relaxed threshold for VAULT transactions.
     */
    public boolean isPlayerProtectionVaultRelaxedThreshold() {
        return config.getBoolean("player-protection.local-mode.vault-transaction-handling.relaxed-threshold", false);
    }

    /**
     * [SYNC-CONFIG-084] Gets whitelist of Vault event sources to bypass guard.
     */
    public List<String> getPlayerProtectionVaultBypassWhitelist() {
        return config.getStringList("player-protection.local-mode.vault-transaction-handling.bypass-whitelist");
    }

    /**
     * [SYNC-CONFIG-085] Whether to lock receiver when a transfer occurs.
     */
    public boolean isPlayerProtectionLockReceiver() {
        return config.getBoolean("player-protection.transfer-protection.lock-receiver", true);
    }

    /**
     * [SYNC-CONFIG-086] Gets amount threshold for locking receiver.
     */
    public long getPlayerProtectionReceiverLockThreshold() {
        return config.getLong("player-protection.transfer-protection.receiver-lock-threshold", 0);
    }

    /**
     * [SYNC-CONFIG-087] Whether to enable Discord webhook notifications.
     */
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord-webhook.enabled", false);
    }

    /**
     * [SYNC-CONFIG-088] Gets Discord webhook embed color (hex).
     */
    public String getDiscordWebhookEmbedColor() {
        return config.getString("discord-webhook.embed.color", "FF5555");
    }

    /**
     * [SYNC-CONFIG-089] Whether to show player name in embed.
     */
    public boolean isDiscordWebhookShowPlayerName() {
        return config.getBoolean("discord-webhook.embed.show-player-name", true);
    }

    /**
     * [SYNC-CONFIG-090] Whether to show timestamp in embed.
     */
    public boolean isDiscordWebhookShowTimestamp() {
        return config.getBoolean("discord-webhook.embed.show-timestamp", true);
    }

    /**
     * [SYNC-CONFIG-091] Gets Discord webhook bot username.
     */
    public String getDiscordWebhookUsername() {
        return config.getString("discord-webhook.embed.username", "Syncmoney Alert");
    }

    /**
     * [SYNC-CONFIG-092] Gets all webhook configurations.
     */
    public List<Map<String, Object>> getDiscordWebhooks() {
        List<Map<String, Object>> webhooks = new ArrayList<>();
        var list = config.getMapList("discord-webhook.webhooks");
        if (list != null) {
            for (var item : list) {
                if (item instanceof Map) {
                    webhooks.add(new HashMap<>((Map<String, Object>) item));
                }
            }
        }
        return webhooks;
    }

    /**
     * [SYNC-CONFIG-093] Whether to enable audit logging.
     */
    public boolean isAuditEnabled() {
        return config.getBoolean("audit.enabled", true);
    }

    /**
     * [SYNC-CONFIG-094] Gets audit log batch size.
     */
    public int getAuditBatchSize() {
        return config.getInt("audit.batch-size", 100);
    }

    /**
     * [SYNC-CONFIG-095] Gets audit log flush interval in milliseconds (0 = disabled).
     */
    public long getAuditFlushIntervalMs() {
        return config.getLong("audit.flush-interval-ms", 5000);
    }

    /**
     * [SYNC-CONFIG-096] Gets audit Redis sliding window enabled status.
     */
    public boolean isAuditRedisEnabled() {
        return config.getBoolean("audit.redis.enabled", true);
    }

    /**
     * [SYNC-CONFIG-097] Gets audit Redis sliding window size.
     */
    public int getAuditRedisWindowSize() {
        return config.getInt("audit.redis.window-size", 200);
    }

    /**
     * [SYNC-CONFIG-098] Gets audit migration threshold.
     */
    public int getAuditMigrationThreshold() {
        return config.getInt("audit.redis.migration-threshold", 50);
    }

    /**
     * [SYNC-CONFIG-099] Gets audit migration batch size.
     */
    public int getAuditMigrationBatchSize() {
        return config.getInt("audit.redis.migration-batch-size", 50);
    }

    /**
     * [SYNC-CONFIG-100] Gets audit migration interval in milliseconds.
     */
    public long getAuditMigrationIntervalMs() {
        return config.getLong("audit.redis.migration-interval-ms", 1000);
    }

    /**
     * [SYNC-CONFIG-101] Gets audit Redis flush interval in milliseconds (time-based trigger).
     * 0 = disabled, otherwise triggers migration after this time even if below threshold.
     */
    public long getAuditRedisFlushIntervalMs() {
        return config.getLong("audit.redis.flush-interval-ms", 5000);
    }

    /**
     * [SYNC-CONFIG-102] Gets audit log retention days (0 = permanent).
     */
    public int getAuditRetentionDays() {
        return config.getInt("audit.retention-days", 90);
    }

    /**
     * [SYNC-CONFIG-103] Whether to enable audit deduplication.
     */
    public boolean isAuditDeduplicationEnabled() {
        return config.getBoolean("audit.deduplication.enabled", true);
    }

    /**
     * [SYNC-CONFIG-104] Gets audit deduplication window in seconds.
     */
    public int getAuditDeduplicationWindowSeconds() {
        return config.getInt("audit.deduplication.server-window-seconds", 3600);
    }

    /**
     * [SYNC-CONFIG-105] Whether to enable automatic audit log cleanup.
     */
    public boolean isAuditCleanupEnabled() {
        return config.getBoolean("audit.cleanup.enabled", true);
    }

    /**
     * [SYNC-CONFIG-106] Gets audit log cleanup interval (hours).
     */
    public int getAuditCleanupIntervalHours() {
        return config.getInt("audit.cleanup.interval-hours", 24);
    }

    /**
     * [SYNC-CONFIG-107] Whether to enable automatic audit log export.
     */
    public boolean isAuditExportEnabled() {
        return config.getBoolean("audit.export.enabled", true);
    }

    /**
     * [SYNC-CONFIG-108] Gets audit log export folder.
     */
    public String getAuditExportFolder() {
        return config.getString("audit.export.export-folder", "./plugins/Syncmoney/logs/");
    }

    /**
     * [SYNC-CONFIG-109] Whether to delete database records after export.
     */
    public boolean isAuditDeleteAfterExport() {
        return config.getBoolean("audit.export.delete-after-export", true);
    }

    /**
     * [SYNC-CONFIG-111] Gets leaderboard cache seconds.
     */
    public int getBaltopCacheSeconds() {
        return config.getInt("baltop.cache-seconds", 30);
    }

    /**
     * [SYNC-CONFIG-112] Gets items per page.
     */
    public int getBaltopEntriesPerPage() {
        return config.getInt("baltop.entries-per-page", 10);
    }

    /**
     * [SYNC-CONFIG-113] Gets balance format (full/smart/abbreviated).
     */
    public String getBaltopFormat() {
        return config.getString("baltop.format", "smart");
    }

    /**
     * [SYNC-CONFIG-114] Gets number format configuration list.
     * Each entry is a list: [threshold, label]
     * Example: [[1000000000000, "T"], [1000000000, "B"], [1000000, "M"], [1000, "K"]]
     */
    @SuppressWarnings("unchecked")
    public List<Object> getBaltopNumberFormat() {
        return (List<Object>) config.getList("baltop.number-format");
    }

    /**
     * [SYNC-CONFIG-115] Whether to enable daily limit.
     */
    public boolean isAdminDailyLimitEnabled() {
        return config.getBoolean("admin-permissions.enforce-daily-limit", true);
    }

    /**
     * [SYNC-CONFIG-116] Gets large operation confirmation threshold.
     */
    public double getAdminConfirmThreshold() {
        return config.getDouble("admin-permissions.confirm-threshold", 500000);
    }

    /**
     * [SYNC-CONFIG-117] Gets REWARD tier daily give limit.
     */
    public double getAdminRewardDailyGiveLimit() {
        return config.getDouble("admin-permissions.levels.reward.daily-give-limit", 100000);
    }

    /**
     * [SYNC-CONFIG-118] Gets GENERAL tier daily give limit.
     */
    public double getAdminGeneralDailyGiveLimit() {
        return config.getDouble("admin-permissions.levels.general.daily-give-limit", 1000000);
    }

    /**
     * [SYNC-CONFIG-119] Gets GENERAL tier daily take limit.
     */
    public double getAdminGeneralDailyTakeLimit() {
        return config.getDouble("admin-permissions.levels.general.daily-take-limit", 1000000);
    }

    /**
     * [SYNC-CONFIG-120] Whether Redis is enabled
     */
    public boolean isRedisEnabled() {
        String manualMode = config.getString("economy.mode", "auto");
        if (manualMode.equalsIgnoreCase("local")) {
            return false;
        }

        if (config.contains("redis.enabled")) {
            return config.getBoolean("redis.enabled", true);
        }

        return config.getBoolean("pubsub-enabled", true);
    }

    /**
     * [SYNC-CONFIG-121] Whether Database is enabled
     */
    public boolean isDatabaseEnabled() {
        String manualMode = config.getString("economy.mode", "auto");
        if (manualMode.equalsIgnoreCase("local")) {
            return false;
        }

        if (config.contains("database.enabled")) {
            return config.getBoolean("database.enabled", true);
        }

        return config.getBoolean("db-enabled", true);
    }

    /**
     * [SYNC-CONFIG-122] Gets economy mode
     */
    public EconomyMode getEconomyMode() {
        String manualMode = config.getString("economy.mode", "auto");

        if (!manualMode.equals("auto")) {
            try {
                return EconomyMode.valueOf(manualMode.toUpperCase());
            } catch (IllegalArgumentException e) {
            }
        }

        return detectEconomyMode();
    }

    /**
     * [SYNC-CONFIG-123] Validates configuration values.
     * Throws IllegalArgumentException if validation fails.
     */
    public void validate() throws IllegalArgumentException {
        double minAmount = getPayMinAmount();
        double maxAmount = getPayMaxAmount();

        if (minAmount < 0) {
            throw new IllegalArgumentException("pay.min-amount cannot be negative");
        }

        if (maxAmount <= 0) {
            throw new IllegalArgumentException("pay.max-amount must be positive");
        }

        if (minAmount > maxAmount) {
            throw new IllegalArgumentException("pay.min-amount must be less than or equal to pay.max-amount");
        }

        double confirmThreshold = getPayConfirmThreshold();
        if (confirmThreshold > maxAmount) {
            throw new IllegalArgumentException("pay.confirm-threshold cannot exceed pay.max-amount");
        }

        long maxAmountPerMinute = getPlayerProtectionMaxAmountPerMinute();
        if (maxAmountPerMinute < 0) {
            throw new IllegalArgumentException("player-protection.rate-limit.max-amount-per-minute cannot be negative");
        }

        double balanceChangeThreshold = getPlayerProtectionBalanceChangeThreshold();
        if (balanceChangeThreshold <= 0) {
            throw new IllegalArgumentException(
                    "player-protection.anomaly-detection.balance-change-threshold must be positive");
        }

        boolean webEnabled = config.getBoolean("web-admin.enabled", false);
        if (webEnabled) {
            validateWebAdminConfig();
        }
    }

    /**
     * [SYNC-CONFIG-124] Validate web admin configuration.
     */
    private void validateWebAdminConfig() {
        int port = config.getInt("web-admin.server.port", 8080);
        if (port < 1024 || port > 65535) {
            getLogger().warning("Web Admin: port should be between 1024 and 65535, using default 8080");
        }

        int rateLimit = config.getInt("web-admin.security.rate-limit.requests-per-minute", 60);
        if (rateLimit < 1) {
            getLogger().warning("Web Admin: rate-limit.requests-per-minute must be at least 1, using default 60");
        }
        if (rateLimit > 10000) {
            getLogger().warning("Web Admin: rate-limit.requests-per-minute is very high (" + rateLimit
                    + "), consider reducing for security");
        }

        String apiKey = config.getString("web-admin.security.api-key", "change-me-in-production");
        if (apiKey == null || apiKey.isBlank()) {
            getLogger().severe("Web Admin: API key is not set! Please configure web-admin.security.api-key");
        } else if (apiKey.equals("change-me-in-production")) {
            getLogger().warning(
                    "Web Admin: Using default API key! Please change web-admin.security.api-key in production");
        } else if (apiKey.length() < 16) {
            getLogger().warning(
                    "Web Admin: API key is too short (less than 16 characters). Consider using a longer key for security");
        }
    }

    /**
     * [SYNC-CONFIG-125] Get logger instance.
     */
    private java.util.logging.Logger getLogger() {
        return java.util.logging.Logger.getLogger("SyncmoneyConfig");
    }

    /**
     * [SYNC-CONFIG-126] Automatically detects economy mode
     */
    public EconomyMode detectEconomyMode() {
        boolean hasRedisConfig = config.getBoolean("redis.enabled", true);
        boolean hasDatabaseConfig = config.getBoolean("database.enabled", true);

        if (!hasRedisConfig && !hasDatabaseConfig) {
            return EconomyMode.LOCAL;
        }

        String manualMode = config.getString("economy.mode", "auto");
        if (manualMode.equalsIgnoreCase("local")) {
            return EconomyMode.LOCAL;
        }
        if (manualMode.equalsIgnoreCase("sync")) {
            return EconomyMode.SYNC;
        }
        if (manualMode.equalsIgnoreCase("cmi")) {
            return EconomyMode.CMI;
        }
        if (manualMode.equalsIgnoreCase("local_redis")) {
            return EconomyMode.LOCAL_REDIS;
        }

        boolean hasRedis = isRedisEnabled();
        boolean hasDatabase = isDatabaseEnabled();

        if (hasRedis && hasDatabase) {
            return EconomyMode.SYNC;
        } else if (hasRedis) {
            String forceMode = config.getString("economy.mode", "auto");
            if (forceMode.equalsIgnoreCase("cmi")) {
                return EconomyMode.CMI;
            }
            return EconomyMode.LOCAL_REDIS;
        } else {
            return EconomyMode.LOCAL;
        }
    }

    /**
     * [SYNC-CONFIG-127] Whether is Sync mode
     */
    public boolean isSyncMode() {
        return getEconomyMode() == EconomyMode.SYNC;
    }

    /**
     * [SYNC-CONFIG-128] Whether is Local mode (SQLite only, no Redis)
     */
    public boolean isLocalMode() {
        return getEconomyMode() == EconomyMode.LOCAL;
    }

    /**
     * [SYNC-CONFIG-129] Whether is CMI mode
     */
    public boolean isCMIMode() {
        return getEconomyMode() == EconomyMode.CMI;
    }

    /**
     * [SYNC-CONFIG-130] Whether Vault interception is enabled
     */
    public boolean isVaultInterceptEnabled() {
        return config.getBoolean("economy.sync.vault-intercept", true);
    }

    /**
     * [SYNC-CONFIG-131] Whether Vault deposit interception is enabled
     */
    public boolean isVaultInterceptDeposit() {
        return config.getBoolean("economy.sync.vault-intercept-deposit", true);
    }

    /**
     * [SYNC-CONFIG-132] Whether Vault withdrawal interception is enabled
     */
    public boolean isVaultInterceptWithdraw() {
        return config.getBoolean("economy.sync.vault-intercept-withdraw", true);
    }

    /**
     * [SYNC-CONFIG-133] Gets Vault source whitelist
     */
    public java.util.List<String> getVaultSourceWhitelist() {
        return config.getStringList("economy.sync.vault-whitelist");
    }

    /**
     * [SYNC-CONFIG-134] Gets Vault ignore command list
     */
    public java.util.List<String> getVaultIgnoreCommands() {
        return config.getStringList("economy.sync.ignore-commands");
    }

    /**
     * [SYNC-CONFIG-135] Gets known plugin classes for economy detection
     */
    public java.util.List<String> getKnownPluginClasses() {
        return config.getStringList("economy.sync.known-plugin-classes");
    }

    /**
     * [SYNC-CONFIG-136] Gets CMI Redis prefix
     */
    public String getCMIRedisPrefix() {
        return config.getString("economy.cmi.redis-prefix", "syncmoney:cmi");
    }

    /**
     * [SYNC-CONFIG-137] Gets CMI detection interval (milliseconds)
     */
    public long getCMIDetectIntervalMs() {
        return config.getLong("economy.cmi.detect-interval-ms", 500);
    }

    /**
     * [SYNC-CONFIG-138] Whether CMI cross-server sync is enabled
     */
    public boolean isCMICrossServerSync() {
        return config.getBoolean("economy.cmi.cross-server-sync", true);
    }

    /**
     * [SYNC-CONFIG-139] CMI balance operation mode.
     */
    public enum CMIBalanceMode {
        API,
        INTERNAL
    }

    /**
     * [SYNC-CONFIG-140] Gets CMI balance operation mode.
     */
    public CMIBalanceMode getCMIBalanceMode() {
        String mode = config.getString("economy.cmi.balance-mode", "internal");
        try {
            return CMIBalanceMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CMIBalanceMode.INTERNAL;
        }
    }

    /**
     * [SYNC-CONFIG-141] Gets CMI debounce ticks.
     */
    public int getCMIDebounceTicks() {
        return config.getInt("economy.cmi.debounce-ticks", 5);
    }

    /**
     * [SYNC-CONFIG-142] Gets SQLite database path
     */
    public String getLocalSQLitePath() {
        return config.getString("economy.local.sqlite-path", "plugins/Syncmoney/syncmoney.db");
    }

    /**
     * [SYNC-CONFIG-143] Gets LOCAL auto-save interval (seconds)
     */
    public int getLocalAutoSaveInterval() {
        return config.getInt("economy.local.auto-save-interval", 60);
    }

    /**
     * [SYNC-CONFIG-144] Whether cross-server notification is enabled
     */
    public boolean isCrossServerNotificationsEnabled() {
        return config.getBoolean("cross-server-notifications.enabled", true);
    }

    /**
     * [SYNC-CONFIG-145] Gets cross-server notification type (all/deposit-only/withdraw-only/none)
     */
    public String getCrossServerNotifyType() {
        return config.getString("cross-server-notifications.notify-type", "all");
    }

    /**
     * [SYNC-CONFIG-146] Whether to show action bar notification
     */
    public boolean showCrossServerActionbar() {
        return config.getBoolean("cross-server-notifications.show-actionbar", true);
    }

    /**
     * [SYNC-CONFIG-147] Gets action bar display duration (ticks)
     */
    public int getActionbarDuration() {
        return config.getInt("cross-server-notifications.actionbar-duration", 80);
    }

    /**
     * [SYNC-CONFIG-148] Whether central management mode is enabled
     */
    public boolean isCentralMode() {
        return config.getBoolean("web-admin.central-mode", false);
    }

    /**
     * [SYNC-CONFIG-149] Node configuration for central management mode
     */
    public static class NodeConfig {
        public String name;
        public String url;
        public String apiKey;
        public boolean enabled;
    }

    /**
     * [SYNC-CONFIG-150] Gets list of configured nodes for central management
     */
    public List<NodeConfig> getNodes() {
        List<NodeConfig> nodes = new ArrayList<>();
        var list = config.getMapList("web-admin.nodes");
        if (list != null) {
            for (var item : list) {
                if (item instanceof Map) {
                    NodeConfig node = new NodeConfig();
                    node.name = (String) ((Map) item).getOrDefault("name", "Unknown");
                    node.url = (String) ((Map) item).getOrDefault("url", "http://localhost:8080");
                    node.apiKey = (String) ((Map) item).getOrDefault("api-key", "");
                    node.enabled = (Boolean) ((Map) item).getOrDefault("enabled", true);
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }
}
