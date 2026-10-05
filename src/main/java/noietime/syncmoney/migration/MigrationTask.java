package noietime.syncmoney.migration;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.migration.CMIDatabaseReader.CMIPlayerData;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * [SYNC-MIGRATE-001] Migration task executor for migrating data from external economy plugins.
 * Handles batch processing of CMI to Syncmoney migration.
 *
 * [AsyncScheduler] This task must run on async threads, not main thread.
 */
public final class MigrationTask {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private CMIDatabaseReader cmiReader;
    private final EconomyFacade economyFacade;
    private final DatabaseManager databaseManager;
    private final RedisManager redisManager;
    private final NameResolver nameResolver;
    private final MigrationCheckpoint checkpoint;
    private final MigrationBackup backup;
    private final CMIEconomyDisabler economyDisabler;

    private final int batchSize;

    private volatile boolean running = false;
    private volatile boolean migrationLockOwned = false;

    private MigrationProgressCallback progressCallback;

    private CMIMultiServerReader multiServerReader;


    private final List<FailedRedisEntry> failedRedisQueue = new CopyOnWriteArrayList<>();

    private static class FailedRedisEntry {
        final UUID uuid;
        final BigDecimal balance;
        final long version;
        final String playerName;

        FailedRedisEntry(UUID uuid, BigDecimal balance, long version, String playerName) {
            this.uuid = uuid;
            this.balance = balance;
            this.version = version;
            this.playerName = playerName;
        }
    }

    public MigrationTask(Plugin plugin, SyncmoneyConfig config,
                         CMIDatabaseReader cmiReader, EconomyFacade economyFacade,
                         DatabaseManager databaseManager,
                         RedisManager redisManager,
                         NameResolver nameResolver,
                         MigrationCheckpoint checkpoint, MigrationBackup backup,
                         CMIEconomyDisabler economyDisabler) {
        this.plugin = plugin;
        this.config = config;
        this.cmiReader = cmiReader;
        this.economyFacade = economyFacade;
        this.databaseManager = databaseManager;
        this.redisManager = redisManager;
        this.nameResolver = nameResolver;
        this.checkpoint = checkpoint;
        this.backup = backup;
        this.economyDisabler = economyDisabler;
        this.batchSize = config.migration().getMigrationBatchSize();
    }

    /**
     * Sets the CMI database reader (called after lazy initialization).
     * @param cmiReader CMI database reader
     */
    public void setCMIReader(CMIDatabaseReader cmiReader) {
        this.cmiReader = cmiReader;
    }

