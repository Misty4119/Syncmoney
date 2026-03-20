package noietime.syncmoney.storage.db;

import noietime.syncmoney.util.Constants;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

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
     */
    private void processBatch(List<DbWriteQueue.DbWriteTask> tasks) {
        try {
            if (tasks.size() == 1) {
                processTask(tasks.get(0));
            } else {
                dbManager.batchInsertOrUpdatePlayers(tasks);
                queue.incrementWrittenCountBy(tasks.size());
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process batch to DB: " + e.getMessage());
            for (DbWriteQueue.DbWriteTask task : tasks) {
                try {
                    processTask(task);
                } catch (Exception ex) {
                    plugin.getLogger().severe("Failed to write player data to DB: " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Processes single write task.
     */
    private void processTask(DbWriteQueue.DbWriteTask task) {
        try {
            dbManager.insertOrUpdatePlayer(
                    task.playerUuid(),
                    task.playerName(),
                    task.balance(),
                    task.version(),
                    task.serverName());
            queue.incrementWrittenCount();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to write player data to DB: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "DB write task stacktrace", e);
        }
    }

    /**
     * Checks if running.
     */
    public boolean isRunning() {
        return running;
    }
}
