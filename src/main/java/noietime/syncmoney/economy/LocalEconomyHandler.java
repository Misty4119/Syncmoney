package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.Bukkit;

import java.io.File;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local economy handler.
 * Uses SQLite for local storage.
 */
public class LocalEconomyHandler implements AutoCloseable {

    private final Syncmoney plugin;
    private final String dbPath;
    private Connection connection;

    private final ConcurrentHashMap<UUID, BigDecimal> balanceCache = new ConcurrentHashMap<>();

    public LocalEconomyHandler(Syncmoney plugin, String dbPath) {
        this.plugin = plugin;
        this.dbPath = dbPath;
        initialize();
    }

    private void initialize() {
        try {
            File dbFile = new File(dbPath);
            dbFile.getParentFile().mkdirs();

            String url = "jdbc:sqlite:" + dbPath +
                "?journal_mode=WAL" +
                "&synchronous=NORMAL" +
                "&cache_size=10000" +
                "&temp_store=MEMORY";

            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(false);

            createTables();

            plugin.getLogger().info("Local SQLite database initialized: " + dbPath);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize SQLite: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_balances (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0.0,
                    version INTEGER NOT NULL DEFAULT 0,
                    last_update INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    balance_before REAL NOT NULL,
                    balance_after REAL NOT NULL,
                    source TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_balance ON player_balances(balance DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_last_update ON player_balances(last_update)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_transactions_uuid ON transactions(uuid)");

            connection.commit();
        }
    }

    /**
     * Get balance.
     */
    public BigDecimal getBalance(UUID uuid) {
        BigDecimal cached = balanceCache.get(uuid);
        if (cached != null) {
            return cached;
        }

        String sql = "SELECT balance, name FROM player_balances WHERE uuid = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal balance = NumericUtil.normalize(rs.getDouble("balance"));
                    balanceCache.put(uuid, balance);

                    String name = rs.getString("name");
                    if (name != null && !name.isEmpty() && !name.matches("^[0-9a-f]{8}-.*")) {
                        var offlinePlayer = Bukkit.getServer().getOfflinePlayer(uuid);
                        if (offlinePlayer != null && offlinePlayer.getName() != null) {
                        }
                    }

                    return balance;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get balance: " + e.getMessage());
        }

        return BigDecimal.ZERO;
    }

    /**
     * Deposit.
     */
    public BigDecimal deposit(UUID uuid, String name, BigDecimal amount) {
        long now = System.currentTimeMillis();

        if (name == null) {
            name = resolvePlayerName(uuid);
            if (name == null) {
                name = uuid.toString();
            }
        }

        try {
            connection.setAutoCommit(false);

            BigDecimal currentBalance = getBalance(uuid);
            BigDecimal newBalance = currentBalance.add(amount);
            int version = getVersion(uuid) + 1;

            String sql = """
                INSERT INTO player_balances (uuid, name, balance, version, last_update, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    balance = excluded.balance,
                    version = excluded.version,
                    last_update = excluded.last_update
            """;

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name != null ? name : uuid.toString());
                pstmt.setDouble(3, newBalance.doubleValue());
                pstmt.setInt(4, version);
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                pstmt.executeUpdate();
            }

            recordTransaction(uuid, "DEPOSIT", amount.doubleValue(),
                currentBalance.doubleValue(), newBalance.doubleValue(), "LOCAL");

            connection.commit();

            balanceCache.put(uuid, newBalance);

            return newBalance;

        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Failed to deposit: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        }
    }

    /**
     * Withdraw.
     */
    public BigDecimal withdraw(UUID uuid, String name, BigDecimal amount) {
        long now = System.currentTimeMillis();

        try {
            connection.setAutoCommit(false);

            BigDecimal currentBalance = getBalance(uuid);

            if (currentBalance.compareTo(amount) < 0) {
                throw new IllegalArgumentException("Insufficient balance: " + currentBalance + " < " + amount);
            }

            BigDecimal newBalance = currentBalance.subtract(amount);
            int version = getVersion(uuid) + 1;

            String sql = """
                INSERT INTO player_balances (uuid, name, balance, version, last_update, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    balance = excluded.balance,
                    version = excluded.version,
                    last_update = excluded.last_update
            """;

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name != null ? name : uuid.toString());
                pstmt.setDouble(3, newBalance.doubleValue());
                pstmt.setInt(4, version);
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                pstmt.executeUpdate();
            }

            recordTransaction(uuid, "WITHDRAW", amount.doubleValue(),
                currentBalance.doubleValue(), newBalance.doubleValue(), "LOCAL");

            connection.commit();

            balanceCache.put(uuid, newBalance);

            return newBalance;

        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Failed to withdraw: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        }
    }

    /**
     * Set balance.
     */
    public BigDecimal setBalance(UUID uuid, String name, BigDecimal newBalance) {
        long now = System.currentTimeMillis();

        try {
            connection.setAutoCommit(false);

            BigDecimal currentBalance = getBalance(uuid);
            int version = getVersion(uuid) + 1;

            String sql = """
                INSERT INTO player_balances (uuid, name, balance, version, last_update, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    balance = excluded.balance,
                    version = excluded.version,
                    last_update = excluded.last_update
            """;

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name != null ? name : uuid.toString());
                pstmt.setDouble(3, newBalance.doubleValue());
                pstmt.setInt(4, version);
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                pstmt.executeUpdate();
            }

            recordTransaction(uuid, "SET_BALANCE", newBalance.doubleValue(),
                currentBalance.doubleValue(), newBalance.doubleValue(), "LOCAL");

            connection.commit();

            balanceCache.put(uuid, newBalance);

            return newBalance;

        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Failed to set balance: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        }
    }

    private int getVersion(UUID uuid) {
        String sql = "SELECT version FROM player_balances WHERE uuid = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("version");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get version for " + uuid + ": " + e.getMessage());
        }

        return 0;
    }

    private void recordTransaction(UUID uuid, String type, double amount,
                                   double balanceBefore, double balanceAfter, String source) {
        String sql = """
            INSERT INTO transactions (uuid, type, amount, balance_before, balance_after, source, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, type);
            pstmt.setDouble(3, amount);
            pstmt.setDouble(4, balanceBefore);
            pstmt.setDouble(5, balanceAfter);
            pstmt.setString(6, source);
            pstmt.setLong(7, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to record transaction: " + e.getMessage());
        }
    }

    /**
     * Resolve player name from UUID.
     */
    private String resolvePlayerName(UUID uuid) {
        var player = Bukkit.getServer().getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }

        var offlinePlayer = Bukkit.getServer().getOfflinePlayer(uuid);
        if (offlinePlayer != null) {
            String name = offlinePlayer.getName();
            if (name != null) {
                return name;
            }
        }

        return null;
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to rollback: " + e.getMessage());
        }
    }

    /**
     * Clear cache.
     */
    public void clearCache(UUID uuid) {
        balanceCache.remove(uuid);
    }

    /**
     * Gets all balances sorted by balance (for baltop).
     * @param limit Maximum number of entries
     * @return List of maps containing uuid, name, and balance
     */
    public List<Map<String, Object>> getAllBalancesSorted(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT uuid, name, balance FROM player_balances ORDER BY balance DESC LIMIT ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("uuid", rs.getString("uuid"));
                    entry.put("name", rs.getString("name"));
                    entry.put("balance", rs.getDouble("balance"));
                    result.add(entry);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get sorted balances: " + e.getMessage());
        }

        return result;
    }

    /**
     * Gets total balance of all players.
     * @return Total balance
     */
    public BigDecimal getTotalBalance() {
        String sql = "SELECT SUM(balance) as total FROM player_balances";

        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                double total = rs.getDouble("total");
                return NumericUtil.normalize(total);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get total balance: " + e.getMessage());
        }

        return BigDecimal.ZERO;
    }

    /**
     * Gets total number of players with balance > 0.
     * @return Total player count
     */
    public int getTotalPlayerCount() {
        String sql = "SELECT COUNT(*) as count FROM player_balances WHERE balance > 0";

        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("count");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player count: " + e.getMessage());
        }

        return 0;
    }

    /**
     * Gets transaction history for a player.
     * @param uuid Player UUID
     * @param limit Max records to return
     * @return List of transaction records
     */
    public List<Map<String, Object>> getTransactionHistory(UUID uuid, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM transactions WHERE uuid = ? ORDER BY timestamp DESC LIMIT ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setInt(2, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> record = new HashMap<>();
                    record.put("uuid", rs.getString("uuid"));
                    record.put("type", rs.getString("type"));
                    record.put("amount", rs.getDouble("amount"));
                    record.put("balance_before", rs.getDouble("balance_before"));
                    record.put("balance_after", rs.getDouble("balance_after"));
                    record.put("source", rs.getString("source"));
                    record.put("timestamp", rs.getLong("timestamp"));
                    result.add(record);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get transaction history: " + e.getMessage());
        }

        return result;
    }

    /**
     * Gets all transactions with pagination.
     * @param offset Starting offset
     * @param limit Max records
     * @return List of transaction records
     */
    public List<Map<String, Object>> getAllTransactions(int offset, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM transactions ORDER BY timestamp DESC LIMIT ? OFFSET ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            pstmt.setInt(2, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> record = new HashMap<>();
                    record.put("uuid", rs.getString("uuid"));
                    record.put("type", rs.getString("type"));
                    record.put("amount", rs.getDouble("amount"));
                    record.put("balance_before", rs.getDouble("balance_before"));
                    record.put("balance_after", rs.getDouble("balance_after"));
                    record.put("source", rs.getString("source"));
                    record.put("timestamp", rs.getLong("timestamp"));
                    result.add(record);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get all transactions: " + e.getMessage());
        }

        return result;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to close connection: " + e.getMessage());
        }
    }
}
