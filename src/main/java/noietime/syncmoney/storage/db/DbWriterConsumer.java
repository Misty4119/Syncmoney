package noietime.syncmoney.storage.db;

import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
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

    private static final long SHUTDOWN_DRAIN_TIMEOUT_MILLIS = 8_000L;

    private final DbWriteQueue queue;
    private final DatabaseManager dbManager;
    private final Plugin plugin;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private volatile boolean running = true;
    private volatile long shutdownDrainDeadlineNanos;
    private final boolean debug;

    private final Map<DbWriteQueue.DbWriteTask, Integer> retryCounts = new IdentityHashMap<>();
    private final Map<String, PendingStaleTransfer> pendingStaleTransfers = new java.util.HashMap<>();

    private record PendingStaleTransfer(DbWriteQueue.DbWriteTask task, int attempts,
            long nextAttemptAtMillis, String reason) { }

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().fine(message);
        }
    }

    public DbWriterConsumer(Plugin plugin, DbWriteQueue queue, DatabaseManager dbManager) {
        this(plugin, queue, dbManager, null, null);
    }

    public DbWriterConsumer(Plugin plugin, DbWriteQueue queue, DatabaseManager dbManager,
            CacheManager cacheManager, RedisManager redisManager) {
        this.plugin = plugin;
        this.queue = queue;
        this.dbManager = dbManager;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.debug = false;
    }

    /**
     * Stops consumer (graceful shutdown).
     * Actually stops after processing remaining tasks.
     */
    public void stop() {
        shutdownDrainDeadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_DRAIN_TIMEOUT_MILLIS);
        running = false;
        debug("DB Writer Consumer shutting down...");
    }

    @Override
    public void run() {
        debug("DB Writer Consumer started.");

        while (running || !queue.isEmpty() || hasStaleTransferShutdownDrainTime()) {
            try {
                processPendingStaleTransfers();
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

        if (!pendingStaleTransfers.isEmpty()) {
            plugin.getLogger().severe("DB writer stopped with " + pendingStaleTransfers.size()
                    + " stale transfer pair reconciliation(s) still pending after the bounded shutdown drain; "
                    + "no durable T07 recovery record exists yet and SQL may remain behind");
            pendingStaleTransfers.values().forEach(pending -> pending.task().complete(false));
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
            List<DbWriteQueue.DbWriteTask> stalePairs = dbManager.batchInsertOrUpdatePlayersWithStale(tasks);
            java.util.Set<String> staleOperationIds = stalePairs.stream()
                    .map(DbWriteQueue.DbWriteTask::operationId)
                    .collect(java.util.stream.Collectors.toSet());
            int persistedAccounts = 0;
            for (DbWriteQueue.DbWriteTask task : tasks) {
                if (staleOperationIds.contains(task.operationId())) {
                    reconcileStaleTransferPair(task);
                } else {
                    persistedAccounts += task.accountCount();
                    retryCounts.remove(task);
                    task.complete(true);
                }
            }
            queue.incrementWrittenCountBy(persistedAccounts);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process batch to DB (" + tasks.size()
                    + " tasks): " + e.getMessage());
            for (DbWriteQueue.DbWriteTask task : tasks) {
                scheduleRetry(task);
            }
        }
    }

    private boolean hasStaleTransferShutdownDrainTime() {
        return !pendingStaleTransfers.isEmpty() && shutdownDrainDeadlineNanos > System.nanoTime();
    }

    private void reconcileStaleTransferPair(DbWriteQueue.DbWriteTask staleTask) {
        if (staleTask.accountCount() != 2) {
            failClosedStaleTransfer(staleTask, "stale reconciliation requires exactly two account snapshots");
            return;
        }
        if (reconcileStaleTransferPairFromAuthority(staleTask)) {
            return;
        }
        retainStaleTransfer(staleTask, 1, "Redis authority pair could not yet be persisted atomically");
    }

    private void failClosedStaleTransfer(DbWriteQueue.DbWriteTask task, String reason) {
        retainStaleTransfer(task, 1, reason);
    }

    private boolean reconcileStaleTransferPairFromAuthority(DbWriteQueue.DbWriteTask staleTask) {
        if (cacheManager == null) {
            return false;
        }
        try {
            DbWriteQueue.AccountWrite first = staleTask.accounts().get(0);
            DbWriteQueue.AccountWrite second = staleTask.accounts().get(1);
            CacheManager.TransferResult authority = cacheManager.readTransferSnapshotStrict(
                    first.account().playerUuid(), second.account().playerUuid());
            DbWriteQueue.DbWriteTask refreshed = new DbWriteQueue.DbWriteTask(staleTask.operationId(), List.of(
                    new DbWriteQueue.AccountWrite(new noietime.syncmoney.core.port.ConfirmedAccount(
                            first.account().playerUuid(), authority.fromNewBalance, authority.fromNewVersion),
                            first.playerName(), first.serverName(), java.time.Instant.now()),
                    new DbWriteQueue.AccountWrite(new noietime.syncmoney.core.port.ConfirmedAccount(
                            second.account().playerUuid(), authority.toNewBalance, authority.toNewVersion),
                            second.playerName(), second.serverName(), java.time.Instant.now())));
            List<DbWriteQueue.DbWriteTask> stillStale = dbManager.batchInsertOrUpdatePlayersWithStale(
                    List.of(refreshed));
            if (!stillStale.isEmpty()) {
                return false;
            }
            retryCounts.remove(staleTask);
            queue.incrementWrittenCountBy(refreshed.accountCount());
            pendingStaleTransfers.remove(staleTask.operationId());
            staleTask.complete(true);
            plugin.getLogger().warning("A stale SQL transfer pair was replaced by one fresh Redis authority snapshot: "
                    + staleTask.operationId());
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Fresh authority-pair reconciliation is still pending for "
                    + staleTask.operationId() + ": " + e.getMessage());
            return false;
        }
    }

    private void processPendingStaleTransfers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PendingStaleTransfer> entry : List.copyOf(pendingStaleTransfers.entrySet())) {
            PendingStaleTransfer pending = entry.getValue();
            if (running && pending.nextAttemptAtMillis() > now) {
                continue;
            }
            if (reconcileStaleTransferPairFromAuthority(pending.task())) {
                continue;
            }
            retainStaleTransfer(pending.task(), pending.attempts() + 1, pending.reason());
        }
    }

    private void retainStaleTransfer(DbWriteQueue.DbWriteTask task, int attempts, String reason) {
        int boundedAttempts = Math.max(1, attempts);
        long retryDelay = Math.min(100L << Math.min(boundedAttempts - 1, 8), 30_000L);
        // Once shutdown starts, cap retry backoff to the drain polling interval. A
        // transient SQL/Redis error must not strand a recoverable pair past the bounded
        // shutdown window solely because its normal exponential delay is longer.
        if (!running) retryDelay = Math.min(retryDelay, 250L);
        pendingStaleTransfers.put(task.operationId(), new PendingStaleTransfer(
                task, boundedAttempts, System.currentTimeMillis() + retryDelay, reason));
        if (redisManager != null) {
            redisManager.setDegraded(true);
        }
        plugin.getLogger().warning("Retaining stale SQL transfer pair " + task.operationId()
                + " for a fresh authority-pair retry in " + retryDelay + "ms: " + reason);
    }

    private void scheduleRetry(DbWriteQueue.DbWriteTask task) {
        int attempts = retryCounts.merge(task, 1, (a, b) -> a + b);

        if (attempts > Constants.DB_WRITE_MAX_RETRIES) {
            retryCounts.remove(task);
            task.complete(false);
            plugin.getLogger().severe("Dropping DB write task after " + (attempts - 1)
                    + " failed retries for player " + task.playerUuid()
                    + " (balance=" + task.balance() + ", version=" + task.version() + ")");
            return;
        }

        if (queue.requeue(task)) {
            plugin.getLogger().warning("Re-queued failed DB write task (attempt " + attempts
                    + "/" + Constants.DB_WRITE_MAX_RETRIES + ") for player " + task.playerUuid());
        } else {
            task.complete(false);
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
