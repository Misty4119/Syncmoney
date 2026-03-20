package noietime.syncmoney.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.web.websocket.SseManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Hybrid audit log manager that uses Redis sliding window for real-time audit
 * and dynamically migrates to database for persistent storage.
 *
 * Architecture:
 * - Redis: Stores latest N records (sliding window) for real-time access
 * - Database: Stores complete audit history
 * - Migration: Dynamically triggers when pending records reach threshold
 *
 * [AsyncScheduler] This class handles async operations for Redis and DB writes.
 */
public final class HybridAuditManager {


    private static final String AUDIT_LIST_KEY = "syncmoney:audit:recent";
    private static final String AUDIT_INDEX_KEY = "syncmoney:audit:index";

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final HikariDataSource dataSource;
    private volatile SseManager sseManager;
    private final Logger logger;


    private final boolean redisEnabled;
    private final int windowSize;
    private final int migrationThreshold;
    private final int migrationBatchSize;
    private final long migrationIntervalMs;
    private final long flushIntervalMs;


    private final AtomicInteger pendingMigrationCount = new AtomicInteger(0);
    private final AtomicBoolean migrating = new AtomicBoolean(false);
    private volatile long lastMigrationTime = 0;


    private final ConcurrentLinkedQueue<AuditRecord> migrationQueue = new ConcurrentLinkedQueue<>();


