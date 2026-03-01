package noietime.syncmoney.economy;

import noietime.syncmoney.util.FormatUtil;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * [SYNC-QUEUE-001] Write queue for economic events using BlockingQueue.
 * Thread-safe for multiple producers and single consumer pattern.
 *
 * [AsyncScheduler] Thread-safe, suitable for multi-producer single-consumer pattern.
 */
public final class EconomyWriteQueue {

    private final BlockingQueue<EconomyEvent> queue;
    private final int capacity;
    private final Logger logger;
    private volatile boolean warnedHighUsage = false;


    private final ConcurrentMap<UUID, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();

    public EconomyWriteQueue(int capacity) {
        this(capacity, null);
    }

    public EconomyWriteQueue(int capacity, Logger logger) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.logger = logger;
    }

    /**
     * Offer economy event (non-blocking).
     * @return true if successfully offered
     */
    public boolean offer(EconomyEvent event) {
        boolean result = queue.offer(event);
        if (result) {
            pendingCounts.computeIfAbsent(event.uuid(), k -> new AtomicInteger(0)).incrementAndGet();
            checkHighUsage();
        }
        return result;
    }

    /**
     * Check high usage and log warning.
     */
    private void checkHighUsage() {
        if (logger == null) return;
        int currentSize = queue.size();
        int usageThreshold = (int) (capacity * 0.8);

        if (currentSize >= usageThreshold && !warnedHighUsage) {
            logger.warning("EconomyWriteQueue usage high: " + currentSize + "/" + capacity +
                    " (" + FormatUtil.formatPercentRaw(currentSize * 100.0 / capacity) + "%)");
            warnedHighUsage = true;
        } else if (currentSize < usageThreshold) {
            warnedHighUsage = false;
        }
    }

    /**
     * Get queue usage ratio.
     * @return Usage ratio between 0.0 and 1.0
     */
    public double getUsageRatio() {
        return (double) queue.size() / capacity;
    }

    /**
     * Poll economy event (blocking until task available or timeout).
     */
    public EconomyEvent poll() throws InterruptedException {
        return poll(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Poll economy event with custom timeout.
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the event, or null if timeout expires
     */
    public EconomyEvent poll(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        EconomyEvent event = queue.poll(timeout, unit);
        if (event != null) {
            AtomicInteger count = pendingCounts.get(event.uuid());
            if (count != null && count.decrementAndGet() <= 0) {
                pendingCounts.remove(event.uuid());
            }
        }
        return event;
    }

    /**
     * Get queue size.
     */
    public int size() {
        return queue.size();
    }

    /**
     * Get capacity.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Check if queue is empty.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Check if there are pending events for specified player.
     * @param uuid Player UUID
     * @return true if there are pending events
     */
    public boolean hasPending(UUID uuid) {
        AtomicInteger count = pendingCounts.get(uuid);
        return count != null && count.get() > 0;
    }

    /**
     * Get number of pending events for specified player.
     * @param uuid Player UUID
     * @return Number of pending events
     */
    public int getPendingCount(UUID uuid) {
        AtomicInteger count = pendingCounts.get(uuid);
        return count != null ? count.get() : 0;
    }
}
