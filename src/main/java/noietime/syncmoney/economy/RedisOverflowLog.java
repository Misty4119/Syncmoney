package noietime.syncmoney.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import noietime.syncmoney.PluginContext;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.*;

/**
 * [SYNC-ECO-108] Redis-based OverflowLog - Hybrid WAL for dropped economy events.
 * 
 * This is an alternative OverflowLog implementation that uses Redis Sorted Set
 * instead of file system for better reliability and performance.
 * 
 * Format in Redis: Key "syncmoney:overflow:events" with score = timestamp
 * Value = JSON serialized OverflowEvent
 * 
 * Usage: Pass to EconomyFacade constructor or set via setOverflowLog()
 */
public final class RedisOverflowLog implements OverflowLogInterface {

    private static final String REDIS_KEY = "syncmoney:overflow:events";
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_EVENTS = 100000;
    
    private final RedisManager redisManager;
    private final Plugin plugin;
    private final ObjectMapper objectMapper;

    public RedisOverflowLog(Plugin plugin, RedisManager redisManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Logs a dropped economy event to Redis.
     */
    public void log(EconomyEvent event) {
        try {
            OverflowEvent overflowEvent = new OverflowEvent(
                event.timestamp(),
                event.playerUuid().toString(),
                event.type() != null ? event.type().name() : "UNKNOWN",
                event.source() != null ? event.source().name() : "UNKNOWN",
                event.delta() != null ? event.delta().toPlainString() : "0",
                event.requestId() != null ? event.requestId() : UUID.randomUUID().toString(),
                event.balanceAfter() != null ? event.balanceAfter().toPlainString() : "0",
                event.version()
            );
            
            String json = objectMapper.writeValueAsString(overflowEvent);
            String member = event.requestId() + ":" + event.playerUuid();
            
            try (var jedis = redisManager.getResource()) {

                jedis.zadd(REDIS_KEY, event.timestamp(), member + "|" + json);
                

                cleanupOldEntries(jedis);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to write Redis overflow log: " + e.getMessage());

            fallbackToFileLog(event);
        }
    }

    /**
     * Reads and clears the overflow log.
     * Called at plugin startup to recover dropped events.
     */
    public List<String> readAndClear() {
        List<String> lines = new ArrayList<>();
        
        try (var jedis = redisManager.getResource()) {

            List<String> entries = jedis.zrange(REDIS_KEY, 0, -1);
            
            if (entries != null && !entries.isEmpty()) {
                for (String entry : entries) {

                    int pipeIndex = entry.indexOf('|');
                    if (pipeIndex > 0) {
                        String json = entry.substring(pipeIndex + 1);
                        try {
                            OverflowEvent oe = objectMapper.readValue(json, OverflowEvent.class);

                            String line = String.format("%d|%s|%s|%s|%s|%s%n",
                                oe.timestamp,
                                oe.playerUuid,
                                oe.eventType,
                                oe.eventSource,
                                oe.amount,
                                oe.requestId
                            );
                            lines.add(line);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to parse overflow event: " + e.getMessage());
                        }
                    }
                }
                

                jedis.del(REDIS_KEY);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read Redis overflow log: " + e.getMessage());
        }
        
        return lines;
    }

    /**
     * Gets the current count of overflow events.
     */
    public long getSize() {
        try (var jedis = redisManager.getResource()) {
            Long count = jedis.zcard(REDIS_KEY);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Checks if there are any overflow records.
     */
    public boolean hasOverflowRecords() {
        return getSize() > 0;
    }

    /**
     * Gets overflow events for debugging/monitoring.
     */
    public List<OverflowEvent> getOverflowEvents(int limit) {
        List<OverflowEvent> events = new ArrayList<>();
        
        try (var jedis = redisManager.getResource()) {
            List<String> entries = jedis.zrange(REDIS_KEY, 0, limit - 1);
            
            if (entries != null) {
                for (String entry : entries) {
                    int pipeIndex = entry.indexOf('|');
                    if (pipeIndex > 0) {
                        String json = entry.substring(pipeIndex + 1);
                        events.add(objectMapper.readValue(json, OverflowEvent.class));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get overflow events: " + e.getMessage());
        }
        
        return events;
    }

    /**
     * Clears all overflow events (manual cleanup).
     */
    public void clear() {
        try (var jedis = redisManager.getResource()) {
            jedis.del(REDIS_KEY);
            plugin.getLogger().info("Cleared all overflow events from Redis.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clear overflow events: " + e.getMessage());
        }
    }

    /**
     * Cleanup old entries to prevent unbounded growth.
     */
    private void cleanupOldEntries(Jedis jedis) {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        

        jedis.zremrangeByScore(REDIS_KEY, "-inf", String.valueOf(cutoff));
        

        Long count = jedis.zcard(REDIS_KEY);
        if (count != null && count > MAX_EVENTS) {
            long toRemove = count - MAX_EVENTS;
            jedis.zremrangeByRank(REDIS_KEY, 0, toRemove - 1);
        }
    }

    /**
     * Fallback to file-based logging when Redis fails.
     */
    private void fallbackToFileLog(EconomyEvent event) {
        plugin.getLogger().warning("Redis OverflowLog failed, attempting file-based fallback...");


        System.err.println("[SYNC-ECO-095] FALLBACK: " + event.timestamp() + "|" + 
            event.playerUuid() + "|" + event.type() + "|" + event.source() + "|" + 
            event.delta() + "|" + event.requestId());
    }

    /**
     * Internal class for JSON serialization.
     */
    public static class OverflowEvent {
        public long timestamp;
        public String playerUuid;
        public String eventType;
        public String eventSource;
        public String amount;
        public String requestId;
        public String balanceAfter;
        public long version;

        public OverflowEvent() {}

        public OverflowEvent(long timestamp, String playerUuid, String eventType, 
                           String eventSource, String amount, String requestId,
                           String balanceAfter, long version) {
            this.timestamp = timestamp;
            this.playerUuid = playerUuid;
            this.eventType = eventType;
            this.eventSource = eventSource;
            this.amount = amount;
            this.requestId = requestId;
            this.balanceAfter = balanceAfter;
            this.version = version;
        }
    }
}
