package noietime.syncmoney.web;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.config.ConfigManager;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.web.server.WebAdminServer;
import noietime.syncmoney.web.server.WebAdminConfig;
import noietime.syncmoney.web.security.ApiKeyAuthFilter;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.api.nodes.NodesApiHandler;
import noietime.syncmoney.web.api.crossserver.CrossServerStatsApiHandler;
import noietime.syncmoney.web.api.extension.ApiExtensionManager;
import noietime.syncmoney.web.nodes.NodeHealthChecker;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;

import java.util.logging.Level;

/**
 * Unified web service manager.
 * Manages WebAdminServer and API handlers.
 */
public class WebServiceManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final ConfigManager configManager;
    private final EconomyFacade economyFacade;
    private final BaltopManager baltopManager;
    private final AuditLogger auditLogger;
    private final HybridAuditManager hybridAuditManager;

    private RedisManager redisManager;
    private DatabaseManager databaseManager;
    private EconomicCircuitBreaker circuitBreaker;
    private NameResolver nameResolver;
    private LocalEconomyHandler localEconomyHandler;

    private WebAdminServer webAdminServer;
    private WebAdminConfig webAdminConfig;
    private ApiKeyAuthFilter apiKeyAuthFilter;
    private NodeApiKeyStore nodeApiKeyStore;
    private NodesApiHandler nodesApiHandler;
    private CrossServerStatsApiHandler crossServerStatsApiHandler;
    private NodeHealthChecker nodeHealthChecker;
    private ApiExtensionManager extensionManager;

    public WebServiceManager(Syncmoney plugin, SyncmoneyConfig config,
                            EconomyFacade economyFacade, BaltopManager baltopManager,
                            AuditLogger auditLogger, HybridAuditManager hybridAuditManager) {
        this.plugin = plugin;
        this.config = config;
        this.configManager = new ConfigManager(plugin, config);
        this.economyFacade = economyFacade;
        this.baltopManager = baltopManager;
        this.auditLogger = auditLogger;
        this.hybridAuditManager = hybridAuditManager;
    }

    /**
     * Set additional dependencies for API handlers.
     */
    public void setDependencies(RedisManager redisManager, DatabaseManager databaseManager,
                               EconomicCircuitBreaker circuitBreaker) {
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Set NameResolver for player name resolution in audit search.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * Set LocalEconomyHandler for LOCAL mode audit fallback.
     */
    public void setLocalEconomyHandler(LocalEconomyHandler localEconomyHandler) {
        this.localEconomyHandler = localEconomyHandler;
    }

    /**
     * Initialize web services.
     */
    public void initialize() {
        this.webAdminConfig = new WebAdminConfig();
        webAdminConfig.load(config.getConfig());

        if (!webAdminConfig.isEnabled()) {
            plugin.getLogger().fine("Web Admin Dashboard is disabled in config.");
            return;
        }


        if ("change-me".equals(webAdminConfig.getApiKey())) {
            plugin.getLogger().severe("╔══════════════════════════════════════════════════════╗");
            plugin.getLogger().severe("║ WEB ADMIN: Using default API Key is extremely dangerous! ║");
            plugin.getLogger().severe("║ Please immediately modify the web-admin.security.api-key in config.yml ║");
            plugin.getLogger().severe("║ Web Admin has been disabled until the API Key is changed            ║");
            plugin.getLogger().severe("╚══════════════════════════════════════════════════════╝");
            plugin.getLogger().fine("Web Admin Dashboard disabled due to default API key.");
            return;
        }


        String corsOrigins = webAdminConfig.getCorsAllowedOrigins();
        if (corsOrigins == null || corsOrigins.isEmpty() || corsOrigins.equals("*")) {
            plugin.getLogger().warning("╔══════════════════════════════════════════════════════════╗");
            plugin.getLogger().warning("║ WEB ADMIN: CORS settings are insecure                              ║");
            plugin.getLogger().warning("║ cors-allowed-origins is empty or \"*\" allows all cross-origin requests       ║");
            plugin.getLogger().warning("║ It is recommended to set specific domains for security                            ║");
            plugin.getLogger().warning("║ For example: cors-allowed-origins: [\"https://your-domain.com\"] ║");
            plugin.getLogger().warning("╚══════════════════════════════════════════════════════════╝");
        }

        this.apiKeyAuthFilter = new ApiKeyAuthFilter(plugin, webAdminConfig);

        this.nodeApiKeyStore = new NodeApiKeyStore();

        try {
            this.webAdminServer = new WebAdminServer(plugin, webAdminConfig, apiKeyAuthFilter);
            registerApiHandlers();
            webAdminServer.start();
            plugin.getLogger().fine("Web Admin Dashboard enabled on http://"
                    + webAdminConfig.getHost() + ":" + webAdminConfig.getPort());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start Web Admin Server: " + e.getMessage(), e);
        }
    }

    /**
     * Register all API handlers.
     */
    private void registerApiHandlers() {
        var router = webAdminServer.getRouter();

        if (auditLogger != null) {
            var auditHandler = new noietime.syncmoney.web.api.audit.AuditApiHandler(plugin, auditLogger, hybridAuditManager);
            auditHandler.setNameResolver(nameResolver);
            auditHandler.setLocalEconomyHandler(localEconomyHandler);
            auditHandler.registerRoutes(router);
        }

        if (redisManager != null || databaseManager != null) {
            var systemHandler = new noietime.syncmoney.web.api.system.SystemApiHandler(
                    plugin, config, redisManager, databaseManager, circuitBreaker);
            systemHandler.registerRoutes(router);
        }

        var configHandler = new noietime.syncmoney.web.api.config.ConfigApiHandler(plugin, config, configManager);
        configHandler.registerRoutes(router);

        if (economyFacade != null && baltopManager != null) {
            var economyHandler = new noietime.syncmoney.web.api.economy.EconomyApiHandler(
                    plugin, economyFacade, baltopManager, auditLogger, localEconomyHandler);
            economyHandler.registerRoutes(router);
        }

        var permissionChecker = new noietime.syncmoney.web.security.PermissionChecker(plugin);
        if (config.isCentralMode()) {
            var sseManager = webAdminServer.getSseManager();

            nodesApiHandler = new NodesApiHandler(plugin, config, permissionChecker, nodeApiKeyStore);
            nodesApiHandler.registerRoutes(router);

            crossServerStatsApiHandler = new CrossServerStatsApiHandler(
                    plugin, config, economyFacade, nodeApiKeyStore);
            crossServerStatsApiHandler.registerRoutes(router);

            nodeHealthChecker = new NodeHealthChecker(plugin, config, nodeApiKeyStore, sseManager);
            nodeHealthChecker.init();

            nodesApiHandler.setNodeHealthChecker(nodeHealthChecker);
        }

        extensionManager = new ApiExtensionManager(plugin, router);
        extensionManager.initialize();
    }

    /**
     * Shutdown web services.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down web layer...");

        if (extensionManager != null) {
            extensionManager.shutdown();
        }

        if (nodeHealthChecker != null) {
            nodeHealthChecker.shutdown();
        }

        if (webAdminServer != null) {
            webAdminServer.stop();
        }

        plugin.getLogger().fine("Web layer shutdown complete");
    }

    public WebAdminServer getWebAdminServer() {
        return webAdminServer;
    }

    public WebAdminConfig getWebAdminConfig() {
        return webAdminConfig;
    }

    public ApiKeyAuthFilter getApiKeyAuthFilter() {
        return apiKeyAuthFilter;
    }
}
