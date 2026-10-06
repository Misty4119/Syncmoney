package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * [SYNC-CONFIG] Migration configuration settings (CMI migration).
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class MigrationConfig {

    private final FileConfiguration config;

    public MigrationConfig(FileConfiguration config) {
        this.config = config;
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
     * [SYNC-CONFIG-016] Legacy setting retained for configuration compatibility.
     * CMI and LOCAL-to-Sync migrations always fence Syncmoney writes for snapshot safety.
     */
    public boolean isMigrationLockEconomy() {
        return config.getBoolean("migration.lock-economy", true);
    }

    /**
     * [SYNC-CONFIG-017] Whether CMI economy commands are disabled before/after safe migration
     */
    public boolean isCMIAutoDisableCommands() {
        return config.getBoolean("migration.cmi.auto-disable-commands", true);
    }

    /**
     * [SYNC-CONFIG-018] Whether the CMI economy module is disabled before a safe migration snapshot.
     */
    public boolean isCMIAutoDisableEconomy() {
        return config.getBoolean("migration.cmi.auto-disable-economy", true);
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
    public List<String> getCMISqlitePaths() {
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
}
