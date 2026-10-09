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

    /** Read legacy records without acknowledging, truncating, or deleting them. */
    List<String> readWithoutClear() throws IOException;
    
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
