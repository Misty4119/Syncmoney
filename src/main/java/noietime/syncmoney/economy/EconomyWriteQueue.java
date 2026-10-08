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
import java.util.function.Consumer;
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

    enum TransferOutcome {
        CONFIRMED,
        REJECTED,
        UNKNOWN,
        CONFIRMED_QUARANTINED
    }

    record TransferCompletion(TransferOutcome outcome, BigDecimal senderBalance,
            BigDecimal receiverBalance) { }

    @FunctionalInterface
    interface TransferCompletionListener {
        void onComplete(TransferCompletion completion);
    }

    @FunctionalInterface
    interface WriteCompletionHandler {
        void onComplete(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion);

        /** Run ownership release and reconciliation under the same account mutation lock. */
        default void withAccountsLocked(UUID firstAccount, UUID secondAccount, Runnable completion) {
            completion.run();
        }

        default void onRejected(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
            onComplete(event, authorityBalance, authorityVersion);
        }

        default void onCompletePair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
                EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
            onComplete(first, firstBalance, firstVersion);
            onComplete(second, secondBalance, secondVersion);
        }

        default void onRejectedPair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
                EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
            onCompletePair(first, firstBalance, firstVersion, second, secondBalance, secondVersion);
        }
    }

    interface TransferAvailabilityHandler {
        void markUnavailable(String operationId, UUID firstAccount, UUID secondAccount);
        void resolve(String operationId, UUID firstAccount, UUID secondAccount);
    }

    private final BlockingQueue<EconomyEvent> queue;
    private final int capacity;
    private final Logger logger;
    private final Semaphore admissionPermits;
    private final Object admissionGate = new Object();
    private final Object commitGate = new Object();
    private boolean acceptingWrites = true;
    private volatile boolean warnedHighUsage = false;


    private static final double BACKPRESSURE_THRESHOLD = 0.7;
    private static final long OFFER_TIMEOUT_MS = 100;
    private static final long HIGH_USAGE_CHECK_INTERVAL = 1000;

    private final ConcurrentMap<UUID, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicInteger> acceptedWriteCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicInteger> committedWriteCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AcceptedWrite> acceptedWrites = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Reservation> reservations = new ConcurrentHashMap<>();
    private final Map<EconomyEvent, Long> reservationsByEvent =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong nextReservationId = new AtomicLong();
    private final ConcurrentMap<Long, AutoCloseable> completionResources = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransferCompletionListener> transferCompletionListeners =
            new ConcurrentHashMap<>();
    private volatile WriteCompletionHandler writeCompletionHandler;
    private volatile TransferAvailabilityHandler transferAvailabilityHandler;
    private volatile Consumer<UUID> unavailableAccountHandler;
    private volatile long lastHighUsageWarning = 0;

    private static final class AcceptedWrite {
        private final UUID uuid;
        private final long acceptedAtMillis;
        private volatile boolean committed;
        private volatile EconomyEvent event;

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

        boolean commit(EconomyEvent event) {
            return commit(event, null);
        }

        /**
         * Commit an event and transfer a resource lease to the consumer. The resource is
         * released only when the consumer completes the accepted event.
         */
        boolean commit(EconomyEvent event, AutoCloseable completionResource) {
            synchronized (commitGate) {
                synchronized (this) {
                    return commitLocked(event, completionResource);
                }
            }
        }

        private boolean canCommit(EconomyEvent event) {
            return !closed && !committed && event != null && uuid.equals(event.playerUuid());
        }

        private boolean commitLocked(EconomyEvent event, AutoCloseable completionResource) {
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
            acceptedWrite.event = event;
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
            reservations.remove(id, this);
            return true;
        }

        @Override
        public void close() {
            synchronized (commitGate) {
                synchronized (this) {
                    if (!committed && !closed) {
                        closed = true;
                        reservations.remove(id, this);
                        releaseOwnership(id, true);
                    }
                }
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
            // A transfer commits both adjacent legs under this same gate. A consumer that
            // takes the first leg waits until the producer has finished committing the pair.
            synchronized (commitGate) {
                // The lock handoff is the visibility barrier for paired admissions.
            }
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

    /** Commit two reserved legs as one queue admission, with no unrelated commit between them. */
    boolean commitPair(Reservation firstReservation, EconomyEvent firstEvent,
            Reservation secondReservation, EconomyEvent secondEvent, AutoCloseable firstCompletionResource) {
        if (firstReservation == null || secondReservation == null
                || firstReservation == secondReservation
                || !TransferRequestIds.arePair(firstEvent, secondEvent)) {
            return false;
        }

        synchronized (commitGate) {
            synchronized (firstReservation) {
                synchronized (secondReservation) {
                    if (!firstReservation.canCommit(firstEvent) || !secondReservation.canCommit(secondEvent)) {
                        return false;
                    }
                    // Both permits were reserved before either account is changed. With the
                    // queue private and all producers serialized here, these offers cannot
                    // run out of capacity between transfer legs.
                    if (!firstReservation.commitLocked(firstEvent, firstCompletionResource)) {
                        return false;
                    }
                    return secondReservation.commitLocked(secondEvent, null);
                }
            }
        }
    }

    boolean registerTransferCompletionListener(String operationId, TransferCompletionListener listener) {
        if (operationId == null || operationId.isBlank() || listener == null) return false;
        return transferCompletionListeners.putIfAbsent(operationId, listener) == null;
    }

    void cancelTransferCompletionListener(String operationId) {
        if (operationId != null) transferCompletionListeners.remove(operationId);
    }

    void completeTransferObservation(String operationId, TransferOutcome outcome,
            BigDecimal senderBalance, BigDecimal receiverBalance) {
        if (operationId == null || outcome == null) return;
        TransferCompletionListener listener = transferCompletionListeners.remove(operationId);
        if (listener == null) return;
        try {
            listener.onComplete(new TransferCompletion(outcome, senderBalance, receiverBalance));
        } catch (RuntimeException e) {
            if (logger != null) {
                logger.warning("Transfer completion observer failed for " + operationId + ": " + e.getMessage());
            }
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

    void cancelUncommittedReservations() {
        synchronized (commitGate) {
            for (Reservation reservation : reservations.values()) {
                reservation.close();
            }
        }
    }

    List<EconomyEvent> snapshotOutstandingEvents() {
        List<EconomyEvent> events = new ArrayList<>();
        synchronized (commitGate) {
            // Paired commits and completions mutate both accepted-write owners under
            // this gate. Snapshot the same boundary so recovery can never journal one
            // half of a transfer while the primary worker releases the pair.
            for (AcceptedWrite accepted : acceptedWrites.values()) {
                EconomyEvent event = accepted.event;
                if (accepted.committed && event != null) {
                    events.add(event);
                }
            }
        }
        events.sort(java.util.Comparator
                .comparing((EconomyEvent event) -> event.playerUuid().toString())
                .thenComparingLong(EconomyEvent::version)
                .thenComparingLong(EconomyEvent::timestamp));
        return List.copyOf(events);
    }

    boolean quarantine(EconomyEvent event) {
        if (event == null) {
            return false;
        }
        Long reservationId;
        synchronized (reservationsByEvent) {
            reservationId = reservationsByEvent.remove(event);
        }
        if (reservationId == null) {
            return false;
        }
        if (removeQueuedIdentity(event)) {
            decrementPendingCount(event.playerUuid(), 1);
        }
        releaseOwnership(reservationId, false);
        return true;
    }

    private Reservation registerReservation(UUID uuid) {
        long id = nextReservationId.incrementAndGet();
        AcceptedWrite acceptedWrite = new AcceptedWrite(uuid, System.currentTimeMillis());
        acceptedWrites.put(id, acceptedWrite);
        incrementCount(acceptedWriteCounts, uuid);
        incrementCount(pendingCounts, uuid);
        Reservation reservation = new Reservation(id, uuid, acceptedWrite);
        reservations.put(id, reservation);
        return reservation;
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
        reservations.remove(reservationId);
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
        WriteCompletionHandler handler = writeCompletionHandler;
        withCompletionAccountLocks(handler, event.playerUuid(), null,
                () -> completeOwnedWrite(event, authorityBalance, authorityVersion, handler, false));
    }

    private boolean completeOwnedWrite(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion,
            WriteCompletionHandler handler, boolean rejected) {
        Long reservationId;
        synchronized (reservationsByEvent) {
            reservationId = reservationsByEvent.remove(event);
        }
        if (reservationId == null) {
            return false;
        }
        if (removeQueuedIdentity(event)) {
            decrementPendingCount(event.playerUuid(), 1);
        }
        AutoCloseable completionResource = completionResources.remove(reservationId);
        releaseOwnership(reservationId, false);
        try {
            if (handler != null) {
                try {
                    if (rejected) handler.onRejected(event, authorityBalance, authorityVersion);
                    else handler.onComplete(event, authorityBalance, authorityVersion);
                } catch (RuntimeException e) {
                    markAccountUnavailable(event.playerUuid());
                    if (logger != null) logger.warning("Failed to reconcile completed write for "
                            + event.playerUuid() + ": " + e.getMessage());
                }
            }
        } finally {
            closeCompletionResource(completionResource);
        }
        return true;
    }

    /** Complete a known-rejected write using its Redis authority snapshot as a rebase point. */
    boolean completeRejected(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
        if (event == null || !validAuthorityAccount(authorityBalance, authorityVersion, true)) {
            return false;
        }
        WriteCompletionHandler handler = writeCompletionHandler;
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        withCompletionAccountLocks(handler, event.playerUuid(), null,
                () -> completed.set(completeOwnedWrite(event, authorityBalance, authorityVersion, handler, true)));
        return completed.get();
    }

    /** Complete both accepted transfer legs as one authority reconciliation boundary. */
    boolean completePair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
        return completePair(first, firstBalance, firstVersion, second, secondBalance, secondVersion, false);
    }

    /** Complete a known-rejected transfer as one pair-wide authority rebase. */
    boolean completeRejectedPair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
        return completePair(first, firstBalance, firstVersion, second, secondBalance, secondVersion, true);
    }

    /** Reconcile a durable receipt recovered after its original queue ownership ended with the old process. */
    void reconcileRecovered(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion,
            boolean rejected) {
        if (event == null || !validAuthorityAccount(authorityBalance, authorityVersion, rejected)) {
            throw new IllegalArgumentException("Recovered account receipt is incomplete");
        }
        WriteCompletionHandler handler = writeCompletionHandler;
        if (handler == null) return;
        withCompletionAccountLocks(handler, event.playerUuid(), null, () -> {
            try {
                if (rejected) handler.onRejected(event, authorityBalance, authorityVersion);
                else handler.onComplete(event, authorityBalance, authorityVersion);
            } catch (RuntimeException failure) {
                markAccountUnavailable(event.playerUuid());
                throw failure;
            }
        });
    }

    /** Reconcile a paired receipt after restart without inventing queue reservations for either transfer leg. */
    void reconcileRecoveredPair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion, boolean rejected) {
        if (!TransferRequestIds.arePair(first, second)
                || !validAuthorityAccount(firstBalance, firstVersion, rejected)
                || !validAuthorityAccount(secondBalance, secondVersion, rejected)) {
            throw new IllegalArgumentException("Recovered transfer receipt is incomplete");
        }
        String operationId = TransferRequestIds.operationId(first.type() == EconomyEvent.EventType.TRANSFER_OUT
                ? first : second);
        WriteCompletionHandler handler = writeCompletionHandler;
        if (handler != null) {
            withCompletionAccountLocks(handler, first.playerUuid(), second.playerUuid(), () -> {
                try {
                    if (rejected) {
                        handler.onRejectedPair(first, firstBalance, firstVersion,
                                second, secondBalance, secondVersion);
                    } else {
                        handler.onCompletePair(first, firstBalance, firstVersion,
                                second, secondBalance, secondVersion);
                    }
                } catch (RuntimeException failure) {
                    markAccountUnavailable(first.playerUuid());
                    markAccountUnavailable(second.playerUuid());
                    throw failure;
                }
            });
        }
        resolveTransferUnavailable(operationId, first.playerUuid(), second.playerUuid());
    }

    private static boolean validAuthorityAccount(BigDecimal balance, long version, boolean allowAbsent) {
        return balance != null ? version >= 0L : allowAbsent && version == -1L;
    }

    private boolean completePair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion, boolean rejected) {
        if (!TransferRequestIds.arePair(first, second)
                || !validAuthorityAccount(firstBalance, firstVersion, rejected)
                || !validAuthorityAccount(secondBalance, secondVersion, rejected)) {
            return false;
        }
        EconomyEvent out = first.type() == EconomyEvent.EventType.TRANSFER_OUT ? first : second;
        String operationId = TransferRequestIds.operationId(out);
        BigDecimal senderBalance = out == first ? firstBalance : secondBalance;
        BigDecimal receiverBalance = out == first ? secondBalance : firstBalance;
        WriteCompletionHandler handler = writeCompletionHandler;
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        withCompletionAccountLocks(handler, first.playerUuid(), second.playerUuid(), () -> {
            Long firstReservationId;
            Long secondReservationId;
            AutoCloseable firstResource;
            AutoCloseable secondResource;
            synchronized (commitGate) {
                synchronized (reservationsByEvent) {
                    firstReservationId = reservationsByEvent.get(first);
                    secondReservationId = reservationsByEvent.get(second);
                    if (firstReservationId == null || secondReservationId == null
                            || firstReservationId.equals(secondReservationId)) {
                        return;
                    }
                    reservationsByEvent.remove(first);
                    reservationsByEvent.remove(second);
                }

                boolean firstRemoved = removeQueuedIdentity(first);
                boolean secondRemoved = removeQueuedIdentity(second);
                if (firstRemoved) decrementPendingCount(first.playerUuid(), 1);
                if (secondRemoved) decrementPendingCount(second.playerUuid(), 1);

                firstResource = completionResources.remove(firstReservationId);
                secondResource = completionResources.remove(secondReservationId);
                releaseOwnership(firstReservationId, false);
                releaseOwnership(secondReservationId, false);
            }

            if (rejected) {
                cancelUncommittedReservations(first.playerUuid(), second.playerUuid());
            }

            try {
                if (handler != null) {
                    if (rejected) {
                        handler.onRejectedPair(first, firstBalance, firstVersion,
                                second, secondBalance, secondVersion);
                    } else {
                        handler.onCompletePair(first, firstBalance, firstVersion,
                                second, secondBalance, secondVersion);
                    }
                }
                resolveTransferUnavailable(operationId, first.playerUuid(), second.playerUuid());
                completeTransferObservation(operationId,
                        rejected ? TransferOutcome.REJECTED : TransferOutcome.CONFIRMED,
                        senderBalance, receiverBalance);
                completed.set(true);
            } catch (RuntimeException e) {
                markAccountUnavailable(first.playerUuid());
                markAccountUnavailable(second.playerUuid());
                completeTransferObservation(operationId,
                        rejected ? TransferOutcome.REJECTED : TransferOutcome.CONFIRMED_QUARANTINED,
                        senderBalance, receiverBalance);
                if (logger != null) {
                    logger.warning("Failed to reconcile completed transfer pair "
                            + TransferRequestIds.operationId(out) + ": " + e.getMessage());
                }
            } finally {
                closeCompletionResource(firstResource);
                closeCompletionResource(secondResource);
            }
        });
        return completed.get();
    }

    /**
     * Known authority rejection invalidates producers that reserved capacity but have not
     * committed an event. Such reservations have not crossed the accepted-write boundary and
     * cannot safely continue against the rejected provisional account state.
     */
    private void cancelUncommittedReservations(UUID firstAccount, UUID secondAccount) {
        synchronized (commitGate) {
            for (Reservation reservation : List.copyOf(reservations.values())) {
                if (reservation.uuid.equals(firstAccount)
                        || reservation.uuid.equals(secondAccount)) {
                    reservation.close();
                }
            }
        }
    }

    private void withCompletionAccountLocks(WriteCompletionHandler handler, UUID firstAccount,
            UUID secondAccount, Runnable completion) {
        if (handler == null) {
            completion.run();
        } else {
            handler.withAccountsLocked(firstAccount, secondAccount, completion);
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

    void setUnavailableAccountHandler(Consumer<UUID> handler) {
        if (handler == null) throw new IllegalArgumentException("Unavailable-account handler cannot be null");
        synchronized (this) {
            if (unavailableAccountHandler != null && unavailableAccountHandler != handler) {
                throw new IllegalStateException("EconomyWriteQueue already has an unavailable-account owner");
            }
            unavailableAccountHandler = handler;
        }
    }

    void setTransferAvailabilityHandler(TransferAvailabilityHandler handler) {
        if (handler == null) throw new IllegalArgumentException("Transfer availability handler cannot be null");
        synchronized (this) {
            if (transferAvailabilityHandler != null && transferAvailabilityHandler != handler) {
                throw new IllegalStateException("EconomyWriteQueue already has a transfer availability owner");
            }
            transferAvailabilityHandler = handler;
        }
    }

    void markAccountUnavailable(UUID uuid) {
        Consumer<UUID> handler = unavailableAccountHandler;
        if (handler != null && uuid != null) handler.accept(uuid);
    }

    void markTransferUnavailable(String operationId, UUID firstAccount, UUID secondAccount) {
        TransferAvailabilityHandler handler = transferAvailabilityHandler;
        if (handler == null) {
            markAccountUnavailable(firstAccount);
            markAccountUnavailable(secondAccount);
            return;
        }
        handler.markUnavailable(operationId, firstAccount, secondAccount);
    }

    private void resolveTransferUnavailable(String operationId, UUID firstAccount, UUID secondAccount) {
        TransferAvailabilityHandler handler = transferAvailabilityHandler;
        if (handler != null) handler.resolve(operationId, firstAccount, secondAccount);
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
