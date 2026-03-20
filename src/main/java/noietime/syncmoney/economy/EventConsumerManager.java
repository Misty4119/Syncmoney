package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.uuid.NameResolver;

/**
 * Unified manager for economy event consumer.
 * Encapsulates EconomyEventConsumer.
 */
public class EventConsumerManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyWriteQueue economyWriteQueue;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final DbWriteQueue dbWriteQueue;
    private final AuditLogger auditLogger;
    private final HybridAuditManager hybridAuditManager;
    private final NameResolver nameResolver;
    private final BaltopManager baltopManager;
    private final ShadowSyncTask shadowSyncTask;
    private final OverflowLogInterface overflowLog;
    private final noietime.syncmoney.storage.db.DatabaseManager databaseManager;

    private EconomyEventConsumer economyEventConsumer;

    public EventConsumerManager(Syncmoney plugin, SyncmoneyConfig config,
                              EconomyWriteQueue economyWriteQueue,
                              CacheManager cacheManager, RedisManager redisManager,
                              DbWriteQueue dbWriteQueue, AuditLogger auditLogger,
                              HybridAuditManager hybridAuditManager,
                              NameResolver nameResolver, BaltopManager baltopManager,
                              ShadowSyncTask shadowSyncTask,
                              OverflowLogInterface overflowLog,
                              noietime.syncmoney.storage.db.DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.config = config;
        this.economyWriteQueue = economyWriteQueue;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.dbWriteQueue = dbWriteQueue;
        this.auditLogger = auditLogger;
        this.hybridAuditManager = hybridAuditManager;
        this.nameResolver = nameResolver;
        this.baltopManager = baltopManager;
        this.shadowSyncTask = shadowSyncTask;
        this.overflowLog = overflowLog;
        this.databaseManager = databaseManager;
    }

    /**
     * Initialize economy event consumer.
     */
    public void initialize() {
        this.economyEventConsumer = new EconomyEventConsumer(
                plugin,
                config,
                economyWriteQueue,
                cacheManager,
                redisManager,
                dbWriteQueue,
                auditLogger,
                hybridAuditManager,
                nameResolver,
                baltopManager,
                shadowSyncTask,
                overflowLog,
                databaseManager);

        plugin.getLogger().fine("EconomyEventConsumer initialized");
    }

    /**
     * Start the consumer.
     */
    public void start() {
        if (economyEventConsumer != null) {
            plugin.getLogger().fine("EconomyEventConsumer ready");
        }
    }

    /**
     * Stop the consumer (graceful shutdown).
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down event consumer layer...");
        
        if (economyEventConsumer != null) {
            economyEventConsumer.stop();
        }
        
        plugin.getLogger().fine("Event consumer layer shutdown complete");
    }

    public EconomyEventConsumer getEconomyEventConsumer() {
        return economyEventConsumer;
    }
}
