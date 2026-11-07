package noietime.syncmoney.shadow;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.shadow.storage.*;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DatabaseManager;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * [SYNC-SHADOW-001] Shadow sync task - event-driven + batch writes.
 * Receives Syncmoney balance change events and synchronizes to CMI database in
 * real-time.
 *
 * [GlobalRegionScheduler] This task uses global scheduler for execution.
 */
public final class ShadowSyncTask {

    private record CoalescedSnapshots(List<CMISyncQueue.SyncEvent> latestEvents) {}

    private static final long DEFAULT_FINAL_DRAIN_TIMEOUT_MILLIS = 30_000L;
    private static final long DEFAULT_FLUSH_WORKER_TERMINATION_WAIT_MILLIS = 5_000L;
    private static final long QUEUE_REJECTION_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private final boolean debug;
    private final Supplier<? extends AutoCloseable> databaseUseLeaseSupplier;
    private final long finalDrainTimeoutMillis;
    private final long flushWorkerTerminationWaitMillis;

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().fine(message);
        }
    }

    /**
     * Resolves a relative path to an absolute path based on server working
     * directory.
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

    private final Object eventAdmissionLock = new Object();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicLong flushRequestVersion = new AtomicLong();
    private final AtomicBoolean databaseUseLeaseReleased = new AtomicBoolean(true);
    private final AtomicBoolean flushExecutorTerminated = new AtomicBoolean();
    private final AtomicBoolean stopFinalizationComplete = new AtomicBoolean();
    private final AtomicBoolean shutdownFinalized = new AtomicBoolean();
    private final AtomicLong rejectedQueueEventCount = new AtomicLong();
    private final AtomicLong lastQueueRejectionWarningNanos = new AtomicLong();
    private volatile AutoCloseable databaseUseLease;
    private volatile Thread flushWorkerThread;

    private volatile boolean running = false;

    private volatile long lastSyncTime = 0;

    public ShadowSyncTask(Syncmoney plugin, SyncmoneyConfig config,
            EconomyFacade economyFacade, CacheManager cacheManager,
            DatabaseManager databaseManager) {
        this(plugin, config, economyFacade, cacheManager, databaseManager, null);
    }

    public ShadowSyncTask(Syncmoney plugin, SyncmoneyConfig config,
            EconomyFacade economyFacade, CacheManager cacheManager,
            DatabaseManager databaseManager, Supplier<? extends AutoCloseable> databaseUseLeaseSupplier) {
        this(plugin, config, economyFacade, cacheManager, databaseManager, databaseUseLeaseSupplier,
                DEFAULT_FINAL_DRAIN_TIMEOUT_MILLIS, DEFAULT_FLUSH_WORKER_TERMINATION_WAIT_MILLIS);
    }

    ShadowSyncTask(Syncmoney plugin, SyncmoneyConfig config,
            EconomyFacade economyFacade, CacheManager cacheManager,
            DatabaseManager databaseManager, Supplier<? extends AutoCloseable> databaseUseLeaseSupplier,
            long finalDrainTimeoutMillis, long flushWorkerTerminationWaitMillis) {
        this.plugin = plugin;
        this.config = config;
        this.debug = config.isDebug();
        this.economyFacade = economyFacade;
        this.cacheManager = cacheManager;
        this.databaseManager = databaseManager;
        this.databaseUseLeaseSupplier = databaseUseLeaseSupplier;
        this.finalDrainTimeoutMillis = Math.max(1L, finalDrainTimeoutMillis);
        this.flushWorkerTerminationWaitMillis = Math.max(1L, flushWorkerTerminationWaitMillis);

        this.rollbackProtection = new RollbackProtection(plugin, economyFacade,
                config.shadowSync().getShadowSyncRollbackThreshold());
        this.syncLog = new ShadowSyncLog(plugin);
    }

    /**
     * Starts the shadow sync task.
     */
    public synchronized void start() {
        if (running) {
            plugin.getLogger().warning("ShadowSyncTask is already running!");
            return;
        }

        if (!config.shadowSync().isShadowSyncEnabled()) {
            plugin.getLogger().fine("Shadow sync is disabled in config.");
            return;
        }

        String target = config.shadowSync().getShadowSyncTarget();
        boolean writeToCMI = target.equals("cmi") || target.equals("all");
        boolean writeToLocal = target.equals("local") || target.equals("all");

        if (!writeToCMI && !writeToLocal) {
            plugin.getLogger().warning("No shadow sync target configured!");
            return;
        }

        if (writeToLocal && databaseManager == null) {
            plugin.getLogger().severe("Shadow sync disabled: target '" + target
                    + "' requires a Syncmoney database, but no DatabaseManager is available.");
            return;
        }

        if (writeToCMI) {
            try {
                this.cmiWriter = new CMIDatabaseWriter(plugin, config);
                if (cmiWriter != null && !cmiWriter.testConnection()) {
                    throw new IllegalStateException("CMI database connection failed");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize CMI database writer: " + e.getMessage());
                if (cmiWriter != null) {
                    try {
                        cmiWriter.close();
                    } catch (Exception closeFailure) {
                        plugin.getLogger().warning("Failed to close unavailable CMI database writer: "
                                + closeFailure.getClass().getSimpleName());
                    }
                }
                cmiWriter = null;
                if (!writeToLocal) {
                    plugin.getLogger().severe("Shadow sync disabled because CMI is the only configured target.");
                    return;
                }
                plugin.getLogger().severe(
                        "CMI mirroring remains inactive until plugin restart; continuing with local SQL only.");
                plugin.getLogger()
                        .severe("Hint: If running in Docker, ensure CMI database host is reachable from container.");
                plugin.getLogger().severe(
                        "Hint: Use host IP instead of 'localhost' (e.g., 172.17.0.1) or enable host network mode.");
            }
        }

        initializeStorage();

        this.syncQueue = new CMISyncQueue(
                config.shadowSync().getShadowSyncTriggerBatchSize(),
                config.shadowSync().getShadowSyncTriggerMaxDelayMs(),
                config.getQueueCapacity());

        if (writeToLocal && databaseUseLeaseSupplier != null) {
            try {
                databaseUseLeaseReleased.set(false);
                databaseUseLease = databaseUseLeaseSupplier.get();
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Shadow sync disabled: could not reserve the shared database lifecycle: "
                        + e.getClass().getSimpleName());
                cleanupFailedInitialization();
                return;
            }
            if (databaseUseLease == null) {
                plugin.getLogger().severe("Shadow sync disabled: shared database shutdown has already started.");
                cleanupFailedInitialization();
                return;
            }
        }

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "syncmoney-shadow-flush");
            t.setDaemon(true);
            flushWorkerThread = t;
            return t;
        }) {
            @Override
            protected void terminated() {
                try {
                    super.terminated();
                } finally {
                    flushExecutorTerminated.set(true);
                    finalizeShutdownIfReady();
                }
            }
        };
        this.flushScheduler = scheduler;
        scheduler.scheduleAtFixedRate(this::flush,
                config.shadowSync().getShadowSyncTriggerMaxDelayMs(),
                config.shadowSync().getShadowSyncTriggerMaxDelayMs(),
                TimeUnit.MILLISECONDS);

        running = true;
        debug("Shadow sync task started (target: " + target +
                ", batch: " + config.shadowSync().getShadowSyncTriggerBatchSize() +
                ", max-delay: " + config.shadowSync().getShadowSyncTriggerMaxDelayMs() + "ms)");
    }

    /**
     * Initializes the storage based on configuration.
     */
    private void initializeStorage() {
        String storageType = config.shadowSync().getShadowSyncStorageType();
        String jsonlPath = resolvePath(config.shadowSync().getShadowSyncStorageJsonlPath());

        switch (storageType) {
            case "sqlite" -> {
                String sqlitePath = resolvePath(config.shadowSync().getShadowSyncStorageSqlitePath());
                this.storage = new SqliteShadowStorage(plugin, sqlitePath, jsonlPath);
                plugin.getLogger().fine("Using SQLite for shadow sync history.");
            }
            case "mysql" -> {
                this.storage = new MysqlShadowStorage(
                        plugin,
                        config.shadowSync().getShadowSyncStorageMysqlHost(),
                        config.shadowSync().getShadowSyncStorageMysqlPort(),
                        config.shadowSync().getShadowSyncStorageMysqlUsername(),
                        config.shadowSync().getShadowSyncStorageMysqlPassword(),
                        config.shadowSync().getShadowSyncStorageMysqlDatabase(),
                        config.shadowSync().getShadowSyncStorageMysqlPoolSize(),
                        jsonlPath);
                plugin.getLogger().fine("Using MySQL for shadow sync history.");
            }
            case "postgresql" -> {
                this.storage = new PostgresShadowStorage(
                        plugin,
                        config.shadowSync().getShadowSyncStoragePostgresHost(),
                        config.shadowSync().getShadowSyncStoragePostgresPort(),
                        config.shadowSync().getShadowSyncStoragePostgresUsername(),
                        config.shadowSync().getShadowSyncStoragePostgresPassword(),
                        config.shadowSync().getShadowSyncStoragePostgresDatabase(),
                        config.shadowSync().getShadowSyncStoragePostgresPoolSize(),
                        jsonlPath);
                plugin.getLogger().fine("Using PostgreSQL for shadow sync history.");
            }
            case "jsonl" -> {
                this.storage = null;
                plugin.getLogger().fine("Using JSONL for shadow sync history (legacy mode).");
            }
            default -> {
                plugin.getLogger().warning("Unknown storage type: " + storageType + ", defaulting to SQLite.");
                String sqlitePath = resolvePath(config.shadowSync().getShadowSyncStorageSqlitePath());
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

        if (config.shadowSync().isShadowSyncHistoryEnabled() && storage != null) {
            int retentionDays = config.shadowSync().getShadowSyncHistoryRetentionDays();
            if (retentionDays > 0) {
                int deleted = storage.cleanupOldRecords(retentionDays);
                if (deleted > 0) {
                    plugin.getLogger().fine("Cleaned up " + deleted + " old shadow sync records.");
                }
            }
        }
    }

    /**
     * Stops the shadow sync task.
     */
    public synchronized void stop() {
        if (stopFinalizationComplete.get()) {
            return;
        }
        synchronized (eventAdmissionLock) {
            running = false;
        }
        // The executor's termination callback must own any final database drain. Setting this
        // before shutdown closes the race where the worker terminates before the stop wait ends.
        stopFinalizationComplete.set(true);

        ScheduledExecutorService scheduler = flushScheduler;
        boolean flushWorkerTerminated = scheduler == null;
        if (scheduler != null) {
            long shutdownTimeoutMillis = Math.max(finalDrainTimeoutMillis, flushWorkerTerminationWaitMillis);
            long shutdownDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMillis);
            Future<?> finalDrain = null;
            try {
                if (!scheduler.isShutdown()) {
                    finalDrain = scheduler.submit(() -> flush(true));
                }
            } catch (RejectedExecutionException e) {
                plugin.getLogger().warning("Could not queue the final Shadow Sync drain: " + e.getMessage());
            }

            scheduler.shutdown();
            try {
                if (finalDrain != null) {
                    long remainingNanos = shutdownDeadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        throw new TimeoutException("Shadow Sync shutdown deadline expired before final drain wait");
                    }
                    finalDrain.get(remainingNanos, TimeUnit.NANOSECONDS);
                }
            } catch (TimeoutException e) {
                plugin.getLogger().warning("Shadow Sync final drain did not finish within the "
                        + shutdownTimeoutMillis + " ms shutdown deadline; interrupting its worker.");
                scheduler.shutdownNow();
            } catch (java.util.concurrent.ExecutionException e) {
                plugin.getLogger().severe("Shadow Sync final drain failed: " + e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }

            if (!scheduler.isTerminated() && System.nanoTime() >= shutdownDeadlineNanos) {
                scheduler.shutdownNow();
            }
            flushWorkerTerminated = scheduler.isTerminated()
                    || awaitFlushWorkerTermination(scheduler, shutdownDeadlineNanos);

            if (flushWorkerTerminated) {
                flushScheduler = null;
            }
        }

        if (syncQueue != null) {
            syncQueue.shutdown();
            if (flushWorkerTerminated) {
                syncQueue = null;
            }
        }

        if (!flushWorkerTerminated) {
            plugin.getLogger().severe("Shadow Sync worker did not terminate; leaving its CMI writer and history storage open to avoid closing resources still in use. Any remaining queue is not confirmed drained.");
        }

        if (flushWorkerTerminated) {
            flushExecutorTerminated.set(true);
        }
        finalizeShutdownIfReady();

        plugin.getLogger().fine("Shadow sync task stopped.");
    }

    private boolean awaitFlushWorkerTermination(ScheduledExecutorService scheduler, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return scheduler.isTerminated();
        }
        try {
            return scheduler.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void closeShadowSyncResources() {
        CMIDatabaseWriter writer = cmiWriter;
        cmiWriter = null;
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to close CMI shadow sync writer: " + e.getMessage());
            }
        }

        ShadowSyncStorage historyStorage = storage;
        storage = null;
        if (historyStorage != null) {
            try {
                historyStorage.close();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to close Shadow Sync history storage: " + e.getMessage());
            }
        }
    }

    private void cleanupFailedInitialization() {
        syncQueue = null;
        databaseUseLeaseReleased.set(true);
        closeShadowSyncResources();
    }

    private void releaseDatabaseUseLease() {
        if (!databaseUseLeaseReleased.compareAndSet(false, true)) {
            return;
        }
        AutoCloseable lease = databaseUseLease;
        databaseUseLease = null;
        if (lease != null) {
            try {
                lease.close();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to release the Shadow Sync database lifecycle lease: "
                        + e.getClass().getSimpleName());
            }
        }
    }

    private void finalizeShutdownIfReady() {
        if (!stopFinalizationComplete.get()
                || !flushExecutorTerminated.get()) {
            return;
        }
        CMISyncQueue queue = syncQueue;
        if (queue != null && queue.size() > 0 && Thread.currentThread() != flushWorkerThread) {
            plugin.getLogger().severe("Shadow Sync final queue drain was not run because the current thread does not own the flush worker; leaving its sinks and shared database open.");
            return;
        }
        if (!shutdownFinalized.compareAndSet(false, true)) {
            return;
        }
        try {
            if (queue != null && queue.size() > 0) {
                plugin.getLogger().warning(
                        "Shadow Sync worker terminated after stop returned; draining its remaining queue.");
                flush(true);
            }
        } finally {
            closeShadowSyncResources();
            releaseDatabaseUseLease();
        }
    }

    /**
     * Receives sync events (called by EconomyEventConsumer).
     */
    public void enqueueSyncEvent(UUID uuid, String playerName, BigDecimal balance) {
        EconomyFacade.EconomyState current = uuid == null || economyFacade == null
                ? null
                : economyFacade.getMemoryState(uuid);
        enqueueSyncEvent(uuid, playerName, balance, current == null ? -1L : current.version());
    }

    /**
     * Receives a completed authority receipt and admits it only while its exact version/balance
     * pair is still the current in-memory authority snapshot.
     */
    public void enqueueSyncEvent(UUID uuid, String playerName, BigDecimal balance, long receiptVersion) {
        if (!running) {
            return;
        }

        if (uuid == null) {
            plugin.getLogger().warning("enqueueSyncEvent: uuid is null, skipping");
            return;
        }
        if (playerName == null || playerName.isBlank()) {
            plugin.getLogger().warning("enqueueSyncEvent: playerName is null or blank for uuid " + uuid + ", skipping");
            return;
        }
        if (balance == null) {
            plugin.getLogger().warning("enqueueSyncEvent: balance is null for uuid " + uuid + ", skipping");
            return;
        }
        if (receiptVersion <= 0L || !matchesCurrentAuthority(uuid, balance, receiptVersion)) {
            plugin.getLogger().fine("Shadow Sync skipped an event whose receipt did not match the current "
                    + "authoritative version for " + uuid);
            return;
        }

        String target = config.shadowSync().getShadowSyncTarget();
        boolean writeToCMI = target.equals("cmi") || target.equals("all");
        boolean writeToLocal = target.equals("local") || target.equals("all");

        if (!writeToCMI && !writeToLocal)
            return;

        if (target.equals("cmi") && cmiWriter == null)
            return;

        boolean accepted;
        boolean shouldFlush;
        long rejectedCount = 0;
        synchronized (eventAdmissionLock) {
            if (!running || syncQueue == null) {
                return;
            }
            // The state may have advanced after receipt validation but before queue admission.
            if (!matchesCurrentAuthority(uuid, balance, receiptVersion)) {
                return;
            }
            CMISyncQueue.OfferResult offerResult = syncQueue.offerWithStatus(
                    new CMISyncQueue.SyncEvent(
                            uuid, playerName, balance, receiptVersion, System.currentTimeMillis()));
            accepted = offerResult == CMISyncQueue.OfferResult.ACCEPTED;
            if (!accepted) {
                if (offerResult == CMISyncQueue.OfferResult.CAPACITY_REJECTED) {
                    rejectedCount = rejectedQueueEventCount.incrementAndGet();
                }
            }
            shouldFlush = syncQueue.size() >= config.shadowSync().getShadowSyncTriggerBatchSize()
                    || (!accepted && syncQueue.size() >= syncQueue.capacity());
        }

        if (rejectedCount > 0) {
            scheduleQueueRejectionWarning(rejectedCount);
        }
        if (shouldFlush) {
            requestFlush();
        }
    }

    private void scheduleQueueRejectionWarning(long rejectedCount) {
        long now = System.nanoTime();
        while (true) {
            long previous = lastQueueRejectionWarningNanos.get();
            if (previous != 0 && now - previous < QUEUE_REJECTION_WARNING_INTERVAL_NANOS) {
                return;
            }
            if (!lastQueueRejectionWarningNanos.compareAndSet(previous, now)) {
                continue;
            }

            ScheduledExecutorService scheduler = flushScheduler;
            if (scheduler != null) {
                long rejectionNumber = rejectedCount;
                try {
                    scheduler.execute(() -> plugin.getLogger().warning("Shadow Sync queue is full; rejected "
                            + rejectionNumber + " new player snapshots. The secondary mirror may miss balance "
                            + "updates until the affected players are queued again after capacity is available."));
                } catch (RejectedExecutionException ignored) {
                    // The queue-rejection count remains visible in /syncmoney shadow status.
                }
            }
            return;
        }
    }

    /** Submits coalesced flush work to the single owned executor so producers cannot reorder writes. */
    private void requestFlush() {
        if (!running) {
            return;
        }
        ScheduledExecutorService scheduler = flushScheduler;
        if (scheduler == null) {
            return;
        }

        flushRequestVersion.incrementAndGet();
        scheduleRequestedFlush(scheduler);
    }

    private void scheduleRequestedFlush(ScheduledExecutorService scheduler) {
        if (!running || !flushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(() -> {
                long handledVersion = -1;
                try {
                    while (running) {
                        handledVersion = flushRequestVersion.get();
                        flush(false);
                        if (flushRequestVersion.get() == handledVersion) {
                            break;
                        }
                    }
                } finally {
                    flushScheduled.set(false);
                    if (running && (flushRequestVersion.get() != handledVersion
                            || syncQueue.size() >= config.shadowSync().getShadowSyncTriggerBatchSize())) {
                        scheduleRequestedFlush(scheduler);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false);
            if (running) {
                plugin.getLogger().warning("Shadow sync flush request was rejected while the task was running.");
            }
        }
    }

    /**
     * Executes batch write operations.
     */
    private void flush() {
        flush(false);
    }

    private void flush(boolean allowStoppedTask) {
        if (!running && !allowStoppedTask)
            return;
        if (syncQueue.size() == 0)
            return;

        String target = config.shadowSync().getShadowSyncTarget();
        boolean cmiRequested = target.equals("cmi") || target.equals("all");
        boolean writeToCMI = cmiRequested && cmiWriter != null;
        boolean writeToLocal = target.equals("local") || target.equals("all");

        if (!writeToCMI && !writeToLocal) {
            return;
        }

        long startTime = System.currentTimeMillis();
        ConcurrentLinkedQueue<CMISyncQueue.SyncEvent> drainedEvents;
        synchronized (eventAdmissionLock) {
            drainedEvents = syncQueue.drainAll();
        }
        if (drainedEvents.isEmpty())
            return;

        CoalescedSnapshots coalescedSnapshots = coalesceLatestSnapshots(List.copyOf(drainedEvents));
        List<CMISyncQueue.SyncEvent> events = coalescedSnapshots.latestEvents();
        int supersededCount = drainedEvents.size() - events.size();
        if (supersededCount > 0) {
            plugin.getLogger().fine("Coalesced " + supersededCount
                    + " superseded Shadow Sync snapshots before writing latest balances");
        }

        AtomicInteger syncedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        try {
            List<CMIDatabaseWriter.PlayerBalance> batch = events.stream()
                    .map(e -> new CMIDatabaseWriter.PlayerBalance(e.playerName(), e.uuid(), e.balance()))
                    .toList();
            List<Boolean> cmiResults = new java.util.ArrayList<>(events.size());
            List<String> cmiFailureReasons = new java.util.ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                cmiResults.add(!cmiRequested);
                cmiFailureReasons.add(cmiRequested && !writeToCMI
                        ? "CMI writer unavailable; local SQL write continued"
                        : null);
            }

            boolean[] currentAuthority = new boolean[events.size()];
            for (int i = 0; i < events.size(); i++) {
                CMISyncQueue.SyncEvent event = events.get(i);
                currentAuthority[i] = matchesCurrentAuthority(
                        event.uuid(), event.balance(), event.authorityVersion());
                if (!currentAuthority[i]) {
                    String reason = "receipt no longer matches the current authoritative balance version";
                    cmiFailureReasons.set(i, reason);
                    plugin.getLogger().fine("Shadow Sync skipped stale event for " + event.uuid());
                }
            }

            if (writeToCMI) {
                List<CMIDatabaseWriter.PlayerBalance> cmiBatch = new java.util.ArrayList<>(batch.size());
                List<Integer> cmiEventIndexes = new java.util.ArrayList<>(batch.size());
                try {
                    for (int i = 0; i < batch.size(); i++) {
                        CMIDatabaseWriter.PlayerBalance pb = batch.get(i);
                        UUID uuid = pb.uuid();
                        BigDecimal newBalance = pb.balance();
                        if (!currentAuthority[i]) {
                            continue;
                        }
                        BigDecimal currentCMIBalance = null;
                        boolean currentBalanceRead = false;

                        if (config.shadowSync().isShadowSyncRollbackProtection()) {
                            // Refresh CMI before every protected write so an external CMI change
                            // cannot be hidden by a stale in-memory baseline. Only a missing account
                            // with no prior baseline follows the initial-baseline write policy.
                            boolean needsInitialBaseline = rollbackProtection.getPreviousCMIBalance(uuid) == null;
                            CMIDatabaseWriter.BalanceReadResult readResult =
                                    cmiWriter.readCMIBalanceStrict(pb.uuid());
                            currentBalanceRead = true;
                            if (readResult == null
                                    || readResult.status() == CMIDatabaseWriter.BalanceReadStatus.FAILED) {
                                String reason = "CMI balance read failed; rollback protection could not be verified";
                                cmiFailureReasons.set(i, reason);
                                plugin.getLogger().warning("Shadow Sync blocked CMI write for " + pb.name()
                                        + ": " + reason);
                                continue;
                            }
                            if (readResult.status() == CMIDatabaseWriter.BalanceReadStatus.FOUND) {
                                currentCMIBalance = readResult.balance();
                                rollbackProtection.recordCMIBalance(uuid, currentCMIBalance);
                            } else if (!needsInitialBaseline) {
                                String reason = "CMI balance disappeared after rollback baseline was established";
                                cmiFailureReasons.set(i, reason);
                                plugin.getLogger().warning("Shadow Sync blocked CMI write for " + pb.name()
                                        + ": " + reason);
                                continue;
                            }
                            if (!rollbackProtection.canWriteToCMI(uuid, newBalance)) {
                                skippedCount.incrementAndGet();
                                cmiFailureReasons.set(i, "CMI write blocked by rollback protection");
                                continue;
                            }
                        }

                        if (!currentBalanceRead) {
                            currentCMIBalance = cmiWriter.readCMIBalance(pb.uuid());
                        }
                        if (currentCMIBalance != null) {
                            rollbackProtection.recordCMIBalance(uuid, currentCMIBalance);
                        }
                        cmiBatch.add(pb);
                        cmiEventIndexes.add(i);
                    }

                    // Revalidate after the CMI pre-reads and immediately before issuing writes.
                    List<CMIDatabaseWriter.PlayerBalance> currentBatch = new java.util.ArrayList<>(cmiBatch.size());
                    List<Integer> currentIndexes = new java.util.ArrayList<>(cmiEventIndexes.size());
                    for (int i = 0; i < cmiBatch.size(); i++) {
                        int eventIndex = cmiEventIndexes.get(i);
                        CMISyncQueue.SyncEvent event = events.get(eventIndex);
                        if (matchesCurrentAuthority(event.uuid(), event.balance(), event.authorityVersion())) {
                            currentBatch.add(cmiBatch.get(i));
                            currentIndexes.add(eventIndex);
                        } else {
                            cmiFailureReasons.set(eventIndex,
                                    "receipt no longer matches the current authoritative balance version");
                        }
                    }
                    cmiBatch = currentBatch;
                    cmiEventIndexes = currentIndexes;

                    List<Boolean> confirmations = cmiBatch.isEmpty()
                            ? List.of()
                            : cmiWriter.batchUpdateBalanceResults(cmiBatch);
                    if (confirmations == null || confirmations.size() != cmiBatch.size()) {
                        cmiEventIndexes.forEach(i -> cmiFailureReasons.set(
                                i, "CMI batch returned incomplete balance confirmations"));
                    } else {
                        for (int i = 0; i < confirmations.size(); i++) {
                            int eventIndex = cmiEventIndexes.get(i);
                            CMISyncQueue.SyncEvent event = events.get(eventIndex);
                            boolean confirmed = Boolean.TRUE.equals(confirmations.get(i))
                                    && matchesCurrentAuthority(
                                            event.uuid(), event.balance(), event.authorityVersion());
                            cmiResults.set(eventIndex, confirmed);
                            if (confirmed) {
                                syncedCount.incrementAndGet();
                            } else {
                                cmiFailureReasons.set(eventIndex,
                                        "CMI balance was not confirmed after batch commit");
                            }
                        }
                    }
                } catch (Exception e) {
                    String reason = "CMI write failed: " + e.getClass().getSimpleName();
                    cmiEventIndexes.forEach(i -> cmiFailureReasons.set(i, reason));
                    plugin.getLogger().severe("Shadow sync CMI write failed: " + e.getMessage());
                }
            }

            List<Boolean> localResults = new java.util.ArrayList<>(events.size());
            List<String> localFailureReasons = new java.util.ArrayList<>(events.size());
            if (writeToLocal && databaseManager != null) {
                for (CMISyncQueue.SyncEvent event : events) {
                    if (!matchesCurrentAuthority(event.uuid(), event.balance(), event.authorityVersion())) {
                        plugin.getLogger().fine("Local shadow sync skipped stale receipt for " + event.uuid());
                        localResults.add(false);
                        localFailureReasons.add(
                                "local SQL write skipped because receipt no longer matches the authoritative version");
                        continue;
                    }
                    try {
                        boolean accepted = databaseManager.tryInsertOrUpdatePlayer(
                                event.uuid(),
                                event.playerName(),
                                event.balance(),
                                event.authorityVersion(),
                                config.getServerName());
                        String localFailureReason = null;
                        if (accepted) {
                            BigDecimal expectedBalance = event.balance().setScale(2, RoundingMode.HALF_UP);
                            accepted = databaseManager.getPlayerStrict(event.uuid())
                                    .map(record -> record.uuid().equals(event.uuid())
                                            && record.version() == event.authorityVersion()
                                            && record.balance().compareTo(expectedBalance) == 0
                                            && matchesCurrentAuthority(
                                                    event.uuid(), event.balance(), event.authorityVersion()))
                                    .orElse(false);
                            if (!accepted) {
                                localFailureReason =
                                        "local SQL readback did not match the expected authoritative snapshot";
                                plugin.getLogger().warning(
                                        "Local shadow sync persisted SQL snapshot did not match the expected authoritative state for "
                                                + event.playerName());
                            }
                        }
                        if (!accepted && localFailureReason == null) {
                            localFailureReason = "local SQL write was rejected";
                        }
                        localResults.add(accepted);
                        localFailureReasons.add(accepted ? null : localFailureReason);
                        if (accepted) {
                            syncedCount.incrementAndGet();
                        } else {
                            plugin.getLogger().warning("Local shadow sync failed to confirm the expected SQL state for "
                                    + event.playerName());
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to write to local DB: " + e.getMessage());
                        localResults.add(false);
                        localFailureReasons.add("local SQL write failed: " + e.getClass().getSimpleName());
                    }
                }
            }

            int eventIndex = 0;
            for (CMISyncQueue.SyncEvent e : events) {
                boolean localSyncSucceeded = !writeToLocal
                        || (eventIndex < localResults.size() && localResults.get(eventIndex));
                boolean cmiSyncSucceeded = cmiResults.get(eventIndex);
                boolean success = cmiSyncSucceeded && localSyncSucceeded;
                String failureReason = cmiSyncSucceeded ? null : cmiFailureReasons.get(eventIndex);
                if (!cmiSyncSucceeded && failureReason == null) {
                    failureReason = "CMI write failed";
                }
                if (!localSyncSucceeded) {
                    String localFailureReason = eventIndex < localFailureReasons.size()
                            ? localFailureReasons.get(eventIndex)
                            : "local SQL write was rejected";
                    failureReason = failureReason == null
                            ? localFailureReason
                            : failureReason + "; " + localFailureReason;
                }

                if (success) {
                    syncLog.logSuccess(e.playerName(), e.balance().toPlainString(), "N/A");
                } else {
                    syncLog.logFailure(e.playerName(), e.balance().toPlainString(), "N/A", failureReason);
                }

                if (storage != null && config.shadowSync().isShadowSyncHistoryEnabled()) {
                    ShadowSyncRecord record = new ShadowSyncRecord(
                            e.uuid().toString(),
                            e.playerName(),
                            e.balance(),
                            target,
                            "sync",
                            Instant.now(),
                            success,
                            failureReason);
                    storage.saveRecord(record);
                }
                eventIndex++;
            }

            lastSyncTime = System.currentTimeMillis();
            long elapsed = System.currentTimeMillis() - startTime;

            String targetInfo = "CMI:" + (writeToCMI ? "Y" : "N") + ",Local:" + (writeToLocal ? "Y" : "N");
            plugin.getLogger().fine("Shadow sync flushed [" + targetInfo + "]. Synced: " + syncedCount.get() +
                    ", Rollback-skipped: " + skippedCount.get() +
                    ", Superseded: " + supersededCount +
                    ", Time: " + elapsed + "ms");

        } catch (Exception e) {
            plugin.getLogger().severe("Shadow sync flush error: " + e.getMessage());
        }
    }

    /**
     * Keeps only the newest balance snapshot per account in a drained batch.
     * Intermediate snapshots are superseded state, not failed mirror writes.
     */
    private static CoalescedSnapshots coalesceLatestSnapshots(
            List<CMISyncQueue.SyncEvent> drainedEvents) {
        java.util.LinkedHashMap<UUID, CMISyncQueue.SyncEvent> latestByPlayer = new java.util.LinkedHashMap<>();
        java.util.Set<UUID> conflictedPlayers = new java.util.HashSet<>();
        for (CMISyncQueue.SyncEvent event : drainedEvents) {
            if (conflictedPlayers.contains(event.uuid())) {
                continue;
            }
            CMISyncQueue.SyncEvent pending = latestByPlayer.get(event.uuid());
            if (pending == null || event.authorityVersion() > pending.authorityVersion()) {
                latestByPlayer.remove(event.uuid());
                latestByPlayer.put(event.uuid(), event);
            } else if (event.authorityVersion() == pending.authorityVersion()
                    && event.balance().compareTo(pending.balance()) != 0) {
                // Conflicting receipts for one authority version are not safe to mirror.
                latestByPlayer.remove(event.uuid());
                conflictedPlayers.add(event.uuid());
            }
        }
        return new CoalescedSnapshots(List.copyOf(latestByPlayer.values()));
    }

    private boolean matchesCurrentAuthority(UUID uuid, BigDecimal balance, long authorityVersion) {
        if (uuid == null || balance == null || authorityVersion <= 0L || economyFacade == null) {
            return false;
        }
        EconomyFacade.EconomyState current = economyFacade.getMemoryState(uuid);
        return current != null
                && current.balance() != null
                && current.version() == authorityVersion
                && current.balance().compareTo(balance) == 0;
    }

    /**
     * Gets the queue size.
     */
    public int getQueueSize() {
        return syncQueue != null ? syncQueue.size() : 0;
    }

    /** Returns how many new player snapshots were rejected because the queue was full. */
    public long getQueueRejectionCount() {
        return rejectedQueueEventCount.get();
    }

    /**
     * Gets the sync status.
     */
    public SyncStatus getStatus() {
        int total = 0;
        int success = 0;
        int failed = 0;

        if (storage != null && config.shadowSync().isShadowSyncHistoryEnabled()) {
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

    /** Returns the task lifecycle state without querying history storage. */
    public boolean isRunning() {
        return running;
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
    public List<ShadowSyncRecord> getHistoryByPlayerAndDateRange(String playerUuid, LocalDate startDate,
            LocalDate endDate, int limit) {
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

        requestFlush();
        plugin.getLogger().fine("Immediate shadow sync requested.");
    }

    /**
     * Sync status record.
     */
    public record SyncStatus(
            boolean running,
            long lastSyncTime,
            int totalSync,
            int successSync,
            int failedSync) {
    }
}
