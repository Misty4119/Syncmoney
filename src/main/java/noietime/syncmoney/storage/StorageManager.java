package noietime.syncmoney.storage;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DbWriterConsumer;

/**
 * Unified storage layer manager.
 * Manages Redis, Cache, and Database components.
 */
public class StorageManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;

    private RedisManager redisManager;
    private CacheManager cacheManager;
    private DatabaseManager databaseManager;
    private DbWriteQueue dbWriteQueue;
    private DbWriterConsumer dbWriterConsumer;
    private Thread dbWriterThread;

    public StorageManager(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
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

        this.dbWriterConsumer = new DbWriterConsumer(plugin, dbWriteQueue, databaseManager);
        this.dbWriterThread = new Thread(dbWriterConsumer, "Syncmoney-DbWriter");
        dbWriterThread.setDaemon(true);
        dbWriterThread.start();

        plugin.getLogger().fine("Storage layer initialized");
    }

    /**
     * Shutdown all storage components in reverse order.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down storage layer...");

        if (dbWriterConsumer != null) {
            dbWriterConsumer.stop();
        }
        if (dbWriterThread != null) {
            dbWriterThread.interrupt();
            try {
                dbWriterThread.join(3000);
            } catch (InterruptedException ignored) {
            }
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        if (redisManager != null) {
            redisManager.close();
        }

        plugin.getLogger().fine("Storage layer shutdown complete");
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
