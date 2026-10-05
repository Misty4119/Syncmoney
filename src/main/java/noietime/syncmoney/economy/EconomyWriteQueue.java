package noietime.syncmoney.economy;

import noietime.syncmoney.util.FormatUtil;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    @FunctionalInterface
    interface WriteCompletionHandler {
        void onComplete(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion);
    }

    private final BlockingQueue<EconomyEvent> queue;
    private final int capacity;
    private final Logger logger;
    private final Semaphore admissionPermits;
    private final Object admissionGate = new Object();
    private boolean acceptingWrites = true;
    private volatile boolean warnedHighUsage = false;


    private static final double BACKPRESSURE_THRESHOLD = 0.7;
    private static final long OFFER_TIMEOUT_MS = 100;
    private static final long HIGH_USAGE_CHECK_INTERVAL = 1000;

    private final ConcurrentMap<UUID, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicInteger> acceptedWriteCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicInteger> committedWriteCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AcceptedWrite> acceptedWrites = new ConcurrentHashMap<>();
    private final Map<EconomyEvent, Long> reservationsByEvent =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong nextReservationId = new AtomicLong();
    private final ConcurrentMap<Long, AutoCloseable> completionResources = new ConcurrentHashMap<>();
    private volatile WriteCompletionHandler writeCompletionHandler;
    private volatile long lastHighUsageWarning = 0;

    private static final class AcceptedWrite {
        private final UUID uuid;
        private final long acceptedAtMillis;
        private volatile boolean committed;

        private AcceptedWrite(UUID uuid, long acceptedAtMillis) {
            this.uuid = uuid;
            this.acceptedAtMillis = acceptedAtMillis;
        }

        UUID uuid() {
            return uuid;
        }

        long acceptedAtMillis() {
            return acceptedAtMillis;
        }
    }

    public EconomyWriteQueue(int capacity) {
        this(capacity, null);
    }

    public EconomyWriteQueue(int capacity, Logger logger) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.logger = logger;
        this.admissionPermits = new Semaphore(capacity);
    }

    /** A non-blocking slot held until the worker completes the accepted event. */
    final class Reservation implements AutoCloseable {
        private final long id;
        private final UUID uuid;
        private final AcceptedWrite acceptedWrite;
        private boolean committed;
        private boolean closed;

        private Reservation(long id, UUID uuid, AcceptedWrite acceptedWrite) {
            this.id = id;
            this.uuid = uuid;
            this.acceptedWrite = acceptedWrite;
        }

        synchronized boolean commit(EconomyEvent event) {
            return commit(event, null);
        }

        /**
         * Commit an event and transfer a resource lease to the consumer. The resource is
         * released only when the consumer completes the accepted event.
         */
        synchronized boolean commit(EconomyEvent event, AutoCloseable completionResource) {
            if (closed || committed || event == null || !uuid.equals(event.playerUuid())) {
                return false;
            }

            synchronized (reservationsByEvent) {
                if (reservationsByEvent.containsKey(event)) {
                    return false;
                }
                reservationsByEvent.put(event, id);
            }

            if (completionResource != null) {
                completionResources.put(id, completionResource);
            }
            acceptedWrite.committed = true;
            incrementCount(committedWriteCounts, uuid);

            boolean queued;
            try {
                queued = queue.offer(event);
            } catch (RuntimeException e) {
                removeEventReservation(event);
                completionResources.remove(id);
                releaseOwnership(id, true);
                closed = true;
                throw e;
            }

            if (!queued) {
                removeEventReservation(event);
                completionResources.remove(id);
                releaseOwnership(id, true);
                closed = true;
                return false;
            }

            committed = true;
            return true;
        }

        @Override
        public synchronized void close() {
            if (!committed && !closed) {
                closed = true;
                releaseOwnership(id, true);
            }
        }
    }

    /**
     * [SYNC-ECO-084] Offer economy event (non-blocking).
     * Added backpressure: rejects when queue usage > 70% (improved from 80%).
     * @return true if successfully offered, false if queue is over 70% capacity
     */
    public boolean offer(EconomyEvent event) {
        int currentSize = capacity - admissionPermits.availablePermits();
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

        Reservation reservation = tryReserve(event.playerUuid());
        if (reservation == null) {
            return false;
        }
        try (reservation) {
            boolean committed = reservation.commit(event);
            if (committed) {
                checkHighUsage();
            }
            return committed;
        }
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
        if (event == null || event.playerUuid() == null) {
            return false;
        }
        try {
            if (!admissionPermits.tryAcquire(timeout, unit)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        Reservation reservation;
        synchronized (admissionGate) {
            if (!acceptingWrites) {
                admissionPermits.release();
                return false;
            }
            reservation = registerReservation(event.playerUuid());
        }
        try (reservation) {
            boolean committed = reservation.commit(event);
            if (committed) {
                checkHighUsage();
            }
            return committed;
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
        int currentSize = capacity - admissionPermits.availablePermits();
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
            decrementPendingCount(event.playerUuid(), 1);
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
                if (event.playerUuid().equals(uuid) && !hasCompletionResource(event)) {
                    toRemove.add(event);
                }
            }

            int removed = 0;
            for (EconomyEvent event : toRemove) {
                if (removeQueuedIdentity(event)) {
                    removed++;
                    complete(event);
                }
            }

            decrementPendingCount(uuid, removed);

            return removed;
        }
    }

    /**
     * [SYNC-ECO-091] Get capacity.
     */
    public int getCapacity() {
        return capacity;
    }

    Reservation tryReserve(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        synchronized (admissionGate) {
            if (!acceptingWrites || !admissionPermits.tryAcquire()) {
                return null;
            }
            return registerReservation(uuid);
        }
    }

    void closeAdmission() {
        synchronized (admissionGate) {
            acceptingWrites = false;
        }
    }

    boolean hasOutstandingWrites() {
        return !acceptedWrites.isEmpty();
    }

    private Reservation registerReservation(UUID uuid) {
        long id = nextReservationId.incrementAndGet();
        AcceptedWrite acceptedWrite = new AcceptedWrite(uuid, System.currentTimeMillis());
        acceptedWrites.put(id, acceptedWrite);
        incrementCount(acceptedWriteCounts, uuid);
        incrementCount(pendingCounts, uuid);
        return new Reservation(id, uuid, acceptedWrite);
    }

    private void removeEventReservation(EconomyEvent event) {
        synchronized (reservationsByEvent) {
            reservationsByEvent.remove(event);
        }
    }

    private boolean removeQueuedIdentity(EconomyEvent event) {
        return queue.removeIf(queuedEvent -> queuedEvent == event);
    }

    private boolean hasCompletionResource(EconomyEvent event) {
        Long reservationId;
        synchronized (reservationsByEvent) {
            reservationId = reservationsByEvent.get(event);
        }
        return reservationId != null && completionResources.containsKey(reservationId);
    }

    void reportUsage() {
        checkHighUsage();
    }

    private void releaseOwnership(long reservationId, boolean releasePendingCount) {
        AcceptedWrite accepted = acceptedWrites.remove(reservationId);
        if (accepted == null) {
            return;
        }
        decrementCount(acceptedWriteCounts, accepted.uuid(), 1);
        if (accepted.committed) {
            decrementCount(committedWriteCounts, accepted.uuid(), 1);
        }
        if (releasePendingCount) {
            decrementPendingCount(accepted.uuid(), 1);
        }
        admissionPermits.release();
        AutoCloseable completionResource = completionResources.remove(reservationId);
        closeCompletionResource(completionResource);
    }

    private void closeCompletionResource(AutoCloseable completionResource) {
        if (completionResource != null) {
            try {
                completionResource.close();
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("Failed to release accepted-write completion resource: " + e.getMessage());
                }
            }
        }
    }

    private static void decrementCount(ConcurrentMap<UUID, AtomicInteger> counts, UUID uuid, int decrement) {
        if (decrement <= 0) {
            return;
        }
        counts.computeIfPresent(uuid, (ignored, count) ->
                count.addAndGet(-decrement) > 0 ? count : null);
    }

    private static void incrementCount(ConcurrentMap<UUID, AtomicInteger> counts, UUID uuid) {
        counts.compute(uuid, (ignored, count) -> {
            if (count == null) {
                return new AtomicInteger(1);
            }
            count.incrementAndGet();
            return count;
        });
    }

    private void decrementPendingCount(UUID uuid, int decrement) {
        decrementCount(pendingCounts, uuid, decrement);
    }

    void complete(EconomyEvent event) {
        complete(event, null, -1L);
    }

    void complete(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
        if (event == null) {
            return;
        }
        Long reservationId;
        synchronized (reservationsByEvent) {
            reservationId = reservationsByEvent.remove(event);
        }
        if (reservationId == null) {
            return;
        }
        if (removeQueuedIdentity(event)) {
            decrementPendingCount(event.playerUuid(), 1);
        }
        AutoCloseable completionResource = completionResources.remove(reservationId);
        releaseOwnership(reservationId, false);
        try {
            WriteCompletionHandler handler = writeCompletionHandler;
            if (handler != null) {
                try {
                    handler.onComplete(event, authorityBalance, authorityVersion);
                } catch (RuntimeException e) {
                    if (logger != null) {
                        logger.warning("Failed to reconcile completed write for " + event.playerUuid() + ": " + e.getMessage());
                    }
                }
            }
        } finally {
            closeCompletionResource(completionResource);
        }
    }

    void setWriteCompletionHandler(WriteCompletionHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Write completion handler cannot be null");
        }
        synchronized (this) {
            if (writeCompletionHandler != null && writeCompletionHandler != handler) {
                throw new IllegalStateException("EconomyWriteQueue already has a completion owner");
            }
            writeCompletionHandler = handler;
        }
    }

    boolean hasAcceptedWrite(UUID uuid) {
        AtomicInteger count = acceptedWriteCounts.get(uuid);
        return count != null && count.get() > 0;
    }

    boolean hasCommittedWrite(UUID uuid) {
        AtomicInteger count = committedWriteCounts.get(uuid);
        return count != null && count.get() > 0;
    }

    long oldestAcceptedAgeMillis() {
        long oldest = Long.MAX_VALUE;
        for (AcceptedWrite accepted : acceptedWrites.values()) {
            oldest = Math.min(oldest, accepted.acceptedAtMillis());
        }
        return oldest == Long.MAX_VALUE ? -1 : Math.max(0, System.currentTimeMillis() - oldest);
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

    /**
     * [SYNC-ECO-113] Emergency clear pending count for a player.
     * Used when transfer guard times out to prevent permanent player blocking.
     * This only clears the pending count tracking - actual events in queue will be
     * processed normally by the consumer thread.
     *
     * @param uuid Player UUID
     */
    public void emergencyClearPending(UUID uuid) {
        pendingCounts.remove(uuid);
    }

    /**
     * [SYNC-ECO-114] Clear pending tracking without removing queue events.
     * Used only when player transfer guard times out - tracking is reset because
     * the player may have disconnected and we cannot rely on tracking state.
     * Actual events remain in queue and will be processed normally by consumer.
     *
     * @param uuid Player UUID
     */
    public void clearPendingTracking(UUID uuid) {
        pendingCounts.remove(uuid);
    }

    /**
     * [SYNC-ECO-115] Drain ALL pending events for a player from the queue.
     * Returns the number of events actually removed.
     *
     * @param uuid Player UUID
     * @return Number of events drained
     */
    public int drainAllPendingForPlayer(UUID uuid) {
        return drainPendingForPlayer(uuid, Integer.MAX_VALUE);
    }
}
