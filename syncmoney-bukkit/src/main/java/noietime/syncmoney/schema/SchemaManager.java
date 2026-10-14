package noietime.syncmoney.schema;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Owns the additive audit schema migration and its independent version row. */
public final class SchemaManager {

    private static final int CURRENT_VERSION = 11;
    private static final String MODULE_AUDIT = "audit";
    private static final String AUDIT_TABLE = "syncmoney_audit_log";

    private final Plugin plugin;
    private final Logger logger;
    private final String databaseType;
    private int databaseVersion;

    public SchemaManager(Plugin plugin, HikariDataSource dataSource, String databaseType) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.databaseType = databaseType.toLowerCase();
        initialize(dataSource);
    }

    private void initialize(HikariDataSource dataSource) {
        try {
            createVersionTable(dataSource);
            loadCurrentVersion(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read audit schema metadata", e);
        }

        logger.info("Syncmoney audit schema version: current " + databaseVersion + ", target " + CURRENT_VERSION);
        if (databaseVersion > CURRENT_VERSION) {
            throw new IllegalStateException("Audit schema version " + databaseVersion
                    + " is newer than this plugin supports (" + CURRENT_VERSION + "); no audit schema changes were made.");
        }

        if (databaseVersion < CURRENT_VERSION) {
            performUpgrade(dataSource, databaseVersion, CURRENT_VERSION);
        } else {
            try {
                ensureAuditLogColumnsOrThrow(dataSource);
                ensureAuditLogIndexesOrThrow(dataSource);
                verifyAuditSchema(dataSource);
            } catch (SQLException e) {
                throw new IllegalStateException("Audit schema validation or repair failed at version "
                        + databaseVersion + "; metadata was not changed", e);
            }
        }
    }

    private void createVersionTable(HikariDataSource dataSource) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS syncmoney_schema_version (
                    id VARCHAR(50) PRIMARY KEY,
                    version INT NOT NULL DEFAULT 0,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    description VARCHAR(255)
                )
                """;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void loadCurrentVersion(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT version FROM syncmoney_schema_version WHERE id = ?")) {
            statement.setString(1, MODULE_AUDIT);
            try (ResultSet result = statement.executeQuery()) {
                databaseVersion = result.next() ? result.getInt("version") : 0;
            }
        }
    }

    private void performUpgrade(HikariDataSource dataSource, int fromVersion, int toVersion) {
        logger.info("Starting audit schema upgrade: from version " + fromVersion + " to " + toVersion);
        for (int version = fromVersion + 1; version <= toVersion; version++) {
            try {
                switch (version) {
                    case 10 -> {
                        upgradeToV10(dataSource);
                        verifyV10AuditSchema(dataSource);
                    }
                    case 11 -> {
                        upgradeToV11();
                        ensureAuditLogColumnsOrThrow(dataSource);
                        ensureAuditLogIndexesOrThrow(dataSource);
                        verifyAuditSchema(dataSource);
                    }
                    default -> {
                        // Historical versions 1–9 had no audit DDL in this schema manager.
                    }
                }
                recordVersion(dataSource, version, getVersionDescription(version));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed audit schema migration to version " + version
                        + "; version metadata was not advanced past " + databaseVersion, e);
            }
        }

        logger.info("Audit schema upgrade completed at version " + databaseVersion);
        sendUpgradeNotifications(fromVersion, toVersion);
    }

    private void upgradeToV10(HikariDataSource dataSource) throws SQLException {
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
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void verifyV10AuditSchema(HikariDataSource dataSource) throws SQLException {
        Set<String> requiredColumns = Set.of("id", "timestamp", "type", "player_uuid", "player_name", "amount",
                "balance_after", "source", "server", "target_uuid", "target_name", "reason", "merged_count");
        try (Connection connection = dataSource.getConnection()) {
            Set<String> missingColumns = new LinkedHashSet<>(requiredColumns);
            missingColumns.removeAll(readAuditColumns(connection));
            if (!missingColumns.isEmpty()) {
                throw new SQLException("Required v10 audit columns are missing: " + missingColumns);
            }
        }
    }

    private void upgradeToV11() {
        logger.info("v1.1.0 audit upgrade: cursor pagination schema checks");
    }

    public void ensureAuditLogColumns(HikariDataSource dataSource) {
        try {
            ensureAuditLogColumnsOrThrow(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot ensure audit log columns", e);
        }
    }

    private void ensureAuditLogColumnsOrThrow(HikariDataSource dataSource) throws SQLException {
        Map<String, String> additions = new LinkedHashMap<>();
        additions.put("request_id", "VARCHAR(36)");
        additions.put("server", "VARCHAR(50) DEFAULT 'unknown'");
        additions.put("target_uuid", "VARCHAR(36)");
        additions.put("target_name", "VARCHAR(16)");
        additions.put("reason", "VARCHAR(255)");
        additions.put("merged_count", "INT DEFAULT 1");
        additions.put("sequence", switch (databaseType) {
            case "mysql", "mariadb", "postgresql" -> "BIGINT DEFAULT 0";
            case "sqlite" -> "INTEGER DEFAULT 0";
            default -> throw new SQLException("Unsupported database type for audit schema: " + databaseType);
        });

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            Set<String> columns = readAuditColumns(connection);
            for (Map.Entry<String, String> addition : additions.entrySet()) {
                String name = addition.getKey();
                if (columns.contains(name)) {
                    continue;
                }

                try {
                    statement.executeUpdate("ALTER TABLE " + AUDIT_TABLE + " ADD COLUMN " + name + " "
                            + addition.getValue());
                } catch (SQLException e) {
                    if (!readAuditColumns(connection).contains(name)) {
                        throw e;
                    }
                }
                columns.add(name);
            }
        }
    }

    public void ensureAuditLogIndexes(HikariDataSource dataSource) {
        try {
            ensureAuditLogIndexesOrThrow(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot ensure audit log indexes", e);
        }
    }

    private void ensureAuditLogIndexesOrThrow(HikariDataSource dataSource) throws SQLException {
        List<IndexDefinition> indexes = auditIndexes();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (IndexDefinition index : indexes) {
                if (readAuditIndexes(connection).containsKey(index.name())) {
                    continue;
                }

                String ifNotExists = switch (databaseType) {
                    case "mysql", "mariadb" -> "";
                    default -> "IF NOT EXISTS ";
                };
                String sql = "CREATE INDEX " + ifNotExists + index.name() + " ON " + AUDIT_TABLE
                        + " (" + index.sqlColumns() + ")";
                try {
                    statement.executeUpdate(sql);
                } catch (SQLException e) {
                    if (!readAuditIndexes(connection).containsKey(index.name())) {
                        throw e;
                    }
                }
                if (!readAuditIndexes(connection).containsKey(index.name())) {
                    throw new SQLException("Required audit index was not created: " + index.name());
                }
            }
        }
    }

    private void verifyAuditSchema(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> actualColumns = readAuditColumns(connection);
            Set<String> requiredColumns = Set.of("id", "timestamp", "type", "player_uuid", "player_name", "amount",
                    "balance_after", "source", "server", "target_uuid", "target_name", "reason", "merged_count",
                    "request_id", "sequence");
            Set<String> missingColumns = new LinkedHashSet<>(requiredColumns);
            missingColumns.removeAll(actualColumns);
            if (!missingColumns.isEmpty()) {
                throw new SQLException("Required audit columns are missing: " + missingColumns);
            }

            Map<String, IndexDetails> actualIndexes = readAuditIndexes(connection);
            List<String> invalidIndexes = auditIndexes().stream()
                    .filter(required -> !matchesIndex(required, actualIndexes.get(required.name())))
                    .map(IndexDefinition::name)
                    .toList();
            if (!invalidIndexes.isEmpty()) {
                throw new SQLException("Required audit indexes are missing or have conflicting definitions: "
                        + invalidIndexes);
            }
        }
    }

    private Set<String> readAuditColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        if (databaseType.equals("sqlite")) {
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("PRAGMA main.table_info(" + sqliteString(AUDIT_TABLE) + ")")) {
                while (result.next()) {
                    columns.add(result.getString("name").toLowerCase());
                }
            }
            return columns;
        }

        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = metadataCatalog(connection);
        String schema = metadataSchema(connection);
        try (ResultSet result = metadata.getColumns(catalog, escapeMetadataPattern(metadata, schema),
                escapeMetadataPattern(metadata, AUDIT_TABLE), "%")) {
            while (result.next()) {
                if (isAuditMetadataRow(result, catalog, schema)) {
                    columns.add(result.getString("COLUMN_NAME").toLowerCase());
                }
            }
        }
        return columns;
    }

    private Map<String, IndexDetails> readAuditIndexes(Connection connection) throws SQLException {
        if (databaseType.equals("sqlite")) {
            return readSqliteAuditIndexes(connection);
        }

        Map<String, TreeMap<Integer, String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, String>> directionsByIndex = new LinkedHashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = metadataCatalog(connection);
        String schema = metadataSchema(connection);
        try (ResultSet result = metadata.getIndexInfo(catalog, schema, AUDIT_TABLE, false, false)) {
            while (result.next()) {
                if (!isAuditMetadataRow(result, catalog, schema)) continue;
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    String normalizedName = name.toLowerCase();
                    int position = result.getInt("ORDINAL_POSITION");
                    columnsByIndex.computeIfAbsent(normalizedName, ignored -> new TreeMap<>())
                            .put(position, column.toLowerCase());
                    String direction = result.getString("ASC_OR_DESC");
                    directionsByIndex.computeIfAbsent(normalizedName, ignored -> new TreeMap<>())
                            .put(position, normalizeDirection(direction));
                }
            }
        }
        Map<String, IndexDetails> indexes = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<Integer, String>> entry : columnsByIndex.entrySet()) {
            List<String> columns = List.copyOf(entry.getValue().values());
            List<String> directions = new java.util.ArrayList<>(directionsByIndex.get(entry.getKey()).values());
            indexes.put(entry.getKey(), new IndexDetails(columns, directions));
        }
        return indexes;
    }

    private Map<String, IndexDetails> readSqliteAuditIndexes(Connection connection) throws SQLException {
        List<String> names = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA main.index_list(" + sqliteString(AUDIT_TABLE) + ")")) {
            while (result.next()) {
                String name = result.getString("name");
                if (name != null) names.add(name);
            }
        }

        Map<String, IndexDetails> indexes = new LinkedHashMap<>();
        for (String name : names) {
            TreeMap<Integer, String> columns = new TreeMap<>();
            TreeMap<Integer, String> directions = new TreeMap<>();
            String sql = "PRAGMA main.index_xinfo(" + sqliteString(name) + ")";
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    if (result.getInt("key") == 0) continue;
                    int position = result.getInt("seqno");
                    String column = result.getString("name");
                    columns.put(position, column == null ? "<expression>" : column.toLowerCase());
                    directions.put(position, result.getInt("desc") == 0 ? "ASC" : "DESC");
                }
            }
            if (!columns.isEmpty()) {
                indexes.put(name.toLowerCase(),
                        new IndexDetails(List.copyOf(columns.values()), List.copyOf(directions.values())));
            }
        }
        return indexes;
    }

    private boolean isAuditMetadataRow(ResultSet result, String catalog, String schema) throws SQLException {
        return AUDIT_TABLE.equalsIgnoreCase(result.getString("TABLE_NAME"))
                && matchesMetadataNamespace(catalog, result.getString("TABLE_CAT"))
                && matchesMetadataNamespace(schema, result.getString("TABLE_SCHEM"));
    }

    private boolean matchesMetadataNamespace(String expected, String actual) {
        return expected == null || expected.equalsIgnoreCase(actual == null ? "" : actual);
    }

    private String escapeMetadataPattern(DatabaseMetaData metadata, String value) throws SQLException {
        if (value == null) return null;
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    private String sqliteString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String metadataCatalog(Connection connection) throws SQLException {
        return switch (databaseType) {
            case "mysql", "mariadb", "postgresql" -> requireMetadataNamespace(connection.getCatalog(), "catalog");
            case "sqlite" -> null;
            default -> throw new SQLException("Unsupported database type for audit schema metadata: " + databaseType);
        };
    }

    private String metadataSchema(Connection connection) throws SQLException {
        return switch (databaseType) {
            case "postgresql" -> requireMetadataNamespace(connection.getSchema(), "schema");
            case "sqlite" -> "main";
            case "mysql", "mariadb" -> null;
            default -> throw new SQLException("Unsupported database type for audit schema metadata: " + databaseType);
        };
    }

    private String requireMetadataNamespace(String value, String kind) throws SQLException {
        if (value == null || value.isBlank()) {
            throw new SQLException("Cannot scope audit schema metadata because the connection " + kind + " is unavailable");
        }
        return value;
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) return null;
        String normalized = direction.toUpperCase();
        if (normalized.startsWith("D")) return "DESC";
        if (normalized.startsWith("A")) return "ASC";
        return null;
    }

    private List<IndexDefinition> auditIndexes() {
        return List.of(
                new IndexDefinition("idx_audit_timestamp", List.of("timestamp", "sequence"),
                        List.of("DESC", "DESC"), "timestamp DESC, sequence DESC"),
                new IndexDefinition("idx_audit_player", List.of("player_uuid", "timestamp", "sequence"),
                        List.of("ASC", "DESC", "DESC"), "player_uuid, timestamp DESC, sequence DESC"),
                new IndexDefinition("idx_audit_type_timestamp_sequence", List.of("type", "timestamp", "sequence"),
                        List.of("ASC", "DESC", "DESC"), "type, timestamp DESC, sequence DESC"));
    }

    private boolean matchesIndex(IndexDefinition expected, IndexDetails actual) {
        if (actual == null || !expected.columns().equals(actual.columns())) return false;
        for (int i = 0; i < actual.directions().size(); i++) {
            String actualDirection = actual.directions().get(i);
            if (!expected.directions().get(i).equals(actualDirection)) return false;
        }
        return true;
    }

    private void recordVersion(HikariDataSource dataSource, int version, String description) throws SQLException {
        String sql = switch (databaseType) {
            case "mysql", "mariadb" -> """
                    INSERT INTO syncmoney_schema_version (id, version, description)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE version = VALUES(version), description = VALUES(description)
                    """;
            case "postgresql" -> """
                    INSERT INTO syncmoney_schema_version (id, version, description)
                    VALUES (?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version, description = EXCLUDED.description
                    """;
            case "sqlite" -> """
                    INSERT OR REPLACE INTO syncmoney_schema_version (id, version, description)
                    VALUES (?, ?, ?)
                    """;
            default -> throw new SQLException("Unsupported database type for audit schema: " + databaseType);
        };

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, MODULE_AUDIT);
            statement.setInt(2, version);
            statement.setString(3, description);
            statement.executeUpdate();
        }

        databaseVersion = version;
        logger.info("Audit schema version recorded: " + version + " - " + description);
    }

    private String getVersionDescription(int version) {
        return switch (version) {
            case 0 -> "Initial (no audit log table)";
            case 1 -> "v1.0.0 Initial schema";
            case 10 -> "v1.0.0 Audit log table created";
            case 11 -> "v1.1.0 Cursor pagination optimization";
            default -> "Version " + version;
        };
    }

    private void sendUpgradeNotifications(int fromVersion, int toVersion) {
        String message;
        if (plugin instanceof Syncmoney syncmoney) {
            message = syncmoney.getMessage("upgrade.completed")
                    .replace("{from_version}", String.valueOf(fromVersion))
                    .replace("{to_version}", String.valueOf(toVersion));
        } else {
            message = "Database upgraded from v" + fromVersion + " to v" + toVersion;
        }

        noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, player -> {
            if (player.hasPermission("syncmoney.admin")) {
                player.sendMessage(net.kyori.adventure.text.Component.text(message));
            }
        });

        logger.info("Audit schema upgrade notification sent: " + fromVersion + " -> " + toVersion);
        if (plugin instanceof Syncmoney syncmoney) {
            try {
                var notificationService = syncmoney.getNotificationService();
                if (notificationService != null) {
                    var discordNotifier = notificationService.getClass().getDeclaredField("discordNotifier");
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
    }

    private record IndexDefinition(String name, List<String> columns, List<String> directions, String sqlColumns) {}

    private record IndexDetails(List<String> columns, List<String> directions) {}

    public int getDatabaseVersion() {
        return databaseVersion;
    }

    public static int getPluginVersion() {
        return CURRENT_VERSION;
    }
}