    /**
     * Sets the progress callback.
     * @param callback callback interface
     */
    public void setProgressCallback(MigrationProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Starts migration (async execution).
     * @param force whether to force overwrite
     * @param createBackup whether to create backup
     */
    public void start(boolean force, boolean createBackup) {
        start(force, createBackup, null);
    }

    /**
     * Starts migration (async execution).
     * @param force whether to force overwrite
     * @param createBackup whether to create backup
     * @param multiServerReader multi-server reader (optional)
     */
    public void start(boolean force, boolean createBackup, CMIMultiServerReader multiServerReader) {
        if (running) {
            plugin.getLogger().warning("Migration is already running!");
            return;
        }

        this.multiServerReader = multiServerReader;

        if (multiServerReader != null) {
            if (!multiServerReader.testConnections()) {
                plugin.getLogger().severe("CMI Multi-Server database connection failed! Cannot start migration.");
                if (progressCallback != null) {
                    progressCallback.onError("CMI multi-server database connection failed");
                }
                return;
            }
        } else {
            if (!cmiReader.testConnection()) {
                plugin.getLogger().severe("CMI database connection failed! Cannot start migration.");
                if (progressCallback != null) {
                    progressCallback.onError("CMI database connection failed");
                }
                return;
            }
        }

        if (!validatePrerequisites()) {
            if (progressCallback != null) {
                progressCallback.onError("Redis is not available. Cannot start migration to SYNC mode.");
            }
            return;
        }

        if (config.migration().isMigrationLockEconomy()) {
            MigrationLock.enable();
            if (!MigrationLock.lock()) {
                plugin.getLogger().warning("CMI migration not started because an economy write is still active or another migration holds the lock");
                if (progressCallback != null) {
                    progressCallback.onError("CMI migration could not acquire the economy lock; no migration snapshot was started");
                }
                return;
            }
            migrationLockOwned = true;
            plugin.getLogger().fine("Economy operations locked for CMI migration");
        }

        final int totalPlayersFinal;
        try {
            int totalPlayers;
            if (multiServerReader != null) {
                totalPlayers = multiServerReader.getTotalPlayerCount();
            } else {
                totalPlayers = cmiReader.getTotalPlayerCount();
            }
            totalPlayersFinal = totalPlayers;
            if (totalPlayers == 0) {
                plugin.getLogger().warning("No players found in CMI database!");
                if (progressCallback != null) {
                    progressCallback.onError("No player data found in CMI database");
                }
                releaseMigrationLock();
                return;
            }

            checkpoint.init(totalPlayers);

            if (createBackup) {
                createBackup();
            }
        } catch (RuntimeException | Error failure) {
            failAndReleaseMigrationLock(failure);
            throw failure;
        }

        running = true;

        AtomicInteger offset = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        scheduleMigrationBatch(force, totalPlayersFinal, offset, successCount, false);
    }

    /**
     * Resumes previous migration.
     * @return true if resumed successfully
     */
    public boolean resume() {
        if (config.migration().isMigrationLockEconomy()
                && MigrationLock.isLocked() && !migrationLockOwned) {
            plugin.getLogger().warning("CMI migration resume not started because another migration holds the economy lock");
            return false;
        }

        if (!checkpoint.resume()) {
            plugin.getLogger().warning("No checkpoint to resume!");
            return false;
        }

        if (checkpoint.getState() != MigrationCheckpoint.MigrationState.RUNNING &&
            checkpoint.getState() != MigrationCheckpoint.MigrationState.PAUSED) {
            plugin.getLogger().warning("Cannot resume: migration state is " + checkpoint.getState());
            return false;
        }

        if (config.migration().isMigrationLockEconomy()) {
            if (migrationLockOwned && !MigrationLock.isLocked()) {
                migrationLockOwned = false;
            }
            if (!migrationLockOwned) {
                MigrationLock.enable();
            }
            if (!migrationLockOwned && !MigrationLock.lock()) {
                plugin.getLogger().warning("CMI migration resume not started because an economy write is active or another migration holds the lock");
                checkpoint.pause();
                return false;
            }
            migrationLockOwned = true;
        }

        running = true;

        AtomicInteger offset = new AtomicInteger(checkpoint.getCurrentOffset());
        AtomicInteger successCount = new AtomicInteger(checkpoint.getMigratedCount());

        scheduleMigrationBatch(false, checkpoint.getTotalPlayers(), offset, successCount, true);

        return true;
    }

    private void scheduleMigrationBatch(boolean force, int totalPlayers, AtomicInteger offset,
                                        AtomicInteger successCount, boolean resuming) {
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    if (!running) {
                        checkpoint.pause();
                        return;
                    }

                    int currentOffset = offset.get();
                    List<CMIPlayerData> players = !resuming && multiServerReader != null
                            ? multiServerReader.readAndMergePlayers()
                            : cmiReader.readPlayers(currentOffset, batchSize);

                    if (players.isEmpty()) {
                        completeMigration();
                        return;
                    }

                    for (CMIPlayerData player : players) {
                        try {
                            migratePlayer(player, force);
                            successCount.incrementAndGet();
                            if (!resuming && !checkRedisHealthDuringMigration(successCount.get())) {
                                plugin.getLogger().warning("Migration paused due to Redis health issue");
                                checkpoint.pause();
                                running = false;
                                if (progressCallback != null) {
                                    progressCallback.onError("Migration paused: Redis connection issue");
                                }
                                return;
                            }
                        } catch (Exception ex) {
                            String playerName = player.playerName();
                            plugin.getLogger().warning("Failed to migrate player " + playerName + ": " + ex.getMessage());
                            checkpoint.recordFailure(playerName, ex.getMessage());
                        }
                    }

                    if (!resuming && multiServerReader != null) {
                        int migrated = successCount.get();
                        checkpoint.updateProgress(totalPlayers, migrated);
                        if (progressCallback != null) {
                            progressCallback.onProgress(migrated, totalPlayers, checkpoint.getProgressPercent());
                        }
                        if (!running) {
                            checkpoint.pause();
                            return;
                        }
                        completeMigration();
                        return;
                    }

                    int newOffset = Math.min(totalPlayers, currentOffset + players.size());
                    offset.set(newOffset);
                    int migrated = successCount.get();
                    checkpoint.updateProgress(newOffset, migrated);
                    if (progressCallback != null) {
                        progressCallback.onProgress(migrated, totalPlayers, checkpoint.getProgressPercent());
                    }

                    if (!running) {
                        checkpoint.pause();
                    } else if (newOffset >= totalPlayers) {
                        completeMigration();
                    } else {
                        scheduleMigrationBatch(force, totalPlayers, offset, successCount, resuming);
                    }
                } catch (Exception failure) {
                    plugin.getLogger().severe((resuming ? "Migration resume error: " : "Migration error: ")
                            + failure.getMessage());
                    failAndReleaseMigrationLock(failure);
                    if (progressCallback != null && !resuming) {
                        progressCallback.onError("Migration error: " + failure.getMessage());
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            failAndReleaseMigrationLock(failure);
            throw failure;
        }
    }

    /**
     * Stops migration.
     */
    public void stop() {
        running = false;
        checkpoint.pause();
    }

    /**
     * Migrates a single player.
     * @param playerData CMI player data
     * @param force whether to force overwrite
     */
    private void migratePlayer(CMIPlayerData playerData, boolean force) {
        String playerName = playerData.playerName();
        BigDecimal balance = playerData.balance();

        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
        }

        UUID uuid = playerData.uuid();
        if (uuid == null) {
            uuid = nameResolver.resolveUUID(playerName);
        }

        if (uuid == null) {
            throw new IllegalArgumentException("Cannot resolve UUID for " + playerName);
        }

        BigDecimal existingBalance = economyFacade.getBalance(uuid);
        if (existingBalance.compareTo(BigDecimal.ZERO) > 0 && !force) {
            return;
        }

        BigDecimal appliedBalance;
        try (var migrationWrite = MigrationLock.beginMigrationWrite()) {
            appliedBalance = economyFacade.setBalance(uuid, balance, EconomyEvent.EventSource.ADMIN_SET);
        }
        if (appliedBalance == null || appliedBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Economy rejected migration write for " + playerName + " (" + uuid + ")");
        }

        checkpoint.addBackupAmount(balance);

        if (databaseManager != null) {
            databaseManager.insertOrUpdatePlayer(
                    uuid,
                    playerName,
                    balance,
                    1L,
                    config.getServerName()
            );
        }

        if (config.migration().isMigrationPopulateRedis()) {
            populateRedis(uuid, balance, 1L);
        }

        plugin.getLogger().fine("Migrated player: " + playerName + " with balance " + balance);
    }

