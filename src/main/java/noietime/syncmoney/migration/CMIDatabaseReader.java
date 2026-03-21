package noietime.syncmoney.migration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CMI Database Reader.
 * Reads player balance data from CMI database.
 *
 * Supports:
 * - MySQL and SQLite database types
 * - Automatic database configuration detection from DataBaseInfo.yml
 * - Multi-server database consolidation (Latest strategy)
 *
 * [AsyncScheduler] Database reads should be executed on async threads.
 */
public final class CMIDatabaseReader {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private HikariDataSource dataSource;
    private String tableName;
    private StorageType storageType;
    private boolean initialized = false;

    public enum StorageType {
        MySQL,
        SQLite
    }

    private String detectedTableName;

    private static final String BALANCE_COLUMN = "Balance";

    private static final String UUID_COLUMN = "player_uuid";

    private static final String USERNAME_COLUMN = "username";

    private static final String LASTLOGOFF_COLUMN = "LastLogoffTime";

    public CMIDatabaseReader(Plugin plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.storageType = StorageType.SQLite;
        this.tableName = "users";
    }

    /**
     * Gets the detected storage type without initializing the database connection.
     * Uses auto-detect from DataBaseInfo.yml if enabled.
     */
    public StorageType getDetectedStorageType() {
        if (config.migration().isCMIAutoDetect()) {
            CMIDatabaseConfig cmiConfig = CMIDatabaseConfig.fromDataBaseInfo(plugin);
            return cmiConfig.getType();
        }
        return StorageType.SQLite;
    }

