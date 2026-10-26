package noietime.syncmoney.storage;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DbWriterConsumer;
import noietime.syncmoney.initialization.ShutdownStepRunner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified storage layer manager.
 * Manages Redis, Cache, and Database components.
 */
public class StorageManager {

    private static final long DB_WRITER_JOIN_TIMEOUT_MILLIS = 10_000L;
    private static final long DB_WRITER_INTERRUPT_JOIN_MILLIS = 2_000L;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;

    private RedisManager redisManager;
    private CacheManager cacheManager;
    private DatabaseManager databaseManager;
    private DbWriteQueue dbWriteQueue;
    private DbWriterConsumer dbWriterConsumer;
    private Thread dbWriterThread;
    private final long dbWriterJoinTimeoutMillis;
    private final long dbWriterInterruptJoinMillis;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean storageDependenciesClosed = new AtomicBoolean();
    private final AtomicBoolean dbWriterFinished = new AtomicBoolean(true);
    private final AtomicInteger activeDatabaseUseLeases = new AtomicInteger();

    public StorageManager(Syncmoney plugin, SyncmoneyConfig config) {
        this(plugin, config, DB_WRITER_JOIN_TIMEOUT_MILLIS, DB_WRITER_INTERRUPT_JOIN_MILLIS);
    }

    StorageManager(Syncmoney plugin, SyncmoneyConfig config, long dbWriterJoinTimeoutMillis,
                   long dbWriterInterruptJoinMillis) {
        this.plugin = plugin;
        this.config = config;
        this.dbWriterJoinTimeoutMillis = Math.max(1, dbWriterJoinTimeoutMillis);
        this.dbWriterInterruptJoinMillis = Math.max(1, dbWriterInterruptJoinMillis);
    }

    /**
     * Initialize all storage components.
     */
    public void initialize() {
        boolean redisRequired = config.getEconomyMode() != EconomyMode.LOCAL;

        this.redisManager = new RedisManager(plugin, config, redisRequired);

        this.cacheManager = new CacheManager(plugin, redisManager, redisRequired, !config.isCMIMode());

        EconomyMode mode = config.getEconomyMode();
        boolean databaseRequiredForMode = mode == EconomyMode.SYNC
                || (mode == EconomyMode.CMI && config.isDatabaseEnabled());
        if (databaseRequiredForMode) {
            this.databaseManager = new DatabaseManager(plugin, config);
        } else {
            this.databaseManager = null;
            plugin.getLogger().fine("Database manager skipped (" + mode + " mode, database disabled or unused)");
        }

        this.dbWriteQueue = new DbWriteQueue(config.getQueueCapacity());

        if (databaseManager != null) {
            this.dbWriterConsumer = new DbWriterConsumer(plugin, dbWriteQueue, databaseManager,
                    cacheManager, redisManager);
            this.dbWriterThread = createDbWriterThread(dbWriterConsumer);
            dbWriterThread.setDaemon(true);
            dbWriterFinished.set(false);
            try {
                dbWriterThread.start();
            } catch (RuntimeException | Error startupFailure) {
                // The wrapper's finally block cannot run when Thread.start() fails.
                // Let initialization rollback close the dependencies it already created.
                dbWriterFinished.set(true);
                throw startupFailure;
            }
        }

        plugin.getLogger().fine("Storage layer initialized");
    }

    /**
     * Shutdown all storage components in reverse order.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down storage layer...");
        shutdownRequested.set(true);
        ShutdownStepRunner.run(plugin.getLogger(),
                new ShutdownStepRunner.Step("cache manager", () -> {
                    if (cacheManager != null) cacheManager.shutdown();
                }),
                new ShutdownStepRunner.Step("database writer", () -> {
                    if (dbWriterConsumer != null) dbWriterConsumer.stop();
                }));
        if (dbWriterThread != null) {
            try {
                dbWriterThread.join(dbWriterJoinTimeoutMillis);
                if (dbWriterThread.isAlive()) {
                    plugin.getLogger().severe("DB writer drain timed out; pending writes require recovery");
                    dbWriterThread.interrupt();
                    dbWriterThread.join(dbWriterInterruptJoinMillis);
                }
            } catch (InterruptedException interrupted) {
                dbWriterThread.interrupt();
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Failed while draining database writer", failure);
            }
        }

        if (dbWriterThread != null && dbWriterThread.isAlive()) {
            plugin.getLogger().severe("DB writer is still using storage after shutdown deadline; "
                    + "database and Redis dependencies will remain open until that writer exits");
            return;
        }

        closeStorageDependencies();
        if (storageDependenciesClosed.get()) {
            plugin.getLogger().fine("Storage layer shutdown complete");
        } else {
            plugin.getLogger().severe("Storage dependencies remain open until all registered database users exit");
        }
    }

    Thread createDbWriterThread(DbWriterConsumer consumer) {
        return new Thread(() -> {
            try {
                consumer.run();
            } finally {
                dbWriterFinished.set(true);
                if (shutdownRequested.get()) {
                    closeStorageDependencies();
                }
            }
        }, "Syncmoney-DbWriter");
    }

    /**
     * Keeps the shared database and Redis dependencies open while an owned worker can use them.
     * The lease must be closed only after that worker has fully terminated.
     *
     * @return a lease, or {@code null} when no database is configured or shutdown has begun
     */
    public DatabaseUseLease acquireDatabaseUseLease() {
        if (databaseManager == null || shutdownRequested.get() || storageDependenciesClosed.get()) {
            return null;
        }
        activeDatabaseUseLeases.incrementAndGet();
        if (shutdownRequested.get() || storageDependenciesClosed.get()) {
            releaseDatabaseUseLease();
            return null;
        }
        return new DatabaseUseLease();
    }

    private void releaseDatabaseUseLease() {
        int active = activeDatabaseUseLeases.decrementAndGet();
        if (active < 0) {
            activeDatabaseUseLeases.incrementAndGet();
            throw new IllegalStateException("Database use lease released more than once");
        }
        if (shutdownRequested.get()) {
            closeStorageDependencies();
        }
    }

    public final class DatabaseUseLease implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private DatabaseUseLease() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                releaseDatabaseUseLease();
            }
        }
    }

    private void closeStorageDependencies() {
        if (!shutdownRequested.get() || !dbWriterFinished.get() || activeDatabaseUseLeases.get() > 0) {
            return;
        }
        if (!storageDependenciesClosed.compareAndSet(false, true)) {
            return;
        }
        ShutdownStepRunner.run(plugin.getLogger(),
                new ShutdownStepRunner.Step("database manager", () -> {
                    if (databaseManager != null) databaseManager.close();
                }),
                new ShutdownStepRunner.Step("Redis manager", () -> {
                    if (redisManager != null) redisManager.close();
                }));
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public DbWriteQueue getDbWriteQueue() {
        return dbWriteQueue;
    }
}
