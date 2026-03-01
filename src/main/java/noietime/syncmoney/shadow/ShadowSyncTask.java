package noietime.syncmoney.shadow;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DatabaseManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [SYNC-SHADOW-001] Shadow sync task - event-driven + batch writes.
 * Receives Syncmoney balance change events and synchronizes to CMI database in real-time.
 *
 * [GlobalRegionScheduler] This task uses global scheduler for execution.
 */
public final class ShadowSyncTask {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private final boolean debug;

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }
    private final DatabaseManager databaseManager;
    private CMIDatabaseWriter cmiWriter;
    private final RollbackProtection rollbackProtection;
    private final ShadowSyncLog syncLog;


    private CMISyncQueue syncQueue;


    private ScheduledExecutorService flushScheduler;


    private volatile boolean running = false;


    private final AtomicBoolean syncNow = new AtomicBoolean(false);


    private final Object syncLock = new Object();


    private volatile long lastSyncTime = 0;

    public ShadowSyncTask(Syncmoney plugin, SyncmoneyConfig config,
                          EconomyFacade economyFacade, CacheManager cacheManager,
                          DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.config = config;
        this.debug = config.isDebug();
        this.economyFacade = economyFacade;
        this.cacheManager = cacheManager;
        this.databaseManager = databaseManager;

        this.rollbackProtection = new RollbackProtection(plugin, economyFacade, config.getShadowSyncRollbackThreshold());
        this.syncLog = new ShadowSyncLog(plugin);
    }

    /**
     * Starts the shadow sync task.
     */
    public void start() {
        if (running) {
            plugin.getLogger().warning("ShadowSyncTask is already running!");
            return;
        }

        if (!config.isShadowSyncEnabled()) {
            plugin.getLogger().info("Shadow sync is disabled in config.");
            return;
        }

        String target = config.getShadowSyncTarget();
        boolean writeToCMI = target.equals("cmi") || target.equals("all");
        boolean writeToLocal = target.equals("local") || target.equals("all");

        if (writeToCMI) {
            try {
                this.cmiWriter = new CMIDatabaseWriter(plugin, config);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize CMI database writer: " + e.getMessage());
                if (writeToCMI && !writeToLocal) {
                    return;
                }
            }

            if (cmiWriter != null && !cmiWriter.testConnection()) {
                plugin.getLogger().severe("CMI database connection failed! Shadow sync disabled.");
                plugin.getLogger().severe("Hint: If running in Docker, ensure CMI database host is reachable from container.");
                plugin.getLogger().severe("Hint: Use host IP instead of 'localhost' (e.g., 172.17.0.1) or enable host network mode.");
                cmiWriter.close();
                cmiWriter = null;
                if (writeToCMI && !writeToLocal) {
                    return;
                }
            }
        }

        if (!writeToCMI && !writeToLocal) {
            plugin.getLogger().warning("No shadow sync target configured!");
            return;
        }

        this.syncQueue = new CMISyncQueue(
                config.getShadowSyncBatchSize(),
                config.getShadowSyncMaxDelayMs()
        );

        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "syncmoney-shadow-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(this::flush,
                config.getShadowSyncMaxDelayMs(),
                config.getShadowSyncMaxDelayMs(),
                TimeUnit.MILLISECONDS);

        running = true;
        debug("Shadow sync task started (target: " + target +
                ", batch: " + config.getShadowSyncBatchSize() +
                ", max-delay: " + config.getShadowSyncMaxDelayMs() + "ms)");
    }

    /**
     * Stops the shadow sync task.
     */
    public void stop() {
        running = false;

        if (flushScheduler != null) {
            flushScheduler.shutdown();
            try {
                if (!flushScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    flushScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushScheduler.shutdownNow();
            }
            flushScheduler = null;
        }

        if (syncQueue != null) {
            syncQueue.shutdown();
            syncQueue = null;
        }

        if (cmiWriter != null) {
            cmiWriter.close();
            cmiWriter = null;
        }

        plugin.getLogger().fine("Shadow sync task stopped.");
    }

    /**
     * Receives sync events (called by EconomyEventConsumer).
     */
    public void enqueueSyncEvent(UUID uuid, String playerName, BigDecimal balance) {
        if (!running) return;

        String target = config.getShadowSyncTarget();
        boolean writeToCMI = target.equals("cmi") || target.equals("all");
        boolean writeToLocal = target.equals("local") || target.equals("all");

        if (!writeToCMI && !writeToLocal) return;

        if (writeToCMI && cmiWriter == null) return;

        syncQueue.offer(new CMISyncQueue.SyncEvent(uuid, playerName, balance, System.currentTimeMillis()));

        if (syncQueue.size() >= config.getShadowSyncBatchSize()) {
            flush();
        }
    }

    /**
     * Executes batch write operations.
     */
    private void flush() {
        if (!running) return;
        if (syncQueue.size() == 0) return;

        String target = config.getShadowSyncTarget();
        boolean writeToCMI = target.equals("cmi") || target.equals("all");
        boolean writeToLocal = target.equals("local") || target.equals("all");

        if (!writeToCMI && !writeToLocal) {
            return;
        }

        if (writeToCMI && cmiWriter == null) {
            return;
        }

        long startTime = System.currentTimeMillis();
        ConcurrentLinkedQueue<CMISyncQueue.SyncEvent> events = syncQueue.drainAll();
        if (events.isEmpty()) return;

        AtomicInteger syncedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        try {
            List<CMIDatabaseWriter.PlayerBalance> batch = events.stream()
                    .map(e -> new CMIDatabaseWriter.PlayerBalance(e.playerName(), e.uuid(), e.balance()))
                    .toList();

            if (writeToCMI) {
                for (CMIDatabaseWriter.PlayerBalance pb : batch) {
                    UUID uuid = pb.uuid();
                    BigDecimal newBalance = pb.balance();

                    if (config.isShadowSyncRollbackProtection()) {
                        if (!rollbackProtection.canWriteToCMI(uuid, newBalance)) {
                            skippedCount.incrementAndGet();
                            continue;
                        }
                    }

                    BigDecimal currentCMIBalance = cmiWriter.readCMIBalance(pb.name());
                    if (currentCMIBalance != null) {
                        rollbackProtection.recordCMIBalance(uuid, currentCMIBalance);
                    }
                }

                int updated = cmiWriter.batchUpdateBalance(batch);
                syncedCount.addAndGet(updated);
            }

            if (writeToLocal && databaseManager != null) {
                for (CMISyncQueue.SyncEvent event : events) {
                    try {
                        databaseManager.insertOrUpdatePlayer(
                                event.uuid(),
                                event.playerName(),
                                event.balance(),
                                1L,
                                config.getServerName()
                        );
                        syncedCount.incrementAndGet();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to write to local DB: " + e.getMessage());
                    }
                }
            }

            for (CMISyncQueue.SyncEvent e : events) {
                syncLog.logSuccess(e.playerName(), e.balance().toPlainString(), "N/A");
            }

            lastSyncTime = System.currentTimeMillis();
            long elapsed = System.currentTimeMillis() - startTime;

            String targetInfo = "CMI:" + (writeToCMI ? "Y" : "N") + ",Local:" + (writeToLocal ? "Y" : "N");
            plugin.getLogger().info("Shadow sync flushed [" + targetInfo + "]. Synced: " + syncedCount.get() +
                    ", Skipped: " + skippedCount.get() +
                    ", Time: " + elapsed + "ms");

        } catch (Exception e) {
            plugin.getLogger().severe("Shadow sync flush error: " + e.getMessage());
        }
    }

    /**
     * Gets the queue size.
     */
    public int getQueueSize() {
        return syncQueue != null ? syncQueue.size() : 0;
    }

    /**
     * Gets the sync status.
     */
    public SyncStatus getStatus() {
        if (syncLog == null) {
            return new SyncStatus(running, lastSyncTime, 0, 0, 0);
        }
        ShadowSyncLog.SyncStats todayStats = syncLog.getTodayStats();
        return new SyncStatus(
                running,
                lastSyncTime,
                todayStats.total(),
                todayStats.success(),
                todayStats.failed()
        );
    }

    /**
     * Gets the ShadowSyncLog instance.
     */
    public ShadowSyncLog getSyncLog() {
        return syncLog;
    }

    /**
     * Triggers immediate synchronization.
     * Sets the flag and wakes up the sync thread to execute flush immediately.
     */
    public void triggerImmediateSync() {
        if (!running) {
            throw new IllegalStateException("ShadowSyncTask is not running");
        }

        syncNow.set(true);

        synchronized (syncLock) {
            syncLock.notifyAll();
        }

        flush();

        plugin.getLogger().info("Immediate shadow sync triggered.");
    }

    /**
     * Sync status record.
     */
    public record SyncStatus(
            boolean running,
            long lastSyncTime,
            int totalSync,
            int successSync,
            int failedSync
    ) {
    }
}
