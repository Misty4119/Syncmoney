package noietime.syncmoney.web.api.nodes;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.ConfigManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.util.concurrent.ConcurrentHashMap;

/**
 * API router for all node-related endpoints.
 * Delegates to specialized handlers for each concern.
 *
 * Sub-handlers:
 * - NodeOperationsHandler  — CRUD, list, ping
 * - NodeStatusHandler    — Detailed status fetching
 * - NodeProxyHandler     — Request proxying to remote nodes
 * - ConfigSyncHandler    — Config push/receive between central and nodes
 */
public class NodesApiHandler {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final PermissionChecker permissionChecker;
    private final NodeApiKeyStore apiKeyStore;
    private final ConcurrentHashMap<String, String> nodeStatusCache = new ConcurrentHashMap<>();

    private final NodeOperationsHandler operationsHandler;
    private final NodeStatusHandler statusHandler;
    private final NodeProxyHandler proxyHandler;
    private final ConfigSyncHandler configSyncHandler;

    private volatile noietime.syncmoney.web.nodes.NodeHealthChecker nodeHealthChecker;

    public NodesApiHandler(Syncmoney plugin, SyncmoneyConfig config,
                          PermissionChecker permissionChecker, NodeApiKeyStore apiKeyStore) {
        this.plugin = plugin;
        this.config = config;
        this.permissionChecker = permissionChecker;
        this.apiKeyStore = apiKeyStore;

        
        this.operationsHandler = new NodeOperationsHandler(plugin, config, permissionChecker, apiKeyStore);
        this.statusHandler = new NodeStatusHandler(plugin, config, permissionChecker, apiKeyStore);
        this.proxyHandler = new NodeProxyHandler(plugin, config, permissionChecker, apiKeyStore);
        this.configSyncHandler = new ConfigSyncHandler(plugin, config, permissionChecker, apiKeyStore);
    }

    public void setNodeHealthChecker(noietime.syncmoney.web.nodes.NodeHealthChecker healthChecker) {
        this.nodeHealthChecker = healthChecker;
    }

    public void setConfigManager(ConfigManager configManager) {
        this.configSyncHandler.setConfigManager(configManager);
    }

    /**
     * Register all node API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        
        router.get("api/nodes", exchange -> operationsHandler.handleGetNodes(exchange));
        router.post("api/nodes", exchange -> operationsHandler.handleCreateNode(exchange));
        router.put("api/nodes/{index}", exchange -> operationsHandler.handleUpdateNode(exchange));
        router.delete("api/nodes/{index}", exchange -> operationsHandler.handleDeleteNode(exchange));
        router.post("api/nodes/{index}/ping", exchange -> operationsHandler.handlePingNode(exchange));

        
        router.get("api/nodes/status", exchange -> statusHandler.handleGetNodesStatus(exchange));

        
        router.post("api/nodes/{index}/proxy", exchange -> proxyHandler.handleNodeProxy(exchange));

        
        router.post("api/nodes/sync", exchange -> configSyncHandler.handleSyncConfigToAllNodes(exchange));
        router.post("api/nodes/{index}/sync", exchange -> configSyncHandler.handleSyncConfigToNode(exchange));

        
        router.post("api/config/sync", exchange -> configSyncHandler.handleReceiveConfigSync(exchange));
    }

    

    public void updateNodeStatus(String nodeUrl, String status) {
        nodeStatusCache.put(nodeUrl, status);
    }

    public String getNodeStatus(String nodeUrl) {
        return nodeStatusCache.getOrDefault(nodeUrl, "unknown");
    }
}
