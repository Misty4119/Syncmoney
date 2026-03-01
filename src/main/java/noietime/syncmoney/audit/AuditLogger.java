package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * [SYNC-AUDIT-001] Audit logger for economic transactions.
 * Records all balance changes and batches writes to database.
 * Supports high-frequency transaction merging for readable logs.
 *
 * [AsyncScheduler] This class is designed to run on async threads.
 */
public final class AuditLogger {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final HikariDataSource dataSource;
    private final Logger logger;


    private final int batchSize;


    private final ConcurrentLinkedQueue<AuditRecord> buffer;


    private final AtomicBoolean flushing = new AtomicBoolean(false);


    private volatile boolean enabled = true;


    private static final int MAX_BUFFER_SIZE = 10000;


    private static final long HIGH_FREQUENCY_WINDOW_MS = 1000;


    private final AtomicReference<AuditRecord> lastRecord = new AtomicReference<>(null);


    public AuditLogger(Plugin plugin, SyncmoneyConfig config, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.config = config;
        this.dataSource = dataSource;
        this.logger = plugin.getLogger();
        this.batchSize = config.getAuditBatchSize();
        this.buffer = new ConcurrentLinkedQueue<>();

        if (dataSource == null) {
            enabled = false;
            logger.fine("Audit logging disabled: no database source available (LOCAL mode?).");
            return;
        }

        initializeSchema();

        if (!config.isAuditEnabled()) {
            enabled = false;
            logger.warning("Audit logging is disabled in config.");
        }
    }

