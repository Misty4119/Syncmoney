package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.web.websocket.SseManager;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [SYNC-ECO-006] Local economy handler.
 * Uses SQLite for local storage.
 */
public class LocalEconomyHandler implements AutoCloseable {

    private final Syncmoney plugin;
    private final String dbPath;
    private Connection connection;

    private BaltopManager baltopManager;

    private volatile SseManager sseManager;

    private final ConcurrentHashMap<UUID, BigDecimal> balanceCache = new ConcurrentHashMap<>();

    /** [SYNC-ECO-007] Monotonically increasing sequence counter for transaction ordering within the same millisecond. */
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    public LocalEconomyHandler(Syncmoney plugin, String dbPath) {
        this.plugin = plugin;
        this.dbPath = dbPath;
        initialize();
    }

    /**
     * [SYNC-ECO-008] Set BaltopManager for LOCAL mode ranking updates.
     */
    public void setBaltopManager(BaltopManager baltopManager) {
        this.baltopManager = baltopManager;
    }

    /**
     * [SYNC-ECO-009] Inject SseManager to enable real-time audit SSE push in LOCAL mode.
     */
    public void setSseManager(SseManager sseManager) {
        this.sseManager = sseManager;
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
            restoreSequenceCounter();

            plugin.getLogger().fine("Local SQLite database initialized: " + dbPath);
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
                    name TEXT,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    balance_before REAL NOT NULL,
                    balance_after REAL NOT NULL,
                    source TEXT NOT NULL,
                    target_name TEXT,
                    timestamp INTEGER NOT NULL,
                    sequence INTEGER DEFAULT 0
                )
            """);

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN name TEXT");
            } catch (SQLException e) {

            }

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN source TEXT");
            } catch (SQLException e) {

            }

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN sequence INTEGER DEFAULT 0");
            } catch (SQLException e) {

            }

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN target_name TEXT");
            } catch (SQLException e) {

            }

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp DESC)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_transactions_timestamp_seq ON transactions(timestamp DESC, sequence DESC)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_balance ON player_balances(balance DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_last_update ON player_balances(last_update)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_transactions_uuid ON transactions(uuid)");

            connection.commit();
        }
    }

    /**
     * [SYNC-ECO-010] Restores the sequence counter from the maximum sequence value in the transactions table.
     */
    private void restoreSequenceCounter() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(sequence) FROM transactions")) {
            if (rs.next()) {
                int maxSeq = rs.getInt(1);
                if (!rs.wasNull() && maxSeq > 0) {
                    sequenceCounter.set(maxSeq);
                    plugin.getLogger().fine("Restored sequence counter to: " + maxSeq);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not restore sequence counter (new database?): " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-011] Get balance.
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
                    BigDecimal balance = NumericUtil.normalize(BigDecimal.valueOf(rs.getDouble("balance")));
                    balanceCache.put(uuid, balance);

                    return balance;
                }
            }
        } catch (SQLException e) {
        }

        return BigDecimal.ZERO;
    }

    /**
     * [SYNC-ECO-012] Deposit.
     */
    public BigDecimal deposit(UUID uuid, String name, BigDecimal amount, String source) {
        long now = System.currentTimeMillis();

        if (name == null) {
            name = resolvePlayerName(uuid);
            if (name == null) {
                name = uuid.toString();
            }
        }

        if (source == null) {
            source = "LOCAL";
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
                pstmt.setBigDecimal(3, newBalance);
                pstmt.setInt(4, version);
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                pstmt.executeUpdate();
            }

            recordTransaction(uuid, name, "DEPOSIT", amount.doubleValue(),
                currentBalance.doubleValue(), newBalance.doubleValue(), source);

            commit();

            balanceCache.put(uuid, newBalance);

            updateBaltopRank(uuid, newBalance.doubleValue());

            return newBalance;

        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Failed to deposit: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * [SYNC-ECO-013] Withdraw.
     */
    public BigDecimal withdraw(UUID uuid, String name, BigDecimal amount, String source) {
        long now = System.currentTimeMillis();

        if (name == null) {
            name = resolvePlayerName(uuid);
            if (name == null) {
                name = uuid.toString();
            }
        }

        if (source == null) {
            source = "LOCAL";
        }

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
                pstmt.setBigDecimal(3, newBalance);
                pstmt.setInt(4, version);
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                pstmt.executeUpdate();
            }

            recordTransaction(uuid, name, "WITHDRAW", amount.doubleValue(),
                currentBalance.doubleValue(), newBalance.doubleValue(), source);

            commit();

            balanceCache.put(uuid, newBalance);

            updateBaltopRank(uuid, newBalance.doubleValue());

            return newBalance;

        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Failed to withdraw: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * [SYNC-ECO-014] Set balance.
     */
    public BigDecimal setBalance(UUID uuid, String name, BigDecimal newBalance, String source) {
        long now = System.currentTimeMillis();

        if (name == null) {
            name = resolvePlayerName(uuid);
            if (name == null) {
                name = uuid.toString();
            }
        }

        if (source == null) {
            source = "LOCAL";
        }

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
                pstmt.setBigDecimal(3, newBalance);
                pstmt.setInt(4, version);
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                pstmt.executeUpdate();
            }

            recordTransaction(uuid, name, "SET_BALANCE", newBalance.doubleValue(),
                currentBalance.doubleValue(), newBalance.doubleValue(), source);

            commit();

            balanceCache.put(uuid, newBalance);

            updateBaltopRank(uuid, newBalance.doubleValue());

            return newBalance;

        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Failed to set balance: " + e.getMessage());
            return BigDecimal.valueOf(-1);
        } finally {
            restoreAutoCommit();
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

    private void recordTransaction(UUID uuid, String name, String type, double amount,
                                   double balanceBefore, double balanceAfter, String source) {
        recordTransaction(uuid, name, type, amount, balanceBefore, balanceAfter, source, null);
    }

    private void recordTransaction(UUID uuid, String name, String type, double amount,
                                   double balanceBefore, double balanceAfter, String source, String targetName) {
        String normalizedType = type;
        if ("PLAYER_TRANSFER".equals(source)) {
            normalizedType = "TRANSFER";
        }

        double signedAmount = amount;
        if ("WITHDRAW".equals(type)) {
            signedAmount = -Math.abs(amount);
        } else if ("TRANSFER_OUT".equals(type)) {
            signedAmount = -Math.abs(amount);
        }

        String sql = """
            INSERT INTO transactions (uuid, name, type, amount, balance_before, balance_after, source, target_name, timestamp, sequence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        long timestamp = System.currentTimeMillis();
        int seq = sequenceCounter.incrementAndGet();

        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, name);
            pstmt.setString(3, normalizedType);
            pstmt.setDouble(4, signedAmount);
            pstmt.setDouble(5, balanceBefore);
            pstmt.setDouble(6, balanceAfter);
            pstmt.setString(7, source);
            pstmt.setString(8, targetName);
            pstmt.setLong(9, timestamp);
            pstmt.setInt(10, seq);
            pstmt.executeUpdate();

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    long rowId = generatedKeys.getLong(1);
                    broadcastLocalAuditEvent(rowId, uuid, name, normalizedType, signedAmount,
                            balanceAfter, source, timestamp, seq);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to record transaction: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-015] Broadcasts a real-time audit event via SSE to all connected Web Admin clients.
     */
    private void broadcastLocalAuditEvent(long rowId, UUID uuid, String name, String type,
                                          double amount, double balanceAfter, String source,
                                          long timestamp, int sequence) {
        if (sseManager == null || !sseManager.isEnabled()) return;
        try {
            String serverName = plugin.getConfig().getString("server-name", "local");
            String safeName = name != null ? name.replace("\\", "\\\\").replace("\"", "\\\"") : "unknown";
            String safeSource = source != null ? source.replace("\"", "\\\"") : "LOCAL";
            String safeServer = serverName.replace("\"", "\\\"");
            String amountStr = BigDecimal.valueOf(amount).toPlainString();
            String balanceAfterStr = BigDecimal.valueOf(balanceAfter).toPlainString();

            String json = "{\"id\":\"local-" + rowId + "\","
                    + "\"playerUuid\":\"" + uuid + "\","
                    + "\"playerName\":\"" + safeName + "\","
                    + "\"type\":\"" + type + "\","
                    + "\"amount\":\"" + amountStr + "\","
                    + "\"balanceAfter\":\"" + balanceAfterStr + "\","
                    + "\"source\":\"" + safeSource + "\","
                    + "\"server\":\"" + safeServer + "\","
                    + "\"timestamp\":" + timestamp + ","
                    + "\"sequence\":" + sequence + "}";

            sseManager.broadcastToChannel(SseManager.CHANNEL_AUDIT, json);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to broadcast local audit event via SSE: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-016] Update baltop rankings in LOCAL mode.
     */
    private void updateBaltopRank(UUID uuid, double newBalance) {
        if (baltopManager != null) {
            try {
                baltopManager.updatePlayerRank(uuid, newBalance);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to update baltop rank: " + e.getMessage());
            }
        }
    }

    /**
     * [SYNC-ECO-017] Resolve player name from UUID.
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

        return uuid.toString();
    }

    private void rollback() {
        try {
            if (connection != null && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to rollback: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-018] Safely commit the transaction with error handling.
     */
    private void commit() {
        try {
            if (connection != null && !connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to commit: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-019] Restore auto-commit mode after transaction completion.
     */
    private void restoreAutoCommit() {
        try {
            if (connection != null && connection.getAutoCommit() == false) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to restore auto-commit: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-020] Clear cache.
     */
    public void clearCache(UUID uuid) {
        balanceCache.remove(uuid);
    }

    /**
     * [SYNC-ECO-021] Gets all balances sorted by balance (for baltop).
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
     * [SYNC-ECO-022] Gets total balance of all players.
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
     * [SYNC-ECO-023] Gets total number of players with balance > 0.
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
     * [SYNC-ECO-024] Gets transaction count for today.
     */
    public int getTodayTransactionCount() {
        long todayStart = getTodayStartTimestamp();
        String sql = "SELECT COUNT(*) as count FROM transactions WHERE timestamp >= ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, todayStart);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get today's transaction count: " + e.getMessage());
        }

        return 0;
    }

    /**
     * [SYNC-ECO-025] Gets the start timestamp of today.
     */
    private long getTodayStartTimestamp() {
        java.time.LocalDateTime todayStart = java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
                .with(java.time.LocalTime.MIN);
        return todayStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * [SYNC-ECO-026] Gets transaction history for a player.
     */
    public List<Map<String, Object>> getTransactionHistory(UUID uuid, int limit) {
        return getTransactionHistory(uuid, limit, 0);
    }

    /**
     * [SYNC-ECO-027] Gets transaction history for a player with offset support.
     */
    public List<Map<String, Object>> getTransactionHistory(UUID uuid, int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql;
        if (offset > 0) {
            sql = "SELECT * FROM transactions WHERE uuid = ? ORDER BY timestamp DESC, sequence DESC LIMIT ? OFFSET ?";
        } else {
            sql = "SELECT * FROM transactions WHERE uuid = ? ORDER BY timestamp DESC, sequence DESC LIMIT ?";
        }

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            if (offset > 0) {
                pstmt.setInt(2, limit);
                pstmt.setInt(3, offset);
            } else {
                pstmt.setInt(2, limit);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> record = new HashMap<>();
                    record.put("id", rs.getInt("id"));
                    record.put("uuid", rs.getString("uuid"));
                    record.put("name", rs.getString("name"));
                    record.put("type", rs.getString("type"));
                    record.put("amount", rs.getDouble("amount"));
                    record.put("balance_before", rs.getDouble("balance_before"));
                    record.put("balance_after", rs.getDouble("balance_after"));
                    record.put("source", rs.getString("source"));
                    record.put("target_name", rs.getString("target_name"));
                    record.put("timestamp", rs.getLong("timestamp"));
                    record.put("sequence", rs.getInt("sequence"));
                    result.add(record);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get transaction history: " + e.getMessage());
        }

        return result;
    }

    /**
     * [SYNC-ECO-028] Gets all transactions with pagination.
     */
    public List<Map<String, Object>> getAllTransactions(int offset, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM transactions ORDER BY timestamp DESC, sequence DESC LIMIT ? OFFSET ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            pstmt.setInt(2, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> record = new HashMap<>();
                    record.put("id", rs.getInt("id"));
                    record.put("uuid", rs.getString("uuid"));
                    record.put("name", rs.getString("name"));
                    record.put("type", rs.getString("type"));
                    record.put("amount", rs.getDouble("amount"));
                    record.put("balance_before", rs.getDouble("balance_before"));
                    record.put("balance_after", rs.getDouble("balance_after"));
                    record.put("source", rs.getString("source"));
                    record.put("target_name", rs.getString("target_name"));
                    record.put("timestamp", rs.getLong("timestamp"));
                    record.put("sequence", rs.getInt("sequence"));
                    result.add(record);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get all transactions: " + e.getMessage());
        }

        return result;
    }

    /**
     * [SYNC-ECO-029] Gets transactions with true cursor-based pagination for infinite scroll support.
     */
    public List<Map<String, Object>> getTransactionsBefore(UUID playerUuid, long startTime, long endTime,
            String type, long cursorTimestamp, int cursorSequence, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
            "SELECT * FROM transactions WHERE (timestamp < ? OR (timestamp = ? AND sequence < ?))");

        List<Object> params = new ArrayList<>();
        params.add(cursorTimestamp);
        params.add(cursorTimestamp);
        params.add(cursorSequence);

        if (playerUuid != null) {
            sql.append(" AND uuid = ?");
            params.add(playerUuid.toString());
        }

        if (startTime > 0) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime > 0) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
            params.add(type.toUpperCase());
        }

        sql.append(" ORDER BY timestamp DESC, sequence DESC LIMIT ?");
        params.add(limit + 1);

        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> record = new HashMap<>();
                    record.put("id", rs.getInt("id"));
                    record.put("uuid", rs.getString("uuid"));
                    record.put("name", rs.getString("name"));
                    record.put("type", rs.getString("type"));
                    record.put("amount", rs.getDouble("amount"));
                    record.put("balance_before", rs.getDouble("balance_before"));
                    record.put("balance_after", rs.getDouble("balance_after"));
                    record.put("source", rs.getString("source"));
                    record.put("target_name", rs.getString("target_name"));
                    record.put("timestamp", rs.getLong("timestamp"));
                    record.put("sequence", rs.getInt("sequence"));
                    result.add(record);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get transactions with cursor: " + e.getMessage());
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
