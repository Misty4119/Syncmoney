package noietime.syncmoney.economy;

import java.io.IOException;
import java.util.List;

/**
 * [SYNC-ECO-116] OverflowLog interface for Write-Ahead Log of dropped economy events.
 * Implementations can use file system or Redis for storage.
 */
public interface OverflowLogInterface {
    
    /**
     * Log a dropped economy event.
     * @param event The event to log
     */
    void log(EconomyEvent event);

    /**
     * Read legacy records without acknowledging, truncating, or deleting them.
     *
     * <p>The default preserves binary compatibility with implementations compiled
     * before this recovery-safe read was added. Those implementations cannot be
     * treated as having no pending records, so recovery fails closed until they
     * provide a non-destructive read.</p>
     */
    default List<String> readWithoutClear() throws IOException {
        throw new IOException("Overflow log does not support non-destructive legacy reads");
    }
    
    /**
     * Read and clear the overflow log.
     * Called at plugin startup to recover dropped events.
     * @return List of events in legacy string format
     */
    java.util.List<String> readAndClear();
    
    /**
     * Get the current size/count of overflow events.
     * @return Number of events in overflow log
     */
    long getSize();
    
    /**
     * Check if there are any overflow records.
     * @return true if there are records
     */
    boolean hasOverflowRecords();
}
