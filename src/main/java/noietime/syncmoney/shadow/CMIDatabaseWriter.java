package noietime.syncmoney.shadow;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * CMI Database Writer.
 * Writes Syncmoney balance data back to CMI database.
 *
 * Prefers local SQLite, falls back to MySQL on failure.
 *
 * [AsyncScheduler] Database writes should be executed on async threads.
 */
public final class CMIDatabaseWriter {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private HikariDataSource dataSource;
    private volatile boolean initialized = false;
    private volatile boolean useSQLite = false;
    private final boolean debug;

    /**
     * Debug level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }


    private static final String TABLE_SQLITE = "user";
    private static final String TABLE_MYSQL = "cmi_user";

    /**
     * Gets current table name in use.
     */
    private String getTableName() {
        return useSQLite ? TABLE_SQLITE : TABLE_MYSQL;
    }

    public CMIDatabaseWriter(Plugin plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.debug = config.isDebug();
    }

    /**
     * Initializes database connection pool (lazy initialization).
     * Prefers SQLite, falls back to MySQL on failure.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        String sqlitePath = config.getShadowSyncCMISQLitePath();
        File sqliteFile = new File(sqlitePath);

        if (!sqliteFile.exists()) {
            File serverDir = plugin.getDataFolder().getParentFile();
            File[] possiblePaths = {
                new File(serverDir, "CMI/cmi.sqlite.db"),
                new File(serverDir, "plugins/CMI/cmi.sqlite.db"),
                new File("plugins/CMI/cmi.sqlite.db")
            };

            for (File path : possiblePaths) {
                if (path.exists()) {
                    sqliteFile = path;
                    sqlitePath = path.getAbsolutePath();
                    break;
                }
            }
        }

        if (sqliteFile.exists()) {
            try {
                this.dataSource = createSQLiteDataSource(sqlitePath);
                useSQLite = true;
                initialized = true;
                debug("CMI Database Writer initialized (SQLite): " + sqlitePath);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("SQLite initialization failed: " + e.getMessage() + ", falling back to MySQL");
            }
        } else {
            plugin.getLogger().info("CMI SQLite file not found, falling back to MySQL");
        }

        try {
            this.dataSource = createMySQLDataSource();
            useSQLite = false;
            initialized = true;
            debug("CMI Database Writer initialized (MySQL)");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize CMI MySQL database writer: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Checks if initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Ensures initialization, throws exception if not initialized.
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("CMIDatabaseWriter not initialized. Call initialize() first.");
        }
    }

    private HikariDataSource createSQLiteDataSource(String dbPath) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(3);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("Syncmoney-CMI-Writer-SQLite");
        return new HikariDataSource(hikariConfig);
    }

    private HikariDataSource createMySQLDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildJdbcUrl());
        hikariConfig.setUsername(config.getShadowSyncCMIMySQLUsername());
        hikariConfig.setPassword(config.getShadowSyncCMIMySQLPassword());
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("Syncmoney-CMI-Writer-MySQL");
        return new HikariDataSource(hikariConfig);
    }

    private String buildJdbcUrl() {
        return String.format("jdbc:mysql://%s:%d/%s",
                config.getShadowSyncCMIMySQLHost(),
                config.getShadowSyncCMIMySQLPort(),
                config.getShadowSyncCMIMySQLDatabase());
    }

    /**
     * Tests CMI database connection.
     * Tests SQLite first, falls back to MySQL on failure.
     *
     * @return true if connection is successful
     */
    public boolean testConnection() {
        if (!initialized) {
            try {
                initialize();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize CMI database writer: " + e.getMessage());
                return false;
            }
        }

        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            plugin.getLogger().severe("CMI database connection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Batch updates player balances.
     *
     * @param players list of player balances
     * @return number of successfully updated records
     */
    public int batchUpdateBalance(List<PlayerBalance> players) {
        if (players == null || players.isEmpty()) {
            return 0;
        }

        ensureInitialized();

        String balanceColumn = detectBalanceColumn();
        if (balanceColumn == null) {
            plugin.getLogger().severe("Cannot detect CMI balance column for write!");
            return 0;
        }

        String sql = String.format(
                "UPDATE %s SET %s = ? WHERE LOWER(playername) = ?",
                getTableName(), balanceColumn
        );

        int updatedCount = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (PlayerBalance player : players) {
                    stmt.setBigDecimal(1, player.balance());
                    stmt.setString(2, player.name().toLowerCase());
                    stmt.addBatch();
                }

                int[] results = stmt.executeBatch();

                for (int result : results) {
                    if (result >= 0 || result == PreparedStatement.SUCCESS_NO_INFO) {
                        updatedCount++;
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to batch update CMI balances: " + e.getMessage());
        }

        return updatedCount;
    }

    /**
     * Asynchronously batch updates player balances.
     *
     * @param players list of player balances
     * @return CompletableFuture with number of updated records upon completion
     */
    public CompletableFuture<Integer> batchUpdateBalanceAsync(List<PlayerBalance> players) {
        return CompletableFuture.supplyAsync(() -> batchUpdateBalance(players));
    }

    /**
     * Updates single player balance.
     *
     * @param playerName player name
     * @param balance    balance
     * @return true if update was successful
     */
    public boolean updateBalance(String playerName, BigDecimal balance) {
        ensureInitialized();

        String balanceColumn = detectBalanceColumn();
        if (balanceColumn == null) {
            return false;
        }

        String sql = String.format(
                "UPDATE %s SET %s = ? WHERE LOWER(playername) = ?",
                getTableName(), balanceColumn
        );

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBigDecimal(1, balance);
            stmt.setString(2, playerName.toLowerCase());

            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to update CMI balance for " + playerName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Reads player balance from CMI (for comparison).
     *
     * @param playerName player name
     * @return balance in CMI, or null if not found
     */
    public BigDecimal readCMIBalance(String playerName) {
        ensureInitialized();

        String balanceColumn = detectBalanceColumn();
        if (balanceColumn == null) {
            return null;
        }

        String sql = String.format(
                "SELECT %s FROM %s WHERE LOWER(playername) = ?",
                balanceColumn, getTableName()
        );

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName.toLowerCase());

            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Object balanceObj = rs.getObject(1);
                    if (balanceObj != null) {
                        if (balanceObj instanceof Number) {
                            return BigDecimal.valueOf(((Number) balanceObj).doubleValue())
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                        } else {
                            return new BigDecimal(balanceObj.toString())
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read CMI balance for " + playerName + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Detects balance column name in CMI table.
     *
     * @return balance column name, or null if not found
     */
    private String detectBalanceColumn() {
        String[] columnNames = {"balance", "money", "coint"};
        String sql = "SELECT * FROM " + getTableName() + " LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             java.sql.ResultSet rs = stmt.executeQuery()) {

            for (String columnName : columnNames) {
                try {
                    rs.findColumn(columnName);
                    return columnName;
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to detect CMI columns: " + e.getMessage());
        }

        return null;
    }

    /**
     * Closes database connection.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("CMI database writer connection closed.");
        }
        initialized = false;
    }

    /**
     * Player balance record.
     */
    public record PlayerBalance(String name, UUID uuid, BigDecimal balance) {
    }
}
