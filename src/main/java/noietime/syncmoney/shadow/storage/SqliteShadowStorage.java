package noietime.syncmoney.shadow.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite implementation of ShadowSyncStorage.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class SqliteShadowStorage implements ShadowSyncStorage {

    private final Plugin plugin;
    private final String dbPath;
    private final String jsonlPath;
    private HikariDataSource dataSource;
    private volatile boolean initialized = false;

    private static final DateTimeFormatter JSONL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public SqliteShadowStorage(Plugin plugin, String dbPath, String jsonlPath) {
        this.plugin = plugin;
        this.dbPath = dbPath;
        this.jsonlPath = jsonlPath;
    }

    @Override
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            File dbFile = new File(dbPath);
            File dbDir = dbFile.getParentFile();
            if (dbDir != null && !dbDir.exists()) {
                dbDir.mkdirs();
            }

            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setMaximumPoolSize(2);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setIdleTimeout(300000);
            hikariConfig.setMaxLifetime(600000);

            this.dataSource = new HikariDataSource(hikariConfig);

            createTables();

            File jsonlDir = new File(jsonlPath);
            if (!jsonlDir.exists()) {
                jsonlDir.mkdirs();
            }

            initialized = true;
            plugin.getLogger().fine("ShadowSync SQLite storage initialized: " + dbPath);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize ShadowSync SQLite storage: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void createTables() throws SQLException {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS shadow_sync_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid VARCHAR(36) NOT NULL,
                player_name VARCHAR(16) NOT NULL,
                balance DECIMAL(20,2) NOT NULL,
                sync_target VARCHAR(10) NOT NULL,
                operation VARCHAR(20) NOT NULL,
                timestamp INTEGER NOT NULL,
                success INTEGER NOT NULL,
                reason VARCHAR(255)
            )
            """;

        String createIndexSql1 = """
            CREATE INDEX IF NOT EXISTS idx_player_time ON shadow_sync_history(player_uuid, timestamp DESC)
            """;

        String createIndexSql2 = """
            CREATE INDEX IF NOT EXISTS idx_date ON shadow_sync_history(timestamp)
            """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
            stmt.execute(createIndexSql1);
            stmt.execute(createIndexSql2);
        }
    }

    @Override
    public void saveRecord(ShadowSyncRecord record) {
        String sql = """
            INSERT INTO shadow_sync_history (player_uuid, player_name, balance, sync_target, operation, timestamp, success, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.getPlayerUuid());
            ps.setString(2, record.getPlayerName());
            ps.setBigDecimal(3, record.getBalance());
            ps.setString(4, record.getSyncTarget());
            ps.setString(5, record.getOperation());
            ps.setLong(6, record.getTimestamp().toEpochMilli());
            ps.setInt(7, record.isSuccess() ? 1 : 0);
            ps.setString(8, record.getReason());

            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save shadow sync record: " + e.getMessage());
        }
    }

    @Override
    public void saveRecordsBatch(List<ShadowSyncRecord> records) {
        if (records.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO shadow_sync_history (player_uuid, player_name, balance, sync_target, operation, timestamp, success, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ShadowSyncRecord record : records) {
                    ps.setString(1, record.getPlayerUuid());
                    ps.setString(2, record.getPlayerName());
                    ps.setBigDecimal(3, record.getBalance());
                    ps.setString(4, record.getSyncTarget());
                    ps.setString(5, record.getOperation());
                    ps.setLong(6, record.getTimestamp().toEpochMilli());
                    ps.setInt(7, record.isSuccess() ? 1 : 0);
                    ps.setString(8, record.getReason());
                    ps.addBatch();
                }

                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to batch save shadow sync records: " + e.getMessage());
        }
    }

    @Override
    public List<ShadowSyncRecord> queryByPlayer(String playerUuid, int limit) {
        String sql = """
            SELECT * FROM shadow_sync_history
            WHERE player_uuid = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<ShadowSyncRecord> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query shadow sync records by player: " + e.getMessage());
        }

        return results;
    }

    @Override
    public List<ShadowSyncRecord> queryByPlayerName(String playerName, int limit) {
        String sql = """
            SELECT * FROM shadow_sync_history
            WHERE player_name = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<ShadowSyncRecord> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query shadow sync records by player name: " + e.getMessage());
        }

        return results;
    }

    @Override
    public List<ShadowSyncRecord> queryByDateRange(LocalDate startDate, LocalDate endDate, int limit) {
        long startTimestamp = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTimestamp = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;

        String sql = """
            SELECT * FROM shadow_sync_history
            WHERE timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<ShadowSyncRecord> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, startTimestamp);
            ps.setLong(2, endTimestamp);
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query shadow sync records by date range: " + e.getMessage());
        }

        return results;
    }

    @Override
    public List<ShadowSyncRecord> queryByPlayerAndDateRange(String playerUuid, LocalDate startDate, LocalDate endDate, int limit) {
        long startTimestamp = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTimestamp = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;

        String sql = """
            SELECT * FROM shadow_sync_history
            WHERE player_uuid = ? AND timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<ShadowSyncRecord> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setLong(2, startTimestamp);
            ps.setLong(3, endTimestamp);
            ps.setInt(4, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query shadow sync records by player and date range: " + e.getMessage());
        }

        return results;
    }

    @Override
    public int exportToJsonl(String playerUuid, LocalDate startDate, LocalDate endDate) {
        List<ShadowSyncRecord> records;

        if (playerUuid != null && startDate != null && endDate != null) {
            records = queryByPlayerAndDateRange(playerUuid, startDate, endDate, Integer.MAX_VALUE);
        } else if (playerUuid != null) {
            records = queryByPlayer(playerUuid, Integer.MAX_VALUE);
        } else if (startDate != null && endDate != null) {
            records = queryByDateRange(startDate, endDate, Integer.MAX_VALUE);
        } else {
            records = new ArrayList<>();
        }

        if (records.isEmpty()) {
            return 0;
        }

        String fileName = "shadow-sync-export-" + LocalDate.now().format(JSONL_DATE_FORMAT) + ".jsonl";
        File exportFile = new File(jsonlPath, fileName);

        int exported = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(exportFile, true))) {
            for (ShadowSyncRecord record : records) {
                String json = String.format(
                    "{\"timestamp\":\"%s\",\"playerUuid\":\"%s\",\"playerName\":\"%s\",\"balance\":\"%s\",\"syncTarget\":\"%s\",\"operation\":\"%s\",\"success\":%b,\"reason\":\"%s\"}",
                    record.getTimestamp().toString(),
                    record.getPlayerUuid(),
                    record.getPlayerName(),
                    record.getBalance().toPlainString(),
                    record.getSyncTarget(),
                    record.getOperation(),
                    record.isSuccess(),
                    record.getReason() != null ? record.getReason() : ""
                );
                writer.println(json);
                exported++;
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to export shadow sync records to JSONL: " + e.getMessage());
        }

        return exported;
    }

    @Override
    public ShadowSyncStats getTodayStats() {
        return getStatsByDate(LocalDate.now());
    }

    @Override
    public ShadowSyncStats getStatsByDate(LocalDate date) {
        long startTimestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTimestamp = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;

        String sql = """
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as successful,
                SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) as failed
            FROM shadow_sync_history
            WHERE timestamp >= ? AND timestamp <= ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, startTimestamp);
            ps.setLong(2, endTimestamp);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ShadowSyncStats(
                        date,
                        rs.getInt("total"),
                        rs.getInt("successful"),
                        rs.getInt("failed")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get shadow sync stats: " + e.getMessage());
        }

        return new ShadowSyncStats(date, 0, 0, 0);
    }

    @Override
    public int cleanupOldRecords(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }

        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
        long cutoffTimestamp = cutoffDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        String sql = "DELETE FROM shadow_sync_history WHERE timestamp < ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoffTimestamp);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to cleanup old shadow sync records: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().fine("ShadowSync SQLite storage closed.");
        }
    }

    private ShadowSyncRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        ShadowSyncRecord record = new ShadowSyncRecord();
        record.setId(rs.getLong("id"));
        record.setPlayerUuid(rs.getString("player_uuid"));
        record.setPlayerName(rs.getString("player_name"));
        record.setBalance(rs.getBigDecimal("balance"));
        record.setSyncTarget(rs.getString("sync_target"));
        record.setOperation(rs.getString("operation"));
        record.setTimestamp(Instant.ofEpochMilli(rs.getLong("timestamp")));
        record.setSuccess(rs.getInt("success") == 1);
        record.setReason(rs.getString("reason"));
        return record;
    }
}
