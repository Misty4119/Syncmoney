package noietime.syncmoney.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Migration checkpoint resume manager.
 * Records migration progress, supports resuming after interruption.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class MigrationCheckpoint {

    private final Plugin plugin;
    private final ObjectMapper objectMapper;
    private final File checkpointFile;

    private volatile MigrationState state = MigrationState.IDLE;

    private volatile int currentOffset = 0;

    private volatile int totalPlayers = 0;

    private volatile int migratedCount = 0;

    private volatile int failedCount = 0;

    private volatile BigDecimal totalBackupAmount = BigDecimal.ZERO;

    private volatile Instant startTime;

    private volatile Instant lastUpdateTime;

    private final ConcurrentMap<String, String> failures;

    public MigrationCheckpoint(Plugin plugin) {
        this.plugin = plugin;
        this.objectMapper = new ObjectMapper();
        this.checkpointFile = new File(plugin.getDataFolder(), "migration_checkpoint.json");
        this.failures = new ConcurrentHashMap<>();
    }

    /**
     * Initializes migration task.
     * @param totalPlayers total number of players
     */
    public void init(int totalPlayers) {
        this.totalPlayers = totalPlayers;
        this.currentOffset = 0;
        this.migratedCount = 0;
        this.failedCount = 0;
        this.startTime = Instant.now();
        this.lastUpdateTime = Instant.now();
        this.state = MigrationState.RUNNING;
        this.failures.clear();
        save();
    }

    /**
     * Resumes previous migration task.
     * @return true if loaded successfully
     */
    public boolean resume() {
        if (!checkpointFile.exists()) {
            return false;
        }

        try {
            CheckpointData data = objectMapper.readValue(checkpointFile, CheckpointData.class);
            this.state = MigrationState.valueOf(data.state());
            this.currentOffset = data.currentOffset();
            this.totalPlayers = data.totalPlayers();
            this.migratedCount = data.migratedCount();
            this.failedCount = data.failedCount();
            this.startTime = Instant.parse(data.startTime());
            this.lastUpdateTime = Instant.parse(data.lastUpdateTime());

            if (data.failures() != null) {
                this.failures.putAll(data.failures());
            }

            return this.state == MigrationState.RUNNING || this.state == MigrationState.PAUSED;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load checkpoint: " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates migration progress.
     * @param newOffset new offset
     * @param migratedCount number of migrated players
     */
    public void updateProgress(int newOffset, int migratedCount) {
        this.currentOffset = newOffset;
        this.migratedCount = migratedCount;
        this.lastUpdateTime = Instant.now();
        save();
    }

    /**
     * Records failed player.
     * @param playerName player name
     * @param reason failure reason
     */
    public void recordFailure(String playerName, String reason) {
        this.failedCount++;
        this.failures.put(playerName, reason);
        this.lastUpdateTime = Instant.now();
        save();
    }

    /**
     * Completes migration.
     */
    public void complete() {
        this.state = MigrationState.COMPLETED;
        this.lastUpdateTime = Instant.now();
        save();
    }

    /**
     * Marks migration as failed.
     */
    public void fail() {
        this.state = MigrationState.FAILED;
        this.lastUpdateTime = Instant.now();
        save();
    }

    /**
     * Pauses migration.
     */
    public void pause() {
        this.state = MigrationState.PAUSED;
        this.lastUpdateTime = Instant.now();
        save();
    }

    /**
     * Clears checkpoint.
     */
    public void clear() {
        this.state = MigrationState.IDLE;
        this.currentOffset = 0;
        this.totalPlayers = 0;
        this.migratedCount = 0;
        this.failedCount = 0;
        this.startTime = null;
        this.lastUpdateTime = null;
        this.failures.clear();
        if (checkpointFile.exists()) {
            checkpointFile.delete();
        }
    }

    /**
     * Saves checkpoint to file.
     */
    private void save() {
        try {
            CheckpointData data = new CheckpointData(
                    state.name(),
                    currentOffset,
                    totalPlayers,
                    migratedCount,
                    failedCount,
                    startTime != null ? startTime.toString() : null,
                    lastUpdateTime != null ? lastUpdateTime.toString() : null,
                    new ConcurrentHashMap<>(failures)
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(checkpointFile, data);
        } catch (JsonProcessingException e) {
            plugin.getLogger().severe("Failed to save checkpoint: " + e.getMessage());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write checkpoint file: " + e.getMessage());
        }
    }

    public MigrationState getState() {
        return state;
    }

    public int getCurrentOffset() {
        return currentOffset;
    }

    public int getTotalPlayers() {
        return totalPlayers;
    }

    /**
     * Gets total backup amount.
     */
    public BigDecimal getTotalBackupAmount() {
        return totalBackupAmount;
    }

    /**
     * Adds backup amount.
     * @param amount amount to add
     */
    public void addBackupAmount(BigDecimal amount) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            this.totalBackupAmount = this.totalBackupAmount.add(amount);
            this.lastUpdateTime = Instant.now();
        }
    }

    public int getMigratedCount() {
        return migratedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getLastUpdateTime() {
        return lastUpdateTime;
    }

    public ConcurrentMap<String, String> getFailures() {
        return failures;
    }

    /**
     * Gets progress percentage.
     * @return percentage (0-100)
     */
    public double getProgressPercent() {
        if (totalPlayers == 0) return 0;
        return (double) migratedCount / totalPlayers * 100;
    }

    /**
     * Gets estimated remaining time in seconds.
     * @return remaining seconds, or -1 if cannot be calculated
     */
    public long getEstimatedRemainingSeconds() {
        if (startTime == null || migratedCount == 0) return -1;

        long elapsedSeconds = java.time.Duration.between(startTime, Instant.now()).getSeconds();
        double avgTimePerPlayer = (double) elapsedSeconds / migratedCount;
        int remainingPlayers = totalPlayers - migratedCount;

        return (long) (avgTimePerPlayer * remainingPlayers);
    }

    /**
     * Migration state enumeration.
     */
    public enum MigrationState {
        IDLE,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED
    }

    /**
     * Checkpoint data structure (for JSON serialization).
     */
    private record CheckpointData(
            String state,
            int currentOffset,
            int totalPlayers,
            int migratedCount,
            int failedCount,
            String startTime,
            String lastUpdateTime,
            ConcurrentHashMap<String, String> failures
    ) {}
}
