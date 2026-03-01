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
        queue.offer(event);
    }


    public ConcurrentLinkedQueue<SyncEvent> drainAll() {
        ConcurrentLinkedQueue<SyncEvent> drained = new ConcurrentLinkedQueue<>();
        SyncEvent event;
        while ((event = queue.poll()) != null) {
            drained.offer(event);
        }
        return drained;
    }


    public int size() {
        return queue.size();
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
