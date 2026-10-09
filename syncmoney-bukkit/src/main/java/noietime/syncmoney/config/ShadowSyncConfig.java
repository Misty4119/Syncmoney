package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Shadow sync configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class ShadowSyncConfig {

    private final FileConfiguration config;

    public ShadowSyncConfig(FileConfiguration config) {
        this.config = config;
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
}
