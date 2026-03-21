package noietime.syncmoney.storage.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [SYNC-DB-001] Database manager for MySQL/PostgreSQL using HikariCP.
 * Manages player table schema and operations.
 *
 * Supports MySQL and PostgreSQL, selected via database.type in config.yml.
 *
 * [AsyncScheduler] All database operations should be called from async threads.
 */
public final class DatabaseManager implements AutoCloseable {

    private final String databaseType;

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final HikariDataSource dataSource;
    private final boolean debug;

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().fine(message);
        }
    }

    private static final String CREATE_TABLE_SQL_MYSQL = """
            CREATE TABLE IF NOT EXISTS players (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(16),
                balance DECIMAL(20,2) NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 0,
                last_server VARCHAR(64),
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_player_name (player_name)
            )
            """;

    private static final String CREATE_TABLE_SQL_PGSQL = """
            CREATE TABLE IF NOT EXISTS players (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(16),
                balance DECIMAL(20,2) NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 0,
                last_server VARCHAR(64),
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private static final String CREATE_INDEX_SQL_PGSQL = """
            CREATE INDEX IF NOT EXISTS idx_player_name ON players (player_name)
            """;

    private static final String CREATE_SCHEMA_VERSION_SQL = """
            CREATE TABLE IF NOT EXISTS syncmoney_schema_version (
                id VARCHAR(50) PRIMARY KEY,
                version INT NOT NULL DEFAULT 0,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                description VARCHAR(255)
            )
            """;

    private static final String GET_SCHEMA_VERSION_SQL = """
            SELECT version FROM syncmoney_schema_version WHERE id = 1
            """;

    private static final String UPSERT_SCHEMA_VERSION_SQL = """
            INSERT INTO syncmoney_schema_version (id, version) VALUES (1, ?)
            ON DUPLICATE KEY UPDATE version = VALUES(version)
            """;

    private static final String INSERT_OR_UPDATE_SQL_MYSQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                balance = VALUES(balance),
                version = VALUES(version),
                last_server = VALUES(last_server),
                updated_at = VALUES(updated_at)
            """;

    private static final String INSERT_OR_UPDATE_SQL_PGSQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid) DO UPDATE SET
                player_name = EXCLUDED.player_name,
                balance = EXCLUDED.balance,
                version = EXCLUDED.version,
                last_server = EXCLUDED.last_server,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String SELECT_PLAYER_SQL = """
            SELECT player_uuid, player_name, balance, version, last_server, updated_at
            FROM players WHERE player_uuid = ?
            """;

    private static final String SELECT_PLAYER_BY_NAME_SQL = """
            SELECT player_uuid, player_name, balance, version, last_server, updated_at
            FROM players WHERE LOWER(player_name) = ?
            """;

    public DatabaseManager(Plugin plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.databaseType = config.database().getDatabaseType();
        this.debug = config.isDebug();

        if ("mysql".equals(databaseType)) {
            createDatabaseIfNotExists();
        } else if ("postgresql".equals(databaseType)) {
            createDatabaseIfNotExists();
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildJdbcUrl());
        hikariConfig.setUsername(config.database().getDatabaseUsername());
        hikariConfig.setPassword(config.database().getDatabasePassword());
        hikariConfig.setMaximumPoolSize(config.database().getDatabasePoolSize());
        hikariConfig.setMinimumIdle(config.database().getDatabaseMinimumIdle());
        hikariConfig.setConnectionTimeout(config.database().getDatabaseConnectionTimeout());
        hikariConfig.setMaxLifetime(config.database().getDatabaseMaxLifetime());
        hikariConfig.setIdleTimeout(config.database().getDatabaseIdleTimeout());
        hikariConfig.setPoolName("Syncmoney-HikariCP");

        if ("mysql".equals(databaseType)) {
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else if ("postgresql".equals(databaseType)) {
            hikariConfig.setDriverClassName("org.postgresql.Driver");
            hikariConfig.addDataSourceProperty("prepareThreshold", "1");
            hikariConfig.addDataSourceProperty("cacheMode", "PREPARE");
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        initializeSchema();
        plugin.getLogger().fine("Database '" + config.database().getDatabaseName() + "' ready (type: " + databaseType + ").");
    }

    /**
     * Get current SQL create table statement.
     */
    private String getCreateTableSql() {
        return "mysql".equals(databaseType) ? CREATE_TABLE_SQL_MYSQL : CREATE_TABLE_SQL_PGSQL;
    }

    /**
     * Get current SQL insert or update statement.
     */
    private String getInsertOrUpdateSql() {
        return "mysql".equals(databaseType) ? INSERT_OR_UPDATE_SQL_MYSQL : INSERT_OR_UPDATE_SQL_PGSQL;
    }

    /**
     * Create database if it does not exist (MySQL and PostgreSQL).
     * Database name is validated against a strict pattern to prevent SQL injection.
     */
    private void createDatabaseIfNotExists() {
        String host = config.database().getDatabaseHost();
        int port = config.database().getDatabasePort();
        String dbName = config.database().getDatabaseName();

        if (!isValidDatabaseName(dbName)) {
            plugin.getLogger().warning("Invalid database name '" + dbName + "'. "
                    + "Database name must match pattern [a-zA-Z0-9_]{1,63} and is not empty.");
            return;
        }

        try {
            HikariConfig tempConfig = new HikariConfig();
            tempConfig.setPoolName("Syncmoney-TempDB");

            if ("mysql".equals(databaseType)) {
                String baseUrl = String.format("jdbc:mysql://%s:%d", host, port);
                tempConfig.setJdbcUrl(baseUrl);
                tempConfig.setUsername(config.database().getDatabaseUsername());
                tempConfig.setPassword(config.database().getDatabasePassword());

                try (HikariDataSource tempDs = new HikariDataSource(tempConfig);
                        Connection conn = tempDs.getConnection();
                        java.sql.Statement stmt = conn.createStatement()) {

                    String escaped = dbName.replace("`", "``");
                    String createDbSql = String.format(
                            "CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                            escaped);
                    stmt.executeUpdate(createDbSql);
                    debug("Database '" + dbName + "' ensured to exist (MySQL).");
                }
            } else if ("postgresql".equals(databaseType)) {
                String baseUrl = String.format("jdbc:postgresql://%s:%d/postgres", host, port);
                tempConfig.setJdbcUrl(baseUrl);
                tempConfig.setUsername(config.database().getDatabaseUsername());
                tempConfig.setPassword(config.database().getDatabasePassword());
                tempConfig.setDriverClassName("org.postgresql.Driver");

                try (HikariDataSource tempDs = new HikariDataSource(tempConfig);
                        Connection conn = tempDs.getConnection();
                        java.sql.Statement stmt = conn.createStatement()) {

                    String escaped = dbName.replace("\"", "\"\"");
                    String checkDbSql = String.format(
                            "SELECT 1 FROM pg_database WHERE datname = '%s'",
                            escaped);
                    try (ResultSet rs = stmt.executeQuery(checkDbSql)) {
                        if (!rs.next()) {
                            String createDbSql = String.format("CREATE DATABASE \"%s\"", escaped);
                            stmt.executeUpdate(createDbSql);
                            debug("Database '" + dbName + "' created (PostgreSQL).");
                        } else {
                            debug("Database '" + dbName + "' already exists (PostgreSQL).");
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to create database: " + e.getMessage());
        }
    }

    /**
     * Validates database name against safe identifier rules.
     * MySQL and PostgreSQL both support: letters, digits, underscore, max 63 chars.
     * Prevents SQL injection via identifier quoting abuse.
     */
    private boolean isValidDatabaseName(String name) {
        if (name == null || name.isEmpty() || name.length() > 63) {
            return false;
        }
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    private String buildJdbcUrl() {
        if ("postgresql".equals(databaseType)) {
            return String.format("jdbc:postgresql://%s:%d/%s",
                    config.database().getDatabaseHost(),
                    config.database().getDatabasePort(),
                    config.database().getDatabaseName());
        }
        return String.format("jdbc:mysql://%s:%d/%s",
                config.database().getDatabaseHost(),
                config.database().getDatabasePort(),
                config.database().getDatabaseName());
    }

    /**
     * Initialize database table schema.
     */
    public void initializeSchema() {
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(getCreateTableSql())) {
            stmt.executeUpdate();
            debug("Database schema initialized (" + databaseType + ").");

            if ("postgresql".equals(databaseType)) {
                try {
                    stmt.executeUpdate(CREATE_INDEX_SQL_PGSQL);
                    debug("Database index created (PostgreSQL).");
                } catch (SQLException e) {
                    debug("Index creation skipped: " + e.getMessage());
                }
            }

            initializeSchemaVersion(conn);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Initialize schema version tracking table and set default version.
     */
    private void initializeSchemaVersion(Connection conn) {
        try (PreparedStatement stmt = conn.prepareStatement(CREATE_SCHEMA_VERSION_SQL)) {
            stmt.executeUpdate();

            try (PreparedStatement checkStmt = conn.prepareStatement(GET_SCHEMA_VERSION_SQL);
                    ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(UPSERT_SCHEMA_VERSION_SQL)) {
                        insertStmt.setInt(1, 1);
                        insertStmt.executeUpdate();
                        debug("Schema version initialized to 1.");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to initialize schema version: " + e.getMessage());
        }
    }

    /**
     * Get current schema version.
     * Returns 0 if no version is found.
     */
    public int getSchemaVersion() {
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(GET_SCHEMA_VERSION_SQL);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("version");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get schema version: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Set schema version.
     */
    public void setSchemaVersion(int version) {
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(UPSERT_SCHEMA_VERSION_SQL)) {
            stmt.setInt(1, version);
            stmt.executeUpdate();
            debug("Schema version updated to " + version + ".");
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set schema version: " + e.getMessage());
        }
    }

    /**
     * Get database connection.
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Insert or update player data.
     */
    public void insertOrUpdatePlayer(UUID uuid, String name, BigDecimal balance, long version, String server) {
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(getInsertOrUpdateSql())) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, name);
            stmt.setBigDecimal(3, NumericUtil.normalize(balance));
            stmt.setLong(4, version);
            stmt.setString(5, server);
            stmt.setTimestamp(6, java.sql.Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to insert/update player " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Insert or update player data (double-compatible version).
     */
    public void insertOrUpdatePlayer(UUID uuid, String name, double balance, long version, String server) {
        insertOrUpdatePlayer(uuid, name, NumericUtil.normalize(balance), version, server);
    }

    /**
     * Batch insert or update player data.
     */
    public void batchInsertOrUpdatePlayers(List<DbWriteQueue.DbWriteTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(getInsertOrUpdateSql())) {
                for (DbWriteQueue.DbWriteTask task : tasks) {
                    stmt.setString(1, task.playerUuid().toString());
                    stmt.setString(2, task.playerName());
                    stmt.setBigDecimal(3, NumericUtil.normalize(task.balance()));
                    stmt.setLong(4, task.version());
                    stmt.setString(5, task.serverName());
                    stmt.setTimestamp(6, java.sql.Timestamp.from(Instant.now()));
                    stmt.addBatch();
                }

                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                plugin.getLogger().severe("Failed to batch insert/update players: " + e.getMessage());
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to batch insert/update players: " + e.getMessage());
        }
    }

    /**
     * Get player data.
     */
    public Optional<PlayerRecord> getPlayer(UUID uuid) {
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(SELECT_PLAYER_SQL)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    Instant updatedAtInstant = updatedAt != null ? updatedAt.toInstant() : Instant.now();

                    return Optional.of(new PlayerRecord(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getBigDecimal("balance"),
                            rs.getLong("version"),
                            rs.getString("last_server"),
                            updatedAtInstant));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player " + uuid + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get player data by player name.
     * 
     * @param nameLower Player name (lowercase)
     */
    public Optional<PlayerRecord> getPlayerByName(String nameLower) {
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(SELECT_PLAYER_BY_NAME_SQL)) {
            stmt.setString(1, nameLower.toLowerCase());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    Instant updatedAtInstant = updatedAt != null ? updatedAt.toInstant() : Instant.now();

                    return Optional.of(new PlayerRecord(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getBigDecimal("balance"),
                            rs.getLong("version"),
                            rs.getString("last_server"),
                            updatedAtInstant));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player by name " + nameLower + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get all player data (for shadow sync).
     * 
     * @return List of player records
     */
    public List<PlayerRecord> getAllPlayers() {
        List<PlayerRecord> players = new ArrayList<>();
        String sql = "SELECT player_uuid, player_name, balance, version, last_server, updated_at FROM players";

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Timestamp updatedAt = rs.getTimestamp("updated_at");
                Instant updatedAtInstant = updatedAt != null ? updatedAt.toInstant() : Instant.now();

                players.add(new PlayerRecord(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getBigDecimal("balance"),
                        rs.getLong("version"),
                        rs.getString("last_server"),
                        updatedAtInstant));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get all players: " + e.getMessage());
        }

        return players;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            debug("Database connection pool closed.");
        }
    }

    /**
     * Player record - uses BigDecimal to avoid floating point errors.
     */
    public record PlayerRecord(
            UUID uuid,
            String name,
            BigDecimal balance,
            long version,
            String lastServer,
            Instant updatedAt) {
    }

    /**
     * Get HikariDataSource (for audit log and other modules).
     */
    public HikariDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Check if database connection is healthy.
     */
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}
