package noietime.syncmoney.migration;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditDbWriter;
import noietime.syncmoney.audit.AuditRecord;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.money.MoneyPolicy;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * [SYNC-MIGRATE-002] Migration task for migrating from LOCAL mode to SYNC mode.
 * Reads data from local SQLite database and migrates to MySQL/PostgreSQL database.
 * Also populates Redis with balance, version, UUID, and baltop data.
 *
 * [AsyncScheduler] This task must run on async threads, not main thread.
 */
public final class LocalToSyncMigrationTask {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final DatabaseManager databaseManager;
    private final RedisManager redisManager;
    private final MigrationCheckpoint checkpoint;
    private final MigrationBackup backup;
    private final Supplier<? extends AutoCloseable> databaseUseLeaseSupplier;
    private final AtomicReference<AutoCloseable> databaseUseLease = new AtomicReference<>();

    private final Object lifecycleMonitor = new Object();
    private volatile boolean running;
    private volatile boolean stopRequested;
    private boolean scheduling;
    private ScheduledTask scheduledTask;
    private LocalToSyncProgressCallback progressCallback;

    private static final String LOCAL_DB_PREFIX = "jdbc:sqlite:";
    private static final String POPULATE_REDIS_IF_ABSENT_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) ~= 0 or redis.call('EXISTS', KEYS[2]) ~= 0 then
                return 0
            end
            redis.call('MSET', KEYS[1], ARGV[1], KEYS[2], ARGV[2])
            return 1
            """;

    public LocalToSyncMigrationTask(Plugin plugin, SyncmoneyConfig config,
                                    EconomyFacade economyFacade,
                                    DatabaseManager databaseManager,
                                    RedisManager redisManager,
                                    MigrationCheckpoint checkpoint,
                                    MigrationBackup backup) {
        this(plugin, config, economyFacade, databaseManager, redisManager, checkpoint, backup, null);
    }

    public LocalToSyncMigrationTask(Plugin plugin, SyncmoneyConfig config,
                                    EconomyFacade economyFacade,
                                    DatabaseManager databaseManager,
                                    RedisManager redisManager,
                                    MigrationCheckpoint checkpoint,
                                    MigrationBackup backup,
                                    Supplier<? extends AutoCloseable> databaseUseLeaseSupplier) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.databaseManager = databaseManager;
        this.redisManager = redisManager;
        this.checkpoint = checkpoint;
        this.backup = backup;
        this.databaseUseLeaseSupplier = databaseUseLeaseSupplier;
    }

    /**
     * Sets the progress callback.
     */
    public void setProgressCallback(LocalToSyncProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Starts migration (async execution).
     */
    public void start(boolean force, boolean createBackup) {
        if (!reserveStart()) {
            plugin.getLogger().warning("Local-to-Sync migration is already running!");
            return;
        }

        if (!acquireDatabaseUseLease()) {
            finishScheduling();
            reportError("Storage is shutting down or unavailable; Local-to-Sync migration was not started");
            return;
        }

        boolean leaseTransferredToTask = false;
        try {
            if (economyFacade != null && !economyFacade.isLocalRecoveryReady()) {
                finishScheduling();
                reportError("Local-to-Sync migration is unavailable until accepted LOCAL writes are persisted or recovered");
                return;
            }

            if (!validatePrerequisites()) {
                finishScheduling();
                reportError("Prerequisites validation failed: Redis or Database is not available");
                return;
            }

            ScheduledTask submitted = plugin.getServer().getAsyncScheduler().runNow(
                    plugin, task -> runMigration(task, force, createBackup));
            boolean submittedActive = isScheduledActive(submitted);
            leaseTransferredToTask = submittedActive;
            synchronized (lifecycleMonitor) {
                scheduling = false;
                if (!running && submittedActive) {
                    scheduledTask = submitted;
                }
            }
        } catch (RuntimeException | Error failure) {
            finishScheduling();
            throw failure;
        } finally {
            if (!leaseTransferredToTask) {
                releaseDatabaseUseLease();
            }
        }
    }

    private void runMigration(ScheduledTask task, boolean force, boolean createBackup) {
        boolean releaseScheduledLease = false;
        synchronized (lifecycleMonitor) {
            if (task != null && task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED) {
                scheduling = false;
                if (scheduledTask == task) {
                    scheduledTask = null;
                }
                stopRequested = false;
                releaseScheduledLease = true;
            } else if (stopRequested) {
                scheduling = false;
                if (scheduledTask == task) {
                    scheduledTask = null;
                }
                stopRequested = false;
                releaseScheduledLease = true;
            } else if (running) {
                return;
            } else {
                scheduling = false;
                running = true;
                scheduledTask = task;
            }
        }
        if (releaseScheduledLease) {
            releaseDatabaseUseLease();
            return;
        }

        MigrationLock.MigrationLease migrationLockLease = null;
        try {
            // This migration always needs a stable LOCAL snapshot. The legacy optional
            // setting cannot disable the write fence without allowing accepted writes
            // after recovery readiness is checked but before SQLite is read.
            migrationLockLease = MigrationLock.tryAcquireMigrationLease();
            if (migrationLockLease == null) {
                plugin.getLogger().warning("Local-to-Sync migration not started because an economy write is still active or another migration holds the lock");
                reportError("Local-to-Sync migration could not acquire the economy lock; no SQLite snapshot was started");
                return;
            }
            plugin.getLogger().fine("Economy operations locked for LOCAL snapshot migration");

            if (economyFacade != null && !economyFacade.isLocalRecoveryReady()) {
                reportError("Local-to-Sync migration is unavailable until accepted LOCAL writes are persisted or recovered");
                return;
            }

            String localDbPath = config.local().getLocalSQLitePath();
            if (!localDbPath.startsWith(LOCAL_DB_PREFIX)) {
                localDbPath = LOCAL_DB_PREFIX + localDbPath;
            }

            List<LocalPlayerData> players = readLocalPlayers(localDbPath);

            if (stopRequested) {
                plugin.getLogger().info("Local-to-Sync migration stopped before shared writes began");
                return;
            }

            if (players.isEmpty()) {
                reportError("No player data found in local database");
                return;
            }

            int total = players.size();
            checkpoint.init(total);

            if (createBackup) {
                for (LocalPlayerData player : players) {
                    if (pauseIfStopRequested()) {
                        return;
                    }
                    backup.addPlayer(player.name(), player.uuid().toString(), player.balance());
                }
            }

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failedCount = new AtomicInteger(0);
            AtomicInteger preservedCount = new AtomicInteger(0);

            for (int i = 0; i < players.size(); i++) {
                if (pauseIfStopRequested()) {
                    return;
                }
                LocalPlayerData player = players.get(i);

                try {
                    boolean shouldPopulateRedis;
                    if (force) {
                        if (!databaseManager.tryReplacePlayerForMigration(
                                player.uuid(), player.name(), player.balance(), player.version(),
                                config.getServerName())) {
                            throw new IllegalStateException("SQL rejected forced migration write for " + player.uuid());
                        }
                        shouldPopulateRedis = true;
                    } else {
                        if (hasRedisAccount(player.uuid())) {
                            preservedCount.incrementAndGet();
                            plugin.getLogger().fine("[LOCAL-TO-SYNC] Preserved existing shared Redis account for "
                                    + player.uuid() + "; pass -force to replace it.");
                            continue;
                        }
                        shouldPopulateRedis = databaseManager.insertPlayerIfAbsentForMigration(
                                player.uuid(), player.name(), player.balance(), player.version(),
                                config.getServerName());
                        if (!shouldPopulateRedis) {
                            preservedCount.incrementAndGet();
                            plugin.getLogger().fine("[LOCAL-TO-SYNC] Preserved existing shared SQL row for "
                                    + player.uuid() + "; pass -force to replace it.");
                        }
                    }

                    if (shouldPopulateRedis && config.migration().isMigrationPopulateRedis()) {
                        populateRedis(player.uuid(), player.balance(), player.version(), force);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    checkpoint.recordFailure(player.uuid().toString(), e.getMessage());
                }

                if ((i + 1) % 100 == 0 || i == players.size() - 1) {
                    double percent = ((double) (i + 1) / total) * 100;
                    reportProgress(i + 1, total, percent);
                }
            }

            if (pauseIfStopRequested()) {
                return;
            }

            int migratedTx = migrateTransactions(localDbPath);
            plugin.getLogger().info("[LOCAL-TO-SYNC] Transaction history migration complete: "
                    + migratedTx + " source records reconciled with syncmoney_audit_log.");

            if (pauseIfStopRequested()) {
                return;
            }

            validateMigration(players.size(), successCount.get(), failedCount.get());
            checkpoint.complete();
            if (preservedCount.get() > 0) {
                plugin.getLogger().info("[LOCAL-TO-SYNC] Preserved " + preservedCount.get()
                        + " existing shared SQL row(s); local balances were not copied to Redis for those accounts.");
            }
            reportComplete(successCount.get(), failedCount.get());
        } catch (Exception e) {
            if (e instanceof InvalidLocalPlayerRowException invalidRow) {
                checkpoint.recordFailure(invalidRow.rowKey(), invalidRow.getMessage());
            }
            plugin.getLogger().severe("Local-to-Sync migration failed: " + e.getMessage());
            checkpoint.fail();
            reportError(e.getMessage());
        } finally {
            if (migrationLockLease != null) {
                migrationLockLease.close();
                plugin.getLogger().fine("Economy operations unlocked after LOCAL snapshot migration");
            }
            synchronized (lifecycleMonitor) {
                running = false;
                scheduling = false;
                stopRequested = false;
                if (scheduledTask == task) {
                    scheduledTask = null;
                }
            }
            releaseDatabaseUseLease();
        }
    }

    private boolean reserveStart() {
        synchronized (lifecycleMonitor) {
            if (running || scheduling) {
                return false;
            }
            if (scheduledTask != null) {
                if (isScheduledActive(scheduledTask)) {
                    return false;
                }
                scheduledTask = null;
            }
            stopRequested = false;
            scheduling = true;
            return true;
        }
    }

    private void finishScheduling() {
        synchronized (lifecycleMonitor) {
            scheduling = false;
        }
    }

    /** Requests a stop at the next safe account boundary. */
    public boolean stop() {
        boolean releaseLease = false;
        synchronized (lifecycleMonitor) {
            if (!running && !scheduling && scheduledTask == null) {
                return false;
            }
            stopRequested = true;
            ScheduledTask task = scheduledTask;
            if (!running && task != null
                    && task.getExecutionState() == ScheduledTask.ExecutionState.IDLE) {
                task.cancel();
                if (task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED) {
                    scheduledTask = null;
                    scheduling = false;
                    stopRequested = false;
                    releaseLease = true;
                }
            }
        }
        if (releaseLease) {
            releaseDatabaseUseLease();
        }
        return true;
    }

    private boolean acquireDatabaseUseLease() {
        if (databaseUseLeaseSupplier == null) {
            return true;
        }
        try {
            AutoCloseable lease = databaseUseLeaseSupplier.get();
            if (lease == null) {
                return false;
            }
            if (databaseUseLease.compareAndSet(null, lease)) {
                return true;
            }
            closeLease(lease);
            return false;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not reserve storage for Local-to-Sync migration: "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private void releaseDatabaseUseLease() {
        AutoCloseable lease = databaseUseLease.getAndSet(null);
        if (lease != null) {
            closeLease(lease);
        }
    }

    private void closeLease(AutoCloseable lease) {
        try {
            lease.close();
        } catch (Exception failure) {
            plugin.getLogger().severe("Failed to release Local-to-Sync storage lease: "
                    + failure.getClass().getSimpleName());
        }
    }

    private boolean pauseIfStopRequested() {
        if (!stopRequested) {
            return false;
        }
        checkpoint.pause();
        plugin.getLogger().info("Local-to-Sync migration stopped at a safe account boundary; rerun the migration to continue");
        return true;
    }

    private boolean isScheduledActive(ScheduledTask task) {
        if (task == null) {
            return false;
        }
        ScheduledTask.ExecutionState state = task.getExecutionState();
        return state == ScheduledTask.ExecutionState.IDLE
                || state == ScheduledTask.ExecutionState.RUNNING
                || state == ScheduledTask.ExecutionState.CANCELLED_RUNNING;
    }

    /**
     * Previews local-to-sync migration without executing.
     * Shows player count, total balance, and sample data.
     *
     * @return PreviewResult containing migration data preview, or null if error
     */
    public PreviewResult preview() {
        if (economyFacade != null && !economyFacade.isLocalRecoveryReady()) {
            plugin.getLogger().warning("LOCAL migration preview is unavailable until accepted writes are persisted or recovered");
            return null;
        }
        try {
            String localDbPath = config.local().getLocalSQLitePath();
            if (!localDbPath.startsWith(LOCAL_DB_PREFIX)) {
                localDbPath = LOCAL_DB_PREFIX + localDbPath;
            }

            List<LocalPlayerData> players = readLocalPlayers(localDbPath);

            if (players.isEmpty()) {
                return null;
            }

            BigDecimal totalBalance = BigDecimal.ZERO;
            for (LocalPlayerData player : players) {
                totalBalance = totalBalance.add(player.balance());
            }

            return new PreviewResult(players.size(), totalBalance, players);
        } catch (Exception e) {
            plugin.getLogger().warning("Preview failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Preview result container.
     */
    public record PreviewResult(int playerCount, BigDecimal totalBalance, List<LocalPlayerData> samplePlayers) {}

    /**
     * Reads player data from local SQLite database.
     */
    private List<LocalPlayerData> readLocalPlayers(String dbPath) throws SQLException {
        List<LocalPlayerData> players = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbPath);
             Statement stmt = conn.createStatement()) {

            String tableName = getTableName(dbPath);
            String query = String.format("SELECT uuid, name, balance, version FROM %s", tableName);

            try (ResultSet rs = stmt.executeQuery(query)) {
                int rowNumber = 0;
                while (rs.next()) {
                    rowNumber++;
                    String uuidStr = rs.getString("uuid");
                    String name = rs.getString("name");

                    BigDecimal sourceBalance = BigDecimal.valueOf(rs.getDouble("balance"));
                    if (!MoneyPolicy.isNonNegative(sourceBalance)) {
                        throw new SQLException("Local migration balance must be non-negative");
                    }
                    BigDecimal balance = MoneyPolicy.normalize(sourceBalance);
                    long version = rs.getLong("version");

                    String rowKey = "LOCAL row " + rowNumber;
                    if (uuidStr == null || uuidStr.isBlank()) {
                        String invalidValue = uuidStr == null ? "<null>" : uuidStr;
                        throw new InvalidLocalPlayerRowException(rowKey,
                                "Invalid UUID in LOCAL source row " + rowNumber + ": " + invalidValue);
                    }
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        if (!uuid.toString().equalsIgnoreCase(uuidStr)) {
                            throw new IllegalArgumentException("UUID must use canonical format");
                        }
                        players.add(new LocalPlayerData(uuid, name, balance, version));
                    } catch (IllegalArgumentException invalidUuid) {
                        throw new InvalidLocalPlayerRowException(rowKey,
                                "Invalid UUID in LOCAL source row " + rowNumber + ": " + uuidStr,
                                invalidUuid);
                    }
                }
            }
        }

        return players;
    }

    /**
     * Gets the table name based on database type.
     * LOCAL mode uses 'player_balances' table.
     */
    private String getTableName(String dbPath) {
        return "player_balances";
    }

    /**
     * [SYNC-MIGRATE-003] Migrates transaction history from SQLite transactions table
     * to syncmoney_audit_log in the target MySQL/PostgreSQL database.
     *
     * Field mapping:
     * - id (INTEGER AUTOINCREMENT)  → new UUID
     * - uuid                        → player_uuid
     * - name                        → player_name
     * - type (DEPOSIT/WITHDRAW/TRANSFER/SET_BALANCE) → type
     * - amount (signed REAL)        → amount (DECIMAL)
     * - balance_after (REAL)        → balance_after (DECIMAL)
     * - source                      → source (EventSource enum name)
     * - timestamp                   → timestamp
     * - sequence (nanotime frag)    → sequence (new monotonic counter)
     * - target_name                 → target_name; target_uuid resolved from player_balances
     * - balance_before (LOCAL only) → stored in reason field as "balance_before:X"
     * - server (not in LOCAL)       → fixed "LOCAL"
     *
     * @param sqliteJdbcPath full JDBC URL for the SQLite database (jdbc:sqlite:...)
     * @return number of source records reconciled with the destination audit table
     * @throws SQLException when the complete history snapshot cannot be imported
     */
    private int migrateTransactions(String sqliteJdbcPath) throws SQLException {
        if (databaseManager == null || !databaseManager.isConnected()) {
            throw new SQLException("Database not available for transaction history migration");
        }

        Map<String, String> nameToUuid = buildNameToUuidMap(sqliteJdbcPath);
        String sourceIdentity = transactionHistorySourceIdentity(sqliteJdbcPath);
        String insertSql = transactionHistoryInsertSql();
        AtomicInteger seqCounter = new AtomicInteger(0);
        int processed = 0;

        try (Connection sqliteConn = DriverManager.getConnection(sqliteJdbcPath);
             Statement countStmt = sqliteConn.createStatement()) {
            boolean txTableExists;
            try (ResultSet tables = sqliteConn.getMetaData().getTables(null, null, "transactions", null)) {
                txTableExists = tables.next();
            }
            if (!txTableExists) {
                plugin.getLogger().fine("[LOCAL-TO-SYNC] No transactions table found in SQLite - nothing to migrate.");
                return 0;
            }

            int totalTx;
            try (ResultSet countRs = countStmt.executeQuery("SELECT COUNT(*) FROM transactions")) {
                totalTx = countRs.next() ? countRs.getInt(1) : 0;
            }

            if (totalTx == 0) {
                plugin.getLogger().fine("[LOCAL-TO-SYNC] No transaction records to migrate.");
                return 0;
            }

            plugin.getLogger().info("[LOCAL-TO-SYNC] Migrating " + totalTx + " transaction records to audit log...");

            try (Statement readStmt = sqliteConn.createStatement();
                 ResultSet rs = readStmt.executeQuery(
                         "SELECT * FROM transactions ORDER BY timestamp ASC, sequence ASC");
                 Connection dbConn = databaseManager.getConnection();
                 PreparedStatement pstmt = dbConn.prepareStatement(insertSql)) {
                boolean ownsTransaction = dbConn.getAutoCommit();
                java.sql.Savepoint savepoint = null;
                int batchCount = 0;
                try {
                    if (ownsTransaction) dbConn.setAutoCommit(false);
                    else savepoint = dbConn.setSavepoint();

                    while (rs.next()) {
                        String rowId = rs.getString("id");
                        String uuidStr = rs.getString("uuid");
                        if (rowId == null || uuidStr == null) {
                            throw new SQLException("Transaction history row is missing its id or player UUID");
                        }
                        try {
                            uuidStr = UUID.fromString(uuidStr).toString();
                        } catch (IllegalArgumentException invalidUuid) {
                            throw new SQLException("Transaction history row has an invalid player UUID", invalidUuid);
                        }

                        String playerName = rs.getString("name");
                        String typeStr = rs.getString("type");
                        double amount = rs.getDouble("amount");
                        double balanceAfter = rs.getDouble("balance_after");
                        double balanceBefore = rs.getDouble("balance_before");
                        String sourceStr = rs.getString("source");
                        long timestamp = rs.getLong("timestamp");
                        String targetName = rs.getString("target_name");

                        if ("PLAYER_TRANSFER".equals(sourceStr) && !"TRANSFER".equalsIgnoreCase(typeStr)) {
                            typeStr = "TRANSFER";
                        }

                        AuditRecord.AuditType auditType;
                        try {
                            auditType = AuditRecord.AuditType.valueOf(
                                    typeStr == null ? "DEPOSIT" : typeStr.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            auditType = AuditRecord.AuditType.DEPOSIT;
                        }

                        EconomyEvent.EventSource eventSource;
                        try {
                            eventSource = EconomyEvent.EventSource.valueOf(sourceStr != null ? sourceStr : "ADMIN_GIVE");
                        } catch (IllegalArgumentException e) {
                            eventSource = switch (auditType) {
                                case DEPOSIT -> EconomyEvent.EventSource.ADMIN_GIVE;
                                case WITHDRAW -> EconomyEvent.EventSource.ADMIN_TAKE;
                                case TRANSFER -> EconomyEvent.EventSource.PLAYER_TRANSFER;
                                default -> EconomyEvent.EventSource.COMMAND_ADMIN;
                            };
                        }

                        String targetUuid = targetName != null ? nameToUuid.get(targetName.toLowerCase()) : null;
                        String reason = "balance_before:" + BigDecimal.valueOf(balanceBefore).toPlainString();
                        if (playerName != null && playerName.length() > 16) playerName = playerName.substring(0, 16);
                        if (targetName != null && targetName.length() > 16) targetName = targetName.substring(0, 16);

                        pstmt.setString(1, stableHistoryId(sourceIdentity, rowId));
                        pstmt.setLong(2, timestamp);
                        pstmt.setInt(3, seqCounter.incrementAndGet());
                        pstmt.setString(4, auditType.name());
                        pstmt.setString(5, uuidStr);
                        pstmt.setString(6, playerName);
                        pstmt.setBigDecimal(7, BigDecimal.valueOf(amount));
                        pstmt.setBigDecimal(8, BigDecimal.valueOf(balanceAfter));
                        pstmt.setString(9, eventSource.name());
                        pstmt.setString(10, "LOCAL");
                        pstmt.setString(11, targetUuid);
                        pstmt.setString(12, targetName);
                        pstmt.setString(13, reason);
                        pstmt.setInt(14, 1);
                        pstmt.addBatch();
                        batchCount++;
                        processed++;

                        if (batchCount == 500) {
                            executeHistoryBatch(pstmt);
                            batchCount = 0;
                            plugin.getLogger().fine("[LOCAL-TO-SYNC] Processed " + processed + "/"
                                    + totalTx + " transaction history rows...");
                        }
                    }

                    if (batchCount > 0) executeHistoryBatch(pstmt);
                    if (ownsTransaction) dbConn.commit();
                    else dbConn.releaseSavepoint(savepoint);
                } catch (SQLException | RuntimeException failure) {
                    try {
                        if (ownsTransaction) dbConn.rollback();
                        else if (savepoint != null) dbConn.rollback(savepoint);
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    throw failure;
                } finally {
                    if (ownsTransaction) dbConn.setAutoCommit(true);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            plugin.getLogger().warning("[LOCAL-TO-SYNC] Transaction history import failed; migration remains incomplete: "
                    + failure.getMessage());
            if (failure instanceof SQLException sqlFailure) throw sqlFailure;
            throw new SQLException("Transaction history import failed", failure);
        }
        return processed;
    }

    private static void executeHistoryBatch(PreparedStatement statement) throws SQLException {
        for (int result : statement.executeBatch()) {
            if (result == Statement.EXECUTE_FAILED) {
                throw new SQLException("Audit history batch reported a failed row");
            }
        }
    }

    private String transactionHistoryInsertSql() throws SQLException {
        String databaseType = config.database().getDatabaseType();
        String insert = AuditDbWriter.INSERT_SQL;
        if ("mysql".equalsIgnoreCase(databaseType) || "mariadb".equalsIgnoreCase(databaseType)) {
            return insert + " ON DUPLICATE KEY UPDATE id = id";
        }
        if ("postgresql".equalsIgnoreCase(databaseType) || "sqlite".equalsIgnoreCase(databaseType)) {
            return insert + " ON CONFLICT (id) DO NOTHING";
        }
        throw new SQLException("Unsupported destination SQL dialect for transaction history migration");
    }

    private static String transactionHistorySourceIdentity(String sqliteJdbcPath) throws SQLException {
        if (!sqliteJdbcPath.startsWith(LOCAL_DB_PREFIX)) {
            throw new SQLException("Transaction history source must be a SQLite JDBC URL");
        }
        try {
            return new java.io.File(sqliteJdbcPath.substring(LOCAL_DB_PREFIX.length())).getCanonicalPath();
        } catch (java.io.IOException failure) {
            throw new SQLException("Unable to identify the local transaction history source", failure);
        }
    }

    private static String stableHistoryId(String sourceIdentity, String rowId) {
        String key = "syncmoney-local-history-v1|" + sourceIdentity + "|" + rowId;
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * Builds a lowercase-name → UUID string map from the SQLite player_balances table.
     * Used for resolving target_uuid during transaction migration.
     */
    private Map<String, String> buildNameToUuidMap(String sqliteJdbcPath) throws SQLException {
        Map<String, String> nameToUuid = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(sqliteJdbcPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid, name FROM player_balances")) {
            while (rs.next()) {
                String name = rs.getString("name");
                String uuid = rs.getString("uuid");
                if (name != null && uuid != null) {
                    nameToUuid.put(name.toLowerCase(), uuid);
                }
            }
        } catch (SQLException failure) {
            plugin.getLogger().warning("[LOCAL-TO-SYNC] Failed to read local name-to-UUID map: "
                    + failure.getMessage());
            throw failure;
        }
        return nameToUuid;
    }

    /**
     * Populates Redis with player balance, version, and baltop data.
     * This is critical for SYNC mode to function properly after migration.
     * Also updates the in-memory state in EconomyFacade.
     */
    private boolean hasRedisAccount(UUID uuid) {
        if (redisManager == null || !redisManager.isConnected()) {
            throw new IllegalStateException("Redis unavailable while checking migration destination " + uuid);
        }

        try (var jedis = redisManager.getResource()) {
            List<String> values = jedis.mget(RedisKeys.balance(uuid), RedisKeys.version(uuid));
            if (values == null || values.size() != 2) {
                throw new IllegalStateException("Redis returned an incomplete migration snapshot for " + uuid);
            }
            boolean hasBalance = values.get(0) != null;
            boolean hasVersion = values.get(1) != null;
            if (hasBalance != hasVersion) {
                throw new IllegalStateException("Redis has an incomplete destination account for " + uuid);
            }
            return hasBalance;
        }
    }

    private void populateRedis(UUID uuid, BigDecimal balance, long version, boolean force) {
        if (redisManager == null || !redisManager.isConnected()) {
            plugin.getLogger().warning("Redis not available, skipping Redis population for: " + uuid);
            throw new IllegalStateException("Redis unavailable while migrating " + uuid);
        }

        try (var jedis = redisManager.getResource()) {
            String keyBalance = RedisKeys.balance(uuid);
            String keyVersion = RedisKeys.version(uuid);
            if (force) {
                jedis.mset(keyBalance, balance.toPlainString(), keyVersion, String.valueOf(version));
            } else {
                Object result = jedis.eval(POPULATE_REDIS_IF_ABSENT_SCRIPT, 2, keyBalance, keyVersion,
                        balance.toPlainString(), String.valueOf(version));
                if (!(result instanceof Number written) || written.longValue() != 1L) {
                    throw new IllegalStateException(
                            "Redis destination account appeared during non-force migration; no Redis keys were changed");
                }
            }

            // Update baltop ZSET for leaderboard
            jedis.zadd(RedisKeys.BALTOP, balance.doubleValue(), uuid.toString());

            // Use the force path only while this maintenance workflow has stopped producers.
            // Without the configured lock, use normal monotonic version acceptance.
            if (MigrationLock.isLocked()) {
                economyFacade.forceUpdateMemoryState(uuid, balance, version);
            } else {
                economyFacade.updateMemoryState(uuid, balance, version);
            }

            plugin.getLogger().fine("Redis populated for: " + uuid + " balance: " + balance + " version: " + version);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to populate Redis for " + uuid + ": " + e.getMessage());
            throw new IllegalStateException("Redis population failed for " + uuid + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validates prerequisites before starting migration.
     * @return true if all prerequisites are met
     */
    private boolean validatePrerequisites() {
        if (databaseManager == null) {
            plugin.getLogger().severe("Database manager is not available. Cannot start migration.");
            return false;
        }

        if (redisManager == null || !redisManager.isConnected()) {
            plugin.getLogger().severe("Redis is not available. Cannot start migration to SYNC mode.");
            return false;
        }

        plugin.getLogger().fine("Migration prerequisites validated successfully.");
        return true;
    }

    /**
     * Validates data consistency after migration.
     * Compares total balance in SQLite vs Redis to ensure migration was successful.
     */
    private void validateMigration(int totalPlayers, int successCount, int failedCount) {
        if (redisManager == null || !redisManager.isConnected()) {
            plugin.getLogger().warning("Skipping migration validation - Redis not available");
            return;
        }

        try {
            BigDecimal sqliteTotal = calculateSQLiteTotalBalance();
            plugin.getLogger().fine("Migration validation - SQLite total balance: " + sqliteTotal);

            BigDecimal redisTotal = calculateRedisTotalBalance(totalPlayers);
            plugin.getLogger().fine("Migration validation - Redis total balance: " + redisTotal);

            if (sqliteTotal.compareTo(redisTotal) == 0) {
                plugin.getLogger().fine("Migration validation PASSED: SQLite and Redis balances match!");
            } else {
                double diffPercent = Math.abs(sqliteTotal.subtract(redisTotal).doubleValue()) /
                                   Math.max(sqliteTotal.doubleValue(), 1.0) * 100;
                plugin.getLogger().warning("Migration validation WARNING: Balance difference is " +
                    String.format("%.2f", diffPercent) + "%");
            }

            plugin.getLogger().fine("Migration validation summary - Total: " + totalPlayers +
                ", Success: " + successCount + ", Failed: " + failedCount);

        } catch (Exception e) {
            plugin.getLogger().warning("Migration validation failed: " + e.getMessage());
        }
    }

    /**
     * Calculates total balance from local SQLite database.
     */
    private BigDecimal calculateSQLiteTotalBalance() throws SQLException {
        String localDbPath = config.local().getLocalSQLitePath();
        if (!localDbPath.startsWith(LOCAL_DB_PREFIX)) {
            localDbPath = LOCAL_DB_PREFIX + localDbPath;
        }

        BigDecimal total = BigDecimal.ZERO;
        try (Connection conn = DriverManager.getConnection(localDbPath);
             Statement stmt = conn.createStatement()) {

            String query = "SELECT SUM(balance) as total FROM " + getTableName(localDbPath);
            try (ResultSet rs = stmt.executeQuery(query)) {
                if (rs.next()) {
                    double sum = rs.getDouble("total");
                    if (!rs.wasNull()) {
                        total = BigDecimal.valueOf(sum);
                    }
                }
            }
        }
        return total;
    }

    /**
     * Calculates total balance from Redis.
     * [DATA-SYNC FIX] Uses SCAN instead of KEYS to avoid Redis blocking.
     * Uses pipeline for batch fetching values.
     */
    private BigDecimal calculateRedisTotalBalance(int expectedCount) {
        BigDecimal total = BigDecimal.ZERO;
        try (var jedis = redisManager.getResource()) {
            String pattern = RedisKeys.balancePattern();
            ScanParams params = new ScanParams().match(pattern).count(1000);

            String cursor = ScanParams.SCAN_POINTER_START;
            int count = 0;

            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                List<String> keys = scanResult.getResult();

                if (keys != null && !keys.isEmpty()) {

                    List<String> values = jedis.mget(keys.toArray(new String[0]));

                    for (String value : values) {
                        if (value != null) {
                            try {
                                total = total.add(new BigDecimal(value));
                                count++;
                            } catch (NumberFormatException e) {
                                plugin.getLogger().warning("Invalid balance value in Redis: " + value);
                            }
                        }
                    }
                }

                cursor = scanResult.getCursor();
            } while (!"0".equals(cursor));

            plugin.getLogger().fine("Redis balance scan found " + count + " player records");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate Redis total balance: " + e.getMessage());
        }
        return total;
    }

    /**
     * Checks if migration is running.
     */
    public boolean isRunning() {
        synchronized (lifecycleMonitor) {
            if (running || scheduling) {
                return true;
            }
            if (scheduledTask == null) {
                return false;
            }
            if (isScheduledActive(scheduledTask)) {
                return true;
            }
            scheduledTask = null;
            return false;
        }
    }

    private void reportProgress(int current, int total, double percent) {
        if (progressCallback != null) {
            progressCallback.onProgress(current, total, percent);
        }
    }

    private void reportComplete(int successCount, int failedCount) {
        if (progressCallback != null) {
            progressCallback.onComplete(successCount, failedCount);
        }
    }

    private void reportError(String error) {
        if (progressCallback != null) {
            progressCallback.onError(error);
        }
    }

    /**
     * Progress callback interface.
     */
    public interface LocalToSyncProgressCallback {
        void onProgress(int current, int total, double percent);
        void onComplete(int successCount, int failedCount);
        void onError(String error);
    }

    /**
     * Local player data record.
     */
    private static final class InvalidLocalPlayerRowException extends SQLException {
        private static final long serialVersionUID = 1L;

        private final String rowKey;

        private InvalidLocalPlayerRowException(String rowKey, String message) {
            super(message);
            this.rowKey = rowKey;
        }

        private InvalidLocalPlayerRowException(String rowKey, String message, Throwable cause) {
            super(message, cause);
            this.rowKey = rowKey;
        }

        private String rowKey() {
            return rowKey;
        }
    }

    public record LocalPlayerData(UUID uuid, String name, BigDecimal balance, long version) {}
}
