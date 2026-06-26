package noietime.syncmoney.storage.db;

import noietime.syncmoney.util.Constants;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB write consumer.
 * Driven by AsyncScheduler, single consumer thread, writes to DB sequentially.
 * Supports batch writes for improved performance.
 *
 * [AsyncScheduler] This thread is driven by Folia AsyncScheduler.
 */
public final class DbWriterConsumer implements Runnable {

    private final DbWriteQueue queue;
    private final DatabaseManager dbManager;
    private final Plugin plugin;
    private volatile boolean running = true;
    private final boolean debug;

    private final Map<DbWriteQueue.DbWriteTask, Integer> retryCounts = new IdentityHashMap<>();

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().fine(message);
        }
    }

    public DbWriterConsumer(Plugin plugin, DbWriteQueue queue, DatabaseManager dbManager) {
        this.plugin = plugin;
        this.queue = queue;
        this.dbManager = dbManager;
        this.debug = false;
    }

    /**
     * Stops consumer (graceful shutdown).
     * Actually stops after processing remaining tasks.
     */
    public void stop() {
        running = false;
        debug("DB Writer Consumer shutting down...");
    }

    @Override
    public void run() {
        debug("DB Writer Consumer started.");

        while (running || !queue.isEmpty()) {
            try {
                List<DbWriteQueue.DbWriteTask> batch = collectBatch();

                if (!batch.isEmpty()) {
                    processBatch(batch);
                } else {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                plugin.getLogger().severe("Error in DB Writer Consumer: " + e.getMessage());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "DB Writer Consumer stacktrace", e);
            }
        }

        plugin.getLogger().fine("DB Writer Consumer stopped.");
    }

    /**
     * Collects batch tasks.
     */
    private List<DbWriteQueue.DbWriteTask> collectBatch() {
        List<DbWriteQueue.DbWriteTask> batch = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        while (batch.size() < Constants.BATCH_SIZE &&
                (System.currentTimeMillis() - startTime) < Constants.BATCH_TIMEOUT_MS) {
            try {
                DbWriteQueue.DbWriteTask task = queue.poll();
                if (task == null) {
                    break;
                }
                batch.add(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return batch;
    }

    /**
     * Processes batch write tasks.
     * On failure each task is re-queued for retry (bounded by
     * {@link Constants#DB_WRITE_MAX_RETRIES}) so transient DB outages do not lose data.
     */
    private void processBatch(List<DbWriteQueue.DbWriteTask> tasks) {
        try {
            dbManager.batchInsertOrUpdatePlayers(tasks);
            queue.incrementWrittenCountBy(tasks.size());
            for (DbWriteQueue.DbWriteTask task : tasks) {
                retryCounts.remove(task);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process batch to DB (" + tasks.size()
                    + " tasks): " + e.getMessage());
            for (DbWriteQueue.DbWriteTask task : tasks) {
                scheduleRetry(task);
            }
        }
    }

    private void scheduleRetry(DbWriteQueue.DbWriteTask task) {
        int attempts = retryCounts.merge(task, 1, (a, b) -> a + b);

        if (attempts > Constants.DB_WRITE_MAX_RETRIES) {
            retryCounts.remove(task);
            plugin.getLogger().severe("Dropping DB write task after " + (attempts - 1)
                    + " failed retries for player " + task.playerUuid()
                    + " (balance=" + task.balance() + ", version=" + task.version() + ")");
            return;
        }

        if (queue.requeue(task)) {
            plugin.getLogger().warning("Re-queued failed DB write task (attempt " + attempts
                    + "/" + Constants.DB_WRITE_MAX_RETRIES + ") for player " + task.playerUuid());
        } else {
            plugin.getLogger().severe("DB write queue is full; could not re-queue task for player "
                    + task.playerUuid() + " - it will be retried on the next failure cycle");
        }
    }

    /**
     * Checks if running.
     */
    public boolean isRunning() {
        return running;
    }
}
