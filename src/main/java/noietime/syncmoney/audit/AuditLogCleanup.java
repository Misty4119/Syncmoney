package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Audit log auto-cleanup task.
 * Periodically cleans up expired audit log records.
 *
 * [AsyncScheduler] This class executes on async threads.
 */
public final class AuditLogCleanup {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final HikariDataSource dataSource;
    private final Logger logger;

    public AuditLogCleanup(Plugin plugin, SyncmoneyConfig config, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.config = config;
        this.dataSource = dataSource;
        this.logger = plugin.getLogger();
    }

    /**
     * Starts scheduled cleanup task.
     */
    public void start() {
        if (!config.isAuditCleanupEnabled()) {
            logger.fine("Audit log cleanup is disabled.");
            return;
        }

        long intervalHours = config.getAuditCleanupIntervalHours();
        long intervalTicks = intervalHours * 1200L;
        long initialDelay = intervalTicks;

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> performCleanup(),
                initialDelay,
                intervalTicks
        );

        logger.fine("Audit log cleanup scheduled every " + intervalHours + " hours (" + intervalTicks + " ticks).");
    }

    /**
     * Performs the cleanup operation.
     */
    public void performCleanup() {
        int retentionDays = config.getAuditRetentionDays();
        if (retentionDays <= 0) {
            logger.fine("Audit log retention is set to infinite, skipping cleanup.");
            return;
        }

        long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

        int deleted = deleteOldRecords(cutoffTime);

        if (deleted > 0) {
            logger.fine("Cleaned up " + deleted + " audit log entries older than " + retentionDays + " days.");

            optimizeTable();
        }
    }

    /**
     * Deletes expired records.
     */
    private int deleteOldRecords(long cutoffTime) {
        String sql = "DELETE FROM syncmoney_audit_log WHERE timestamp < ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, cutoffTime);
            return stmt.executeUpdate();

        } catch (SQLException e) {
            logger.severe("Failed to cleanup audit log: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Cleans up small transactions while preserving large ones.
     * Strategy: Delete records older than retention days AND with amount below threshold.
     */
    public int cleanupWithThreshold(double minAmount, int retentionDays) {
        if (retentionDays <= 0 || minAmount <= 0) {
            return 0;
        }

        long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

        String sql = """
            DELETE FROM syncmoney_audit_log
            WHERE timestamp < ?
            AND ABS(amount) < ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, cutoffTime);
            stmt.setDouble(2, minAmount);
            return stmt.executeUpdate();

        } catch (SQLException e) {
            logger.severe("Failed to cleanup audit log with threshold: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Cleans up records for a specific player.
     */
    public int cleanupPlayerRecords(String playerUuid) {
        String sql = "DELETE FROM syncmoney_audit_log WHERE player_uuid = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerUuid);
            return stmt.executeUpdate();

        } catch (SQLException e) {
            logger.severe("Failed to cleanup player audit records: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Optimizes database table.
     * [AU07 FIX] Supports MySQL, PostgreSQL, and SQLite.
     */
    private void optimizeTable() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            String dbType = conn.getMetaData().getDatabaseProductName().toLowerCase();

            if (dbType.contains("mysql")) {
                stmt.execute("OPTIMIZE TABLE syncmoney_audit_log");
                logger.fine("MySQL: Audit log table optimized.");
            } else if (dbType.contains("postgresql")) {
                stmt.execute("VACUUM ANALYZE syncmoney_audit_log");
                logger.fine("PostgreSQL: Audit log table vacuumed and analyzed.");
            } else if (dbType.contains("sqlite")) {
                stmt.execute("VACUUM");
                logger.fine("SQLite: Database vacuumed.");
            } else {
                logger.fine("Database type not supported for optimization: " + dbType);
            }

        } catch (SQLException e) {
            logger.warning("Failed to optimize audit log table: " + e.getMessage());
        }
    }

    /**
     * Gets the total record count.
     */
    public long getRecordCount() {
        String sql = "SELECT COUNT(*) FROM syncmoney_audit_log";

        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.severe("Failed to get audit log count: " + e.getMessage());
        }

        return 0;
    }

    /**
     * Gets estimated database size.
     */
    public String getDatabaseSize() {
        String sql = """
            SELECT ROUND(SUM(data_length + index_length) / 1024 / 1024, 2) AS size_mb
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
            AND table_name = 'syncmoney_audit_log'
            """;

        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getString("size_mb") + " MB";
            }
        } catch (SQLException e) {
            logger.severe("Failed to get database size: " + e.getMessage());
        }

        return "Unknown";
    }
}
