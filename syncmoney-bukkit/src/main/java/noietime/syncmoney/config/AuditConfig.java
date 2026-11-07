package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Audit logging configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class AuditConfig {

    private final FileConfiguration config;

    public AuditConfig(FileConfiguration config) {
        this.config = config;
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
}
