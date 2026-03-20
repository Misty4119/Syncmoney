package noietime.syncmoney.economy;

import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [SYNC-ECO-095] OverflowLog - Write-Ahead Log for dropped economy events.
 * Records events that are dropped when WriteQueue is full.
 */
public final class OverflowLog implements OverflowLogInterface {

    private final Path logFile;
    private final Plugin plugin;

    public OverflowLog(Plugin plugin, Path dataFolder) {
        this.plugin = plugin;
        this.logFile = dataFolder.resolve("overflow.log");
        ensureFileExists();
    }

    private void ensureFileExists() {
        try {
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create overflow log file: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-096] Logs a dropped economy event to WAL.
     * Format: timestamp|uuid|eventType|eventSource|amount|requestId
     */
    public void log(EconomyEvent event) {
        String line = String.format("%d|%s|%s|%s|%s|%s%n",
            event.timestamp(),
            event.playerUuid(),
            event.type() != null ? event.type().name() : "UNKNOWN",
            event.source() != null ? event.source().name() : "UNKNOWN",
            event.delta() != null ? event.delta().toPlainString() : "0",
            event.requestId() != null ? event.requestId() : "UNKNOWN"
        );

        try {
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write overflow log: " + e.getMessage());
        }
    }

    /**
     * [SYNC-ECO-097] Reads and clears the overflow log.
     * Called at plugin startup to recover dropped events.
     */
    public List<String> readAndClear() {
        List<String> lines = new ArrayList<>();
        
        try {
            if (Files.exists(logFile) && Files.size(logFile) > 0) {
                lines = Files.readAllLines(logFile);
                Files.writeString(logFile, "");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read overflow log: " + e.getMessage());
        }
        
        return lines;
    }

    /**
     * [SYNC-ECO-098] Gets the current size of the overflow log.
     */
    public long getSize() {
        try {
            return Files.size(logFile);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * [SYNC-ECO-099] Checks if there are any overflow records.
     */
    public boolean hasOverflowRecords() {
        return getSize() > 0;
    }
}
