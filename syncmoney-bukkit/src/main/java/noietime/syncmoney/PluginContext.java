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
import noietime.syncmoney.web.server.WebAdminServer;

/**
 * Centralized service context for the plugin.
 * Provides a unified way to access all plugin services.
 *
 * This class is primarily used internally to reduce getter delegation code
 * in the main Syncmoney class. External code should continue using the
 * existing getters from Syncmoney for backward compatibility.
 *
 * <p>Internally the 25 services are grouped into four immutable sub-contexts
 * ({@link StorageContext}, {@link EconomyContext}, {@link SecurityContext},
 * {@link AdminContext}). The public getters delegate to those sub-contexts so
 * downstream callers see no change.
 *
 * <p>Note: the web admin configuration type is referenced with its fully
 * qualified name ({@code noietime.syncmoney.web.server.WebAdminConfig}) to
 * disambiguate it from {@code noietime.syncmoney.config.WebAdminConfig}, which
 * shares the same simple name. Using the fully qualified name avoids any import
 * ambiguity without renaming either class or touching their many call sites.
 *
 * [ThreadSafe] This class is immutable after construction.
 */
public class PluginContext {

    private final StorageContext storage;
    private final EconomyContext economy;
    private final SecurityContext security;
    private final AdminContext admin;

    /**
     * Storage-layer services: configuration and persistence/caching backends.
     */
    public static final class StorageContext {
        private final SyncmoneyConfig config;
        private final RedisManager redisManager;
        private final CacheManager cacheManager;
        private final DatabaseManager databaseManager;
        private final DbWriteQueue dbWriteQueue;

        StorageContext(SyncmoneyConfig config, RedisManager redisManager, CacheManager cacheManager,
                       DatabaseManager databaseManager, DbWriteQueue dbWriteQueue) {
            this.config = config;
            this.redisManager = redisManager;
            this.cacheManager = cacheManager;
            this.databaseManager = databaseManager;
            this.dbWriteQueue = dbWriteQueue;
        }
    }

    /**
     * Economy-layer services: balances, sync, routing, listeners and leaderboard.
     */
    public static final class EconomyContext {
        private final EconomyFacade economyFacade;
        private final NameResolver nameResolver;
        private final FallbackEconomyWrapper fallbackWrapper;
        private final SyncmoneyVaultProvider vaultProvider;
        private final CrossServerSyncManager crossServerSyncManager;
        private final EconomyModeRouter economyModeRouter;
        private final SyncManager syncManager;
        private final BaltopManager baltopManager;
        private final ShadowSyncTask shadowSyncTask;
        private final PlayerJoinListener playerJoinListener;
        private final PlayerQuitListener playerQuitListener;

        EconomyContext(EconomyFacade economyFacade, NameResolver nameResolver,
                       FallbackEconomyWrapper fallbackWrapper, SyncmoneyVaultProvider vaultProvider,
                       CrossServerSyncManager crossServerSyncManager, EconomyModeRouter economyModeRouter,
                       SyncManager syncManager, BaltopManager baltopManager, ShadowSyncTask shadowSyncTask,
                       PlayerJoinListener playerJoinListener, PlayerQuitListener playerQuitListener) {
            this.economyFacade = economyFacade;
            this.nameResolver = nameResolver;
            this.fallbackWrapper = fallbackWrapper;
            this.vaultProvider = vaultProvider;
            this.crossServerSyncManager = crossServerSyncManager;
            this.economyModeRouter = economyModeRouter;
            this.syncManager = syncManager;
            this.baltopManager = baltopManager;
            this.shadowSyncTask = shadowSyncTask;
            this.playerJoinListener = playerJoinListener;
            this.playerQuitListener = playerQuitListener;
        }
    }

    /**
     * Security-layer services: circuit breaking, guards, auditing and notifications.
     */
    public static final class SecurityContext {
        private final BreakerManager breakerManager;
        private final EconomicCircuitBreaker circuitBreaker;
        private final NotificationService notificationService;
        private final PlayerTransferGuard playerTransferGuard;
        private final AuditLogger auditLogger;

        SecurityContext(BreakerManager breakerManager, EconomicCircuitBreaker circuitBreaker,
                        NotificationService notificationService, PlayerTransferGuard playerTransferGuard,
                        AuditLogger auditLogger) {
            this.breakerManager = breakerManager;
            this.circuitBreaker = circuitBreaker;
            this.notificationService = notificationService;
            this.playerTransferGuard = playerTransferGuard;
            this.auditLogger = auditLogger;
        }
    }

