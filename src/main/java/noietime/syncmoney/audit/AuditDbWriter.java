package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.economy.EconomyEvent;

import java.sql.*;
import java.util.List;
import java.util.UUID;

/**
 * [REFACTORED] Shared database writer for audit records.
 * Extracts common database operations from AuditLogger, HybridAuditManager, and AuditLogExporter.
 *
 * This class is thread-safe for read operations (mapToRecord).
 * The batchWrite method should be called from a synchronized context or async executor.
 */
public final class AuditDbWriter {

    /**
     * Shared INSERT SQL statement.
     */
    public static final String INSERT_SQL = """
        INSERT INTO syncmoney_audit_log
        (id, timestamp, sequence, type, player_uuid, player_name, amount, balance_after, source, server, target_uuid, target_name, reason, merged_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private AuditDbWriter() {
    }

    /**
     * Maps ResultSet to AuditRecord.
     * This method is thread-safe for concurrent reads.
     *
     * @param rs the ResultSet to map
     * @return the mapped AuditRecord
     * @throws SQLException if a database access error occurs
     */
    public static AuditRecord mapToRecord(ResultSet rs) throws SQLException {
        int mergedCount = 1;
        try {
            mergedCount = rs.getInt("merged_count");
            if (rs.wasNull()) {
                mergedCount = 1;
            }
        } catch (SQLException e) {
            mergedCount = 1;
        }

        int sequence = 0;
        try {
            sequence = rs.getInt("sequence");
            if (rs.wasNull()) {
                sequence = 0;
            }
        } catch (SQLException e) {
            sequence = 0;
        }

        return new AuditRecord(
                rs.getString("id"),
                rs.getLong("timestamp"),
                sequence,
                AuditRecord.AuditType.valueOf(rs.getString("type")),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"),
                EconomyEvent.EventSource.valueOf(rs.getString("source")),
                rs.getString("server"),
                rs.getString("target_uuid") != null ? UUID.fromString(rs.getString("target_uuid")) : null,
                rs.getString("target_name"),
                rs.getString("reason"),
                mergedCount
        );
    }

    /**
     * Batch writes audit records to database.
     * This method should be called from a synchronized context or async executor.
     *
     * @param dataSource the database connection pool
     * @param records    the list of audit records to write
     * @throws SQLException if a database access error occurs
     */
    public static void batchWrite(HikariDataSource dataSource, List<AuditRecord> records) throws SQLException {
        if (dataSource == null || records == null || records.isEmpty()) {
            return;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            for (AuditRecord r : records) {
                setStatementParameters(stmt, r);
                stmt.addBatch();
            }

            stmt.executeBatch();
        }
    }

    /**
     * Batch writes audit records to database with retry logic.
     *
     * @param dataSource the database connection pool
     * @param records    the list of audit records to write
     * @param maxRetry  maximum number of retry attempts
     * @throws SQLException if a database access error occurs after all retries
     */
    public static void batchWriteWithRetry(HikariDataSource dataSource, List<AuditRecord> records, int maxRetry) throws SQLException {
        if (dataSource == null || records == null || records.isEmpty()) {
            return;
        }

        int retryCount = 0;
        long retryDelay = 100;

        while (retryCount < maxRetry) {
            try {
                batchWrite(dataSource, records);
                return;
            } catch (SQLException e) {
                retryCount++;
                if (retryCount >= maxRetry) {
                    throw e;
                }
                try {
                    Thread.sleep(retryDelay * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Batch write interrupted", ie);
                }
            }
        }
    }

    /**
     * Sets the PreparedStatement parameters from an AuditRecord.
     *
     * @param stmt  the PreparedStatement
     * @param record the AuditRecord
     * @throws SQLException if a database access error occurs
     */
    private static void setStatementParameters(PreparedStatement stmt, AuditRecord record) throws SQLException {
        String playerName = record.playerName();
        if (playerName != null && playerName.length() > 16) {
            playerName = playerName.substring(0, 16);
        }

        String targetName = record.targetName();
        if (targetName != null && targetName.length() > 16) {
            targetName = targetName.substring(0, 16);
        }

        stmt.setString(1, record.id());
        stmt.setLong(2, record.timestamp());
        stmt.setInt(3, record.sequence());
        stmt.setString(4, record.type().name());
        stmt.setString(5, record.playerUuid().toString());
        stmt.setString(6, playerName);
        stmt.setBigDecimal(7, record.amount());
        stmt.setBigDecimal(8, record.balanceAfter());
        stmt.setString(9, record.source().name());
        stmt.setString(10, record.server());
        stmt.setString(11, record.targetUuid() != null ? record.targetUuid().toString() : null);
        stmt.setString(12, targetName);
        stmt.setString(13, record.reason());
        stmt.setInt(14, record.mergedCount());
    }
}
