package noietime.syncmoney.storage;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DbWriterConsumer;
import noietime.syncmoney.initialization.ShutdownStepRunner;
import java.util.concurrent.atomic.AtomicBoolean;

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

        this.cacheManager = new CacheManager(plugin, redisManager, redisRequired);

        EconomyMode mode = config.getEconomyMode();
        if (mode != EconomyMode.LOCAL && mode != EconomyMode.LOCAL_REDIS) {
            this.databaseManager = new DatabaseManager(plugin, config);
        } else {
            this.databaseManager = null;
            plugin.getLogger().fine("Database manager skipped (" + mode + " mode)");
        }

        this.dbWriteQueue = new DbWriteQueue(config.getQueueCapacity());

        if (databaseManager != null) {
            this.dbWriterConsumer = new DbWriterConsumer(plugin, dbWriteQueue, databaseManager,
                    cacheManager, redisManager);
            this.dbWriterThread = createDbWriterThread(dbWriterConsumer);
            dbWriterThread.setDaemon(true);
            dbWriterThread.start();
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
        plugin.getLogger().fine("Storage layer shutdown complete");
    }

    Thread createDbWriterThread(DbWriterConsumer consumer) {
        return new Thread(() -> {
            try {
                consumer.run();
            } finally {
                if (shutdownRequested.get()) {
                    closeStorageDependencies();
                }
            }
        }, "Syncmoney-DbWriter");
    }

    private void closeStorageDependencies() {
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
