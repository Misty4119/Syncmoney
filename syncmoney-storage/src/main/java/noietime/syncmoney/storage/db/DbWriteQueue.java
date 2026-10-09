package noietime.syncmoney.storage.db;

import noietime.syncmoney.core.port.ConfirmedAccount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * DB write queue.
 * Uses BlockingQueue for non-blocking task insertion.
 *
 * [AsyncScheduler] This class is thread-safe, suitable for multi-producer single-consumer pattern.
 */
public final class DbWriteQueue {

    private static final double HIGH_USAGE_THRESHOLD = 0.8;

    private final BlockingQueue<DbWriteTask> queue;
    private final int capacity;
    private final Semaphore admissionPermits;
    private final Object admissionGate = new Object();

    private final java.util.concurrent.atomic.AtomicLong writtenCount = new java.util.concurrent.atomic.AtomicLong(0);

    public DbWriteQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.admissionPermits = new Semaphore(capacity);
    }

    /** A bounded queue slot reserved before an authority mutation is attempted. */
    public final class Reservation implements AutoCloseable {
        private boolean committed;
        private boolean closed;

        private Reservation() { }

        public boolean commit(DbWriteTask task) {
            if (task == null) return false;
            synchronized (admissionGate) {
                if (closed || committed) return false;
                if (!queue.offer(task)) {
                    closed = true;
                    admissionPermits.release();
                    return false;
                }
                committed = true;
                return true;
            }
        }

        @Override
        public void close() {
            synchronized (admissionGate) {
                if (!closed && !committed) {
                    closed = true;
                    admissionPermits.release();
                }
            }
        }
    }

    /** Reserve one pair-capable task slot without blocking. */
    public Reservation tryReserve() {
        synchronized (admissionGate) {
            int highWatermark = (int) (capacity * HIGH_USAGE_THRESHOLD);
            int occupied = capacity - admissionPermits.availablePermits();
            if (occupied >= highWatermark || !admissionPermits.tryAcquire()) {
                return null;
            }
            return new Reservation();
        }
    }

    /**
     * Inserts DB write task (non-blocking).
     * [STORE-02 FIX] Added backpressure: rejects when queue usage > 80%.
     * @return true if successfully inserted
     */
    public boolean offer(DbWriteTask task) {
        Reservation reservation = tryReserve();
        if (reservation == null) return false;
        try (reservation) {
            return reservation.commit(task);
        }
    }

    /**
     * Offers a task and returns a completion receipt that resolves only after the SQL
     * writer has persisted it or exhausted its bounded retry path. A null result means
     * the task was not admitted.
     */
    public CompletableFuture<Boolean> offerAndTrack(DbWriteTask task) {
        if (task == null) return null;
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        if (!offer(task.withCompletion(completion))) return null;
        return completion;
    }

    public boolean requeue(DbWriteTask task) {
        if (task == null) return false;
        synchronized (admissionGate) {
            if (!admissionPermits.tryAcquire()) return false;
            if (queue.offer(task)) return true;
            admissionPermits.release();
            return false;
        }
    }

    /**
     * Retrieves DB write task (blocks until task available or timeout).
     */
    public DbWriteTask poll() throws InterruptedException {
        DbWriteTask task = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
        if (task != null) admissionPermits.release();
        return task;
    }

    /**
     * Gets queue size.
     */
    public int size() {
        return queue.size();
    }

    /**
     * Gets capacity.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Checks if queue is empty.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Records write count (for monitoring purposes).
     */
    public void incrementWrittenCount() {
        writtenCount.incrementAndGet();
    }

    /**
     * Batch records write count.
     */
    public void incrementWrittenCountBy(int count) {
        writtenCount.addAndGet(count);
    }

    /**
     * Gets write count.
     */
    public long getWrittenCount() {
        return writtenCount.get();
    }

    /**
     * DB write task record.
     *
     * @param playerUuid player UUID
     * @param playerName player name (can be null)
     * @param balance balance
     * @param version version number
     * @param serverName server name
     * @param timestamp timestamp
     */
    public record DbWriteTask(String operationId, List<AccountWrite> accounts,
            CompletableFuture<Boolean> completion) {
        public DbWriteTask {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId must not be blank");
            }
            accounts = List.copyOf(java.util.Objects.requireNonNull(accounts, "accounts"));
            if (accounts.isEmpty()) {
                throw new IllegalArgumentException("accounts must not be empty");
            }
        }

        public DbWriteTask(String operationId, List<AccountWrite> accounts) {
            this(operationId, accounts, null);
        }

        public DbWriteTask(String operationId, ConfirmedAccount account, String playerName,
                String serverName, Instant timestamp) {
            this(operationId, List.of(new AccountWrite(account, playerName, serverName, timestamp)), null);
        }

        /** Compatibility constructor for non-receipt maintenance and test callers. */
        public DbWriteTask(UUID playerUuid, String playerName, double balance, long version,
                String serverName, Instant timestamp) {
            this("legacy-" + playerUuid + "-" + version,
                    new ConfirmedAccount(playerUuid, BigDecimal.valueOf(balance), version),
                    playerName, serverName, timestamp);
        }

        /** Compatibility accessors for consumers that handle a single-account task. */
        public ConfirmedAccount account() {
            return accounts.get(0).account();
        }

        public String playerName() {
            return accounts.get(0).playerName();
        }

        public String serverName() {
            return accounts.get(0).serverName();
        }

        public Instant timestamp() {
            return accounts.get(0).timestamp();
        }

        public UUID playerUuid() {
            return account().playerUuid();
        }

        public double balance() {
            return account().balance().doubleValue();
        }

        public long version() {
            return account().version();
        }

        public int accountCount() {
            return accounts.size();
        }

        public DbWriteTask withCompletion(CompletableFuture<Boolean> trackedCompletion) {
            return new DbWriteTask(operationId, accounts, java.util.Objects.requireNonNull(trackedCompletion));
        }

        public void complete(boolean persisted) {
            if (completion != null) completion.complete(persisted);
        }
    }

    public record AccountWrite(ConfirmedAccount account, String playerName, String serverName,
            Instant timestamp) {
        public AccountWrite {
            java.util.Objects.requireNonNull(account, "account");
            java.util.Objects.requireNonNull(timestamp, "timestamp");
        }
    }
}
