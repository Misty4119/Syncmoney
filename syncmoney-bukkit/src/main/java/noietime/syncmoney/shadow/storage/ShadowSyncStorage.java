package noietime.syncmoney.shadow.storage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Storage interface for shadow sync history.
 *
 * [ThreadSafe] Implementations must be thread-safe for concurrent operations.
 */
public interface ShadowSyncStorage {

    /**
     * Saves a sync record to storage.
     *
     * @param record the sync record to save
     */
    void saveRecord(ShadowSyncRecord record);

    /**
     * Saves multiple sync records in batch.
     *
     * @param records list of sync records
     */
    void saveRecordsBatch(List<ShadowSyncRecord> records);

    /**
     * Queries sync history by player UUID.
     *
     * @param playerUuid player's UUID
     * @param limit maximum number of records
     * @return list of sync records, ordered by timestamp descending
     */
    List<ShadowSyncRecord> queryByPlayer(String playerUuid, int limit);

    /**
     * Queries sync history by player name.
     *
     * @param playerName player's name
     * @param limit maximum number of records
     * @return list of sync records, ordered by timestamp descending
     */
    List<ShadowSyncRecord> queryByPlayerName(String playerName, int limit);

    /**
     * Queries sync history by date range.
     *
     * @param startDate start date (inclusive)
     * @param endDate end date (inclusive)
     * @param limit maximum number of records
     * @return list of sync records
     */
    List<ShadowSyncRecord> queryByDateRange(LocalDate startDate, LocalDate endDate, int limit);

    /**
     * Queries sync history by player and date range.
     *
     * @param playerUuid player's UUID
     * @param startDate start date (inclusive)
     * @param endDate end date (inclusive)
     * @param limit maximum number of records
     * @return list of sync records
     */
    List<ShadowSyncRecord> queryByPlayerAndDateRange(String playerUuid, LocalDate startDate, LocalDate endDate, int limit);

    /**
     * Exports sync history to JSONL file.
     *
     * @param playerUuid player's UUID (null for all players)
     * @param startDate start date (null for no limit)
     * @param endDate end date (null for no limit)
     * @return number of records exported
     */
    int exportToJsonl(String playerUuid, LocalDate startDate, LocalDate endDate);

    /**
     * Gets today's sync statistics.
     *
     * @return today's stats
     */
    ShadowSyncStats getTodayStats();

    /**
     * Gets statistics for a specific date.
     *
     * @param date the date
     * @return stats for that date
     */
    ShadowSyncStats getStatsByDate(LocalDate date);

    /**
     * Cleans up old records based on retention policy.
     *
     * @param retentionDays number of days to retain
     * @return number of records deleted
     */
    int cleanupOldRecords(int retentionDays);

    /**
     * Initializes the storage (creates tables, etc).
     */
    void initialize();

    /**
     * Closes the storage and releases resources.
     */
    void close();
}
