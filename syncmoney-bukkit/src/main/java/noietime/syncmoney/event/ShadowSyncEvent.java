package noietime.syncmoney.event;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Event fired during Shadow synchronization operations.
 * Provides information about sync progress and results.
 */
public class ShadowSyncEvent extends SyncmoneyEvent {

    /**
     * Types of synchronization.
     */
    public enum SyncType {
        /**
         * Full synchronization - syncs all player data
         */
        FULL,
        /**
         * Incremental synchronization - syncs only changes
         */
        INCREMENTAL,
        /**
         * Manual synchronization triggered by admin
         */
        MANUAL
    }

    /**
     * Status of the synchronization.
     */
    public enum SyncStatus {
        /**
         * Synchronization started
         */
        STARTED,
        /**
         * Synchronization in progress
         */
        IN_PROGRESS,
        /**
         * Synchronization completed successfully
         */
        COMPLETED,
        /**
         * Synchronization failed
         */
        FAILED
    }

    private final SyncType syncType;
    private final SyncStatus status;
    private final int playersProcessed;
    private final int totalPlayers;
    private final String serverName;
    private final String errorMessage;
    private final Duration duration;
    private final Set<UUID> affectedPlayers;

    public ShadowSyncEvent(
            SyncType syncType,
            SyncStatus status,
            int playersProcessed,
            int totalPlayers,
            String serverName,
            String errorMessage,
            Duration duration,
            Set<UUID> affectedPlayers) {
        super("ShadowSyncEvent");
        this.syncType = syncType;
        this.status = status;
        this.playersProcessed = playersProcessed;
        this.totalPlayers = totalPlayers;
        this.serverName = serverName;
        this.errorMessage = errorMessage;
        this.duration = duration;
        this.affectedPlayers = affectedPlayers;
    }

    /**
     * Get the synchronization type.
     */
    public SyncType getSyncType() {
        return syncType;
    }

    /**
     * Get the current synchronization status.
     */
    public SyncStatus getStatus() {
        return status;
    }

    /**
     * Get the number of players processed so far.
     */
    public int getPlayersProcessed() {
        return playersProcessed;
    }

    /**
     * Get the total number of players to sync.
     */
    public int getTotalPlayers() {
        return totalPlayers;
    }

    /**
     * Get the source server name.
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Get error message if status is FAILED.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Get the duration of the synchronization.
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Get the set of affected player UUIDs.
     */
    public Set<UUID> getAffectedPlayers() {
        return affectedPlayers;
    }

    /**
     * Get the progress percentage (0-100).
     */
    public int getProgressPercentage() {
        if (totalPlayers == 0) return 0;
        return (int) ((playersProcessed * 100.0) / totalPlayers);
    }

    /**
     * Check if this is a final status event.
     */
    public boolean isFinalStatus() {
        return status == SyncStatus.COMPLETED || status == SyncStatus.FAILED;
    }

    /**
     * Check if synchronization was successful.
     */
    public boolean isSuccessful() {
        return status == SyncStatus.COMPLETED;
    }

    @Override
    public String toString() {
        return "ShadowSyncEvent{" +
                "syncType=" + syncType +
                ", status=" + status +
                ", playersProcessed=" + playersProcessed +
                ", totalPlayers=" + totalPlayers +
                ", serverName='" + serverName + '\'' +
                ", progress=" + getProgressPercentage() + "%" +
                ", duration=" + duration +
                ", affectedPlayers=" + (affectedPlayers != null ? affectedPlayers.size() : 0) +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
