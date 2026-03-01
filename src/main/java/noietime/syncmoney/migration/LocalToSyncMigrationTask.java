package noietime.syncmoney.migration;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
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
    private final MigrationCheckpoint checkpoint;
    private final MigrationBackup backup;

    private volatile boolean running = false;
    private LocalToSyncProgressCallback progressCallback;

    private static final String LOCAL_DB_PREFIX = "jdbc:sqlite:";

    public LocalToSyncMigrationTask(Plugin plugin, SyncmoneyConfig config,
                                    EconomyFacade economyFacade,
                                    DatabaseManager databaseManager,
                                    MigrationCheckpoint checkpoint,
                                    MigrationBackup backup) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.databaseManager = databaseManager;
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
                reportComplete(successCount.get(), failedCount.get());

            } catch (Exception e) {
                plugin.getLogger().severe("Local-to-Sync migration failed: " + e.getMessage());
                checkpoint.fail();
                reportError(e.getMessage());
            } finally {
                running = false;
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