    private volatile ScheduledTask scheduledMigrationTask = null;


    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);


    private AuditLuaScriptManager luaScriptManager;


    private final AtomicInteger sequenceGenerator = new AtomicInteger(0);

    private volatile boolean enabled = true;

    public HybridAuditManager(Syncmoney plugin, SyncmoneyConfig config,
                               RedisManager redisManager, HikariDataSource dataSource, SseManager sseManager) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.dataSource = dataSource;
        this.sseManager = sseManager;
        this.logger = plugin.getLogger();


        this.redisEnabled = config.isAuditRedisEnabled() && redisManager != null && !redisManager.isDegraded();
        this.windowSize = config.getAuditRedisWindowSize();
        this.migrationThreshold = config.getAuditMigrationThreshold();
        this.migrationBatchSize = config.getAuditMigrationBatchSize();
        this.migrationIntervalMs = config.getAuditMigrationIntervalMs();
        this.flushIntervalMs = config.getAuditRedisFlushIntervalMs();

        if (!redisEnabled) {
            logger.fine("Hybrid audit manager initialized in DB-only mode (Redis disabled or degraded).");
            return;
        }


        this.luaScriptManager = new AuditLuaScriptManager(plugin, redisManager);
        luaScriptManager.load();


        initializeRedisKeys();


        startScheduledMigration();

        logger.info("Hybrid audit manager initialized (Redis sliding window: " + windowSize + ").");
    }

    /**
     * Initializes Redis keys with expiration.
     */
    private void initializeRedisKeys() {
        try (var jedis = redisManager.getResource()) {

            jedis.expire(AUDIT_LIST_KEY, 2592000);
            jedis.expire(AUDIT_INDEX_KEY, 2592000);
        } catch (Exception e) {
            logger.warning("Failed to initialize Redis audit keys: " + e.getMessage());
        }
    }

    /**
     * Starts the scheduled migration task.
     */
    private void startScheduledMigration() {
        if (migrationIntervalMs <= 0) {
            logger.fine("Scheduled audit migration disabled (interval = 0).");
            return;
        }

        long intervalTicks = migrationIntervalMs / 50;
        if (intervalTicks < 1) {
            intervalTicks = 1;
        }

        scheduledMigrationTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            task -> {
                if (!migrationQueue.isEmpty()) {
                    checkAndTriggerMigration();
                }
            },
            intervalTicks,
            intervalTicks
        );

        logger.info("Audit scheduled migration started (interval: " + migrationIntervalMs + "ms).");
    }

    /**
     * Logs an audit record to Redis (real-time) or DB (fallback).
     * [P2-FIX] Added deduplication check before logging.
     */
    public void log(AuditRecord record) {
        if (!enabled || record == null) {
            return;
        }


        if (isDuplicateRecord(record)) {
            logger.fine("Duplicate audit record skipped: " + record.id());
            return;
        }


        int sequence = sequenceGenerator.incrementAndGet();
        AuditRecord recordWithSequence = record.withSequence(sequence);

        if (redisEnabled && luaScriptManager != null) {

            writeToRedis(recordWithSequence);


            migrationQueue.add(recordWithSequence);


            checkAndTriggerMigration();
        } else {

            writeToDatabase(recordWithSequence);
        }


        broadcastAuditEvent(recordWithSequence);
    }

    /**
     * [P2-FIX] Layer 2: Check for duplicate records using composite key
     * Composite key = timestamp + type + player_uuid + amount
     */
    private boolean isDuplicateRecord(AuditRecord record) {
        if (!redisEnabled) {
            return false;
        }


        if (!plugin.getSyncmoneyConfig().isAuditDeduplicationEnabled()) {
            return false;
        }

        try (var jedis = redisManager.getResource()) {

            String dedupKey = "syncmoney:audit:dedup:" + record.timestamp() + ":" + record.type() + ":" + record.playerUuid() + ":" + record.amount();


            Long added = jedis.setnx(dedupKey, String.valueOf(record.timestamp()));
            if (added == null || added == 0) {

                return true;
            }


            int windowSeconds = plugin.getSyncmoneyConfig().getAuditDeduplicationWindowSeconds();
            jedis.expire(dedupKey, windowSeconds);

            return false;
        } catch (Exception e) {

            logger.warning("Failed to check duplicate in Redis: " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes audit record to Redis using Lua script.
     */
    private void writeToRedis(AuditRecord record) {
        try {
            String json = serializeToJson(record);

            String sha = luaScriptManager.getAuditScriptSha();
            if (sha == null || luaScriptManager.isAuditScriptBlocked()) {

                writeToRedisFallback(json);
                return;
            }

            try (var jedis = redisManager.getResource()) {
                jedis.evalsha(sha, 2, AUDIT_LIST_KEY, AUDIT_INDEX_KEY,
                    String.valueOf(windowSize), json);
            }
        } catch (Exception e) {
            logger.warning("Failed to write audit to Redis: " + e.getMessage());


            if (luaScriptManager.handleError(e.getMessage())) {
                logger.info("Audit Lua script reloaded, will retry on next write.");
            } else {

                writeToRedisFallback(serializeToJson(record));
            }
        }
    }

    /**
     * Fallback method using regular Redis commands with Pipeline optimization.
     * [TASK-445 FIX] Uses Redis Pipeline for batch operations.
     */
    private void writeToRedisFallback(String json) {
        try (var jedis = redisManager.getResource()) {

            var pipeline = jedis.pipelined();


            pipeline.lpush(AUDIT_LIST_KEY, json);


            pipeline.ltrim(AUDIT_LIST_KEY, 0, windowSize - 1);


            pipeline.incr(AUDIT_INDEX_KEY);


            pipeline.sync();
        } catch (Exception e) {
            logger.severe("Failed to write audit to Redis fallback: " + e.getMessage());
        }
    }

    /**
     * Checks and triggers migration if threshold is reached or time-based flush is due.
     */
    private void checkAndTriggerMigration() {
        int pending = migrationQueue.size();
        long currentTime = System.currentTimeMillis();


        boolean shouldMigrate = false;


        if (pending >= migrationThreshold) {
            shouldMigrate = true;
        }


        if (flushIntervalMs > 0 && !shouldMigrate && pending > 0) {
            if (lastMigrationTime == 0) {

                shouldMigrate = true;
            } else if (currentTime - lastMigrationTime >= flushIntervalMs) {

                shouldMigrate = true;
            }
        }

        if (shouldMigrate && migrating.compareAndSet(false, true)) {
            lastMigrationTime = currentTime;
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    performMigration();
                } finally {
                    migrating.set(false);
                }
            });
        }
    }

    /**
     * Performs migration from queue to database.
     */
    private void performMigration() {
        List<AuditRecord> batch = new ArrayList<>();


        while (batch.size() < migrationBatchSize) {
            AuditRecord record = migrationQueue.poll();
            if (record == null) break;
            batch.add(record);
        }

        if (!batch.isEmpty()) {
            try {
                batchWriteToDatabase(batch);
                logger.fine("Migrated " + batch.size() + " audit records to database.");
            } catch (Exception e) {
                logger.severe("Failed to migrate audit records: " + e.getMessage());

                migrationQueue.addAll(batch);
            }
        }


        if (!migrationQueue.isEmpty()) {
            checkAndTriggerMigration();
        }
    }

    /**
     * Batch writes audit records to database.
     * [REFACTORED] Now delegates to AuditDbWriter.batchWrite()
     */
    private void batchWriteToDatabase(List<AuditRecord> records) throws SQLException {
        if (dataSource == null || records.isEmpty()) {
            return;
        }

        AuditDbWriter.batchWrite(dataSource, records);
    }

    /**
     * Writes a single audit record to database (fallback).
     */
    private void writeToDatabase(AuditRecord record) {
        try {
            batchWriteToDatabase(List.of(record));
        } catch (SQLException e) {
            logger.severe("Failed to write audit to database: " + e.getMessage());
        }
    }

    /**
     * Queries audit records with merged results from Redis and Database.
     */
    public List<AuditRecord> queryAudit(UUID playerUuid, int limit) {
        return queryAudit(playerUuid, limit, 0);
    }

    /**
     * Queries audit records with merged results from Redis and Database.
     * @param playerUuid player UUID to filter
     * @param limit maximum number of results
     * @param offset number of records to skip
     * @return list of audit records
     */
    public List<AuditRecord> queryAudit(UUID playerUuid, int limit, int offset) {
        List<AuditRecord> results = new ArrayList<>();






        if (redisEnabled) {

            int redisFetchSize = 500;
            List<AuditRecord> redisRecords = queryRedisNoPagination(playerUuid, redisFetchSize);
            results.addAll(redisRecords);
        }


        if (dataSource != null) {
            List<AuditRecord> dbRecords = queryDatabase(playerUuid, limit, 0);
            results.addAll(dbRecords);
        }


        results = results.stream()
            .collect(java.util.stream.Collectors.toMap(
                AuditRecord::id,
                r -> r,
                (existing, replacement) -> existing,
                java.util.LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();


        return results.stream()
            .sorted(Comparator.comparingLong(AuditRecord::timestamp).reversed())
            .skip(offset)
            .limit(limit)
            .toList();
    }

    /**
     * Queries audit records from Redis WITHOUT pagination - returns all matching records.
     * Used by queryAudit() to fix the pagination bug.
     */
    private List<AuditRecord> queryRedisNoPagination(UUID playerUuid, int fetchSize) {
        List<AuditRecord> records = new ArrayList<>();

        if (!redisEnabled) {
            return records;
        }

        try (var jedis = redisManager.getResource()) {

            List<String> jsonList = jedis.lrange(AUDIT_LIST_KEY, 0, fetchSize - 1);

            for (String json : jsonList) {
                try {
                    AuditRecord record = deserializeFromJson(json);
                    if (record != null && record.playerUuid().equals(playerUuid)) {
                        records.add(record);
                    }
                } catch (Exception e) {
                    logger.warning("Failed to deserialize audit record: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to query audit from Redis: " + e.getMessage());
        }

        return records;
    }

    /**
     * Queries audit records with merged results from Redis and Database with full filter support.
     * [AU08 FIX] Supports type, startTime, endTime filters.
     *
     * @param playerUuid player UUID to filter (null for all players)
     * @param auditType audit type to filter (null for all types)
     * @param startTime start timestamp to filter (0 for no filter)
     * @param endTime end timestamp to filter (0 for no filter)
     * @param limit maximum number of results
     * @param offset number of records to skip
     * @return list of audit records
     */
    public List<AuditRecord> queryAuditFiltered(UUID playerUuid, AuditRecord.AuditType auditType,
                                                long startTime, long endTime, int limit, int offset) {
        List<AuditRecord> results = new ArrayList<>();


        int totalNeeded = limit + offset;


        if (redisEnabled) {
            List<AuditRecord> redisRecords = getRecentFromRedisFiltered(
                playerUuid, auditType, startTime, endTime, totalNeeded);
            results.addAll(redisRecords);
        }


        if (dataSource != null) {
            List<AuditRecord> dbRecords = queryDatabaseFiltered(
                playerUuid, auditType, startTime, endTime, limit, 0);
            results.addAll(dbRecords);
        }


        results = results.stream()
            .collect(java.util.stream.Collectors.toMap(
                AuditRecord::id,
                r -> r,
                (existing, replacement) -> existing,
                java.util.LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();


        return results.stream()
            .sorted(Comparator.comparingLong(AuditRecord::timestamp).reversed())
            .skip(offset)
            .limit(limit)
            .toList();
    }

    /**
     * Queries audit records from Database with filters.
     */
    private List<AuditRecord> queryDatabaseFiltered(UUID playerUuid, AuditRecord.AuditType auditType,
                                                    long startTime, long endTime, int limit, int offset) {
        List<AuditRecord> records = new ArrayList<>();

        if (dataSource == null) {
            return records;
        }

        StringBuilder sql = new StringBuilder(
            "SELECT * FROM syncmoney_audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (playerUuid != null) {
            sql.append(" AND player_uuid = ?");
            params.add(playerUuid.toString());
        }
        if (auditType != null) {
            sql.append(" AND type = ?");
            params.add(auditType.name());
        }
        if (startTime > 0) {
            sql.append(" AND timestamp >= ?");
            params.add(startTime);
        }
        if (endTime > 0) {
            sql.append(" AND timestamp <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY timestamp DESC, sequence DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AuditRecord record = mapToRecord(rs);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to query filtered audit from Database: " + e.getMessage());
        }

        return records;
    }

    /**
     * Queries audit records from Redis.
     */
    private List<AuditRecord> queryRedis(UUID playerUuid, int limit) {
        return queryRedis(playerUuid, limit, 0);
    }

    /**
     * Queries audit records from Redis with offset support.
     * 
     * [BUG FIX] Previous implementation used lrange with offset for pagination, but then
     * filtered by playerUuid - this caused wrong results because lrange returns fixed positions
     * in the list, not filtered results.
     * 
     * New implementation: Fetch enough records from Redis first, then filter and paginate.
     */
    private List<AuditRecord> queryRedis(UUID playerUuid, int limit, int offset) {
        List<AuditRecord> records = new ArrayList<>();

        if (!redisEnabled) {
            return records;
        }

        try (var jedis = redisManager.getResource()) {


            int fetchSize = (offset + limit) * 3;
            

            List<String> jsonList = jedis.lrange(AUDIT_LIST_KEY, 0, fetchSize - 1);

            for (String json : jsonList) {
                try {
                    AuditRecord record = deserializeFromJson(json);
                    if (record != null && record.playerUuid().equals(playerUuid)) {
                        records.add(record);
                    }
                } catch (Exception e) {
                    logger.warning("Failed to deserialize audit record: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to query audit from Redis: " + e.getMessage());
        }

        return records;
    }

    /**
     * Queries audit records from Database.
     */
    private List<AuditRecord> queryDatabase(UUID playerUuid, int limit) {
        return queryDatabase(playerUuid, limit, 0);
    }

    /**
     * Queries audit records from Database with offset support.
     */
    private List<AuditRecord> queryDatabase(UUID playerUuid, int limit, int offset) {
        List<AuditRecord> records = new ArrayList<>();

        if (dataSource == null) {
            return records;
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerUuid.toString());
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
            logger.severe("Failed to query audit from database: " + e.getMessage());
        }

        return records;
    }

    /**
     * Maps ResultSet to AuditRecord.
     * [REFACTORED] Now delegates to AuditDbWriter.mapToRecord()
     */
    private AuditRecord mapToRecord(ResultSet rs) throws SQLException {
        return AuditDbWriter.mapToRecord(rs);
    }

    /**
     * Serializes AuditRecord to JSON.
     */
    private String serializeToJson(AuditRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            logger.warning("Failed to serialize audit record: " + e.getMessage());
            return "{}";
        }
    }

    /**
     * Deserializes JSON to AuditRecord.
     */
    private AuditRecord deserializeFromJson(String json) {
        try {
            return objectMapper.readValue(json, AuditRecord.class);
        } catch (JsonProcessingException e) {
            logger.warning("Failed to deserialize audit record: " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets the current pending migration count.
     */
    public int getPendingMigrationCount() {
        return migrationQueue.size();
    }

    /**
     * Queries recent audit records from Redis without filtering.
     * Used by API to get the latest audit records from Redis.
     *
     * @param limit maximum number of records to return
     * @return list of recent audit records from Redis
     */
    public List<AuditRecord> getRecentFromRedis(int limit) {
        List<AuditRecord> records = new ArrayList<>();

        if (!redisEnabled) {
            return records;
        }

        try (var jedis = redisManager.getResource()) {
            List<String> jsonList = jedis.lrange(AUDIT_LIST_KEY, 0, limit - 1);

            for (String json : jsonList) {
                try {
                    AuditRecord record = deserializeFromJson(json);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (Exception e) {
                    logger.warning("Failed to deserialize audit record: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to query audit from Redis: " + e.getMessage());
        }

        return records;
    }

    /**
     * Queries recent audit records from Redis with optional filters.
     * Used by API to get filtered audit records from Redis.
     *
     * @param playerUuid player UUID to filter (null for all players)
     * @param auditType audit type to filter (null for all types)
     * @param startTime start timestamp to filter (0 for no filter)
     * @param endTime end timestamp to filter (0 for no filter)
     * @param limit maximum number of records to return
     * @return list of filtered audit records from Redis
     */
    public List<AuditRecord> getRecentFromRedisFiltered(UUID playerUuid, AuditRecord.AuditType auditType,
                                                         long startTime, long endTime, int limit) {
        List<AuditRecord> records = new ArrayList<>();

        if (!redisEnabled) {
            return records;
        }

        try (var jedis = redisManager.getResource()) {
            List<String> jsonList = jedis.lrange(AUDIT_LIST_KEY, 0, -1);

            for (String json : jsonList) {
                if (records.size() >= limit) {
                    break;
                }

                try {
                    AuditRecord record = deserializeFromJson(json);
                    if (record == null) {
                        continue;
                    }


                    if (playerUuid != null && !record.playerUuid().equals(playerUuid)) {
                        continue;
                    }

                    if (auditType != null && record.type() != auditType) {
                        continue;
                    }

                    if (startTime > 0 && record.timestamp() < startTime) {
                        continue;
                    }

                    if (endTime > 0 && record.timestamp() > endTime) {
                        continue;
                    }

                    records.add(record);
                } catch (Exception e) {
                    logger.warning("Failed to deserialize audit record: " + e.getMessage());
                }
            }


            records.sort((a, b) -> {
                int cmp = Long.compare(b.timestamp(), a.timestamp());
                if (cmp == 0) {
                    cmp = Integer.compare(b.sequence(), a.sequence());
                }
                return cmp;
            });
        } catch (Exception e) {
            logger.warning("Failed to query audit from Redis: " + e.getMessage());
        }

        return records;
    }

    /**
     * Gets Redis boundary info for cursor pagination.
     * Returns the oldest timestamp in Redis sliding window for DB query optimization.
     *
     * @return RedisBoundaryInfo containing oldest/newest timestamps, or null if Redis is disabled
     */
    public RedisBoundaryInfo getRedisBoundaryInfo() {
        if (!redisEnabled) {
            return null;
        }

        try (var jedis = redisManager.getResource()) {

            String oldestJson = jedis.lindex(AUDIT_LIST_KEY, -1);

            String newestJson = jedis.lindex(AUDIT_LIST_KEY, 0);

            long oldestTimestamp = 0;
            long newestTimestamp = 0;

            if (oldestJson != null && !oldestJson.isEmpty()) {
                try {
                    AuditRecord oldest = deserializeFromJson(oldestJson);
                    if (oldest != null) {
                        oldestTimestamp = oldest.timestamp();
                    }
                } catch (Exception e) {
                    logger.fine("Failed to deserialize oldest record: " + e.getMessage());
                }
            }

            if (newestJson != null && !newestJson.isEmpty()) {
                try {
                    AuditRecord newest = deserializeFromJson(newestJson);
                    if (newest != null) {
                        newestTimestamp = newest.timestamp();
                    }
                } catch (Exception e) {
                    logger.fine("Failed to deserialize newest record: " + e.getMessage());
                }
            }

            return new RedisBoundaryInfo(oldestTimestamp, newestTimestamp);
        } catch (Exception e) {
            logger.warning("Failed to get Redis boundary info: " + e.getMessage());
            return null;
        }
    }

    /**
     * Record containing Redis sliding window boundary timestamps.
     */
    public record RedisBoundaryInfo(long oldestTimestamp, long newestTimestamp) {}

    /**
     * Gets the Redis sliding window size.
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Checks if Redis is enabled for hybrid audit.
     */
    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    /**
     * Closes and flushes remaining data.
     */
    public void close() {

        if (scheduledMigrationTask != null) {
            scheduledMigrationTask.cancel();
            logger.fine("Cancelled scheduled audit migration task.");
        }


        if (!migrationQueue.isEmpty()) {
            logger.info("Flushing " + migrationQueue.size() + " remaining audit records...");
            performMigration();
        }

        logger.fine("HybridAuditManager closed.");
    }

    /**
     * [P2-FIX] Broadcast audit event to SSE clients for real-time updates.
     * This enables the frontend to receive audit events instantly without polling.
     */
    private void broadcastAuditEvent(AuditRecord record) {
        if (sseManager == null || !sseManager.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", record.id());
            data.put("playerUuid", record.playerUuid().toString());
            data.put("playerName", record.playerName());
            data.put("type", record.type().name());

            data.put("amount", record.amount().toPlainString());
            data.put("balanceAfter", record.balanceAfter() != null ? record.balanceAfter().toPlainString() : "0");
            data.put("source", record.source());
            data.put("server", record.server());
            data.put("timestamp", record.timestamp());
            data.put("sequence", record.sequence());

            String json = objectMapper.writeValueAsString(data);
            sseManager.broadcastToChannel(SseManager.CHANNEL_AUDIT, json);
        } catch (Exception e) {
            logger.fine("Failed to broadcast audit event via SSE: " + e.getMessage());
        }
    }

    /**
     * Injects the SseManager after it has been created.
     * Required because WebServiceManager (which creates SseManager) is initialized
     * after AuditServiceManager (which creates HybridAuditManager), so the sseManager
     * field starts as null and must be set via this method post-initialization.
     */
    public void setSseManager(SseManager sseManager) {
        this.sseManager = sseManager;
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
}
