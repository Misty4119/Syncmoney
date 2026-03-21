package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Audit log exporter.
 * Exports old audit logs to .log files and optionally deletes database records.
 *
 * [AsyncScheduler] This class executes on async threads.
 */
public final class AuditLogExporter {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final HikariDataSource dataSource;
    private final Logger logger;

    private final Path exportFolder;

    public AuditLogExporter(Plugin plugin, SyncmoneyConfig config, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.config = config;
        this.dataSource = dataSource;
        this.logger = plugin.getLogger();

        String folderPath = config.audit().getAuditExportFolder();
        this.exportFolder = Paths.get(folderPath);

        try {
            Files.createDirectories(exportFolder);
        } catch (IOException e) {
            logger.severe("Failed to create export folder: " + e.getMessage());
        }
    }

    /**
     * Starts export task (scheduled execution)
     */
    public void start() {
        if (!config.audit().isAuditExportEnabled()) {
            logger.fine("Audit log export is disabled in config.");
            return;
        }

        long intervalTicks = 20L * 60 * 60 * 24;

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> {
                    try {
                        exportToLogFile(config.audit().getAuditRetentionDays());
                    } catch (Exception e) {
                        logger.warning("Failed to export audit logs: " + e.getMessage());
                    }
                },
                intervalTicks,
                intervalTicks
        );

        logger.fine("Audit log export task scheduled.");
    }

    /**
     * Exports expired logs to .log file.
     * @param daysOld How many days old the records should be
     * @return Number of records exported, -1 if failed
     */
    public int exportToLogFile(int daysOld) {
        if (!config.audit().isAuditExportEnabled()) {
            logger.fine("Audit log export is disabled.");
            return -1;
        }

        if (daysOld <= 0) {
            logger.warning("Invalid daysOld parameter: " + daysOld);
            return -1;
        }

        long cutoffTime = System.currentTimeMillis() - (daysOld * 24L * 60L * 60L * 1000L);

        List<AuditRecord> records = queryOldRecords(cutoffTime);

        if (records.isEmpty()) {
            logger.fine("No records older than " + daysOld + " days to export.");
            return 0;
        }

        try {
            String filename = String.format("audit_log_%dd_%s.log",
                    daysOld,
                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
            );
            Path filePath = exportFolder.resolve(filename);

            writeToFile(filePath, records);

            logger.fine("Exported " + records.size() + " audit records to " + filename);

            if (config.audit().isAuditDeleteAfterExport()) {
                deleteExportedRecords(cutoffTime);
            }

            return records.size();

        } catch (IOException e) {
            logger.severe("Failed to export audit log: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Queries old records.
     */
    private List<AuditRecord> queryOldRecords(long cutoffTime) {
        String sql = """
            SELECT * FROM syncmoney_audit_log
            WHERE timestamp < ?
            ORDER BY timestamp ASC
            """;

        List<AuditRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, cutoffTime);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to query old audit records: " + e.getMessage());
        }

        return records;
    }

    /**
     * Writes records to file.
     */
    private void writeToFile(Path filePath, List<AuditRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append("# ========================================\n");
        sb.append("# Syncmoney Audit Log Export\n");
        sb.append("# Generated: ").append(new Date().toString()).append("\n");
        sb.append("# Total Records: ").append(records.size()).append("\n");
        sb.append("# ========================================\n\n");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (AuditRecord record : records) {
            Date date = new Date(record.timestamp());
            String time = sdf.format(date);
            String type = record.type().name();
            String player = record.playerName() != null ? record.playerName() : "Unknown";
            String amount = record.getFormattedAmount();
            String balance = record.balanceAfter().toPlainString();
            String source = record.source().name();
            String server = record.server() != null ? record.server() : "Unknown";

            sb.append(String.format("[%s] %s | %s | %s | %s | %s | %s\n",
                    time, type, player, amount, balance, source, server));
        }

        Files.writeString(filePath, sb.toString());
    }

    /**
     * Deletes exported records.
     */
    private void deleteExportedRecords(long cutoffTime) {
        String sql = "DELETE FROM syncmoney_audit_log WHERE timestamp < ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, cutoffTime);
            int deleted = stmt.executeUpdate();

            logger.fine("Deleted " + deleted + " exported audit records from database.");

        } catch (SQLException e) {
            logger.severe("Failed to delete exported records: " + e.getMessage());
        }
    }

    /**
     * Maps ResultSet to AuditRecord.
     * [REFACTORED] Now delegates to AuditDbWriter.mapToRecord()
     */
    private AuditRecord mapToRecord(ResultSet rs) throws SQLException {
        return AuditDbWriter.mapToRecord(rs);
    }

    /**
     * Gets export folder path.
     */
    public Path getExportFolder() {
        return exportFolder;
    }

    /**
     * Gets list of files in export folder.
     */
    public List<String> getExportedFiles() {
        List<String> files = new ArrayList<>();

        try (var stream = Files.list(exportFolder)) {
            stream.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.startsWith("audit_log_"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            logger.severe("Failed to list exported files: " + e.getMessage());
        }

        return files;
    }

    /**
     * Deletes exported file.
     */
    public boolean deleteExportedFile(String filename) {
        Path filePath = exportFolder.resolve(filename);

        if (!Files.exists(filePath)) {
            return false;
        }

        try {
            Files.delete(filePath);
            logger.fine("Deleted exported file: " + filename);
            return true;
        } catch (IOException e) {
            logger.severe("Failed to delete exported file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets total size of exported files.
     */
    public long getExportedFilesSize() {
        long totalSize = 0;

        try (var stream = Files.list(exportFolder)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                totalSize += Files.size(file);
            }
        } catch (IOException e) {
            logger.severe("Failed to calculate exported files size: " + e.getMessage());
        }

        return totalSize;
    }

    /**
     * Formats file size to human-readable string.
     */
    public String getFormattedFilesSize() {
        long size = getExportedFilesSize();

        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return FormatUtil.formatFileSize(size);
        } else if (size < 1024 * 1024 * 1024) {
            return FormatUtil.formatFileSize(size);
        } else {
            return FormatUtil.formatFileSize(size);
        }
    }
}
