package noietime.syncmoney.schema;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditRecord;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * [SYNC-SCHEMA-001] Database Schema Manager.
 * Responsible for version detection, upgrades, field completion, and index creation.
 * Uses an incremental update strategy to ensure data safety.
 *
 * [AsyncScheduler] All database operations should be called from async threads.
 */
public final class SchemaManager {

    /**
     * Current plugin version (corresponds to 1.1.0)
     */
    private static final int CURRENT_VERSION = 11;

    /**
     * Audit log module identifier
     */
    private static final String MODULE_AUDIT = "audit";

    private final Plugin plugin;
    private final Logger logger;
    private final String databaseType;
    private int databaseVersion = 0;

    public SchemaManager(Plugin plugin, HikariDataSource dataSource, String databaseType) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.databaseType = databaseType;

        initialize(dataSource);
    }

    /**
     * Initialize database structure and version detection
     */
    private void initialize(HikariDataSource dataSource) {
        createVersionTable(dataSource);
        loadCurrentVersion(dataSource);

        logger.info("Syncmoney database version: current " + databaseVersion + ", target " + CURRENT_VERSION);

        if (databaseVersion < CURRENT_VERSION) {
            performUpgrade(dataSource, databaseVersion, CURRENT_VERSION);
        } else if (databaseVersion > CURRENT_VERSION) {
            logger.warning("Database version (" + databaseVersion + ") is higher than plugin version (" + CURRENT_VERSION + "), please upgrade the plugin");
        }

        ensureAuditLogColumns(dataSource);
        ensureAuditLogIndexes(dataSource);
    }

    /**
     * Create version record table
     */
    private void createVersionTable(HikariDataSource dataSource) {
        String sql = switch (databaseType) {
            case "postgresql" -> """
                CREATE TABLE IF NOT EXISTS syncmoney_schema_version (
                    id VARCHAR(50) PRIMARY KEY,
                    version INT NOT NULL DEFAULT 0,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    description VARCHAR(255)
                )
                """;
            default -> """
                CREATE TABLE IF NOT EXISTS syncmoney_schema_version (
                    id VARCHAR(50) PRIMARY KEY,
                    version INT NOT NULL DEFAULT 0,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    description VARCHAR(255)
                )
                """;
        };

        executeSafely(dataSource, sql, "Create version record table");
    }

    /**
     * Load current database version
     */
    private void loadCurrentVersion(HikariDataSource dataSource) {
        String sql = "SELECT version FROM syncmoney_schema_version WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, MODULE_AUDIT);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    databaseVersion = rs.getInt("version");
                } else {
                    databaseVersion = 0;
                }
            }
        } catch (SQLException e) {
            logger.warning("Cannot load database version: " + e.getMessage());
            databaseVersion = 0;
        }
    }

    /**
     * Execute upgrade task
     */
    private void performUpgrade(HikariDataSource dataSource, int fromVersion, int toVersion) {
        logger.info("Starting database structure upgrade: from version " + fromVersion + " to " + toVersion);

        for (int v = fromVersion + 1; v <= toVersion; v++) {
            try {
                switch (v) {
                    case 10 -> upgradeToV10(dataSource);
                    case 11 -> upgradeToV11(dataSource);
                }
                recordVersion(dataSource, v, getVersionDescription(v));
            } catch (Exception e) {
                logger.severe("Failed to upgrade to version " + v + ": " + e.getMessage());
            }
        }

        logger.info("Database structure upgrade completed");


        sendUpgradeNotifications(fromVersion, toVersion);
    }

    /**
     * Send upgrade notifications to in-game admins and Discord
     */
    private void sendUpgradeNotifications(int fromVersion, int toVersion) {

        String message;
        if (plugin instanceof Syncmoney) {
            message = ((Syncmoney) plugin).getMessage("upgrade.completed")
                    .replace("{from_version}", String.valueOf(fromVersion))
                    .replace("{to_version}", String.valueOf(toVersion));
        } else {
            message = "Database upgraded from v" + fromVersion + " to v" + toVersion;
        }


        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.hasPermission("syncmoney.admin")) {
                player.sendMessage(net.kyori.adventure.text.Component.text(message));
            }
        });


        logger.info("Schema upgrade notification sent: " + fromVersion + " -> " + toVersion);


        try {
            var syncmoney = (Syncmoney) plugin;
            var notificationService = syncmoney.getNotificationService();
            if (notificationService != null) {
                var discordNotifier = notificationService.getClass()
                    .getDeclaredField("discordNotifier");
                discordNotifier.setAccessible(true);
                var notifier = (noietime.syncmoney.breaker.DiscordWebhookNotifier) discordNotifier.get(notificationService);
                if (notifier != null) {
                    notifier.sendSchemaUpgradeEvent(fromVersion, toVersion);
                }
            }
        } catch (Exception e) {
            logger.fine("Cannot send Discord webhook notification: " + e.getMessage());
        }
    }

    /**
     * Record version upgrade
     */
    private void recordVersion(HikariDataSource dataSource, int version, String description) {
        String sql;
        switch (databaseType) {
            case "mysql", "mariadb" -> sql = """
                INSERT INTO syncmoney_schema_version (id, version, description)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE version = VALUES(version), description = VALUES(description)
                """;
            case "postgresql" -> sql = """
                INSERT INTO syncmoney_schema_version (id, version, description)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version, description = EXCLUDED.description
                """;
            case "sqlite" -> sql = """
                INSERT OR REPLACE INTO syncmoney_schema_version (id, version, description)
                VALUES (?, ?, ?)
                """;
            default -> {
                logger.warning("Unknown database type: " + databaseType + ", using SQLite syntax");
                sql = """
                    INSERT OR REPLACE INTO syncmoney_schema_version (id, version, description)
                    VALUES (?, ?, ?)
                    """;
            }
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, MODULE_AUDIT);
            stmt.setInt(2, version);
            stmt.setString(3, description);
            stmt.executeUpdate();

            databaseVersion = version;
            logger.info("Database version recorded: " + version + " - " + description);
        } catch (SQLException e) {
            logger.severe("Failed to record version: " + e.getMessage());
        }
    }

    /**
     * Get version description
     */
    private String getVersionDescription(int version) {
        return switch (version) {
            case 0 -> "Initial (no audit log table)";
            case 1 -> "v1.0.0 Initial schema";
            case 10 -> "v1.0.0 Audit log table created";
            case 11 -> "v1.1.0 Cursor pagination optimization";
            default -> "Version " + version;
        };
    }

    /**
     * v1.0.0 upgrade: add audit log table
     */
    private void upgradeToV10(HikariDataSource dataSource) {
        String sql = """
            CREATE TABLE IF NOT EXISTS syncmoney_audit_log (
                id VARCHAR(36) PRIMARY KEY,
                timestamp BIGINT NOT NULL,
                type VARCHAR(20) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                player_name VARCHAR(16),
                amount DECIMAL(20, 2) NOT NULL,
                balance_after DECIMAL(20, 2) NOT NULL,
                source VARCHAR(30) NOT NULL,
                server VARCHAR(50) NOT NULL,
                target_uuid VARCHAR(36),
                target_name VARCHAR(16),
                reason VARCHAR(255),
                merged_count INT DEFAULT 1
            )
            """;

        executeSafely(dataSource, sql, "Create audit log table");
    }

    /**
     * v1.1.0 upgrade: add request_id field (maybe needed)
     */
    private void upgradeToV11(HikariDataSource dataSource) {
        logger.info("v1.1.0 upgrade: Cursor pagination optimization ready");
    }

    /**
     * Check and complete missing fields in audit log table
     */
    public void ensureAuditLogColumns(HikariDataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet columns = meta.getColumns(null, null, "syncmoney_audit_log", "%");

            Set<String> existingColumns = new HashSet<>();
            while (columns.next()) {
                existingColumns.add(columns.getString("COLUMN_NAME").toLowerCase());
            }

            if (!existingColumns.contains("request_id")) {
                executeAlterTable(dataSource,
                    "ALTER TABLE syncmoney_audit_log ADD COLUMN request_id VARCHAR(36)",
                    "Add request_id field");
            }

            if (!existingColumns.contains("server")) {
                executeAlterTable(dataSource,
                    "ALTER TABLE syncmoney_audit_log ADD COLUMN server VARCHAR(50) DEFAULT 'unknown'",
                    "Add server field");
            }

            if (!existingColumns.contains("target_uuid")) {
                executeAlterTable(dataSource,
                    "ALTER TABLE syncmoney_audit_log ADD COLUMN target_uuid VARCHAR(36)",
                    "Add target_uuid field");
            }

            if (!existingColumns.contains("target_name")) {
                executeAlterTable(dataSource,
                    "ALTER TABLE syncmoney_audit_log ADD COLUMN target_name VARCHAR(16)",
                    "Add target_name field");
            }

            if (!existingColumns.contains("reason")) {
                executeAlterTable(dataSource,
                    "ALTER TABLE syncmoney_audit_log ADD COLUMN reason VARCHAR(255)",
                    "Add reason field");
            }

            if (!existingColumns.contains("merged_count")) {
                executeAlterTable(dataSource,
                    "ALTER TABLE syncmoney_audit_log ADD COLUMN merged_count INT DEFAULT 1",
                    "Add merged_count field");
            }


            if (!existingColumns.contains("sequence")) {
                switch (databaseType) {
                    case "mysql" -> executeAlterTable(dataSource,
                        "ALTER TABLE syncmoney_audit_log ADD COLUMN sequence BIGINT DEFAULT 0",
                        "Add sequence field");
                    case "postgresql" -> executeAlterTable(dataSource,
                        "ALTER TABLE syncmoney_audit_log ADD COLUMN sequence BIGINT DEFAULT 0",
                        "Add sequence field");
                    case "sqlite" -> executeAlterTable(dataSource,
                        "ALTER TABLE syncmoney_audit_log ADD COLUMN sequence INTEGER DEFAULT 0",
                        "Add sequence field");
                }
            }

        } catch (SQLException e) {
            logger.warning("Failed to check fields: " + e.getMessage());
        }
    }

    /**
     * Check and create missing indexes in audit log table
     */
    public void ensureAuditLogIndexes(HikariDataSource dataSource) {
        Map<String, String> indexes = Map.of(
            "idx_audit_timestamp",
                "CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON syncmoney_audit_log (timestamp DESC, sequence DESC)",
            "idx_audit_player",
                "CREATE INDEX IF NOT EXISTS idx_audit_player ON syncmoney_audit_log (player_uuid, timestamp DESC, sequence DESC)",
            "idx_audit_type",
                "CREATE INDEX IF NOT EXISTS idx_audit_type ON syncmoney_audit_log (type, timestamp DESC, sequence DESC)"
        );

        for (var entry : indexes.entrySet()) {
            createIndexSafely(dataSource, entry.getValue(), entry.getKey());
        }
    }

    /**
     * Safe creation of indexes
     */
    private void createIndexSafely(HikariDataSource dataSource, String sql, String indexName) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(sql);
            logger.info("Index created successfully: " + indexName);

        } catch (SQLException e) {
            if (e.getMessage().contains("Duplicate") || e.getMessage().contains("already exists")) {
                logger.fine("Index already exists, skipping: " + indexName);
            } else {
                logger.warning("Index creation skipped: " + indexName + " - " + e.getMessage());
            }
        }
    }

    /**
     * Safe execution of SQL (capture exceptions)
     */
    private void executeSafely(HikariDataSource dataSource, String sql, String operation) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(sql);
            logger.info("Execution successful: " + operation);

        } catch (SQLException e) {
            if (e.getMessage().contains("already exists")) {
                logger.fine("Already exists, skipping: " + operation);
            } else {
                logger.warning("Execution failed: " + operation + " - " + e.getMessage());
            }
        }
    }

    /**
     * Safe execution of ALTER TABLE
     */
    private void executeAlterTable(HikariDataSource dataSource, String sql, String operation) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(sql);
            logger.info("Structure upgrade: " + operation);

        } catch (SQLException e) {
            if (e.getMessage().contains("Duplicate") || e.getMessage().contains("already exists")) {
                logger.fine("Field already exists, skipping: " + operation);
            } else {
                logger.warning("Structure upgrade skipped: " + operation + " - " + e.getMessage());
            }
        }
    }

    /**
     * Get current database version
     */
    public int getDatabaseVersion() {
        return databaseVersion;
    }

    /**
     * Get plugin version
     */
    public static int getPluginVersion() {
        return CURRENT_VERSION;
    }
}
