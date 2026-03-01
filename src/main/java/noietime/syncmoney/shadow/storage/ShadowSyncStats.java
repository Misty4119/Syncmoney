package noietime.syncmoney.shadow.storage;

import java.time.LocalDate;

/**
 * Statistics for shadow sync operations.
 */
public class ShadowSyncStats {

    private LocalDate date;
    private int totalSyncs;
    private int successfulSyncs;
    private int failedSyncs;

    public ShadowSyncStats() {
    }

    public ShadowSyncStats(LocalDate date, int totalSyncs, int successfulSyncs, int failedSyncs) {
        this.date = date;
        this.totalSyncs = totalSyncs;
        this.successfulSyncs = successfulSyncs;
        this.failedSyncs = failedSyncs;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getTotalSyncs() {
        return totalSyncs;
    }

    public void setTotalSyncs(int totalSyncs) {
        this.totalSyncs = totalSyncs;
    }

    public int getSuccessfulSyncs() {
        return successfulSyncs;
    }

    public void setSuccessfulSyncs(int successfulSyncs) {
        this.successfulSyncs = successfulSyncs;
    }

    public int getFailedSyncs() {
        return failedSyncs;
    }

    public void setFailedSyncs(int failedSyncs) {
        this.failedSyncs = failedSyncs;
    }

    public double getSuccessRate() {
        return totalSyncs > 0 ? (double) successfulSyncs / totalSyncs * 100 : 0;
    }
}