    /**
     * Lazily initializes database connection.
     * This method is only invoked when migration command is executed.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        CMIDatabaseConfig cmiConfig = CMIDatabaseConfig.fromDataBaseInfo(plugin);
        this.storageType = cmiConfig.getType();

        if (storageType == StorageType.MySQL) {
            this.tableName = cmiConfig.getTablePrefix() + "users";
        } else {
            this.tableName = "users";
        }

        if (storageType == StorageType.MySQL) {
            this.dataSource = createMySQLDataSource(cmiConfig);
        } else {
            this.dataSource = createSQLiteDataSource(cmiConfig.getSqlitePath());
        }

        initialized = true;
        plugin.getLogger().fine("CMI Reader connected: type=" + storageType + ", table=" + tableName);
    }

    /**
     * Checks if the reader has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Closes the database connection.
     */
    public synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().fine("CMI Reader connection closed.");
        }
        initialized = false;
    }

    /**
     * Creates MySQL data source.
     */
    private HikariDataSource createMySQLDataSource(CMIDatabaseConfig cmiConfig) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s",
                cmiConfig.getHost(),
                cmiConfig.getPort(),
                cmiConfig.getDatabase()));
        hikariConfig.setUsername(cmiConfig.getUsername());
        hikariConfig.setPassword(cmiConfig.getPassword());
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("Syncmoney-CMI-Reader-MySQL");
        return new HikariDataSource(hikariConfig);
    }

    /**
     * Creates SQLite data source.
     */
    private HikariDataSource createSQLiteDataSource(String dbPath) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("Syncmoney-CMI-Reader-SQLite");
        return new HikariDataSource(hikariConfig);
    }

    /**
     * Tests CMI database connection.
     * @return true if connection is successful
     */
    public boolean testConnection() {
        ensureInitialized();
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            plugin.getLogger().severe("CMI database connection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets total player count in CMI table.
     * @return player count
     */
    public int getTotalPlayerCount() {
        ensureInitialized();
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get CMI player count: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Reads CMI player balance data (paginated).
     * @param offset starting offset
     * @param limit page size
     * @return list of player balances
     */
    public List<CMIPlayerData> readPlayers(int offset, int limit) {
        ensureInitialized();
        List<CMIPlayerData> players = new ArrayList<>();

        String sql = String.format(
                "SELECT * FROM %s LIMIT ? OFFSET ?",
                tableName
        );

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            stmt.setInt(2, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String playerName = rs.getString(USERNAME_COLUMN);
                    Object uuidObj = rs.getObject(UUID_COLUMN);

                    UUID uuid = null;
                    if (uuidObj != null) {
                        try {
                            uuid = UUID.fromString(uuidObj.toString());
                        } catch (IllegalArgumentException e) {
                        }
                    }

                    BigDecimal balance = BigDecimal.ZERO;
                    try {
                        Object balanceObj = rs.getObject(BALANCE_COLUMN);
                        if (balanceObj != null) {
                            if (balanceObj instanceof Number) {
                                balance = BigDecimal.valueOf(((Number) balanceObj).doubleValue())
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                            } else {
                                balance = new BigDecimal(balanceObj.toString())
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to read balance for " + playerName + ": " + e.getMessage());
                    }

                    long lastLogoffTime = 0;
                    try {
                        Object lastLogoffObj = rs.getObject(LASTLOGOFF_COLUMN);
                        if (lastLogoffObj != null) {
                            if (lastLogoffObj instanceof Number) {
                                lastLogoffTime = ((Number) lastLogoffObj).longValue();
                            } else {
                                lastLogoffTime = Long.parseLong(lastLogoffObj.toString());
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    players.add(new CMIPlayerData(uuid, playerName, balance, lastLogoffTime));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read CMI players: " + e.getMessage());
        }

        return players;
    }

    /**
     * Reads single player balance.
     * @param playerName player name
     * @return player balance data (if exists)
     */
    public CMIPlayerData readPlayer(String playerName) {
        ensureInitialized();
        String sql = String.format(
                "SELECT * FROM %s WHERE LOWER(%s) = ?",
                tableName, USERNAME_COLUMN
        );

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerName.toLowerCase(Locale.ROOT));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Object uuidObj = rs.getObject(UUID_COLUMN);
                    UUID uuid = null;
                    if (uuidObj != null) {
                        try {
                            uuid = UUID.fromString(uuidObj.toString());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    BigDecimal balance = BigDecimal.ZERO;
                    try {
                        Object balanceObj = rs.getObject(BALANCE_COLUMN);
                        if (balanceObj != null) {
                            if (balanceObj instanceof Number) {
                                balance = BigDecimal.valueOf(((Number) balanceObj).doubleValue())
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                            } else {
                                balance = new BigDecimal(balanceObj.toString())
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    long lastLogoffTime = 0;
                    try {
                        Object lastLogoffObj = rs.getObject(LASTLOGOFF_COLUMN);
                        if (lastLogoffObj != null) {
                            if (lastLogoffObj instanceof Number) {
                                lastLogoffTime = ((Number) lastLogoffObj).longValue();
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    return new CMIPlayerData(uuid, playerName, balance, lastLogoffTime);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read CMI player " + playerName + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Ensures database connection is initialized.
     */
    private void ensureInitialized() {
        if (!initialized || dataSource == null) {
            throw new IllegalStateException("CMI Reader not initialized. Call initialize() first.");
        }
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        shutdown();
    }

    /**
     * Gets storage type.
     * @return MySQL or SQLite
     */
    public StorageType getStorageType() {
        return storageType;
    }

    /**
     * Gets table name.
     * @return table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * CMI database configuration class.
     * Automatically reads configuration from DataBaseInfo.yml.
     */
    public static class CMIDatabaseConfig {
        private StorageType type;
        private String host;
        private int port;
        private String username;
        private String password;
        private String database;
        private String tablePrefix;
        private String sqlitePath;

        public StorageType getType() {
            return type;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getDatabase() {
            return database;
        }

        public String getTablePrefix() {
            return tablePrefix;
        }

        public String getSqlitePath() {
            return sqlitePath;
        }

        /**
         * Reads CMI configuration from DataBaseInfo.yml.
         */
        public static CMIDatabaseConfig fromDataBaseInfo(Plugin plugin) {
            CMIDatabaseConfig config = new CMIDatabaseConfig();

            File cmiConfigFile = new File(plugin.getDataFolder().getParent(), "CMI/Settings/DataBaseInfo.yml");

            if (!cmiConfigFile.exists()) {
                plugin.getLogger().warning("CMI DataBaseInfo.yml not found, using default MySQL config");
                config.type = StorageType.MySQL;
                config.host = "localhost";
                config.port = 3306;
                config.username = "root";
                config.password = "";
                config.database = "minecraft";
                config.tablePrefix = "CMI_";
                return config;
            }

            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cmiConfigFile);

                String method = yaml.getString("storage.method", "MySQL").toLowerCase(Locale.ROOT);
                config.type = "sqlite".equals(method) ? StorageType.SQLite : StorageType.MySQL;

                if (config.type == StorageType.MySQL) {
                    String hostname = yaml.getString("mysql.hostname", "localhost:3306");
                    String[] parts = hostname.split(":");
                    config.host = parts[0];
                    config.port = parts.length > 1 ? Integer.parseInt(parts[1]) : 3306;

                    config.username = yaml.getString("mysql.username", "root");
                    config.password = yaml.getString("mysql.password", "");
                    config.database = yaml.getString("mysql.database", "minecraft");
                    config.tablePrefix = yaml.getString("mysql.tablePrefix", "CMI_");

                    plugin.getLogger().fine("CMI MySQL config: host=" + config.host + ", database=" + config.database);
                } else {
                    config.tablePrefix = "";
                    config.sqlitePath = new File(plugin.getDataFolder().getParent(), "CMI/cmi.sqlite.db").getAbsolutePath();
                    plugin.getLogger().fine("CMI SQLite config: path=" + config.sqlitePath);
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to parse CMI DataBaseInfo.yml: " + e.getMessage());
                config.type = StorageType.MySQL;
                config.host = "localhost";
                config.port = 3306;
                config.username = "root";
                config.password = "";
                config.database = "minecraft";
                config.tablePrefix = "CMI_";
            }

            return config;
        }
    }

    /**
     * CMI player data record.
     * @param uuid player UUID (if available)
     * @param playerName player name
     * @param balance balance
     * @param lastLogoffTime last logoff time (used for multi-server consolidation)
     */
    public record CMIPlayerData(UUID uuid, String playerName, BigDecimal balance, long lastLogoffTime) {}
}
