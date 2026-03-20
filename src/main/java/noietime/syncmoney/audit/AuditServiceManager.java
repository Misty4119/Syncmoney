package noietime.syncmoney.audit;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.web.server.WebAdminServer;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Unified audit service manager.
 * Manages AuditLogger, HybridAuditManager, AuditLogCleanup, and AuditLogExporter.
 */
public class AuditServiceManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final HikariDataSource dataSource;

    private AuditLogger auditLogger;
    private HybridAuditManager hybridAuditManager;
    private AuditLogCleanup auditLogCleanup;
    private AuditLogExporter auditLogExporter;

    public AuditServiceManager(Syncmoney plugin, SyncmoneyConfig config,
                              RedisManager redisManager, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.dataSource = dataSource;
    }

    /**
     * Initialize all audit components.
     */
    public void initialize() {

        var webAdminServer = plugin.getWebAdminServer();
        var sseManager = webAdminServer != null ? webAdminServer.getSseManager() : null;


        if (config.isAuditRedisEnabled() && redisManager != null && !redisManager.isDegraded()) {
            this.hybridAuditManager = new HybridAuditManager(plugin, config, redisManager, dataSource, sseManager);
            plugin.getLogger().info("HybridAuditManager initialized (Redis sliding window mode)");
        }


        this.auditLogger = new AuditLogger(plugin, config, dataSource);
        plugin.getLogger().fine("AuditLogger initialized");

        if (dataSource != null) {
            this.auditLogCleanup = new AuditLogCleanup(plugin, config, dataSource);
            auditLogCleanup.start();
            plugin.getLogger().fine("AuditLogCleanup started");

            this.auditLogExporter = new AuditLogExporter(plugin, config, dataSource);
            auditLogExporter.start();
            plugin.getLogger().fine("AuditLogExporter started");
        }

        plugin.getLogger().fine("Audit layer initialized");
    }

    /**
     * Shutdown audit components.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down audit layer...");


        if (hybridAuditManager != null) {
            hybridAuditManager.close();
            plugin.getLogger().fine("HybridAuditManager shutdown");
        }


        if (auditLogger != null) {
            auditLogger.close();
            plugin.getLogger().fine("AuditLogger shutdown");
        }

        if (auditLogCleanup != null) {
            plugin.getLogger().fine("AuditLogCleanup shutdown");
        }

        if (auditLogExporter != null) {
            plugin.getLogger().fine("AuditLogExporter shutdown");
        }

        plugin.getLogger().fine("Audit layer shutdown complete");
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public HybridAuditManager getHybridAuditManager() {
        return hybridAuditManager;
    }

    public AuditLogCleanup getAuditLogCleanup() {
        return auditLogCleanup;
    }

    public AuditLogExporter getAuditLogExporter() {
        return auditLogExporter;
    }
}
