package noietime.syncmoney.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shadow sync log.
 * Records sync history from Syncmoney to CMI.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class ShadowSyncLog {

    private final Plugin plugin;
    private final ObjectMapper objectMapper;
    private final File logDirectory;


    private final ConcurrentLinkedQueue<SyncRecord> recentLogs;


    private static final int MAX_RECENT_LOGS = 1000;

    public ShadowSyncLog(Plugin plugin) {
        this.plugin = plugin;
        this.objectMapper = new ObjectMapper();
        this.recentLogs = new ConcurrentLinkedQueue<>();

        this.logDirectory = new File(plugin.getDataFolder(), "logs/shadow-sync");
        if (!logDirectory.exists()) {
            logDirectory.mkdirs();
        }
    }

    /**
     * Logs sync event.
     *
     * @param playerName   player name
     * @param syncmoneyBalance Syncmoney balance
     * @param cmibalance    CMI balance
     * @param success       whether operation succeeded
     * @param reason        reason (optional)
     */
    public void log(String playerName, String syncmoneyBalance, String cmibalance, boolean success, String reason) {
        SyncRecord record = new SyncRecord(
                Instant.now().toString(),
                playerName,
                syncmoneyBalance,
                cmibalance,
                success,
                reason
        );

        recentLogs.add(record);

        while (recentLogs.size() > MAX_RECENT_LOGS) {
            recentLogs.poll();
        }

        writeToFile(record);
    }

    /**
     * Logs sync failure.
     */
    public void logFailure(String playerName, String syncmoneyBalance, String cmibalance, String reason) {
        log(playerName, syncmoneyBalance, cmibalance, false, reason);
    }

    /**
     * Logs sync success.
     */
    public void logSuccess(String playerName, String syncmoneyBalance, String cmibalance) {
        log(playerName, syncmoneyBalance, cmibalance, true, null);
    }

    /**
     * Writes log to file.
     */
    private void writeToFile(SyncRecord record) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File logFile = new File(logDirectory, "sync-" + dateStr + ".jsonl");

        try {
            String jsonLine = objectMapper.writeValueAsString(record);
            java.nio.file.Files.writeString(
                    logFile.toPath(),
                    jsonLine + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write shadow sync log: " + e.getMessage());
        }
    }

    /**
     * Gets recent sync records.
     *
     * @param limit maximum number of records
     * @return list of sync records
     */
    public List<SyncRecord> getRecentLogs(int limit) {
        List<SyncRecord> result = new ArrayList<>();
        for (SyncRecord record : recentLogs) {
            if (result.size() >= limit) break;
            result.add(record);
        }
        return result;
    }

    /**
     * Gets failed sync records.
     *
     * @param limit maximum number of records
     * @return list of failed sync records
     */
    public List<SyncRecord> getFailedLogs(int limit) {
        List<SyncRecord> result = new ArrayList<>();
        for (SyncRecord record : recentLogs) {
            if (!record.success() && result.size() < limit) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * Gets today's sync statistics.
     *
     * @return statistics information
     */
    public SyncStats getTodayStats() {
        int total = 0;
        int success = 0;
        int failed = 0;

        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File logFile = new File(logDirectory, "sync-" + today + ".jsonl");

        if (logFile.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
                for (String line : lines) {
                    try {
                        SyncRecord record = objectMapper.readValue(line, SyncRecord.class);
                        total++;
                        if (record.success()) {
                            success++;
                        } else {
                            failed++;
                        }
                    } catch (JsonProcessingException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }

        return new SyncStats(total, success, failed);
    }

    /**
     * Clears in-memory logs.
     */
    public void clearMemoryLogs() {
        recentLogs.clear();
    }

    /**
     * Sync record.
     */
    public record SyncRecord(
            String timestamp,
            String playerName,
            String syncmoneyBalance,
            String cmibalance,
            boolean success,
            String reason
    ) {
    }

    /**
     * Sync statistics.
     */
    public record SyncStats(int total, int success, int failed) {
        public double getSuccessRate() {
            if (total == 0) return 0;
            return (double) success / total * 100;
        }
    }
}