    /**
     * Initializes database schema.
     */
    private void initializeSchema() {
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
            logger.info("Audit log table initialized.");
        } catch (SQLException e) {
            logger.severe("Failed to initialize audit log table: " + e.getMessage());
        }
    }

    /**
     * Logs a single audit record.
     * @param record audit record
     */
    public void log(AuditRecord record) {
        if (!enabled || record == null) {
            return;
        }

        if (shouldMergeWithLastRecord(record)) {
            record = mergeWithLastRecord(record);
            if (record == null) {
                return;
            }
        }

        if (buffer.size() >= MAX_BUFFER_SIZE) {
            logger.warning("Audit buffer is full, forcing flush...");
            flush();
        }

        buffer.add(record);

        if (buffer.size() >= batchSize) {
            flushAsync();
        }
    }

    /**
     * Checks if should merge with previous record.
     * Merge conditions:
     * 1. Same player
     * 2. Same type
     * 3. Same source
     * 4. Time difference within HIGH_FREQUENCY_WINDOW_MS
     */
    private boolean shouldMergeWithLastRecord(AuditRecord record) {
        AuditRecord last = lastRecord.get();
        if (last == null) {
            return false;
        }

        if (!last.playerUuid().equals(record.playerUuid())) {
            return false;
        }

        if (last.type() != record.type()) {
            return false;
        }

        if (last.source() != record.source()) {
            return false;
        }

        long timeDiff = record.timestamp() - last.timestamp();
        return timeDiff > 0 && timeDiff <= HIGH_FREQUENCY_WINDOW_MS;
    }

    /**
     * Merges with previous record.
     * Merge strategy: sums amounts and updates merge count.
     */
    private AuditRecord mergeWithLastRecord(AuditRecord record) {
        AuditRecord last = lastRecord.get();
        if (last == null) {
            lastRecord.set(record);
            return record;
        }

        try {
            BigDecimal mergedAmount = last.amount().add(record.amount());

            AuditRecord mergedRecord = new AuditRecord(
                    last.id(),
                    last.timestamp(),
                    last.type(),
                    last.playerUuid(),
                    last.playerName(),
                    mergedAmount,
                    record.balanceAfter(),
                    last.source(),
                    last.server(),
                    last.targetUuid(),
                    last.targetName(),
                    last.reason(),
                    last.mergedCount() + 1
            );

            if (lastRecord.compareAndSet(last, mergedRecord)) {
                return mergedRecord;
            } else {
                lastRecord.set(record);
                return record;
            }
        } catch (Exception e) {
            logger.warning("Failed to merge audit records: " + e.getMessage());
            lastRecord.set(record);
            return record;
        }
    }

    /**
     * Creates and logs audit record from EconomyEvent.
     */
    public void logFromEvent(EconomyEvent event, String playerName, String server) {
        AuditRecord record = AuditRecord.fromEconomyEvent(event, playerName, server);
        log(record);
    }

    /**
     * Logs critical failure (for tracking after Redis write failures).
     */
    public void logCriticalFailure(String playerName, EconomyEvent event, long failureDurationMs) {
        try {
            AuditRecord record = new AuditRecord(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                AuditRecord.AuditType.CRITICAL_FAILURE,
                event.playerUuid(),
                playerName,
                BigDecimal.ZERO,
                event.balanceAfter(),
                event.source(),
                config.getServerName(),
                null,
                null,
                "Redis write failed after " + failureDurationMs + "ms, player kicked",
                1
            );
            log(record);
            logger.warning("Critical failure logged for player: " + playerName);
        } catch (Exception e) {
            logger.severe("Failed to log critical failure: " + e.getMessage());
        }
    }

    /**
     * Asynchronously flushes buffer.
     */
    private void flushAsync() {
        if (flushing.get() || buffer.isEmpty()) {
            return;
        }

        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                flush();
            } finally {
                flushing.set(false);
            }
        });
    }

    /**
     * Synchronously flushes buffer (batch writes to database).
     */
    public synchronized void flush() {
        if (dataSource == null || buffer.isEmpty()) {
            return;
        }

        List<AuditRecord> batch = new ArrayList<>();
        AuditRecord record;

        while ((record = buffer.poll()) != null && batch.size() < batchSize) {
            batch.add(record);
        }

        if (batch.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO syncmoney_audit_log
            (id, timestamp, type, player_uuid, player_name, amount, balance_after, source, server, target_uuid, target_name, reason, merged_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (AuditRecord r : batch) {
                String playerName = r.playerName();
                if (playerName != null && playerName.length() > 16) {
                    playerName = playerName.substring(0, 16);
                }

                String targetName = r.targetName();
                if (targetName != null && targetName.length() > 16) {
                    targetName = targetName.substring(0, 16);
                }

                stmt.setString(1, r.id());
                stmt.setLong(2, r.timestamp());
                stmt.setString(3, r.type().name());
                stmt.setString(4, r.playerUuid().toString());
                stmt.setString(5, playerName);
                stmt.setBigDecimal(6, r.amount());
                stmt.setBigDecimal(7, r.balanceAfter());
                stmt.setString(8, r.source().name());
                stmt.setString(9, r.server());
                stmt.setString(10, r.targetUuid() != null ? r.targetUuid().toString() : null);
                stmt.setString(11, targetName);
                stmt.setString(12, r.reason());
                stmt.setInt(13, r.mergedCount());
                stmt.addBatch();
            }

            stmt.executeBatch();
            logger.info("Flushed " + batch.size() + " audit records to database.");

        } catch (SQLException e) {
            logger.severe("Failed to flush audit records: " + e.getMessage());
            buffer.addAll(batch);
        }
    }

    /**
     * Queries player's audit records.
     * @param uuid player UUID
     * @param limit maximum number of results
     * @return list of audit records
     */
    public List<AuditRecord> getPlayerRecords(UUID uuid, int limit) {
        if (dataSource == null) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT * FROM syncmoney_audit_log
            WHERE player_uuid = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """;

        List<AuditRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, uuid.toString());
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to query audit records: " + e.getMessage());
        }

        return records;
    }

    /**
     * Searches audit records.
     */
    public List<AuditRecord> search(AuditSearchCriteria criteria) {
        if (dataSource == null) {
            return Collections.emptyList();
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM syncmoney_audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (criteria.playerUuid() != null) {
            sql.append(" AND player_uuid = ?");
            params.add(criteria.playerUuid().toString());
        }

        if (criteria.startTime() > 0) {
            sql.append(" AND timestamp >= ?");
            params.add(criteria.startTime());
        }

        if (criteria.endTime() > 0) {
            sql.append(" AND timestamp <= ?");
            params.add(criteria.endTime());
        }

        if (criteria.type() != null) {
            sql.append(" AND type = ?");
            params.add(criteria.type().name());
        }

        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        params.add(criteria.limit());

        List<AuditRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to search audit records: " + e.getMessage());
        }

        return records;
    }

    /**
     * Maps ResultSet to AuditRecord.
     */
    private AuditRecord mapToRecord(ResultSet rs) throws SQLException {
        int mergedCount = 1;
        try {
            mergedCount = rs.getInt("merged_count");
            if (rs.wasNull()) {
                mergedCount = 1;
            }
        } catch (SQLException e) {
            mergedCount = 1;
        }

        return new AuditRecord(
                rs.getString("id"),
                rs.getLong("timestamp"),
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
     * Gets buffer size.
     */
    public int getBufferSize() {
        return buffer.size();
    }

    /**
     * Sets enabled status.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks if enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Closes and flushes remaining data.
     */
    public void close() {
        flush();
        logger.info("AuditLogger closed.");
    }

    /**
     * Audit search criteria.
     */
    public record AuditSearchCriteria(
            UUID playerUuid,
            long startTime,
            long endTime,
            AuditRecord.AuditType type,
            int limit
    ) {
        public AuditSearchCriteria {
            if (limit <= 0) {
                limit = 100;
            }
        }
    }
}
