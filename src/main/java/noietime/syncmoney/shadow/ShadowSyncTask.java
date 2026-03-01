package noietime.syncmoney.shadow;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.shadow.storage.*;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DatabaseManager;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Resolves a relative path to an absolute path based on server working directory.
     * If the path is already absolute, returns it as-is.
     */
    private String resolvePath(String relativePath) {
        File path = new File(relativePath);
        if (path.isAbsolute()) {
            return relativePath;
        }
        return path.getAbsolutePath();
    }

    private final DatabaseManager databaseManager;
    private CMIDatabaseWriter cmiWriter;
    private final RollbackProtection rollbackProtection;
    private ShadowSyncLog syncLog;
    private ShadowSyncStorage storage;

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

        initializeStorage();

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
                config.getShadowSyncTriggerBatchSize(),
                config.getShadowSyncTriggerMaxDelayMs()
        );

        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "syncmoney-shadow-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(this::flush,
                config.getShadowSyncTriggerMaxDelayMs(),
                config.getShadowSyncTriggerMaxDelayMs(),
                TimeUnit.MILLISECONDS);

        running = true;
        debug("Shadow sync task started (target: " + target +
                ", batch: " + config.getShadowSyncTriggerBatchSize() +
                ", max-delay: " + config.getShadowSyncTriggerMaxDelayMs() + "ms)");
    }

    /**
     * Initializes the storage based on configuration.
     */
    private void initializeStorage() {
        String storageType = config.getShadowSyncStorageType();
        String jsonlPath = resolvePath(config.getShadowSyncStorageJsonlPath());

        switch (storageType) {
            case "sqlite" -> {
                String sqlitePath = resolvePath(config.getShadowSyncStorageSqlitePath());
                this.storage = new SqliteShadowStorage(plugin, sqlitePath, jsonlPath);
                plugin.getLogger().info("Using SQLite for shadow sync history.");
            }
            case "mysql" -> {
                this.storage = new MysqlShadowStorage(
                        plugin,
                        config.getShadowSyncStorageMysqlHost(),
                        config.getShadowSyncStorageMysqlPort(),
                        config.getShadowSyncStorageMysqlUsername(),
                        config.getShadowSyncStorageMysqlPassword(),
                        config.getShadowSyncStorageMysqlDatabase(),
                        config.getShadowSyncStorageMysqlPoolSize(),
                        jsonlPath
                );
                plugin.getLogger().info("Using MySQL for shadow sync history.");
            }
            case "postgresql" -> {
                this.storage = new PostgresShadowStorage(
                        plugin,
                        config.getShadowSyncStoragePostgresHost(),
                        config.getShadowSyncStoragePostgresPort(),
                        config.getShadowSyncStoragePostgresUsername(),
                        config.getShadowSyncStoragePostgresPassword(),
                        config.getShadowSyncStoragePostgresDatabase(),
                        config.getShadowSyncStoragePostgresPoolSize(),
                        jsonlPath
                );
                plugin.getLogger().info("Using PostgreSQL for shadow sync history.");
            }
            case "jsonl" -> {
                this.storage = null;
                plugin.getLogger().info("Using JSONL for shadow sync history (legacy mode).");
            }
            default -> {
                plugin.getLogger().warning("Unknown storage type: " + storageType + ", defaulting to SQLite.");
                String sqlitePath = resolvePath(config.getShadowSyncStorageSqlitePath());
                this.storage = new SqliteShadowStorage(plugin, sqlitePath, jsonlPath);
            }
        }

        if (storage != null) {
            try {
                storage.initialize();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize shadow sync storage: " + e.getMessage());
                this.storage = null;
            }
        }

        if (config.isShadowSyncHistoryEnabled() && storage != null) {
            int retentionDays = config.getShadowSyncHistoryRetentionDays();
            if (retentionDays > 0) {
                int deleted = storage.cleanupOldRecords(retentionDays);
                if (deleted > 0) {
                    plugin.getLogger().info("Cleaned up " + deleted + " old shadow sync records.");
                }
            }
        }
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

        if (storage != null) {
            storage.close();
            storage = null;
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

        if (syncQueue.size() >= config.getShadowSyncTriggerBatchSize()) {
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

                if (storage != null && config.isShadowSyncHistoryEnabled()) {
                    ShadowSyncRecord record = new ShadowSyncRecord(
                            e.uuid().toString(),
                            e.playerName(),
                            e.balance(),
                            target,
                            "sync",
                            Instant.now(),
                            true,
                            null
                    );
                    storage.saveRecord(record);
                }
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
        int total = 0;
        int success = 0;
        int failed = 0;

        if (storage != null && config.isShadowSyncHistoryEnabled()) {
            try {
                var stats = storage.getTodayStats();
                total = stats.getTotalSyncs();
                success = stats.getSuccessfulSyncs();
                failed = stats.getFailedSyncs();
            } catch (Exception e) {
                debug("Failed to get stats from storage: " + e.getMessage());
            }
        }

        if (total == 0 && syncLog != null) {
            var todayStats = syncLog.getTodayStats();
            total = todayStats.total();
            success = todayStats.success();
            failed = todayStats.failed();
        }

        return new SyncStatus(running, lastSyncTime, total, success, failed);
    }

    /**
     * Gets the ShadowSyncLog instance.
     */
    public ShadowSyncLog getSyncLog() {
        return syncLog;
    }

    /**
     * Gets the storage instance.
     */
    public ShadowSyncStorage getStorage() {
        return storage;
    }

    /**
     * Queries sync history by player UUID.
     */
    public List<ShadowSyncRecord> getHistoryByPlayer(String playerUuid, int limit) {
        if (storage == null) {
            plugin.getLogger().warning("Storage not initialized.");
            return List.of();
        }
        return storage.queryByPlayer(playerUuid, limit);
    }

    /**
     * Queries sync history by player name.
     */
    public List<ShadowSyncRecord> getHistoryByPlayerName(String playerName, int limit) {
        if (storage == null) {
            plugin.getLogger().warning("Storage not initialized.");
            return List.of();
        }
        return storage.queryByPlayerName(playerName, limit);
    }

    /**
     * Queries sync history by player and date range.
     */
    public List<ShadowSyncRecord> getHistoryByPlayerAndDateRange(String playerUuid, LocalDate startDate, LocalDate endDate, int limit) {
        if (storage == null) {
            plugin.getLogger().warning("Storage not initialized.");
            return List.of();
        }
        return storage.queryByPlayerAndDateRange(playerUuid, startDate, endDate, limit);
    }

    /**
     * Exports sync history to JSONL file.
     */
    public int exportHistory(String playerUuid, LocalDate startDate, LocalDate endDate) {
        if (storage == null) {
            plugin.getLogger().warning("Storage not initialized.");
            return 0;
        }
        return storage.exportToJsonl(playerUuid, startDate, endDate);
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
