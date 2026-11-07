package noietime.syncmoney.permission;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;

/**
 * Unified manager for permission-related components.
 * Encapsulates AdminPermissionService.
 */
public class PermissionManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    
    private AdminPermissionService permissionService;

    public PermissionManager(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Initialize permission layer components.
     */
    public void initialize() {
        this.permissionService = new AdminPermissionService(config);
        plugin.getLogger().fine("Permission service initialized");
    }

    /**
     * Reload permission service.
     */
    public void reload() {
        this.permissionService = new AdminPermissionService(config);
        plugin.getLogger().fine("Permission service reloaded");
    }

    /**
     * Shutdown permission layer components.
     */
    public void shutdown() {
        plugin.getLogger().fine("Permission layer shutdown");
    }

    public AdminPermissionService getPermissionService() {
        return permissionService;
    }
}
