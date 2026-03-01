package noietime.syncmoney.migration;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

    private volatile boolean running = false;
    private LocalToSyncProgressCallback progressCallback;

    private static final String LOCAL_DB_PREFIX = "jdbc:sqlite:";
    private static final String KEY_PREFIX_BALANCE = "syncmoney:balance:";
    private static final String KEY_PREFIX_VERSION = "syncmoney:version:";

    public LocalToSyncMigrationTask(Plugin plugin, SyncmoneyConfig config,
                                    EconomyFacade economyFacade,
                                    DatabaseManager databaseManager,
                                    RedisManager redisManager,
                                    MigrationCheckpoint checkpoint,
                                    MigrationBackup backup) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.databaseManager = databaseManager;
        this.redisManager = redisManager;
        this.checkpoint = checkpoint;
        this.backup = backup;
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
        if (running) {
            plugin.getLogger().warning("Local-to-Sync migration is already running!");
            return;
        }

        if (!validatePrerequisites()) {
            reportError("Prerequisites validation failed: Redis or Database is not available");
            return;
        }

        if (config.isMigrationLockEconomy()) {
            MigrationLock.enable();
            MigrationLock.lock();
            plugin.getLogger().info("Economy operations locked for migration");
        }

        running = true;

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            try {
                String localDbPath = config.getLocalSQLitePath();
                if (!localDbPath.startsWith(LOCAL_DB_PREFIX)) {
                    localDbPath = LOCAL_DB_PREFIX + localDbPath;
                }

                List<LocalPlayerData> players = readLocalPlayers(localDbPath);

                if (players.isEmpty()) {
                    reportError("No player data found in local database");
                    running = false;
                    return;
                }

                int total = players.size();
                checkpoint.init(total);

                if (createBackup) {
                    for (LocalPlayerData player : players) {
                        backup.addPlayer(player.name(), player.uuid().toString(), player.balance());
                    }
                }

                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failedCount = new AtomicInteger(0);

                for (int i = 0; i < players.size(); i++) {
                    LocalPlayerData player = players.get(i);

                    try {
                        databaseManager.insertOrUpdatePlayer(
                                player.uuid(),
                                player.name(),
                                player.balance(),
                                player.version(),
                                config.getServerName()
                        );

                        populateRedis(player.uuid(), player.balance(), player.version());

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

                checkpoint.complete();

                validateMigration(players.size(), successCount.get(), failedCount.get());

                reportComplete(successCount.get(), failedCount.get());

            } catch (Exception e) {
                plugin.getLogger().severe("Local-to-Sync migration failed: " + e.getMessage());
                checkpoint.fail();
                reportError(e.getMessage());
            } finally {
                running = false;
                if (config.isMigrationLockEconomy()) {
                    MigrationLock.unlock();
                    MigrationLock.disable();
                    plugin.getLogger().info("Economy operations unlocked after migration");
                }
            }
        });
    }

    /**
     * Reads player data from local SQLite database.
     */
    private List<LocalPlayerData> readLocalPlayers(String dbPath) throws SQLException {
        List<LocalPlayerData> players = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbPath);
             Statement stmt = conn.createStatement()) {

            String tableName = getTableName(dbPath);

            String query = String.format(
                    "SELECT uuid, name, balance, version FROM %s",
                    tableName
            );

            try (ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    String name = rs.getString("name");
                    BigDecimal balance = rs.getBigDecimal("balance");
                    long version = rs.getLong("version");

                    if (uuidStr != null && balance != null) {
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            players.add(new LocalPlayerData(uuid, name, balance, version));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid UUID: " + uuidStr);
                        }
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
     * Populates Redis with player balance and version data.
     * This is critical for SYNC mode to function properly after migration.
     */
    private void populateRedis(UUID uuid, BigDecimal balance, long version) {
        if (redisManager == null || !redisManager.isConnected()) {
            plugin.getLogger().warning("Redis not available, skipping Redis population for: " + uuid);
            return;
        }

        try (var jedis = redisManager.getResource()) {
            String keyBalance = KEY_PREFIX_BALANCE + uuid.toString();
            String keyVersion = KEY_PREFIX_VERSION + uuid.toString();

            jedis.set(keyBalance, balance.toPlainString());
            jedis.set(keyVersion, String.valueOf(version));

            plugin.getLogger().fine("Redis populated for: " + uuid + " balance: " + balance + " version: " + version);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to populate Redis for " + uuid + ": " + e.getMessage());
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

        plugin.getLogger().info("Migration prerequisites validated successfully.");
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
            plugin.getLogger().info("Migration validation - SQLite total balance: " + sqliteTotal);

            BigDecimal redisTotal = calculateRedisTotalBalance(totalPlayers);
            plugin.getLogger().info("Migration validation - Redis total balance: " + redisTotal);

            if (sqliteTotal.compareTo(redisTotal) == 0) {
                plugin.getLogger().info("Migration validation PASSED: SQLite and Redis balances match!");
            } else {
                double diffPercent = Math.abs(sqliteTotal.subtract(redisTotal).doubleValue()) /
                                   Math.max(sqliteTotal.doubleValue(), 1.0) * 100;
                plugin.getLogger().warning("Migration validation WARNING: Balance difference is " +
                    String.format("%.2f", diffPercent) + "%");
            }

            plugin.getLogger().info("Migration validation summary - Total: " + totalPlayers +
                ", Success: " + successCount + ", Failed: " + failedCount);

        } catch (Exception e) {
            plugin.getLogger().warning("Migration validation failed: " + e.getMessage());
        }
    }

    /**
     * Calculates total balance from local SQLite database.
     */
    private BigDecimal calculateSQLiteTotalBalance() throws SQLException {
        String localDbPath = config.getLocalSQLitePath();
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
     * Scans all balance keys and sums up.
     */
    private BigDecimal calculateRedisTotalBalance(int expectedCount) {
        BigDecimal total = BigDecimal.ZERO;
        try (var jedis = redisManager.getResource()) {
            String pattern = KEY_PREFIX_BALANCE + "*";
            var keys = jedis.keys(pattern);

            int count = 0;
            for (String key : keys) {
                String value = jedis.get(key);
                if (value != null) {
                    try {
                        total = total.add(new BigDecimal(value));
                        count++;
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid balance value in Redis: " + value);
                    }
                }
            }

            plugin.getLogger().info("Redis balance scan found " + count + " player records");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate Redis total balance: " + e.getMessage());
        }
        return total;
    }

    /**
     * Checks if migration is running.
     */
    public boolean isRunning() {
        return running;
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
    private record LocalPlayerData(UUID uuid, String name, BigDecimal balance, long version) {}
}
