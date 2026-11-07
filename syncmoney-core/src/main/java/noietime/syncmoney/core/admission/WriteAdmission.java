package noietime.syncmoney.core.admission;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns bounded write capacity until an accepted operation releases its lease. */
public final class WriteAdmission {

    private final int capacity;
    private final Semaphore permits;

    public WriteAdmission(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.permits = new Semaphore(capacity);
    }

    /** Attempts to reserve one slot without blocking. */
    public Lease tryAcquire() {
        return permits.tryAcquire() ? new Lease() : null;
    }

    /** Waits up to the given duration for one slot, returning null on timeout. */
    public Lease acquire(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return permits.tryAcquire(timeout, unit) ? new Lease() : null;
    }

    public int capacity() {
        return capacity;
    }

    public int availableCapacity() {
        return permits.availablePermits();
    }

    /** Releases its slot at most once. */
    public final class Lease implements AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease() { }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
