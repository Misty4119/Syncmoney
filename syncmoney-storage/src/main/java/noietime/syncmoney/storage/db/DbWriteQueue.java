package noietime.syncmoney.storage.db;

import noietime.syncmoney.core.port.ConfirmedAccount;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_RETAINED_OPERATION_IDS = 10_000;

    private final BlockingQueue<DbWriteTask> queue;
    private final int capacity;
    private final Semaphore admissionPermits;
    private final Object admissionGate = new Object();
    private final LinkedHashMap<String, OperationAdmission> operationAdmissions =
            new LinkedHashMap<>(16, 0.75f, true);

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
        return enqueueOrReuse(task).accepted();
    }

    /**
     * Offers a task and returns a completion receipt that resolves only after the SQL
     * writer has persisted it or exhausted its bounded retry path. A null result means
     * the task was not admitted.
     */
    public CompletableFuture<Boolean> offerAndTrack(DbWriteTask task) {
        return enqueueOrReuse(task).completion();
    }

    /**
     * Admit at most one outstanding or recently completed absolute snapshot per operation ID.
     * A retry while the writer is still working shares its completion future; a retry after a
     * confirmed write returns that receipt. A failed write may be admitted again with the same
     * operation ID and economic payload. Reuse of an ID for different account snapshots fails
     * closed and never falls through to the queue.
     */
    private Admission enqueueOrReuse(DbWriteTask task) {
        if (task == null) return new Admission(false, null);
        String fingerprint = fingerprint(task);
        synchronized (admissionGate) {
            OperationAdmission existing = operationAdmissions.get(task.operationId());
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    CompletableFuture<Boolean> collision = new CompletableFuture<>();
                    collision.completeExceptionally(new IllegalStateException(
                            "Database write operation ID was reused with a different account snapshot"));
                    relayCompletion(collision, task.completion());
                    return new Admission(false, collision);
                }
                CompletableFuture<Boolean> current = existing.completion();
                if (!current.isDone()) {
                    relayCompletion(current, task.completion());
                    return new Admission(true, current);
                }
                boolean persisted;
                try {
                    persisted = Boolean.TRUE.equals(current.getNow(false));
                } catch (java.util.concurrent.CompletionException failed) {
                    persisted = false;
                }
                if (persisted) {
                    relayCompletion(current, task.completion());
                    return new Admission(true, current);
                }
                operationAdmissions.remove(task.operationId());
            }

            Reservation reservation = tryReserve();
            if (reservation == null) return new Admission(false, null);

            CompletableFuture<Boolean> completion = new CompletableFuture<>();
            relayCompletion(completion, task.completion());
            try (reservation) {
                if (!reservation.commit(task.withCompletion(completion))) {
                    return new Admission(false, null);
                }
            }
            operationAdmissions.put(task.operationId(), new OperationAdmission(fingerprint, completion));
            trimCompletedOperationAdmissions();
            return new Admission(true, completion);
        }
    }

    private void trimCompletedOperationAdmissions() {
        if (operationAdmissions.size() <= MAX_RETAINED_OPERATION_IDS) return;
        Iterator<Map.Entry<String, OperationAdmission>> entries = operationAdmissions.entrySet().iterator();
        while (operationAdmissions.size() > MAX_RETAINED_OPERATION_IDS && entries.hasNext()) {
            Map.Entry<String, OperationAdmission> entry = entries.next();
            if (entry.getValue().completion().isDone()) entries.remove();
        }
    }

    private static String fingerprint(DbWriteTask task) {
        List<AccountWrite> accounts = new ArrayList<>(task.accounts());
        accounts.sort(Comparator.comparing(write -> write.account().playerUuid().toString()));
        StringBuilder canonical = new StringBuilder();
        for (AccountWrite write : accounts) {
            ConfirmedAccount account = write.account();
            appendField(canonical, account.playerUuid().toString());
            appendField(canonical, account.balance().stripTrailingZeros().toPlainString());
            appendField(canonical, Long.toString(account.version()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static void appendField(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value);
    }

    private static void relayCompletion(CompletableFuture<Boolean> source,
            CompletableFuture<Boolean> target) {
        if (target == null || target == source) return;
        source.whenComplete((persisted, failure) -> {
            if (failure == null) target.complete(persisted);
            else target.completeExceptionally(failure);
        });
    }

    private record OperationAdmission(String fingerprint, CompletableFuture<Boolean> completion) { }

    private record Admission(boolean accepted, CompletableFuture<Boolean> completion) { }

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
