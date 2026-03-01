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
            plugin.getLogger().info("[DEBUG] " + message);
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
        this.databaseType = config.getDatabaseType();
        this.debug = config.isDebug();

        if ("mysql".equals(databaseType)) {
            createDatabaseIfNotExists();
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildJdbcUrl());
        hikariConfig.setUsername(config.getDatabaseUsername());
        hikariConfig.setPassword(config.getDatabasePassword());
        hikariConfig.setMaximumPoolSize(config.getDatabasePoolSize());
        hikariConfig.setConnectionTimeout(config.getDatabaseConnectionTimeout());
        hikariConfig.setMaxLifetime(config.getDatabaseMaxLifetime());
        hikariConfig.setIdleTimeout(config.getDatabaseIdleTimeout());
        hikariConfig.setPoolName("Syncmoney-HikariCP");

        if ("mysql".equals(databaseType)) {
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else if ("postgresql".equals(databaseType)) {
            hikariConfig.addDataSourceProperty("prepareThreshold", "1");
            hikariConfig.addDataSourceProperty("cacheMode", "PREPARE");
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        initializeSchema();
        plugin.getLogger().info("Database '" + config.getDatabaseName() + "' ready (type: " + databaseType + ").");
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
     * Create database if it does not exist (MySQL only).
     */
    private void createDatabaseIfNotExists() {
        String baseUrl = String.format("jdbc:mysql://%s:%d",
                config.getDatabaseHost(),
                config.getDatabasePort());

        try {
            HikariConfig tempConfig = new HikariConfig();
            tempConfig.setJdbcUrl(baseUrl);
            tempConfig.setUsername(config.getDatabaseUsername());
            tempConfig.setPassword(config.getDatabasePassword());
            tempConfig.setPoolName("Syncmoney-TempDB");

            try (HikariDataSource tempDs = new HikariDataSource(tempConfig);
                 Connection conn = tempDs.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {

                String createDbSql = String.format(
                        "CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                        config.getDatabaseName());
                stmt.executeUpdate(createDbSql);
                debug("Database '" + config.getDatabaseName() + "' ensured to exist.");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to create database: " + e.getMessage());
        }
    }

    private String buildJdbcUrl() {
        if ("postgresql".equals(databaseType)) {
            return String.format("jdbc:postgresql://%s:%d/%s",
                    config.getDatabaseHost(),
                    config.getDatabasePort(),
                    config.getDatabaseName());
        }
        return String.format("jdbc:mysql://%s:%d/%s",
                config.getDatabaseHost(),
                config.getDatabasePort(),
                config.getDatabaseName());
    }

    /**
     * Initialize database table schema.
     */
    public void initializeSchema() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(getCreateTableSql())) {
            stmt.executeUpdate();
            debug("Database schema initialized (" + databaseType + ").");

            // PostgreSQL requires additional index creation (MySQL includes this in CREATE TABLE)
            if ("postgresql".equals(databaseType)) {
                try {
                    stmt.executeUpdate(CREATE_INDEX_SQL_PGSQL);
                    debug("Database index created (PostgreSQL).");
                } catch (SQLException e) {
                    debug("Index creation skipped: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
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
                    return Optional.of(new PlayerRecord(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getBigDecimal("balance"),
                            rs.getLong("version"),
                            rs.getString("last_server"),
                            rs.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player " + uuid + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get player data by player name.
     * @param nameLower Player name (lowercase)
     */
    public Optional<PlayerRecord> getPlayerByName(String nameLower) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_PLAYER_BY_NAME_SQL)) {
            stmt.setString(1, nameLower.toLowerCase());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PlayerRecord(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getBigDecimal("balance"),
                            rs.getLong("version"),
                            rs.getString("last_server"),
                            rs.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player by name " + nameLower + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get all player data (for shadow sync).
     * @return List of player records
     */
    public List<PlayerRecord> getAllPlayers() {
        List<PlayerRecord> players = new ArrayList<>();
        String sql = "SELECT player_uuid, player_name, balance, version, last_server, updated_at FROM players";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                players.add(new PlayerRecord(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getBigDecimal("balance"),
                        rs.getLong("version"),
                        rs.getString("last_server"),
                        rs.getTimestamp("updated_at").toInstant()
                ));
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
            Instant updatedAt
    ) {}

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
