package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.util.Constants;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final long flushIntervalMs;
    private final int maxRetry;

    private final ConcurrentLinkedQueue<AuditRecord> buffer;

    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private volatile boolean enabled = true;

    private static final int MAX_BUFFER_SIZE = 10000;

    private static final long HIGH_FREQUENCY_WINDOW_MS = 300;

    private final AtomicReference<AuditRecord> lastRecord = new AtomicReference<>(null);

    private final Object mergeLock = new Object();

    private final AtomicInteger sequenceGenerator = new AtomicInteger(0);

    private volatile ScheduledTask scheduledFlushTask = null;

    private final ConcurrentLinkedQueue<AuditRecord> deadLetterQueue = new ConcurrentLinkedQueue<>();

    private static final int MAX_DEAD_LETTER_SIZE = 1000;

    public AuditLogger(Plugin plugin, SyncmoneyConfig config, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.config = config;
        this.dataSource = dataSource;
        this.logger = plugin.getLogger();
        this.batchSize = config.audit().getAuditBatchSize();
        this.flushIntervalMs = config.audit().getAuditFlushIntervalMs();
        this.maxRetry = Constants.AUDIT_MAX_RETRY;
        this.buffer = new ConcurrentLinkedQueue<>();

        if (dataSource == null) {
            enabled = false;
            logger.fine("Audit logging disabled: no database source available (LOCAL mode?).");
            return;
        }

        initializeSchema();

        if (!config.audit().isAuditEnabled()) {
            enabled = false;
            logger.warning("Audit logging is disabled in config.");
            return;
        }

        startScheduledFlush();
    }

    /**
     * Starts the scheduled flush task.
     */
    private void startScheduledFlush() {
        if (flushIntervalMs <= 0) {
            logger.fine("Scheduled audit flush disabled (interval = 0).");
            return;
        }

        long intervalTicks = flushIntervalMs / 50;
        if (intervalTicks < 1) {
            intervalTicks = 1;
        }

        scheduledFlushTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            task -> {
                if (!buffer.isEmpty()) {
                    flushAsync();
                }
            },
            intervalTicks,
            intervalTicks
        );

        logger.info("Audit scheduled flush started (interval: " + flushIntervalMs + "ms, ticks: " + intervalTicks + ").");
    }

    /**
     * Initializes database schema.
     */
    private void initializeSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS syncmoney_audit_log (
                id VARCHAR(36) PRIMARY KEY,
                timestamp BIGINT NOT NULL,
                sequence INT DEFAULT 0,
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
            logger.fine("Audit log table initialized.");

            createIndexesIfNotExist(conn);
        } catch (SQLException e) {
            logger.severe("Failed to initialize audit log table: " + e.getMessage());
        }
    }

    /**
     * Create indexes for audit log table.
     */
    private void createIndexesIfNotExist(Connection conn) {
        String[] indexes = {
            "CREATE INDEX IF NOT EXISTS idx_audit_timestamp_sequence ON syncmoney_audit_log(timestamp DESC, sequence DESC)",
            "CREATE INDEX IF NOT EXISTS idx_audit_player_uuid ON syncmoney_audit_log(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_audit_type ON syncmoney_audit_log(type)"
        };

        for (String indexSql : indexes) {
            try (PreparedStatement stmt = conn.prepareStatement(indexSql)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.fine("Index creation: " + e.getMessage());
            }
        }
    }

    /**
     * Logs a single audit record.
     * [THR-04 FIX] Added synchronized to prevent race condition between shouldMerge and merge.
     * @param record audit record
     */
    public void log(AuditRecord record) {
        if (!enabled || record == null) {
            return;
        }

        synchronized (mergeLock) {
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
     * [THREAD-SAFE FIX] Uses retry loop to handle CAS failures.
     * Merge strategy: sums amounts and updates merge count.
     */
    private AuditRecord mergeWithLastRecord(AuditRecord record) {
        while (true) {
            AuditRecord last = lastRecord.get();

            if (last == null) {
                if (lastRecord.compareAndSet(null, record)) {
                    return record;
                }
                continue;
            }

            if (!canMerge(last, record)) {
                if (lastRecord.compareAndSet(last, record)) {
                    return record;
                }
                continue;
            }

            try {
                BigDecimal mergedAmount = last.amount().add(record.amount());

                AuditRecord mergedRecord = new AuditRecord(
                        last.id(),
                        last.timestamp(),
                        last.sequence(),
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
                }
            } catch (Exception e) {
                logger.warning("Failed to merge audit records: " + e.getMessage());
                return record;
            }
        }
    }

    /**
     * Checks if two records can be merged.
     */
    private boolean canMerge(AuditRecord last, AuditRecord record) {
        if (last == null || record == null) {
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
     * Creates and logs audit record from EconomyEvent.
     */
    public void logFromEvent(EconomyEvent event, String playerName, String server) {
        AuditRecord record = AuditRecord.fromEconomyEvent(event, playerName, server);
        log(record);
    }

    /**
     * Creates and logs audit record from EconomyEvent with explicit balanceAfter.
     * @param balanceAfter the actual balance after the transaction
     */
    public void logFromEvent(EconomyEvent event, String playerName, String server, BigDecimal balanceAfter) {
        AuditRecord record = AuditRecord.fromEconomyEvent(event, playerName, server, balanceAfter);
        log(record);
    }

    /**
     * Creates and logs audit record from EconomyEvent with sequence for ordering concurrent transactions.
     * @param balanceAfter the actual balance after the transaction
     * @param mergedCount number of merged transactions
     * @param sequence the sequence number for ordering (auto-generated if <= 0)
     */
    public void logFromEvent(EconomyEvent event, String playerName, String server, BigDecimal balanceAfter, int mergedCount, int sequence) {
        if (sequence <= 0) {
            sequence = sequenceGenerator.incrementAndGet();
        }
        AuditRecord record = AuditRecord.fromEconomyEvent(event, playerName, server, balanceAfter, mergedCount, sequence);
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
                0,
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
     * [RED-01 FIX] Now delegates to AuditDbWriter.batchWriteWithRetry()
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

        try {
            AuditDbWriter.batchWriteWithRetry(dataSource, batch, maxRetry);
            logger.fine("Flushed " + batch.size() + " audit records to database.");
        } catch (SQLException e) {
            logger.severe("Failed to flush " + batch.size() + " audit records: " + e.getMessage());
            handleFailedBatch(batch);
        }
    }

    /**
     * Handles failed batch by adding to dead letter queue.
     */
    private void handleFailedBatch(List<AuditRecord> batch) {
        for (AuditRecord record : batch) {
            if (deadLetterQueue.size() < MAX_DEAD_LETTER_SIZE) {
                deadLetterQueue.add(record);
            } else {
                logger.severe("Dead letter queue full, dropping audit record: " + record.id());
            }
        }
        logger.warning("Added " + batch.size() + " records to dead letter queue. Current size: " + deadLetterQueue.size());
    }

    /**
     * Attempts to retry failed records from dead letter queue.
     */
    public void retryDeadLetterQueue() {
        if (deadLetterQueue.isEmpty()) {
            return;
        }

        List<AuditRecord> retryBatch = new ArrayList<>();
        AuditRecord record;
        while ((record = deadLetterQueue.poll()) != null && retryBatch.size() < batchSize) {
            retryBatch.add(record);
        }

        if (!retryBatch.isEmpty()) {
            logger.info("Retrying " + retryBatch.size() + " records from dead letter queue...");
            buffer.addAll(retryBatch);
            flush();
        }
    }

    /**
     * Queries player's audit records.
     * @param uuid player UUID
     * @param limit maximum number of results
     * @return list of audit records
     */
    public List<AuditRecord> getPlayerRecords(UUID uuid, int limit) {
        return getPlayerRecords(uuid, limit, 0);
    }

    /**
     * Queries player's audit records with offset support.
     * @param uuid player UUID
     * @param limit maximum number of results
     * @param offset number of records to skip
     * @return list of audit records
     */
    public List<AuditRecord> getPlayerRecords(UUID uuid, int limit, int offset) {
        if (dataSource == null) {
            return Collections.emptyList();
        }

        String sql;
        if (offset > 0) {
            sql = """
                SELECT * FROM syncmoney_audit_log
                WHERE player_uuid = ?
                ORDER BY timestamp DESC, sequence DESC
                LIMIT ? OFFSET ?
                """;
        } else {
            sql = """
                SELECT * FROM syncmoney_audit_log
                WHERE player_uuid = ?
                ORDER BY timestamp DESC, sequence DESC
                LIMIT ?
                """;
        }

        List<AuditRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, uuid.toString());
            if (offset > 0) {
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
            } else {
                stmt.setInt(2, limit);
            }

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
     * Returns the count of audit records whose timestamp falls within [startTime, endTime].
     * Uses a SELECT COUNT(*) query — no LIMIT, no row fetching — so the result is always
     * accurate regardless of how many records exist (avoids the capped-at-100 issue).
     *
     * @param startTime inclusive start timestamp (epoch ms); 0 for no lower bound
     * @param endTime   inclusive end timestamp (epoch ms); 0 for no upper bound
     * @return exact number of matching records, or 0 if unavailable
     */
    public int countByTimeRange(long startTime, long endTime) {
        if (dataSource == null) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM syncmoney_audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (startTime > 0) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }

        if (endTime > 0) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to count audit records by time range: " + e.getMessage());
        }

        return 0;
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

        sql.append(" ORDER BY timestamp DESC, sequence DESC LIMIT ?");
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
     * Searches audit records with cursor pagination.
     * This is more efficient than offset-based pagination for large datasets.
     *
     * @param criteria search criteria including cursor
     * @return SearchResult with records, nextCursor and hasMore flag
     */
    public SearchResult searchWithCursor(AuditSearchCriteria criteria) {
        if (dataSource == null) {
            return new SearchResult(Collections.emptyList(), null, false);
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

        String cursor = criteria.cursor();
        if (cursor != null && !cursor.isEmpty()) {
            String[] parts = cursor.split(",");
            if (parts.length >= 2) {
                try {
                    long lastTimestamp = Long.parseLong(parts[0]);
                    int lastSequence = Integer.parseInt(parts[1]);
                    sql.append(" AND (timestamp < ? OR (timestamp = ? AND sequence < ?))");
                    params.add(lastTimestamp);
                    params.add(lastTimestamp);
                    params.add(lastSequence);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid cursor format: " + cursor);
                }
            }
        }


        sql.append(" ORDER BY timestamp DESC, sequence DESC LIMIT ?");

        params.add(criteria.limit() + 1);

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
            logger.severe("Failed to search audit records with cursor: " + e.getMessage());
            return new SearchResult(Collections.emptyList(), null, false);
        }


        boolean hasMore = records.size() > criteria.limit();
        if (hasMore) {
            records = records.subList(0, criteria.limit());
        }


        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            AuditRecord last = records.get(records.size() - 1);
            nextCursor = last.timestamp() + "," + last.sequence();
        }

        return new SearchResult(records, nextCursor, hasMore);
    }

    /**
     * Maps ResultSet to AuditRecord.
     * [REFACTORED] Now delegates to AuditDbWriter.mapToRecord()
     */
    private AuditRecord mapToRecord(ResultSet rs) throws SQLException {
        return AuditDbWriter.mapToRecord(rs);
    }

    /**
     * Gets buffer size.
     */
    public int getBufferSize() {
        return buffer.size();
    }

    /**
     * Gets dead letter queue size.
     */
    public int getDeadLetterQueueSize() {
        return deadLetterQueue.size();
    }

    /**
     * Gets scheduled flush interval in milliseconds.
     */
    public long getFlushIntervalMs() {
        return flushIntervalMs;
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

        if (scheduledFlushTask != null) {
            scheduledFlushTask.cancel();
            logger.fine("Cancelled scheduled audit flush task.");
        }


        flush();


        retryDeadLetterQueue();

        logger.fine("AuditLogger closed. Dead letter queue size: " + deadLetterQueue.size());
    }

    /**
     * Audit search criteria.
     */
    public record AuditSearchCriteria(
            UUID playerUuid,
            long startTime,
            long endTime,
            AuditRecord.AuditType type,
            int limit,
            String cursor
    ) {
        public AuditSearchCriteria {
            if (limit <= 0) {
                limit = 100;
            }
        }
    }

    /**
     * Search result with cursor pagination.
     */
    public record SearchResult(
            List<AuditRecord> records,
            String nextCursor,
            boolean hasMore
    ) {}
}
