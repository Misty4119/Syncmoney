package noietime.syncmoney.shadow;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CMI Sync Queue - Event-driven + Delayed batch write.
 */
public final class CMISyncQueue {

    public record SyncEvent(UUID uuid, String playerName, BigDecimal balance, long authorityVersion, long timestamp) {
        public SyncEvent {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(playerName, "playerName");
            Objects.requireNonNull(balance, "balance");
            if (authorityVersion <= 0L) {
                throw new IllegalArgumentException("authorityVersion must be positive");
            }
        }
    }

    public enum OfferResult {
        ACCEPTED,
        STALE,
        CAPACITY_REJECTED
    }

    private final Object queueLock = new Object();
    private final Map<UUID, SyncEvent> pendingByUuid = new LinkedHashMap<>();
    private final int capacity;
    private final int batchSize;
    private final long maxDelayMs;
    private final ScheduledExecutorService scheduler;
    private long rejectedOfferCount;

    public CMISyncQueue(int batchSize, long maxDelayMs) {
        this(batchSize, maxDelayMs, Integer.MAX_VALUE);
    }

    public CMISyncQueue(int batchSize, long maxDelayMs, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.batchSize = batchSize;
        this.maxDelayMs = maxDelayMs;
        this.capacity = capacity;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "syncmoney-cmi-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Adds or replaces the pending snapshot for a player. A replacement is accepted even when the
     * queue is at capacity because it does not increase the number of pending accounts.
     *
     * @return {@code true} when the snapshot is pending or supersedes an older pending snapshot;
     *         {@code false} when capacity is exhausted by other UUIDs
     */
    public boolean offer(SyncEvent event) {
        return offerWithStatus(event) == OfferResult.ACCEPTED;
    }

    /**
     * Adds a snapshot unless it is older than the same UUID's queued authority version.
     */
    public OfferResult offerWithStatus(SyncEvent event) {
        Objects.requireNonNull(event, "event");
        UUID uuid = Objects.requireNonNull(event.uuid(), "event.uuid");
        synchronized (queueLock) {
            SyncEvent pending = pendingByUuid.get(uuid);
            if (pending != null) {
                if (event.authorityVersion() < pending.authorityVersion()
                        || (event.authorityVersion() == pending.authorityVersion()
                                && event.balance().compareTo(pending.balance()) != 0)) {
                    return OfferResult.STALE;
                }
                pendingByUuid.put(uuid, event);
                return OfferResult.ACCEPTED;
            }
            if (pendingByUuid.size() >= capacity) {
                rejectedOfferCount++;
                return OfferResult.CAPACITY_REJECTED;
            }
            pendingByUuid.put(uuid, event);
            return OfferResult.ACCEPTED;
        }
    }

    public ConcurrentLinkedQueue<SyncEvent> drainAll() {
        synchronized (queueLock) {
            ConcurrentLinkedQueue<SyncEvent> drained = new ConcurrentLinkedQueue<>(pendingByUuid.values());
            pendingByUuid.clear();
            return drained;
        }
    }

    public int size() {
        synchronized (queueLock) {
            return pendingByUuid.size();
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Returns the number of new UUID snapshots rejected since this queue was created. */
    public long getRejectedOfferCount() {
        synchronized (queueLock) {
            return rejectedOfferCount;
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
