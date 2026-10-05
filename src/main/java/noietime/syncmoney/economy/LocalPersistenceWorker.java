package noietime.syncmoney.economy;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
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

    private static final long POLL_MILLIS = 100;
    private static final long BASE_RETRY_MILLIS = 100;
    private static final long MAX_RETRY_MILLIS = 2_000;
    private static final long SHUTDOWN_WARNING_MILLIS = 10_000;

    private final EconomyWriteQueue queue;
    private final PersistenceAction persistenceAction;
    private final Logger logger;
    private volatile boolean running;
    private volatile Thread workerThread;

    LocalPersistenceWorker(EconomyWriteQueue queue, PersistenceAction persistenceAction, Logger logger) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.persistenceAction = Objects.requireNonNull(persistenceAction, "persistenceAction");
        this.logger = logger;
    }

    synchronized void start() {
        if (workerThread != null) {
            return;
        }
        running = true;
        workerThread = new Thread(this, "Syncmoney-LocalPersistence");
        workerThread.setDaemon(true);
        workerThread.start();
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
            return;
        }
        boolean interrupted = false;
        long nextWarningAt = System.currentTimeMillis() + SHUTDOWN_WARNING_MILLIS;
        while (thread.isAlive()) {
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (thread.isAlive() && System.currentTimeMillis() >= nextWarningAt) {
                log("LOCAL persistence drain is still pending; shutdown will keep retrying accepted writes");
                nextWarningAt = System.currentTimeMillis() + SHUTDOWN_WARNING_MILLIS;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        EconomyEvent retryEvent = null;
        int retryCount = 0;
        long retryAtMillis = 0;

        while (running || retryEvent != null || !queue.isEmpty() || queue.hasOutstandingWrites()) {
            try {
                if (retryEvent == null) {
                    retryEvent = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    retryCount = 0;
                    if (retryEvent == null) {
                        continue;
                    }
                }

                long delay = retryAtMillis - System.currentTimeMillis();
                if (delay > 0) {
                    Thread.sleep(Math.min(delay, POLL_MILLIS));
                    continue;
                }

                LocalPersistenceReceipt receipt = persistenceAction.persist(retryEvent);
                queue.complete(retryEvent, receipt.balance(), receipt.version());
                retryEvent = null;
                retryCount = 0;
                retryAtMillis = 0;
            } catch (InterruptedException e) {
                // A wake-up must not discard a retry or any accepted queue entries.
            } catch (Exception e) {
                retryCount++;
                long retryDelay = Math.min(BASE_RETRY_MILLIS << Math.min(retryCount - 1, 10), MAX_RETRY_MILLIS);
                retryAtMillis = System.currentTimeMillis() + retryDelay;
                if (retryCount == 1 || retryCount % 10 == 0) {
                    log("LOCAL SQLite write remains accepted and will retry (attempt " + retryCount + "): "
                            + e.getMessage());
                }
            }
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
