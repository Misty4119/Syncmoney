package noietime.syncmoney.shadow;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CMI Sync Queue - Event-driven + Delayed batch write.
 */
public final class CMISyncQueue {

    public record SyncEvent(UUID uuid, String playerName, BigDecimal balance, long timestamp) {}

    private final ConcurrentLinkedQueue<SyncEvent> queue = new ConcurrentLinkedQueue<>();
    private final int batchSize;
    private final long maxDelayMs;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.atomic.AtomicInteger queueSize = new java.util.concurrent.atomic.AtomicInteger(0);

    public CMISyncQueue(int batchSize, long maxDelayMs) {
        this.batchSize = batchSize;
        this.maxDelayMs = maxDelayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "syncmoney-cmi-sync");
            t.setDaemon(true);
            return t;
        });
    }

    public void offer(SyncEvent event) {
        if (queue.offer(event)) {
            queueSize.incrementAndGet();
        }
    }

    public ConcurrentLinkedQueue<SyncEvent> drainAll() {
        ConcurrentLinkedQueue<SyncEvent> drained = new ConcurrentLinkedQueue<>();
        SyncEvent event;
        int count = 0;
        while ((event = queue.poll()) != null) {
            drained.offer(event);
            count++;
        }
        queueSize.addAndGet(-count);
        return drained;
    }

    /**
     * Returns the current queue size using atomic counter.
     * This is more accurate than queue.size() for monitoring purposes.
     */
    public int size() {
        return queueSize.get();
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
