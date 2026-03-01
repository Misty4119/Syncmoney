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
 *
 * [MainThread] Config loaded on main thread, read-only operations.
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
     * Gets database type (mysql or postgresql)
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
     * Whether to populate Redis during migration (strongly recommended for SYNC mode).
     */
    public boolean isMigrationPopulateRedis() {
        return config.getBoolean("migration.populate-redis", true);
    }

    /**
     * Whether to lock economy operations during migration.
     * When enabled, prevents data inconsistency during migration.
     */
    public boolean isMigrationLockEconomy() {
        return config.getBoolean("migration.lock-economy", false);
    }

    /**
     * Whether to automatically disable CMI economy commands after migration
     */
    public boolean isCMIAutoDisableCommands() {
        return config.getBoolean("migration.cmi.auto-disable-commands", true);
    }

    /**
     * Whether to automatically disable CMI economy module after migration
     */
    public boolean isCMIAutoDisableEconomy() {
        return config.getBoolean("migration.cmi.auto-disable-economy", false);
    }


    /**
     * Whether to enable CMI multi-server mode
     */
    public boolean isCMIMultiServerEnabled() {
        return config.getBoolean("migration.cmi.multi-server.enabled", false);
    }

    /**
     * Gets CMI merge strategy (latest/sum/max)
     */
    public String getCMIMergeStrategy() {
        return config.getString("migration.cmi.multi-server.merge-strategy", "latest");
    }

    /**
     * Gets CMI SQLite single path
     */
    public String getCMISqlitePath() {
        return config.getString("migration.cmi.sqlite-path", "plugins/CMI/cmi.sqlite.db");
    }

    /**
     * Gets CMI SQLite multiple paths list
     */
    public java.util.List<String> getCMISqlitePaths() {
        return config.getStringList("migration.cmi.multi-server.sqlite-paths");
    }

    /**
     * Whether to enable CMI auto-detection (from DataBaseInfo.yml)
     */
    public boolean isCMIAutoDetect() {
        return config.getBoolean("migration.cmi.auto-detect", true);
    }

    /**
     * Gets CMI table prefix
     */
    public String getCMITablePrefix() {
        return config.getString("migration.cmi.table-prefix", "CMI_");
    }

    public boolean isShadowSyncEnabled() {
        return config.getBoolean("shadow-sync.enabled", false);
    }

    /**
     * Gets shadow sync target (cmi/local/all)
     */
    public String getShadowSyncTarget() {
        return config.getString("shadow-sync.target", "cmi").toLowerCase();
    }

    public int getShadowSyncBatchSize() {
        return config.getInt("shadow-sync.batch-size", 10);
    }

    /**
     * Gets CMI sync maximum delay (milliseconds).
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
     * Gets CMI SQLite database path (for shadow sync).
     */
    public String getShadowSyncCMISQLitePath() {
        return config.getString("shadow-sync.cmi-sqlite-path", "plugins/CMI/cmi.sqlite.db");
    }

    /**
     * Gets CMI MySQL host (for shadow sync, when SQLite fails).
     */
    public String getShadowSyncCMIMySQLHost() {
        return config.getString("shadow-sync.cmi-mysql.host", "localhost");
    }

    /**
     * Gets CMI MySQL port (for shadow sync).
     */
    public int getShadowSyncCMIMySQLPort() {
        return config.getInt("shadow-sync.cmi-mysql.port", 3306);
    }

    /**
     * Gets CMI MySQL username (for shadow sync).
     */
    public String getShadowSyncCMIMySQLUsername() {
        return config.getString("shadow-sync.cmi-mysql.username", "root");
    }

    /**
     * Gets CMI MySQL password (for shadow sync).
     */
    public String getShadowSyncCMIMySQLPassword() {
        return config.getString("shadow-sync.cmi-mysql.password", "");
    }

    /**
     * Gets CMI MySQL database name (for shadow sync).
     */
    public String getShadowSyncCMIMySQLDatabase() {
        return config.getString("shadow-sync.cmi-mysql.database", "minecraft");
    }

    // ========== New Shadow Sync Config (v2) ==========

    /**
     * Gets shadow sync storage type (jsonl/sqlite/mysql/postgresql)
     */
    public String getShadowSyncStorageType() {
        return config.getString("shadow-sync.storage.type", "sqlite").toLowerCase();
    }

    /**
     * Gets shadow sync SQLite path
     */
    public String getShadowSyncStorageSqlitePath() {
        return config.getString("shadow-sync.storage.sqlite.path", "plugins/Syncmoney/data/shadow-sync.db");
    }

    /**
     * Gets shadow sync JSONL path
     */
    public String getShadowSyncStorageJsonlPath() {
        return config.getString("shadow-sync.storage.jsonl.path", "plugins/Syncmoney/logs/shadow-sync/");
    }

    /**
     * Gets shadow sync MySQL host
     */
    public String getShadowSyncStorageMysqlHost() {
        return config.getString("shadow-sync.storage.mysql.host", "localhost");
    }

    /**
     * Gets shadow sync MySQL port
     */
    public int getShadowSyncStorageMysqlPort() {
        return config.getInt("shadow-sync.storage.mysql.port", 3306);
    }

    /**
     * Gets shadow sync MySQL username
     */
    public String getShadowSyncStorageMysqlUsername() {
        return config.getString("shadow-sync.storage.mysql.username", "root");
    }

    /**
     * Gets shadow sync MySQL password
     */
    public String getShadowSyncStorageMysqlPassword() {
        return config.getString("shadow-sync.storage.mysql.password", "");
    }

    /**
     * Gets shadow sync MySQL database name
     */
    public String getShadowSyncStorageMysqlDatabase() {
        return config.getString("shadow-sync.storage.mysql.database", "syncmoney_shadow");
    }

    /**
     * Gets shadow sync MySQL pool size
     */
    public int getShadowSyncStorageMysqlPoolSize() {
        return config.getInt("shadow-sync.storage.mysql.pool-size", 5);
    }

    /**
     * Gets shadow sync PostgreSQL host
     */
    public String getShadowSyncStoragePostgresHost() {
        return config.getString("shadow-sync.storage.postgresql.host", "localhost");
    }

    /**
     * Gets shadow sync PostgreSQL port
     */
    public int getShadowSyncStoragePostgresPort() {
        return config.getInt("shadow-sync.storage.postgresql.port", 5432);
    }

    /**
     * Gets shadow sync PostgreSQL username
     */
    public String getShadowSyncStoragePostgresUsername() {
        return config.getString("shadow-sync.storage.postgresql.username", "root");
    }

    /**
     * Gets shadow sync PostgreSQL password
     */
    public String getShadowSyncStoragePostgresPassword() {
        return config.getString("shadow-sync.storage.postgresql.password", "");
    }

    /**
     * Gets shadow sync PostgreSQL database name
     */
    public String getShadowSyncStoragePostgresDatabase() {
        return config.getString("shadow-sync.storage.postgresql.database", "syncmoney_shadow");
    }

    /**
     * Gets shadow sync PostgreSQL pool size
     */
    public int getShadowSyncStoragePostgresPoolSize() {
        return config.getInt("shadow-sync.storage.postgresql.pool-size", 5);
    }

    /**
     * Gets shadow sync CMI connection mode (sqlite/mysql)
     */
    public String getShadowSyncCMIConnection() {
        return config.getString("shadow-sync.cmi.connection", "sqlite").toLowerCase();
    }

    /**
     * Gets shadow sync CMI SQLite path
     */
    public String getShadowSyncCMIConnectionSqlitePath() {
        return config.getString("shadow-sync.cmi.sqlite-path", "plugins/CMI/cmi.sqlite.db");
    }

    /**
     * Gets shadow sync CMI MySQL host
     */
    public String getShadowSyncCMIConnectionMySQLHost() {
        return config.getString("shadow-sync.cmi.mysql.host", "localhost");
    }

    /**
     * Gets shadow sync CMI MySQL port
     */
    public int getShadowSyncCMIConnectionMySQLPort() {
        return config.getInt("shadow-sync.cmi.mysql.port", 3306);
    }

    /**
     * Gets shadow sync CMI MySQL username
     */
    public String getShadowSyncCMIConnectionMySQLUsername() {
        return config.getString("shadow-sync.cmi.mysql.username", "root");
    }

    /**
     * Gets shadow sync CMI MySQL password
     */
    public String getShadowSyncCMIConnectionMySQLPassword() {
        return config.getString("shadow-sync.cmi.mysql.password", "");
    }

    /**
     * Gets shadow sync CMI MySQL database name
     */
    public String getShadowSyncCMIConnectionMySQLDatabase() {
        return config.getString("shadow-sync.cmi.mysql.database", "minecraft");
    }

    /**
     * Gets shadow sync trigger batch size
     */
    public int getShadowSyncTriggerBatchSize() {
        return config.getInt("shadow-sync.trigger.batch-size", 10);
    }

    /**
     * Gets shadow sync trigger max delay milliseconds
     */
    public long getShadowSyncTriggerMaxDelayMs() {
        return config.getLong("shadow-sync.trigger.max-delay-ms", 1000);
    }

    /**
     * Whether shadow sync history feature is enabled
     */
    public boolean isShadowSyncHistoryEnabled() {
        return config.getBoolean("shadow-sync.features.history-enabled", true);
    }

    /**
     * Gets shadow sync history retention days
     */
    public int getShadowSyncHistoryRetentionDays() {
        return config.getInt("shadow-sync.features.history-retention-days", 90);
    }


    /**
     * Whether to enable cross-server portal protection.
     */
    public boolean isTransferGuardEnabled() {
        return config.getBoolean("transfer-guard.enabled", true);
    }

    /**
     * Gets portal protection maximum wait time (milliseconds).
     */
    public int getTransferGuardMaxWaitMs() {
        return config.getInt("transfer-guard.max-wait-ms", 500);
    }


    /**
     * Whether to enable economic circuit breaker mechanism.
     */
    public boolean isCircuitBreakerEnabled() {
        return config.getBoolean("circuit-breaker.enabled", true);
    }

    /**
     * Gets single transaction limit (blocks transactions exceeding this amount).
     */
    public long getCircuitBreakerMaxSingleTransaction() {
        return config.getLong("circuit-breaker.max-single-transaction", 100_000_000L);
    }

    /**
     * Gets transaction frequency limit (maximum transactions per second).
     */
    public int getCircuitBreakerMaxTransactionsPerSecond() {
        return config.getInt("circuit-breaker.max-transactions-per-second", 10);
    }

    /**
     * Gets rapid inflation threshold (total increase percentage within 5 minutes).
     */
    public double getCircuitBreakerRapidInflationThreshold() {
        return config.getDouble("circuit-breaker.rapid-inflation-threshold", 0.2);
    }

    /**
     * Gets rapid inflation check interval (minutes).
     */
    public int getCircuitBreakerInflationCheckIntervalMinutes() {
        return config.getInt("circuit-breaker.inflation-check-interval-minutes", 5);
    }

    /**
     * Gets abnormal large change detection threshold (balance change multiplier).
     */
    public double getCircuitBreakerSuddenChangeThreshold() {
        return config.getDouble("circuit-breaker.sudden-change-threshold", 100.0);
    }

    /**
     * Gets time before entering LOCKED mode after Redis disconnection (seconds).
     */
    public int getCircuitBreakerRedisDisconnectLockSeconds() {
        return config.getInt("circuit-breaker.redis-disconnect-lock-seconds", 5);
    }

    /**
     * Gets memory usage warning threshold (percentage).
     */
    public int getCircuitBreakerMemoryWarningThreshold() {
        return config.getInt("circuit-breaker.memory-warning-threshold", 80);
    }

    /**
     * Gets connection pool exhaustion warning threshold (remaining connections).
     */
    public int getCircuitBreakerPoolExhaustedWarning() {
        return config.getInt("circuit-breaker.pool-exhausted-warning", 2);
    }

    // ===== Player Protection System =====

    /**
     * Whether to enable per-player protection system.
     */
    public boolean isPlayerProtectionEnabled() {
        return config.getBoolean("player-protection.enabled", true);
    }

    /**
     * Gets maximum transactions per second per player.
     */
    public int getPlayerProtectionMaxTransactionsPerSecond() {
        return config.getInt("player-protection.rate-limit.max-transactions-per-second", 5);
    }

    /**
     * Gets maximum transactions per minute per player.
     */
    public int getPlayerProtectionMaxTransactionsPerMinute() {
        return config.getInt("player-protection.rate-limit.max-transactions-per-minute", 50);
    }

    /**
     * Gets maximum transaction amount per minute per player (0 = disabled).
     */
    public long getPlayerProtectionMaxAmountPerMinute() {
        return config.getLong("player-protection.rate-limit.max-amount-per-minute", 1000000);
    }

    /**
     * Gets warning window in seconds.
     */
    public int getPlayerProtectionWarningWindowSeconds() {
        return config.getInt("player-protection.anomaly-detection.warning-window-seconds", 30);
    }

    /**
     * Gets transaction count threshold to trigger WARNING.
     */
    public int getPlayerProtectionWarningThreshold() {
        return config.getInt("player-protection.anomaly-detection.warning-threshold", 30);
    }

    /**
     * Gets balance change multiplier threshold.
     */
    public double getPlayerProtectionBalanceChangeThreshold() {
        return config.getDouble("player-protection.anomaly-detection.balance-change-threshold", 50.0);
    }

    /**
     * Gets lock duration in minutes before auto-unlock attempt.
     */
    public int getPlayerProtectionLockDurationMinutes() {
        return config.getInt("player-protection.auto-unlock.lock-duration-minutes", 5);
    }

    /**
     * Gets maximum number of lock extensions.
     */
    public int getPlayerProtectionMaxLockExtensions() {
        return config.getInt("player-protection.auto-unlock.max-lock-extensions", 3);
    }

    /**
     * Gets number of successful transactions required to confirm unlock.
     */
    public int getPlayerProtectionUnlockTestTransactions() {
        return config.getInt("player-protection.auto-unlock.unlock-test-transactions", 3);
    }

    /**
     * Whether to enable global lock when total economy spikes.
     */
    public boolean isPlayerProtectionGlobalLockEnabled() {
        return config.getBoolean("player-protection.global-lock.enabled", true);
    }

    /**
     * Gets total inflation threshold for global lock.
     */
    public double getPlayerProtectionGlobalLockThreshold() {
        return config.getDouble("player-protection.global-lock.total-inflation-threshold", 0.2);
    }

    // ===== Discord Webhook =====

    /**
     * Whether to enable Discord webhook notifications.
     */
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord-webhook.enabled", false);
    }

    /**
     * Gets Discord webhook embed color (hex).
     */
    public String getDiscordWebhookEmbedColor() {
        return config.getString("discord-webhook.embed.color", "FF5555");
    }

    /**
     * Whether to show player name in embed.
     */
    public boolean isDiscordWebhookShowPlayerName() {
        return config.getBoolean("discord-webhook.embed.show-player-name", true);
    }

    /**
     * Whether to show timestamp in embed.
     */
    public boolean isDiscordWebhookShowTimestamp() {
        return config.getBoolean("discord-webhook.embed.show-timestamp", true);
    }

    /**
     * Gets Discord webhook bot username.
     */
    public String getDiscordWebhookUsername() {
        return config.getString("discord-webhook.embed.username", "Syncmoney Alert");
    }

    /**
     * Gets all webhook configurations.
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
     * Whether to enable audit logging.
     */
    public boolean isAuditEnabled() {
        return config.getBoolean("audit.enabled", true);
    }

    /**
     * Gets audit log batch size.
     */
    public int getAuditBatchSize() {
        return config.getInt("audit.batch-size", 100);
    }

    /**
     * Gets audit log retention days (0 = permanent).
     */
    public int getAuditRetentionDays() {
        return config.getInt("audit.retention-days", 90);
    }

    /**
     * Whether to enable automatic audit log cleanup.
     */
    public boolean isAuditCleanupEnabled() {
        return config.getBoolean("audit.cleanup.enabled", true);
    }

    /**
     * Gets audit log cleanup interval (hours).
     */
    public int getAuditCleanupIntervalHours() {
        return config.getInt("audit.cleanup.interval-hours", 24);
    }

    /**
     * Whether to enable automatic audit log export.
     */
    public boolean isAuditExportEnabled() {
        return config.getBoolean("audit.export.enabled", true);
    }

    /**
     * Gets audit log export folder.
     */
    public String getAuditExportFolder() {
        return config.getString("audit.export.export-folder", "./plugins/Syncmoney/logs/");
    }

    /**
     * Whether to delete database records after export.
     */
    public boolean isAuditDeleteAfterExport() {
        return config.getBoolean("audit.export.delete-after-export", true);
    }


    /**
     * Whether to enable baltop/leaderboard functionality.
     */
    public boolean isBaltopEnabled() {
        return config.getBoolean("baltop.enabled", true);
    }

    /**
     * Gets leaderboard cache seconds.
     */
    public int getBaltopCacheSeconds() {
        return config.getInt("baltop.cache-seconds", 30);
    }

    /**
     * Gets items per page.
     */
    public int getBaltopEntriesPerPage() {
        return config.getInt("baltop.entries-per-page", 10);
    }

    /**
     * Gets balance format (full/smart/abbreviated).
     */
    public String getBaltopFormat() {
        return config.getString("baltop.format", "smart");
    }

    /**
     * Gets number format configuration list.
     * Each entry is a list: [threshold, label]
     * Example: [[1000000000000, "兆"], [100000000, "億"], [10000, "萬"], [1000, "K"]]
     */
    @SuppressWarnings("unchecked")
    public List<Object> getBaltopNumberFormat() {
        return (List<Object>) config.getList("baltop.number-format");
    }


    /**
     * Whether to enable daily limit.
     */
    public boolean isAdminDailyLimitEnabled() {
        return config.getBoolean("admin-permissions.enforce-daily-limit", true);
    }

    /**
     * Gets large operation confirmation threshold.
     */
    public double getAdminConfirmThreshold() {
        return config.getDouble("admin-permissions.confirm-threshold", 500000);
    }

    /**
     * Gets REWARD tier daily give limit.
     */
    public double getAdminRewardDailyGiveLimit() {
        return config.getDouble("admin-permissions.levels.reward.daily-give-limit", 100000);
    }

    /**
     * Gets GENERAL tier daily give limit.
     */
    public double getAdminGeneralDailyGiveLimit() {
        return config.getDouble("admin-permissions.levels.general.daily-give-limit", 1000000);
    }

    /**
     * Gets GENERAL tier daily take limit.
     */
    public double getAdminGeneralDailyTakeLimit() {
        return config.getDouble("admin-permissions.levels.general.daily-take-limit", 1000000);
    }


    /**
     * Whether Redis is enabled
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
     * Whether Database is enabled
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
     * Gets economy mode
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
     * Automatically detects economy mode
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
     * Whether is Sync mode
     */
    public boolean isSyncMode() {
        return getEconomyMode() == EconomyMode.SYNC;
    }

    /**
     * Whether is Local mode (SQLite only, no Redis)
     */
    public boolean isLocalMode() {
        return getEconomyMode() == EconomyMode.LOCAL;
    }

    /**
     * Whether is CMI mode
     */
    public boolean isCMIMode() {
        return getEconomyMode() == EconomyMode.CMI;
    }


    /**
     * Whether Vault interception is enabled
     */
    public boolean isVaultInterceptEnabled() {
        return config.getBoolean("economy.sync.vault-intercept", true);
    }

    /**
     * Whether Vault deposit interception is enabled
     */
    public boolean isVaultInterceptDeposit() {
        return config.getBoolean("economy.sync.vault-intercept-deposit", true);
    }

    /**
     * Whether Vault withdrawal interception is enabled
     */
    public boolean isVaultInterceptWithdraw() {
        return config.getBoolean("economy.sync.vault-intercept-withdraw", true);
    }

    /**
     * Gets Vault source whitelist
     */
    public java.util.List<String> getVaultSourceWhitelist() {
        return config.getStringList("economy.sync.vault-whitelist");
    }

    /**
     * Gets Vault ignore command list
     */
    public java.util.List<String> getVaultIgnoreCommands() {
        return config.getStringList("economy.sync.ignore-commands");
    }


    /**
     * Gets CMI Redis prefix
     */
    public String getCMIRedisPrefix() {
        return config.getString("economy.cmi.redis-prefix", "syncmoney:cmi");
    }

    /**
     * Gets CMI detection interval (milliseconds)
     */
    public long getCMIDetectIntervalMs() {
        return config.getLong("economy.cmi.detect-interval-ms", 500);
    }

    /**
     * Whether CMI cross-server sync is enabled
     */
    public boolean isCMICrossServerSync() {
        return config.getBoolean("economy.cmi.cross-server-sync", true);
    }

    /**
     * CMI balance operation mode.
     */
    public enum CMIBalanceMode {
        API,        // Use CMI API (slower but more compatible)
        INTERNAL    // Direct database access (faster, default)
    }

    /**
     * Gets CMI balance operation mode.
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
     * Gets CMI debounce ticks.
     */
    public int getCMIDebounceTicks() {
        return config.getInt("economy.cmi.debounce-ticks", 5);
    }


    /**
     * Gets SQLite database path
     */
    public String getLocalSQLitePath() {
        return config.getString("economy.local.sqlite-path", "plugins/Syncmoney/syncmoney.db");
    }

    /**
     * Gets LOCAL auto-save interval (seconds)
     */
    public int getLocalAutoSaveInterval() {
        return config.getInt("economy.local.auto-save-interval", 60);
    }


    /**
     * Whether cross-server notification is enabled
     */
    public boolean isCrossServerNotificationsEnabled() {
        return config.getBoolean("cross-server-notifications.enabled", true);
    }

    /**
     * Gets cross-server notification type (all/deposit-only/withdraw-only/none)
     */
    public String getCrossServerNotifyType() {
        return config.getString("cross-server-notifications.notify-type", "all");
    }

    /**
     * Whether to show action bar notification
     */
    public boolean showCrossServerActionbar() {
        return config.getBoolean("cross-server-notifications.show-actionbar", true);
    }

    /**
     * Gets action bar display duration (ticks)
     */
    public int getActionbarDuration() {
        return config.getInt("cross-server-notifications.actionbar-duration", 80);
    }
}
