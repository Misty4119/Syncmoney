package noietime.syncmoney.economy;

import noietime.syncmoney.util.FormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * [SYNC-ECO-083] Write queue for economic events using BlockingQueue.
 * Thread-safe for multiple producers and single consumer pattern.
 * 
 * [FIX-002] Improved backpressure mechanism:
 * - Lowered threshold from 80% to 70%
 * - Added blocking offer with timeout
 * - Added tryOffer with timeout for better control
 */
public final class EconomyWriteQueue {

    private final BlockingQueue<EconomyEvent> queue;
    private final int capacity;
    private final Logger logger;
    private volatile boolean warnedHighUsage = false;


    private static final double BACKPRESSURE_THRESHOLD = 0.7;
    private static final long OFFER_TIMEOUT_MS = 100;
    private static final long HIGH_USAGE_CHECK_INTERVAL = 1000;

    private final ConcurrentMap<UUID, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private volatile long lastHighUsageWarning = 0;

    public EconomyWriteQueue(int capacity) {
        this(capacity, null);
    }

    public EconomyWriteQueue(int capacity, Logger logger) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.logger = logger;
    }

    /**
     * [SYNC-ECO-084] Offer economy event (non-blocking).
     * Added backpressure: rejects when queue usage > 70% (improved from 80%).
     * @return true if successfully offered, false if queue is over 70% capacity
     */
    public boolean offer(EconomyEvent event) {
        int currentSize = queue.size();
        int usageThreshold = (int) (capacity * BACKPRESSURE_THRESHOLD);
        
        if (currentSize >= usageThreshold) {
            if (logger != null) {

                long now = System.currentTimeMillis();
                if (now - lastHighUsageWarning > HIGH_USAGE_CHECK_INTERVAL) {
                    logger.warning("EconomyWriteQueue backpressure triggered: " + currentSize + "/" + capacity +
                            " (" + FormatUtil.formatPercentRaw(currentSize * 100.0 / capacity) + 
                            "%) - rejecting event for " + event.uuid());
                    lastHighUsageWarning = now;
                }
            }
            return false;
        }
        
        boolean result = queue.offer(event);
        if (result) {
            pendingCounts.computeIfAbsent(event.uuid(), k -> new AtomicInteger(0)).incrementAndGet();
            checkHighUsage();
        }
        return result;
    }

    /**
     * [FIX-002] Blocking offer with timeout.
     * This method will block for up to the specified timeout if the queue is full,
     * giving the consumer time to catch up before rejecting the event.
     * 
     * @param event The event to offer
     * @param timeout Maximum time to wait
     * @param unit Time unit
     * @return true if offered, false if timeout expired
     */
    public boolean offerBlocking(EconomyEvent event, long timeout, TimeUnit unit) {
        try {
            boolean result = queue.offer(event, timeout, unit);
            if (result) {
                pendingCounts.computeIfAbsent(event.uuid(), k -> new AtomicInteger(0)).incrementAndGet();
                checkHighUsage();
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * [FIX-002] Try offer with timeout - convenience method.
     * Uses default timeout of 100ms.
     * 
     * @param event The event to offer
     * @return true if offered, false if timeout expired
     */
    public boolean offerWithTimeout(EconomyEvent event) {
        return offerBlocking(event, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * [SYNC-ECO-085] Check high usage and log warning.
     */
    private void checkHighUsage() {
        if (logger == null) return;
        int currentSize = queue.size();
        int usageThreshold = (int) (capacity * BACKPRESSURE_THRESHOLD);

        if (currentSize >= usageThreshold && !warnedHighUsage) {
            logger.warning("EconomyWriteQueue usage high: " + currentSize + "/" + capacity +
                    " (" + FormatUtil.formatPercentRaw(currentSize * 100.0 / capacity) + "%)");
            warnedHighUsage = true;
        } else if (currentSize < usageThreshold) {
            warnedHighUsage = false;
        }
    }

    /**
     * [SYNC-ECO-086] Get queue usage ratio.
     * @return Usage ratio between 0.0 and 1.0
     */
    public double getUsageRatio() {
        return (double) queue.size() / capacity;
    }

    /**
     * [FIX-002] Get current queue size.
     * @return Current number of events in queue
     */
    public int size() {
        return queue.size();
    }

    /**
     * [FIX-002] Get remaining capacity.
     * @return Number of available slots
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * [SYNC-ECO-087] Poll economy event (blocking until task available or timeout).
     */
    public EconomyEvent poll() throws InterruptedException {
        return poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * [SYNC-ECO-088] Poll economy event with custom timeout.
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
     * [SYNC-ECO-089] Try to drain all pending events for a specific player.
     * Returns the number of events drained.
     * Improved thread safety with synchronized block.
     * @param uuid Player UUID
     * @param maxDrain Maximum events to drain
     * @return Number of events drained
     */
    public int drainPendingForPlayer(UUID uuid, int maxDrain) {
        synchronized (queue) {
            List<EconomyEvent> toRemove = new ArrayList<>();

            for (EconomyEvent event : queue) {
                if (toRemove.size() >= maxDrain) {
                    break;
                }
                if (event.playerUuid().equals(uuid)) {
                    toRemove.add(event);
                }
            }

            int removed = 0;
            for (EconomyEvent event : toRemove) {
                if (queue.remove(event)) {
                    removed++;
                }
            }

            if (removed > 0) {
                AtomicInteger count = pendingCounts.get(uuid);
                if (count != null) {
                    int newCount = count.addAndGet(-removed);
                    if (newCount <= 0) {
                        pendingCounts.remove(uuid);
                    }
                }
            }

            return removed;
        }
    }

    /**
     * [SYNC-ECO-091] Get capacity.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * [SYNC-ECO-092] Check if queue is empty.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * [SYNC-ECO-093] Check if there are pending events for specified player.
     * @param uuid Player UUID
     * @return true if there are pending events
     */
    public boolean hasPending(UUID uuid) {
        AtomicInteger count = pendingCounts.get(uuid);
        return count != null && count.get() > 0;
    }

    /**
     * [SYNC-ECO-094] Get number of pending events for specified player.
     * @param uuid Player UUID
     * @return Number of pending events
     */
    public int getPendingCount(UUID uuid) {
        AtomicInteger count = pendingCounts.get(uuid);
        return count != null ? count.get() : 0;
    }
}