    /**
     * Populates Redis with player balance and version data.
     * Uses SYNC mode key space (syncmoney:balance:) instead of CMI key space.
     */
    private void populateRedis(UUID uuid, BigDecimal balance, long version) {
        if (redisManager == null || !redisManager.isConnected()) {
            plugin.getLogger().warning("Redis not available, skipping Redis population for: " + uuid);

            String playerName = nameResolver != null ? nameResolver.getName(uuid) : uuid.toString();
            failedRedisQueue.add(new FailedRedisEntry(uuid, balance, version, playerName));
            return;
        }

        try (var jedis = redisManager.getResource()) {
            String keyBalance = "syncmoney:balance:" + uuid.toString();
            String keyVersion = "syncmoney:version:" + uuid.toString();

            jedis.set(keyBalance, balance.toPlainString());
            jedis.set(keyVersion, String.valueOf(version));

            plugin.getLogger().fine("CMI Migration: Redis populated for: " + uuid + " balance: " + balance + " version: " + version);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to populate Redis for " + uuid + ": " + e.getMessage());

            String playerName = nameResolver != null ? nameResolver.getName(uuid) : uuid.toString();
            failedRedisQueue.add(new FailedRedisEntry(uuid, balance, version, playerName));


            if (failedRedisQueue.size() % 10 == 0) {
                notifyRedisFailure();
            }
        }
    }

    /**
     * Notify admins about Redis failures during migration.
     */
    private void notifyRedisFailure() {
        String message;
        if (plugin instanceof Syncmoney) {
            message = ((Syncmoney) plugin).getMessage("migration.redis-failed-notification")
                    .replace("{count}", String.valueOf(failedRedisQueue.size()));
        } else {
            message = "Warning: " + failedRedisQueue.size() + " Redis population failures during migration.";
        }


        noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, player -> {
            if (player.hasPermission("syncmoney.admin")) {
                player.sendMessage(net.kyori.adventure.text.Component.text(message));
            }
        });

