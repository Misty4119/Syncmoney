package noietime.syncmoney.shadow;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private record DatabaseSchema(
            String tableName, String playerUuidColumn, String playerNameColumn, String balanceColumn) {}

    private static final int BALANCE_CONFIRMATION_CHUNK_SIZE = 500;
    private static final String[] SQLITE_TABLE_NAMES = { "users", "user" };
    private static final String[] MYSQL_TABLE_NAMES = { "users", "cmi_user" };
    private static final String[] PLAYER_NAME_COLUMNS = { "username", "playername" };
    private static final String[] PLAYER_UUID_COLUMNS = { "player_uuid", "uuid" };
    private static final String[] BALANCE_COLUMNS = { "Balance", "balance", "money", "coint" };

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private HikariDataSource dataSource;
    private volatile boolean initialized = false;
    private volatile boolean useSQLite = false;
    private volatile String resolvedTableName;
    private volatile String resolvedPlayerUuidColumn;
    private volatile String resolvedPlayerNameColumn;
    private volatile String resolvedBalanceColumn;
    private final boolean debug;

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().fine(message);
        }
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

        String sqlitePath = config.shadowSync().getShadowSyncCMISQLitePath();
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
            HikariDataSource sqliteDataSource = null;
            try {
                sqliteDataSource = createSQLiteDataSource(sqlitePath);
                DatabaseSchema schema = detectSchema(sqliteDataSource, true);
                if (schema == null) {
                    throw new IllegalStateException("No supported CMI SQLite user table was found");
                }
                this.dataSource = sqliteDataSource;
                this.useSQLite = true;
                cacheSchema(schema);
                initialized = true;
                debug("CMI Database Writer initialized (SQLite): " + sqlitePath);
                return;
            } catch (Exception e) {
                if (sqliteDataSource != null) {
                    sqliteDataSource.close();
                }
                plugin.getLogger().warning("SQLite initialization or schema detection failed: "
                        + e.getMessage() + ", falling back to MySQL");
            }
        } else {
            plugin.getLogger().fine("CMI SQLite file not found, falling back to MySQL");
        }

        HikariDataSource mysqlDataSource = null;
        try {
            mysqlDataSource = createMySQLDataSource();
            DatabaseSchema schema = detectSchema(mysqlDataSource, false);
            if (schema == null) {
                throw new IllegalStateException("No supported CMI MySQL user table was found");
            }
            this.dataSource = mysqlDataSource;
            this.useSQLite = false;
            cacheSchema(schema);
            initialized = true;
            debug("CMI Database Writer initialized (MySQL)");
        } catch (Exception e) {
            if (mysqlDataSource != null) {
                mysqlDataSource.close();
            }
            plugin.getLogger().severe("Failed to initialize CMI MySQL database writer: " + e.getMessage());
            throw new IllegalStateException("Failed to initialize CMI MySQL database writer", e);
        }
    }

    /**
     * Finds a supported CMI user table using only known table and column identifiers.
     * CMI 9.8.10.5 uses users(player_uuid, username, Balance); older installations may use the
     * legacy user/playername or cmi_user/playername layouts.
     */
    private DatabaseSchema detectSchema(HikariDataSource candidateDataSource, boolean sqlite) {
        String[] tableNames = sqlite ? SQLITE_TABLE_NAMES : MYSQL_TABLE_NAMES;
        try (Connection connection = candidateDataSource.getConnection()) {
            for (String tableName : tableNames) {
                for (String playerUuidColumn : PLAYER_UUID_COLUMNS) {
                    for (String playerNameColumn : PLAYER_NAME_COLUMNS) {
                        for (String balanceColumn : BALANCE_COLUMNS) {
                            String sql = "SELECT " + playerUuidColumn + ", " + playerNameColumn + ", "
                                    + balanceColumn + " FROM " + tableName + " LIMIT 0";
                            try (PreparedStatement statement = connection.prepareStatement(sql);
                                    ResultSet ignored = statement.executeQuery()) {
                                return new DatabaseSchema(
                                        tableName, playerUuidColumn, playerNameColumn, balanceColumn);
                            } catch (SQLException ignored) {
                                // Identifiers are fixed allowlist entries; try the next known layout.
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            debug("Failed to inspect CMI database schema: " + e.getMessage());
        }
        return null;
    }

    private void cacheSchema(DatabaseSchema schema) {
        resolvedTableName = schema.tableName();
        resolvedPlayerUuidColumn = schema.playerUuidColumn();
        resolvedPlayerNameColumn = schema.playerNameColumn();
        resolvedBalanceColumn = schema.balanceColumn();
    }

    private String getTableName() {
        return resolvedTableName;
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
        hikariConfig.setUsername(config.shadowSync().getShadowSyncCMIMySQLUsername());
        hikariConfig.setPassword(config.shadowSync().getShadowSyncCMIMySQLPassword());
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("Syncmoney-CMI-Writer-MySQL");
        return new HikariDataSource(hikariConfig);
    }

    private String buildJdbcUrl() {
        return String.format("jdbc:mysql://%s:%d/%s",
                config.shadowSync().getShadowSyncCMIMySQLHost(),
                config.shadowSync().getShadowSyncCMIMySQLPort(),
                config.shadowSync().getShadowSyncCMIMySQLDatabase());
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
        int confirmed = 0;
        for (boolean success : batchUpdateBalanceResults(players)) {
            if (success) {
                confirmed++;
            }
        }
        return confirmed;
    }

    /**
     * Batch updates player balances and returns one post-commit confirmation per input row.
     *
     * @param players list of player balances
     * @return whether each requested balance was found in CMI after the batch committed
     */
    public List<Boolean> batchUpdateBalanceResults(List<PlayerBalance> players) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }

        List<Boolean> failedResults = new java.util.ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            failedResults.add(false);
        }

        ensureInitialized();

        String balanceColumn = resolvedBalanceColumn;
        String playerUuidColumn = resolvedPlayerUuidColumn;
        String tableName = getTableName();
        if (balanceColumn == null || playerUuidColumn == null || tableName == null) {
            plugin.getLogger().severe("Cannot write CMI balances without a detected supported user schema");
            return failedResults;
        }
        if (players.stream().noneMatch(player -> player != null && player.uuid() != null
                && player.balance() != null)) {
            return failedResults;
        }

        String sql = String.format(
                "UPDATE %s SET %s = ? WHERE LOWER(%s) = ?",
                tableName, balanceColumn, playerUuidColumn);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (hasDuplicateUuidRows(conn, tableName, playerUuidColumn, players)) {
                    conn.rollback();
                    plugin.getLogger().severe(
                            "Refusing to update CMI balances because a requested UUID matches multiple user rows.");
                    return failedResults;
                }

                for (PlayerBalance player : players) {
                    if (player == null || player.uuid() == null || player.balance() == null) {
                        continue;
                    }
                    stmt.setBigDecimal(1, player.balance());
                    stmt.setString(2, player.uuid().toString().toLowerCase(Locale.ROOT));
                    stmt.addBatch();
                }

                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

            return readPersistedBalanceResults(conn, tableName, playerUuidColumn, balanceColumn, players);
        } catch (SQLException e) {
            // A failed commit or post-commit readback cannot be reported as success, even
            // when executeBatch returned one success count per row before commit.
            plugin.getLogger().severe("Failed to commit or verify CMI balances: " + e.getMessage());
            return failedResults;
        }
    }

    /**
     * Rejects a write batch before mutation if any requested UUID maps to multiple CMI rows.
     */
    private boolean hasDuplicateUuidRows(
            Connection conn, String tableName, String playerUuidColumn, List<PlayerBalance> players)
            throws SQLException {
        Set<String> uniquePlayerUuids = new LinkedHashSet<>();
        for (PlayerBalance player : players) {
            if (player != null && player.uuid() != null) {
                uniquePlayerUuids.add(player.uuid().toString());
            }
        }

        List<String> playerUuids = new ArrayList<>(uniquePlayerUuids);
        for (int offset = 0; offset < playerUuids.size(); offset += BALANCE_CONFIRMATION_CHUNK_SIZE) {
            List<String> chunk = playerUuids.subList(offset,
                    Math.min(offset + BALANCE_CONFIRMATION_CHUNK_SIZE, playerUuids.size()));
            String placeholders = String.join(", ", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = String.format(
                    "SELECT LOWER(%s) FROM %s WHERE LOWER(%s) IN (%s) "
                            + "GROUP BY LOWER(%s) HAVING COUNT(*) > 1",
                    playerUuidColumn, tableName, playerUuidColumn, placeholders, playerUuidColumn);
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i).toLowerCase(Locale.ROOT));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Counts only rows whose committed balance can be read back as the requested value.
     * JDBC update counts of zero can mean either a missing player or an unchanged balance,
     * so the batch count alone is not a reliable success receipt.
     */
    private List<Boolean> readPersistedBalanceResults(
            Connection conn, String tableName, String playerUuidColumn,
            String balanceColumn, List<PlayerBalance> players)
            throws SQLException {
        Set<String> uniquePlayerUuids = new LinkedHashSet<>();
        for (PlayerBalance player : players) {
            if (player != null && player.uuid() != null) {
                uniquePlayerUuids.add(player.uuid().toString());
            }
        }

        Map<String, BigDecimal> persistedBalances = new HashMap<>();
        Set<String> seenPersistedUuids = new LinkedHashSet<>();
        Set<String> ambiguousPlayerUuids = new LinkedHashSet<>();
        List<String> playerUuids = new ArrayList<>(uniquePlayerUuids);
        for (int offset = 0; offset < playerUuids.size(); offset += BALANCE_CONFIRMATION_CHUNK_SIZE) {
            List<String> chunk = playerUuids.subList(offset,
                    Math.min(offset + BALANCE_CONFIRMATION_CHUNK_SIZE, playerUuids.size()));
            String placeholders = String.join(", ", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = String.format(
                    "SELECT LOWER(%s), %s FROM %s WHERE LOWER(%s) IN (%s)",
                    playerUuidColumn, balanceColumn, tableName, playerUuidColumn, placeholders);
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i).toLowerCase(Locale.ROOT));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String playerUuid = resultSet.getString(1);
                        BigDecimal balance = toMoney(resultSet.getObject(2));
                        if (playerUuid != null) {
                            String normalizedPlayerUuid = playerUuid.toLowerCase(Locale.ROOT);
                            if (!seenPersistedUuids.add(normalizedPlayerUuid)) {
                                ambiguousPlayerUuids.add(normalizedPlayerUuid);
                            } else if (balance != null) {
                                persistedBalances.put(normalizedPlayerUuid, balance);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to verify a CMI balance batch of "
                        + chunk.size() + " players: " + e.getMessage());
            }
        }

        List<Boolean> results = new ArrayList<>(players.size());
        for (PlayerBalance player : players) {
            BigDecimal persistedBalance = player == null || player.uuid() == null
                    ? null
                    : persistedBalances.get(player.uuid().toString().toLowerCase(Locale.ROOT));
            String normalizedPlayerUuid = player == null || player.uuid() == null
                    ? null
                    : player.uuid().toString().toLowerCase(Locale.ROOT);
            BigDecimal expectedBalance = player == null || player.balance() == null
                    ? null
                    : player.balance().setScale(2, RoundingMode.HALF_UP);
            results.add(persistedBalance != null && expectedBalance != null
                    && !ambiguousPlayerUuids.contains(normalizedPlayerUuid)
                    && persistedBalance.compareTo(expectedBalance) == 0);
        }
        return results;
    }

    private static BigDecimal toMoney(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal balance = value instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(value.toString());
            return balance.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        // A name is mutable and may identify a different account after a name change.
        // Keep this legacy signature fail-closed; callers must supply the stable UUID.
        return false;
    }

    public boolean updateBalance(UUID playerUuid, BigDecimal balance) {
        if (playerUuid == null || balance == null) {
            return false;
        }
        List<Boolean> result = batchUpdateBalanceResults(
                List.of(new PlayerBalance(null, playerUuid, balance)));
        return result.size() == 1 && Boolean.TRUE.equals(result.get(0));
    }

    /**
     * Reads player balance from CMI (for comparison).
     *
     * @param playerName player name
     * @return balance in CMI, or null if not found or unreadable
     */
    public BigDecimal readCMIBalance(String playerName) {
        return null;
    }

    /**
     * Reads a CMI balance while distinguishing a missing player from a failed read.
     *
     * @param playerName player name
     * @return found balance, absent player, or an unreadable CMI balance
     */
    public BalanceReadResult readCMIBalanceStrict(String playerName) {
        ensureInitialized();
        // A name-only lookup is ambiguous for renamed or duplicate-name historical rows.
        return BalanceReadResult.failed();
    }

    public BigDecimal readCMIBalance(UUID playerUuid) {
        BalanceReadResult result = readCMIBalanceStrict(playerUuid);
        return result.status() == BalanceReadStatus.FOUND ? result.balance() : null;
    }

    public BalanceReadResult readCMIBalanceStrict(UUID playerUuid) {
        ensureInitialized();
        if (playerUuid == null) {
            return BalanceReadResult.failed();
        }

        String balanceColumn = resolvedBalanceColumn;
        String playerUuidColumn = resolvedPlayerUuidColumn;
        String tableName = getTableName();
        if (balanceColumn == null || playerUuidColumn == null || tableName == null) {
            plugin.getLogger().severe("Cannot read CMI balance for " + playerUuid
                    + ": no supported user schema was detected.");
            return BalanceReadResult.failed();
        }

        String sql = String.format(
                "SELECT %s FROM %s WHERE LOWER(%s) = ?",
                balanceColumn, tableName, playerUuidColumn);

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerUuid.toString().toLowerCase(Locale.ROOT));

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return BalanceReadResult.notFound();
                }

                BigDecimal balance = toMoney(rs.getObject(1));
                if (rs.next()) {
                    plugin.getLogger().severe("Failed to read CMI balance for " + playerUuid
                            + ": UUID matches multiple user rows.");
                    return BalanceReadResult.failed();
                }
                if (balance == null) {
                    plugin.getLogger().severe("Failed to read CMI balance for " + playerUuid
                            + ": balance value is null or invalid.");
                    return BalanceReadResult.failed();
                }
                return BalanceReadResult.found(balance);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read CMI balance for " + playerUuid + ": " + e.getMessage());
            return BalanceReadResult.failed();
        }
    }

    /**
     * Closes database connection.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().fine("CMI database writer connection closed.");
        }
        dataSource = null;
        resolvedTableName = null;
        resolvedPlayerUuidColumn = null;
        resolvedPlayerNameColumn = null;
        resolvedBalanceColumn = null;
        initialized = false;
    }

    /**
     * Player balance record.
     */
    public record PlayerBalance(String name, UUID uuid, BigDecimal balance) {
    }

    public enum BalanceReadStatus {
        FOUND,
        NOT_FOUND,
        FAILED
    }

    public record BalanceReadResult(BalanceReadStatus status, BigDecimal balance) {
        public BalanceReadResult {
            if (status == null || (status == BalanceReadStatus.FOUND) != (balance != null)) {
                throw new IllegalArgumentException("Balance read status and value do not match");
            }
        }

        public static BalanceReadResult found(BigDecimal balance) {
            return new BalanceReadResult(BalanceReadStatus.FOUND, balance);
        }

        public static BalanceReadResult notFound() {
            return new BalanceReadResult(BalanceReadStatus.NOT_FOUND, null);
        }

        public static BalanceReadResult failed() {
            return new BalanceReadResult(BalanceReadStatus.FAILED, null);
        }
    }
}
