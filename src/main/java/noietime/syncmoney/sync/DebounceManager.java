package noietime.syncmoney.sync;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;
import java.util.UUID;

/**
 * Prevents duplicate processing of the same message.
 * Uses (sourceServer, version, uuid) composite key for deduplication.
 *
 * [AsyncScheduler] This class is thread-safe and suitable for multi-threaded environments.
 */
public final class DebounceManager {

    private static final int MAX_ENTRIES = 10_000;
    private static final long MESSAGE_TTL_MS = 5000;
    private static final long CLEANUP_INTERVAL_MS = 60000;

    private final Plugin plugin;
    private final ConcurrentMap<String, ProcessedMessage> processedMessages;
    private volatile long lastCleanupTime = 0;

    public DebounceManager(Plugin plugin) {
        this.plugin = plugin;
        this.processedMessages = new ConcurrentHashMap<>();
    }

    /**
     * Checks if message needs to be processed.
     * @param uuid player UUID
     * @param version version number
     * @param sourceServer source server
     * @param messageId message ID
     * @return true if needs processing, false if already processed or duplicate
     */
    public boolean shouldProcess(UUID uuid, long version, String sourceServer, String messageId) {
        String key = buildKey(sourceServer, version, uuid);

        ProcessedMessage existing = processedMessages.get(key);

        if (existing != null) {
            if (System.currentTimeMillis() - existing.timestamp() < MESSAGE_TTL_MS) {
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().fine("Debounce: rejected duplicate " + uuid + " v" + version);
                }
                return false;
            }
            processedMessages.remove(key);
        }

        processedMessages.put(key, new ProcessedMessage(version, System.currentTimeMillis(), messageId));

        if (processedMessages.size() > MAX_ENTRIES || shouldCleanup()) {
            cleanup();
        }

        return true;
    }

    private String buildKey(String sourceServer, long version, UUID uuid) {
        return sourceServer + ":" + version + ":" + uuid.toString();
    }

    private boolean shouldCleanup() {
        long now = System.currentTimeMillis();
        return now - lastCleanupTime > CLEANUP_INTERVAL_MS;
    }

    /**
     * Cleans up expired entries.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        lastCleanupTime = now;

        int removed = 0;
        for (Map.Entry<String, ProcessedMessage> entry : processedMessages.entrySet()) {
            if (now - entry.getValue().timestamp() > MESSAGE_TTL_MS) {
                if (processedMessages.remove(entry.getKey()) != null) {
                    removed++;
                }
            }
        }

        if (removed > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().fine("Debounce: cleaned up " + removed + " expired entries");
        }
    }

    /**
     * Gets current cache size (for monitoring).
     */
    public int getCacheSize() {
        return processedMessages.size();
    }

    /**
     * Clears all cache.
     */
    public void clear() {
        processedMessages.clear();
    }

    record ProcessedMessage(long version, long timestamp, String messageId) {}
}