        plugin.getLogger().warning("Migration Redis failures: " + failedRedisQueue.size() + " entries failed. Use /syncmoney migrate resume to retry.");
    }

    /**
     * Retry failed Redis entries.
     * @return number of successfully retried entries
     */
    public int retryFailedRedisEntries() {
        if (failedRedisQueue.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        List<FailedRedisEntry> remaining = new ArrayList<>();

        for (FailedRedisEntry entry : failedRedisQueue) {
            try {
                if (redisManager != null && redisManager.isConnected()) {
                    try (var jedis = redisManager.getResource()) {
                        String keyBalance = "syncmoney:balance:" + entry.uuid.toString();
                        String keyVersion = "syncmoney:version:" + entry.uuid.toString();

                        jedis.set(keyBalance, entry.balance.toPlainString());
                        jedis.set(keyVersion, String.valueOf(entry.version));
                        successCount++;
                    }
                } else {
                    remaining.add(entry);
                }
            } catch (Exception e) {
                remaining.add(entry);
            }
        }

        failedRedisQueue.clear();
        failedRedisQueue.addAll(remaining);

        if (successCount > 0) {
            plugin.getLogger().info("Retry completed: " + successCount + " Redis entries restored, " + failedRedisQueue.size() + " still failed");
        }

        return successCount;
    }

    /**
     * Get failed Redis entry count.
     */
    public int getFailedRedisCount() {
        return failedRedisQueue.size();
    }

    /**
     * Validates prerequisites before starting CMI migration.
     * Redis must be available for SYNC mode.
     * @return true if all prerequisites are met
     */
    private boolean validatePrerequisites() {
        if (redisManager == null || !redisManager.isConnected()) {
            plugin.getLogger().severe("Redis is not available. Cannot start CMI migration to SYNC mode.");
            return false;
        }

        plugin.getLogger().fine("CMI Migration prerequisites validated successfully.");
        return true;
    }

    /**
     * Check Redis health during migration.
     * @param processedCount number of records processed so far
     * @return true if Redis is healthy, false if migration should pause
     */
    public boolean checkRedisHealthDuringMigration(int processedCount) {

        if (processedCount % 100 != 0) {
            return true;
        }

        if (redisManager == null) {
            notifyRedisHealthIssue("Redis manager is null");
            return false;
        }

        try {
            if (!redisManager.isConnected()) {
                notifyRedisHealthIssue("Redis disconnected");
                return false;
            }


            try (var jedis = redisManager.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equals(pong)) {
                    notifyRedisHealthIssue("Redis ping failed");
                    return false;
                }
            }
        } catch (Exception e) {
            notifyRedisHealthIssue("Redis health check failed: " + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Notify admins about Redis health issues.
     */
    private void notifyRedisHealthIssue(String reason) {
        String message;
        if (plugin instanceof Syncmoney) {
            message = ((Syncmoney) plugin).getMessage("migration.paused-redis")
                    .replace("{reason}", reason);
        } else {
            message = "Warning: Migration paused - " + reason;
        }

        noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, player -> {
            if (player.hasPermission("syncmoney.admin")) {
                player.sendMessage(net.kyori.adventure.text.Component.text(message));
            }
        });

        plugin.getLogger().warning("Migration paused: " + reason);
    }

    /**
     * Show migration summary to admins.
     */
    private void showMigrationSummary(int successCount, int failedCount, BigDecimal totalAmount, int redisFailedCount) {
        String header;
        String playersInfo;
        String amountInfo;
        String failedInfo;
        String nextSteps;

        if (plugin instanceof Syncmoney) {
            header = ((Syncmoney) plugin).getMessage("migration.summary-header");
            playersInfo = ((Syncmoney) plugin).getMessage("migration.summary-players")
                    .replace("{count}", String.valueOf(successCount));
            amountInfo = ((Syncmoney) plugin).getMessage("migration.summary-amount")
                    .replace("{amount}", totalAmount.toPlainString());
            failedInfo = ((Syncmoney) plugin).getMessage("migration.summary-failed")
                    .replace("{count}", String.valueOf(failedCount));
            nextSteps = ((Syncmoney) plugin).getMessage("migration.summary-next-steps");
        } else {
            header = "===== Migration Complete =====";
            playersInfo = "Migrated players: " + successCount;
            amountInfo = "Total amount: " + totalAmount;
            failedInfo = "Failed: " + failedCount;
            nextSteps = "Run /syncmoney reload to apply changes";
        }


        noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, player -> {
            if (player.hasPermission("syncmoney.admin")) {
                player.sendMessage(net.kyori.adventure.text.Component.text(header));
                player.sendMessage(net.kyori.adventure.text.Component.text(playersInfo));
                player.sendMessage(net.kyori.adventure.text.Component.text(amountInfo));
                player.sendMessage(net.kyori.adventure.text.Component.text(failedInfo));
                if (redisFailedCount > 0) {
                    String redisInfo = "Redis failed: " + redisFailedCount + " (use /syncmoney migrate resume)";
                    player.sendMessage(net.kyori.adventure.text.Component.text(redisInfo));
                }
                player.sendMessage(net.kyori.adventure.text.Component.text(nextSteps));
            }
        });


        plugin.getLogger().info(header);
        plugin.getLogger().info(playersInfo);
        plugin.getLogger().info(amountInfo);
        plugin.getLogger().info(failedInfo);
        if (redisFailedCount > 0) {
            plugin.getLogger().warning("Redis failures: " + redisFailedCount);
        }
        plugin.getLogger().info(nextSteps);
    }

    /**
     * Creates backup.
     */
    private void createBackup() {
        plugin.getLogger().fine("Creating CMI backup...");

        int totalPlayers;
        if (multiServerReader != null) {
            totalPlayers = multiServerReader.getTotalPlayerCount();
        } else {
            totalPlayers = cmiReader.getTotalPlayerCount();
        }

        if (multiServerReader != null) {
            List<CMIPlayerData> players = multiServerReader.readAndMergePlayers();
            backup.addPlayers(players);
        } else {
            int offset = 0;
            while (offset < totalPlayers) {
                List<CMIPlayerData> players = cmiReader.readPlayers(offset, 100);
                backup.addPlayers(players);
                offset += 100;
            }
        }

        String backupPath = backup.save();
        if (backupPath != null) {
            plugin.getLogger().fine("Backup created: " + backupPath);
        }
    }

    /**
     * Completes migration.
     */
    private void completeMigration() {
        running = false;
        checkpoint.complete();

        if (progressCallback != null) {
            progressCallback.onComplete(checkpoint.getMigratedCount(), checkpoint.getFailedCount());
        }

        plugin.getLogger().fine("Migration completed! Migrated: " + checkpoint.getMigratedCount() +
                ", Failed: " + checkpoint.getFailedCount());


        showMigrationSummary(checkpoint.getMigratedCount(), checkpoint.getFailedCount(),
                checkpoint.getTotalBackupAmount(), failedRedisQueue.size());

        if (economyDisabler != null) {
            scheduleCMIEconomyDisable();
        }

        releaseMigrationLock();

        cancel();
    }

    private void releaseMigrationLock() {
        if (migrationLockOwned) {
            MigrationLock.unlock();
            MigrationLock.disable();
            migrationLockOwned = false;
            plugin.getLogger().fine("Economy operations unlocked after CMI migration");
        }
    }

    private void failAndReleaseMigrationLock(Throwable failure) {
        try {
            checkpoint.fail();
        } catch (RuntimeException | Error checkpointFailure) {
            failure.addSuppressed(checkpointFailure);
        } finally {
            running = false;
            cancel();
            releaseMigrationLock();
        }
    }

    /**
     * Schedules CMI economy disable task (executes on main thread).
     */
    private void scheduleCMIEconomyDisable() {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                if (config.migration().isCMIAutoDisableCommands()) {
                    boolean commandsDisabled = economyDisabler.disableEconomyCommands();
                    if (commandsDisabled) {
                        plugin.getLogger().fine("CMI economy commands disabled successfully");
                    } else {
                        plugin.getLogger().warning("Failed to disable CMI economy commands");
                    }
                }

                if (config.migration().isCMIAutoDisableEconomy()) {
                    boolean economyDisabled = economyDisabler.disableEconomyModule();
                    if (economyDisabled) {
                        plugin.getLogger().fine("CMI economy module disabled successfully");
                    } else {
                        plugin.getLogger().warning("Failed to disable CMI economy module");
                    }
                }

                economyDisabler.reloadCMI();
                plugin.getLogger().fine("CMI reload completed");

            } catch (Exception e) {
                plugin.getLogger().severe("Error during CMI economy disable: " + e.getMessage());
            }
        });
    }

    /**
     * Cancels the task.
     */
    private void cancel() {
        running = false;
    }

    /**
     * Gets migration status.
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the checkpoint.
     * @return checkpoint
     */
    public MigrationCheckpoint getCheckpoint() {
        return checkpoint;
    }

    /**
     * Migration progress callback interface.
     */
    public interface MigrationProgressCallback {
        /**
         * Progress update.
         * @param current current migration count
         * @param total total count
         * @param percent percentage
         */
        void onProgress(int current, int total, double percent);

        /**
         * Migration completed.
         * @param successCount success count
         * @param failedCount failed count
         */
        void onComplete(int successCount, int failedCount);

        /**
         * Error occurred.
         * @param error error message
         */
        void onError(String error);
    }
}
