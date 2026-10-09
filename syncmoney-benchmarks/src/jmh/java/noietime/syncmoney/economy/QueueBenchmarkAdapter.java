package noietime.syncmoney.economy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Package-local bridge to queue completion methods for isolated JMH measurements. */
public final class QueueBenchmarkAdapter implements AutoCloseable {
    private static final BigDecimal DELTA = BigDecimal.ONE.setScale(2);
    private final EconomyWriteQueue queue;

    public QueueBenchmarkAdapter(int capacity) {
        queue = new EconomyWriteQueue(capacity, Logger.getLogger("SyncmoneyQueueBenchmark"));
    }

    public boolean offerPollComplete(EconomyEvent event) {
        if (!queue.offer(event)) return false;
        try {
            EconomyEvent accepted = queue.poll(0L, TimeUnit.MILLISECONDS);
            if (accepted == null) throw new IllegalStateException("Accepted queue event could not be dequeued");
            queue.complete(accepted);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during queue benchmark", interrupted);
        }
    }

    public int primeToBackpressure(UUID account) {
        int accepted = 0;
        for (;;) {
            EconomyEvent event = event(account, accepted);
            if (!queue.offer(event)) return accepted;
            accepted++;
        }
    }

    public int primeToCapacity(UUID account) {
        int accepted = 0;
        for (;;) {
            EconomyEvent event = event(account, accepted);
            if (!queue.offerBlocking(event, 0L, TimeUnit.NANOSECONDS)) return accepted;
            accepted++;
        }
    }

    public boolean attemptOffer(EconomyEvent event) {
        if (!queue.offer(event)) return false;
        try {
            EconomyEvent accepted = queue.poll(0L, TimeUnit.MILLISECONDS);
            if (accepted == null) throw new IllegalStateException("Accepted full-queue probe could not be dequeued");
            queue.complete(accepted);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during queue-full benchmark", interrupted);
        }
    }

    public boolean attemptBlockingOffer(EconomyEvent event) {
        if (!queue.offerBlocking(event, 0L, TimeUnit.NANOSECONDS)) return false;
        try {
            EconomyEvent accepted = queue.poll(0L, TimeUnit.MILLISECONDS);
            if (accepted == null) throw new IllegalStateException("Accepted full-capacity probe could not be dequeued");
            queue.complete(accepted);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during queue-capacity benchmark", interrupted);
        }
    }

    public int size() {
        return queue.size();
    }

    public int drain() {
        int drained = 0;
        try {
            EconomyEvent event;
            while ((event = queue.poll(0L, TimeUnit.MILLISECONDS)) != null) {
                queue.complete(event);
                drained++;
            }
            return drained;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining benchmark queue", interrupted);
        }
    }

    private static EconomyEvent event(UUID account, long sequence) {
        return new EconomyEvent(account, DELTA, DELTA, sequence + 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "queue-bench-" + sequence, System.currentTimeMillis(), "benchmark");
    }

    @Override
    public void close() {
        if (queue.size() != 0) drain();
    }
}
