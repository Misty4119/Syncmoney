package noietime.syncmoney;

import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.breaker.BreakerManager;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.breaker.NotificationService;
import noietime.syncmoney.permission.AdminPermissionService;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyModeRouter;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.guard.PlayerTransferGuard;
import noietime.syncmoney.listener.PlayerJoinListener;
import noietime.syncmoney.listener.PlayerQuitListener;
import noietime.syncmoney.permission.PermissionManager;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.SyncManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import noietime.syncmoney.web.server.WebAdminConfig;
import noietime.syncmoney.web.server.WebAdminServer;

/**
 * Centralized service context for the plugin.
 * Provides a unified way to access all plugin services.
 * 
 * This class is primarily used internally to reduce getter delegation code
 * in the main Syncmoney class. External code should continue using the
 * existing getters from Syncmoney for backward compatibility.
 * 
 * [ThreadSafe] This class is immutable after construction.
 */
public class PluginContext {

    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final CacheManager cacheManager;
    private final DatabaseManager databaseManager;
    private final DbWriteQueue dbWriteQueue;
    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;
    private final FallbackEconomyWrapper fallbackWrapper;
    private final SyncmoneyVaultProvider vaultProvider;
    private final CrossServerSyncManager crossServerSyncManager;
    private final EconomyModeRouter economyModeRouter;
    private final SyncManager syncManager;
    private final BreakerManager breakerManager;
    private final PermissionManager permissionManager;
    private final PlayerJoinListener playerJoinListener;
    private final PlayerQuitListener playerQuitListener;
    private final PlayerTransferGuard playerTransferGuard;
    private final ShadowSyncTask shadowSyncTask;
    private final AuditLogger auditLogger;
    private final BaltopManager baltopManager;
    private final AdminPermissionService permissionService;
    private final EconomicCircuitBreaker circuitBreaker;
    private final WebAdminServer webAdminServer;
    private final WebAdminConfig webAdminConfig;
    private final NotificationService notificationService;

    /**
     * Builder for PluginContext to ensure all services are properly set.
     */
    public static class Builder {
        private SyncmoneyConfig config;
        private RedisManager redisManager;
        private CacheManager cacheManager;
        private DatabaseManager databaseManager;
        private DbWriteQueue dbWriteQueue;
        private EconomyFacade economyFacade;
        private NameResolver nameResolver;
        private FallbackEconomyWrapper fallbackWrapper;
        private SyncmoneyVaultProvider vaultProvider;
        private CrossServerSyncManager crossServerSyncManager;
        private EconomyModeRouter economyModeRouter;
        private SyncManager syncManager;
        private BreakerManager breakerManager;
        private PermissionManager permissionManager;
        private PlayerJoinListener playerJoinListener;
        private PlayerQuitListener playerQuitListener;
        private PlayerTransferGuard playerTransferGuard;
        private ShadowSyncTask shadowSyncTask;
        private AuditLogger auditLogger;
        private BaltopManager baltopManager;
        private AdminPermissionService permissionService;
        private EconomicCircuitBreaker circuitBreaker;
        private WebAdminServer webAdminServer;
        private WebAdminConfig webAdminConfig;
        private NotificationService notificationService;

        public Builder setConfig(SyncmoneyConfig config) {
            this.config = config;
            return this;
        }

        public Builder setRedisManager(RedisManager redisManager) {
            this.redisManager = redisManager;
            return this;
        }

        public Builder setCacheManager(CacheManager cacheManager) {
            this.cacheManager = cacheManager;
            return this;
        }

        public Builder setDatabaseManager(DatabaseManager databaseManager) {
            this.databaseManager = databaseManager;
            return this;
        }

        public Builder setDbWriteQueue(DbWriteQueue dbWriteQueue) {
            this.dbWriteQueue = dbWriteQueue;
            return this;
        }

        public Builder setEconomyFacade(EconomyFacade economyFacade) {
            this.economyFacade = economyFacade;
            return this;
        }

        public Builder setNameResolver(NameResolver nameResolver) {
            this.nameResolver = nameResolver;
            return this;
        }

        public Builder setFallbackWrapper(FallbackEconomyWrapper fallbackWrapper) {
            this.fallbackWrapper = fallbackWrapper;
            return this;
        }

        public Builder setVaultProvider(SyncmoneyVaultProvider vaultProvider) {
            this.vaultProvider = vaultProvider;
            return this;
        }

        public Builder setCrossServerSyncManager(CrossServerSyncManager crossServerSyncManager) {
            this.crossServerSyncManager = crossServerSyncManager;
            return this;
        }

        public Builder setEconomyModeRouter(EconomyModeRouter economyModeRouter) {
            this.economyModeRouter = economyModeRouter;
            return this;
        }