    /**
     * Admin-layer services: permissions and the web admin interface.
     */
    public static final class AdminContext {
        private final PermissionManager permissionManager;
        private final AdminPermissionService permissionService;
        private final WebAdminServer webAdminServer;
        private final noietime.syncmoney.web.server.WebAdminConfig webAdminConfig;

        AdminContext(PermissionManager permissionManager, AdminPermissionService permissionService,
                     WebAdminServer webAdminServer, noietime.syncmoney.web.server.WebAdminConfig webAdminConfig) {
            this.permissionManager = permissionManager;
            this.permissionService = permissionService;
            this.webAdminServer = webAdminServer;
            this.webAdminConfig = webAdminConfig;
        }
    }

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
        private noietime.syncmoney.web.server.WebAdminConfig webAdminConfig;
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

        public Builder setWebAdminConfig(noietime.syncmoney.web.server.WebAdminConfig webAdminConfig) {
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
        this.storage = new StorageContext(
                builder.config,
                builder.redisManager,
                builder.cacheManager,
                builder.databaseManager,
                builder.dbWriteQueue);
        this.economy = new EconomyContext(
                builder.economyFacade,
                builder.nameResolver,
                builder.fallbackWrapper,
                builder.vaultProvider,
                builder.crossServerSyncManager,
                builder.economyModeRouter,
                builder.syncManager,
                builder.baltopManager,
                builder.shadowSyncTask,
                builder.playerJoinListener,
                builder.playerQuitListener);
        this.security = new SecurityContext(
                builder.breakerManager,
                builder.circuitBreaker,
                builder.notificationService,
                builder.playerTransferGuard,
                builder.auditLogger);
        this.admin = new AdminContext(
                builder.permissionManager,
                builder.permissionService,
                builder.webAdminServer,
                builder.webAdminConfig);
    }

    /**
     * @return the storage sub-context (config + persistence/caching backends).
     */
    public StorageContext storage() {
        return storage;
    }

    /**
     * @return the economy sub-context.
     */
    public EconomyContext economy() {
        return economy;
    }

    /**
     * @return the security sub-context.
     */
    public SecurityContext security() {
        return security;
    }

    /**
     * @return the admin sub-context.
     */
    public AdminContext admin() {
        return admin;
    }

    public SyncmoneyConfig getConfig() {
        return storage.config;
    }

    public RedisManager getRedisManager() {
        return storage.redisManager;
    }

    public CacheManager getCacheManager() {
        return storage.cacheManager;
    }

    public DatabaseManager getDatabaseManager() {
        return storage.databaseManager;
    }

    public DbWriteQueue getDbWriteQueue() {
        return storage.dbWriteQueue;
    }

    public EconomyFacade getEconomyFacade() {
        return economy.economyFacade;
    }

    public NameResolver getNameResolver() {
        return economy.nameResolver;
    }

    public FallbackEconomyWrapper getFallbackWrapper() {
        return economy.fallbackWrapper;
    }

    public SyncmoneyVaultProvider getVaultProvider() {
        return economy.vaultProvider;
    }

    public CrossServerSyncManager getCrossServerSyncManager() {
        return economy.crossServerSyncManager;
    }

    public EconomyModeRouter getEconomyModeRouter() {
        return economy.economyModeRouter;
    }

    public SyncManager getSyncManager() {
        return economy.syncManager;
    }

    public BreakerManager getBreakerManager() {
        return security.breakerManager;
    }

    public PermissionManager getPermissionManager() {
        return admin.permissionManager;
    }

    public PlayerJoinListener getPlayerJoinListener() {
        return economy.playerJoinListener;
    }

    public PlayerQuitListener getPlayerQuitListener() {
        return economy.playerQuitListener;
    }

    public PlayerTransferGuard getPlayerTransferGuard() {
        return security.playerTransferGuard;
    }

    public ShadowSyncTask getShadowSyncTask() {
        return economy.shadowSyncTask;
    }

    public AuditLogger getAuditLogger() {
        return security.auditLogger;
    }

    public BaltopManager getBaltopManager() {
        return economy.baltopManager;
    }

    public AdminPermissionService getPermissionService() {
        return admin.permissionService;
    }

    public EconomicCircuitBreaker getCircuitBreaker() {
        return security.circuitBreaker;
    }

    public WebAdminServer getWebAdminServer() {
        return admin.webAdminServer;
    }

    public noietime.syncmoney.web.server.WebAdminConfig getWebAdminConfig() {
        return admin.webAdminConfig;
    }

    public NotificationService getNotificationService() {
        return security.notificationService;
    }
}
