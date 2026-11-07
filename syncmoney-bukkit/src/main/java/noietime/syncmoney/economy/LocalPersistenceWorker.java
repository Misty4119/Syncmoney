package noietime.syncmoney.economy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Single owner for accepted LOCAL SQLite writes. The bounded EconomyWriteQueue is
 * both admission control and the worker's pending-work store.
 */
final class LocalPersistenceWorker implements Runnable {

    @FunctionalInterface
    interface PersistenceAction {
        LocalPersistenceReceipt persist(EconomyEvent event) throws Exception;
    }

    @FunctionalInterface
    interface TransferPersistenceAction {
        LocalTransferPersistenceReceipt persist(EconomyEvent out, EconomyEvent in) throws Exception;
    }

    @FunctionalInterface
    interface QuarantineAction {
        void quarantine(List<EconomyEvent> events) throws Exception;
    }

    private static final long POLL_MILLIS = 100;
    private static final long BASE_RETRY_MILLIS = 100;
    private static final long MAX_RETRY_MILLIS = 2_000;
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MILLIS = 10_000;

    private final EconomyWriteQueue queue;
    private final PersistenceAction persistenceAction;
    private final TransferPersistenceAction transferPersistenceAction;
    private final QuarantineAction quarantineAction;
    private final Logger logger;
    private final long shutdownDrainTimeoutMillis;
    private final Consumer<Thread> workerThreadRegistration;
    private final Runnable workerStopped;
    private final Consumer<Boolean> workerFailed;
    private final Runnable recoveryHandoffCompleted;
    private final CountDownLatch recoverySignal = new CountDownLatch(1);
    private final AtomicBoolean recoveryRequested = new AtomicBoolean();
    private volatile boolean running;
    private volatile boolean terminalAfterQuarantine;
    private volatile Thread workerThread;
    private volatile Thread recoveryThread;

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction, Logger logger) {
        this(queue, persistenceAction, events -> {
            throw new IllegalStateException("No durable LOCAL quarantine owner is configured");
        }, logger, SHUTDOWN_DRAIN_TIMEOUT_MILLIS, ignored -> { }, () -> { });
    }

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction,
            QuarantineAction quarantineAction, Logger logger) {
        this(queue, persistenceAction, quarantineAction, logger,
                SHUTDOWN_DRAIN_TIMEOUT_MILLIS, ignored -> { }, () -> { });
    }

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction,
            QuarantineAction quarantineAction, Logger logger, long shutdownDrainTimeoutMillis) {
        this(queue, persistenceAction, quarantineAction, logger, shutdownDrainTimeoutMillis,
                ignored -> { }, () -> { });
    }

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction,
            QuarantineAction quarantineAction, Logger logger, long shutdownDrainTimeoutMillis,
            Consumer<Thread> workerThreadRegistration, Runnable workerStopped) {
        this(queue, persistenceAction, quarantineAction, logger, shutdownDrainTimeoutMillis,
                workerThreadRegistration, workerStopped, ignored -> { }, () -> { });
    }

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction,
            QuarantineAction quarantineAction, Logger logger, long shutdownDrainTimeoutMillis,
            Consumer<Thread> workerThreadRegistration, Runnable workerStopped,
            Consumer<Boolean> workerFailed, Runnable recoveryHandoffCompleted) {
        this(queue, persistenceAction, null, quarantineAction, logger, shutdownDrainTimeoutMillis,
                workerThreadRegistration, workerStopped, workerFailed, recoveryHandoffCompleted);
    }

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction,
            TransferPersistenceAction transferPersistenceAction, QuarantineAction quarantineAction,
            Logger logger) {
        this(queue, persistenceAction, transferPersistenceAction, quarantineAction, logger,
                SHUTDOWN_DRAIN_TIMEOUT_MILLIS, ignored -> { }, () -> { }, ignored -> { }, () -> { });
    }

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction,
            TransferPersistenceAction transferPersistenceAction, QuarantineAction quarantineAction,
            Logger logger, long shutdownDrainTimeoutMillis, Consumer<Thread> workerThreadRegistration,
            Runnable workerStopped, Consumer<Boolean> workerFailed, Runnable recoveryHandoffCompleted) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.persistenceAction = Objects.requireNonNull(persistenceAction, "persistenceAction");
        this.transferPersistenceAction = transferPersistenceAction;
        this.quarantineAction = Objects.requireNonNull(quarantineAction, "quarantineAction");
        this.logger = logger;
        this.shutdownDrainTimeoutMillis = Math.max(0L, shutdownDrainTimeoutMillis);
        this.workerThreadRegistration = Objects.requireNonNull(workerThreadRegistration, "workerThreadRegistration");
        this.workerStopped = Objects.requireNonNull(workerStopped, "workerStopped");
        this.workerFailed = Objects.requireNonNull(workerFailed, "workerFailed");
        this.recoveryHandoffCompleted = Objects.requireNonNull(recoveryHandoffCompleted, "recoveryHandoffCompleted");
    }

    synchronized void start() {
        if (workerThread != null) {
            return;
        }
        running = true;
        Thread handoff = new Thread(this::awaitRecoveryRequest, "Syncmoney-LocalRecoveryJournal");
        // Start the non-daemon recovery owner before accepting writes. A later shutdown
        // timeout only signals this actor, so it never needs to create a thread or do I/O
        // on the Paper shutdown callback.
        handoff.setDaemon(false);
        recoveryThread = handoff;
        try {
            handoff.start();
        } catch (RuntimeException | Error e) {
            recoveryThread = null;
            running = false;
            queue.closeAdmission();
            if (queue.hasOutstandingWrites()) {
                workerFailed.accept(true);
            }
            throw e;
        }

        workerThread = new Thread(this, "Syncmoney-LocalPersistence");
        // The worker may be stuck in an uninterruptible SQLite call after a durable journal
        // handoff. The recovery actor owns JVM liveness while the event is not durable.
        workerThread.setDaemon(true);
        try {
            workerThreadRegistration.accept(workerThread);
            workerThread.start();
        } catch (RuntimeException | Error e) {
            running = false;
            queue.closeAdmission();
            boolean outstandingWrites = queue.hasOutstandingWrites();
            workerFailed.accept(outstandingWrites);
            if (queue.hasOutstandingWrites()) {
                requestRecoveryHandoff();
            } else {
                stopRecoveryActor();
            }
            if (!workerThread.isAlive()) {
                workerStopped.run();
            }
            throw e;
        }
    }

    void shutdown() {
        queue.closeAdmission();
        Thread thread = workerThread;
        if (thread == null && queue.hasOutstandingWrites()) {
            // Initialization may have admitted a write before the worker was started.
            // Once admission is closed, this worker can safely own the final drain.
            start();
            thread = workerThread;
        }
        running = false;
        if (thread == null || thread == Thread.currentThread()) {
            if (queue.hasOutstandingWrites()) {
                requestRecoveryHandoff();
            } else {
                stopRecoveryActor();
            }
            return;
        }

        boolean interrupted = false;
        long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownDrainTimeoutMillis);
        while (thread.isAlive()) {
            long remainingNanos = drainDeadline - System.nanoTime();
            if (remainingNanos > 0) {
                try {
                    long joinMillis = Math.max(1L, Math.min(POLL_MILLIS,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    thread.join(joinMillis);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                continue;
            }

            queue.cancelUncommittedReservations();
            if (!queue.hasOutstandingWrites()) {
                terminalAfterQuarantine = true;
                thread.interrupt();
                break;
            }

            requestRecoveryHandoff();
            log("LOCAL shutdown deadline expired; accepted writes remain owned while durable journal handoff runs "
                    + "outside the shutdown callback");
            break;
        }
        if (!thread.isAlive() && queue.hasOutstandingWrites() && !terminalAfterQuarantine) {
            requestRecoveryHandoff();
        } else if (!queue.hasOutstandingWrites()) {
            stopRecoveryActor();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    Thread workerThread() {
        return workerThread;
    }

    Thread recoveryThread() {
        return recoveryThread;
    }

    private void handoffAfterWorkerStoppedUnexpectedly() {
        logSevere("LOCAL persistence worker stopped with accepted writes still outstanding; starting durable journal "
                + "handoff");
        requestRecoveryHandoff();
    }

    private void requestRecoveryHandoff() {
        Thread thread = recoveryThread;
        if (thread == null || !thread.isAlive()) {
            logSevere("LOCAL recovery actor is unavailable; LOCAL admission remains closed and accepted-write "
                    + "ownership is retained without blocking the shutdown caller");
            return;
        }
        recoveryRequested.set(true);
        recoverySignal.countDown();
    }

    private void stopRecoveryActor() {
        recoverySignal.countDown();
    }

    private void awaitRecoveryRequest() {
        boolean interrupted = false;
        while (true) {
            try {
                recoverySignal.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (recoveryRequested.get()) {
            quarantineOutstandingWrites();
            if (!queue.hasOutstandingWrites()) {
                recoveryHandoffCompleted.run();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void quarantineOutstandingWrites() {
        int attempts = 0;
        long retryDelayMillis = BASE_RETRY_MILLIS;
        long reservationDeadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(shutdownDrainTimeoutMillis);
        boolean uncommittedReservationsCancelled = false;
        while (queue.hasOutstandingWrites()) {
            List<EconomyEvent> unresolved = queue.snapshotOutstandingEvents();
            if (unresolved.isEmpty()) {
                long remainingNanos = reservationDeadline - System.nanoTime();
                if (!uncommittedReservationsCancelled && remainingNanos <= 0) {
                    queue.cancelUncommittedReservations();
                    uncommittedReservationsCancelled = true;
                    continue;
                }
                try {
                    long waitMillis = remainingNanos <= 0 ? POLL_MILLIS
                            : Math.max(1L, Math.min(POLL_MILLIS,
                                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    Thread.sleep(waitMillis);
                } catch (InterruptedException ignored) {
                    // A pending reservation may still commit; keep ownership until it resolves.
                }
                continue;
            }
            try {
                quarantineAction.quarantine(unresolved);
                terminalAfterQuarantine = true;
                for (EconomyEvent event : unresolved) {
                    queue.quarantine(event);
                }
                log("LOCAL accepted writes were durably journaled for idempotent startup recovery");
                Thread thread = workerThread;
                if (thread != null) {
                    thread.interrupt();
                }
                if (!queue.hasOutstandingWrites()) {
                    return;
                }
            } catch (Exception e) {
                attempts++;
                if (attempts == 1 || attempts % 10 == 0) {
                    logSevere("LOCAL durable journal handoff failed; accepted writes remain owned and SQLite stays "
                            + "open while persistence retries continue (attempt " + attempts + "): " + e.getMessage());
                }
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException ignored) {
                    // A wake-up only shortens retry delay; accepted work remains owned.
                }
                retryDelayMillis = Math.min(retryDelayMillis * 2, MAX_RETRY_MILLIS);
            }
        }
    }

    @Override
    public void run() {
        EconomyEvent retryEvent = null;
        EconomyEvent retryPartner = null;
        EconomyEvent deferredEvent = null;
        int retryCount = 0;
        long retryAtMillis = 0;

        try {
            while (!terminalAfterQuarantine
                    && (running || retryEvent != null || deferredEvent != null
                        || !queue.isEmpty() || queue.hasOutstandingWrites())) {
                try {
                    if (retryEvent == null) {
                        retryEvent = deferredEvent != null ? deferredEvent : queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                        deferredEvent = null;
                        retryCount = 0;
                        if (retryEvent == null) {
                            continue;
                        }
                    }

                    if (TransferRequestIds.isTransferLeg(retryEvent) && retryPartner == null) {
                        retryPartner = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                        if (retryPartner == null) {
                            continue;
                        }
                        if (!TransferRequestIds.arePair(retryEvent, retryPartner)) {
                            logSevere("LOCAL transfer queue lost a paired leg; marking "
                                    + retryEvent.playerUuid() + " unavailable");
                            queue.markAccountUnavailable(retryEvent.playerUuid());
                            queue.quarantine(retryEvent);
                            deferredEvent = retryPartner;
                            retryEvent = null;
                            retryPartner = null;
                            retryCount = 0;
                            continue;
                        }
                    }

                    long delay = retryAtMillis - System.currentTimeMillis();
                    if (delay > 0) {
                        Thread.sleep(Math.min(delay, POLL_MILLIS));
                        continue;
                    }

                    if (TransferRequestIds.isTransferLeg(retryEvent)) {
                        if (transferPersistenceAction == null) {
                            throw new IllegalStateException("LOCAL transfer persistence is not configured");
                        }
                        EconomyEvent out = retryEvent.type() == EconomyEvent.EventType.TRANSFER_OUT
                                ? retryEvent : retryPartner;
                        EconomyEvent in = out == retryEvent ? retryPartner : retryEvent;
                        LocalTransferPersistenceReceipt receipt = transferPersistenceAction.persist(out, in);
                        if (terminalAfterQuarantine) return;
                        queue.completePair(out, receipt.out().balance(), receipt.out().version(),
                                in, receipt.in().balance(), receipt.in().version());
                        retryEvent = null;
                        retryPartner = null;
                    } else {
                        LocalPersistenceReceipt receipt = persistenceAction.persist(retryEvent);
                        if (terminalAfterQuarantine) return;
                        queue.complete(retryEvent, receipt.balance(), receipt.version());
                        retryEvent = null;
                    }
                    retryCount = 0;
                    retryAtMillis = 0;
                } catch (InterruptedException e) {
                    // A wake-up must not discard a retry or any accepted queue entries.
                } catch (Exception e) {
                    if (terminalAfterQuarantine) {
                        return;
                    }
                    retryCount++;
                    long retryDelay = Math.min(BASE_RETRY_MILLIS << Math.min(retryCount - 1, 10),
                            MAX_RETRY_MILLIS);
                    retryAtMillis = System.currentTimeMillis() + retryDelay;
                    if (retryCount == 1 || retryCount % 10 == 0) {
                        log("LOCAL SQLite write remains accepted and will retry (attempt " + retryCount + "): "
                                + e.getMessage());
                    }
                }
            }
        } finally {
            boolean unexpectedExit = !terminalAfterQuarantine
                    && (running || retryEvent != null || retryPartner != null || queue.hasOutstandingWrites());
            if (unexpectedExit) {
                running = false;
                queue.closeAdmission();
                boolean outstandingWrites = queue.hasOutstandingWrites();
                try {
                    workerFailed.accept(outstandingWrites);
                } finally {
                    if (outstandingWrites) {
                        handoffAfterWorkerStoppedUnexpectedly();
                    }
                }
            }
            workerStopped.run();
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }

    private void logSevere(String message) {
        if (logger != null) {
            logger.severe(message);
        }
    }
}