        public Builder setSyncManager(SyncManager syncManager) {
            this.syncManager = syncManager;
            return this;
        }

        public Builder setBreakerManager(BreakerManager breakerManager) {
            this.breakerManager = breakerManager;
            return this;
        }

        public Builder setPermissionManager(PermissionManager permissionManager) {
            this.permissionManager = permissionManager;
            return this;
        }

        public Builder setPlayerJoinListener(PlayerJoinListener playerJoinListener) {
            this.playerJoinListener = playerJoinListener;
            return this;
        }

        public Builder setPlayerQuitListener(PlayerQuitListener playerQuitListener) {
            this.playerQuitListener = playerQuitListener;
            return this;
        }

        public Builder setPlayerTransferGuard(PlayerTransferGuard playerTransferGuard) {
            this.playerTransferGuard = playerTransferGuard;
            return this;
        }

        public Builder setShadowSyncTask(ShadowSyncTask shadowSyncTask) {
            this.shadowSyncTask = shadowSyncTask;
            return this;
        }

        public Builder setAuditLogger(AuditLogger auditLogger) {
            this.auditLogger = auditLogger;
            return this;
        }

        public Builder setBaltopManager(BaltopManager baltopManager) {
            this.baltopManager = baltopManager;
            return this;
        }

        public Builder setPermissionService(AdminPermissionService permissionService) {
            this.permissionService = permissionService;
            return this;
        }

        public Builder setCircuitBreaker(EconomicCircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
            return this;
        }

        public Builder setWebAdminServer(WebAdminServer webAdminServer) {
            this.webAdminServer = webAdminServer;
            return this;
        }

        public Builder setWebAdminConfig(WebAdminConfig webAdminConfig) {
            this.webAdminConfig = webAdminConfig;
            return this;
        }

        public Builder setNotificationService(NotificationService notificationService) {
            this.notificationService = notificationService;
            return this;
        }

        public PluginContext build() {
            return new PluginContext(this);
        }
    }

    private PluginContext(Builder builder) {
        this.config = builder.config;
        this.redisManager = builder.redisManager;
        this.cacheManager = builder.cacheManager;
        this.databaseManager = builder.databaseManager;
        this.dbWriteQueue = builder.dbWriteQueue;
        this.economyFacade = builder.economyFacade;
        this.nameResolver = builder.nameResolver;
        this.fallbackWrapper = builder.fallbackWrapper;
        this.vaultProvider = builder.vaultProvider;
        this.crossServerSyncManager = builder.crossServerSyncManager;
        this.economyModeRouter = builder.economyModeRouter;
        this.syncManager = builder.syncManager;
        this.breakerManager = builder.breakerManager;
        this.permissionManager = builder.permissionManager;
        this.playerJoinListener = builder.playerJoinListener;
        this.playerQuitListener = builder.playerQuitListener;
        this.playerTransferGuard = builder.playerTransferGuard;
        this.shadowSyncTask = builder.shadowSyncTask;
        this.auditLogger = builder.auditLogger;
        this.baltopManager = builder.baltopManager;
        this.permissionService = builder.permissionService;
        this.circuitBreaker = builder.circuitBreaker;
        this.webAdminServer = builder.webAdminServer;
        this.webAdminConfig = builder.webAdminConfig;
        this.notificationService = builder.notificationService;
    }



    public SyncmoneyConfig getConfig() {
        return config;
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

    public EconomyFacade getEconomyFacade() {
        return economyFacade;
    }

    public NameResolver getNameResolver() {
        return nameResolver;
    }

    public FallbackEconomyWrapper getFallbackWrapper() {
        return fallbackWrapper;
    }

    public SyncmoneyVaultProvider getVaultProvider() {
        return vaultProvider;
    }

    public CrossServerSyncManager getCrossServerSyncManager() {
        return crossServerSyncManager;
    }

    public EconomyModeRouter getEconomyModeRouter() {
        return economyModeRouter;
    }

    public SyncManager getSyncManager() {
        return syncManager;
    }

    public BreakerManager getBreakerManager() {
        return breakerManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public PlayerJoinListener getPlayerJoinListener() {
        return playerJoinListener;
    }

    public PlayerQuitListener getPlayerQuitListener() {
        return playerQuitListener;
    }

    public PlayerTransferGuard getPlayerTransferGuard() {
        return playerTransferGuard;
    }

    public ShadowSyncTask getShadowSyncTask() {
        return shadowSyncTask;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public BaltopManager getBaltopManager() {
        return baltopManager;
    }

    public AdminPermissionService getPermissionService() {
        return permissionService;
    }

    public EconomicCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public WebAdminServer getWebAdminServer() {
        return webAdminServer;
    }

    public WebAdminConfig getWebAdminConfig() {
        return webAdminConfig;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }
}
