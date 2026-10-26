package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.recovery.RecoverySpool;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.initialization.ShutdownStepRunner;

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
    private final EconomyFacade economyFacade;
    private final LocalEconomyHandler localEconomyHandler;

    private EconomyEventConsumer economyEventConsumer;
    private LocalPersistenceWorker localPersistenceWorker;

    public EventConsumerManager(Syncmoney plugin, SyncmoneyConfig config,
                              EconomyWriteQueue economyWriteQueue,
                              CacheManager cacheManager, RedisManager redisManager,
                              DbWriteQueue dbWriteQueue, AuditLogger auditLogger,
                              HybridAuditManager hybridAuditManager,
                              NameResolver nameResolver, BaltopManager baltopManager,
                              ShadowSyncTask shadowSyncTask,
                              OverflowLogInterface overflowLog,
                              noietime.syncmoney.storage.db.DatabaseManager databaseManager,
                              EconomyFacade economyFacade, LocalEconomyHandler localEconomyHandler) {
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
        this.economyFacade = economyFacade;
        this.localEconomyHandler = localEconomyHandler;
    }

    /**
     * Initialize economy event consumer.
     */
    public void initialize() {
        if (config.isLocalMode()) {
            if (economyFacade == null || localEconomyHandler == null) {
                plugin.getLogger().severe("LOCAL persistence worker unavailable: economy facade or SQLite handler is missing");
                return;
            }
            this.localPersistenceWorker = new LocalPersistenceWorker(
                    economyWriteQueue,
                    event -> localEconomyHandler.persistAccepted(
                            event, economyFacade.resolveCachedPlayerName(event.playerUuid())),
                    (out, in) -> localEconomyHandler.persistAcceptedTransfer(
                            out, economyFacade.resolveCachedPlayerName(out.playerUuid()),
                            in, economyFacade.resolveCachedPlayerName(in.playerUuid())),
                    events -> localEconomyHandler.quarantineAcceptedWrites(
                            events, economyFacade::resolveCachedPlayerName),
                    plugin.getLogger(), 10_000,
                    localEconomyHandler::registerPersistenceWorker,
                    localEconomyHandler::persistenceWorkerStopped,
                    localEconomyHandler::persistenceWorkerFailed,
                    localEconomyHandler::persistenceRecoveryHandoffCompleted);
            plugin.getLogger().fine("LocalPersistenceWorker initialized");
            return;
        }

        RecoverySpool recoverySpool = config.isCMIMode() ? null
                : new RecoverySpool(plugin.getDataFolder().toPath().resolve("recovery-v1.jsonl"));
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
                databaseManager,
                recoverySpool);
        if (recoverySpool != null) {
            economyWriteQueue.setTransferAdmissionHandler(economyEventConsumer::checkpointPayTransferAdmission);
        }

        plugin.getLogger().fine("EconomyEventConsumer initialized");
    }

    /**
     * Start the consumer.
     */
    public void start() {
        if (localPersistenceWorker != null) {
            localPersistenceWorker.start();
            plugin.getLogger().fine("LocalPersistenceWorker ready");
            return;
        }
        if (economyEventConsumer == null) {
            return;
        }
        if (config.isCMIMode()) {
            plugin.getLogger().info("EconomyEventConsumer skipped: CMI mode publishes directly via CMIPubsubHandler, no in-process queue to drain.");
            return;
        }
        economyEventConsumer.start();
        plugin.getLogger().fine("EconomyEventConsumer ready");
    }

    /**
     * Stop the consumer (graceful shutdown).
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down event consumer layer...");
        ShutdownStepRunner.run(plugin.getLogger(),
                new ShutdownStepRunner.Step("economy event consumer", () -> {
                    if (economyEventConsumer != null) economyEventConsumer.shutdown();
                }),
                new ShutdownStepRunner.Step("LOCAL persistence worker", () -> {
                    if (localPersistenceWorker != null) localPersistenceWorker.shutdown();
                }));
        plugin.getLogger().fine("Event consumer layer shutdown complete");
    }

    public EconomyEventConsumer getEconomyEventConsumer() {
        return economyEventConsumer;
    }
}
