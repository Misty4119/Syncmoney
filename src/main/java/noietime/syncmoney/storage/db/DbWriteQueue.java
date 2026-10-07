package noietime.syncmoney.storage.db;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.util.Constants;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * DB write queue.
 * Uses BlockingQueue for non-blocking task insertion.
 *
 * [AsyncScheduler] This class is thread-safe, suitable for multi-producer single-consumer pattern.
 */
public final class DbWriteQueue {

    private final BlockingQueue<DbWriteTask> queue;
    private final int capacity;

    private final java.util.concurrent.atomic.AtomicLong writtenCount = new java.util.concurrent.atomic.AtomicLong(0);

    public DbWriteQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Inserts DB write task (non-blocking).
     * [STORE-02 FIX] Added backpressure: rejects when queue usage > 80%.
     * @return true if successfully inserted
     */
    public boolean offer(DbWriteTask task) {

        int currentSize = queue.size();
        int usageThreshold = (int) (capacity * Constants.QUEUE_HIGH_USAGE_THRESHOLD);
        
        if (currentSize >= usageThreshold) {
            return false;
        }
        
        return queue.offer(task);
    }

    public boolean requeue(DbWriteTask task) {
        return queue.offer(task);
    }

    /**
     * Retrieves DB write task (blocks until task available or timeout).
     */
    public DbWriteTask poll() throws InterruptedException {
        return queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
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
    public record DbWriteTask(String operationId, List<AccountWrite> accounts) {
        public DbWriteTask {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId must not be blank");
            }
            accounts = List.copyOf(java.util.Objects.requireNonNull(accounts, "accounts"));
            if (accounts.isEmpty()) {
                throw new IllegalArgumentException("accounts must not be empty");
            }
        }

        public DbWriteTask(String operationId, ConfirmedAccount account, String playerName,
                String serverName, Instant timestamp) {
            this(operationId, List.of(new AccountWrite(account, playerName, serverName, timestamp)));
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
    }

    public record AccountWrite(ConfirmedAccount account, String playerName, String serverName,
            Instant timestamp) {
        public AccountWrite {
            java.util.Objects.requireNonNull(account, "account");
            java.util.Objects.requireNonNull(timestamp, "timestamp");
        }
    }
}
