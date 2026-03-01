package noietime.syncmoney.storage.db;

import java.time.Instant;
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
     * @return true if successfully inserted
     */
    public boolean offer(DbWriteTask task) {
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
    public record DbWriteTask(
            UUID playerUuid,
            String playerName,
            double balance,
            long version,
            String serverName,
            Instant timestamp
    ) {}
}
