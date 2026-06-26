package noietime.syncmoney.web.api.system;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.web.api.AbstractApiHandler;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.server.HttpHandlerRegistry;
import noietime.syncmoney.web.util.HttpClientUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API handler for system status endpoints.
 * Provides access to plugin, economy, Redis, database, and circuit-breaker status.
 */
public class SystemApiHandler extends AbstractApiHandler {

    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final DatabaseManager databaseManager;
    private final EconomicCircuitBreaker circuitBreaker;
    private final long startTimeMs;
    private final noietime.syncmoney.schema.SchemaManager schemaManager;
    private final NodeApiKeyStore apiKeyStore;

    public SystemApiHandler(Syncmoney plugin, SyncmoneyConfig config,
                            RedisManager redisManager,
                            DatabaseManager databaseManager,
                            EconomicCircuitBreaker circuitBreaker,
                            noietime.syncmoney.schema.SchemaManager schemaManager,
                            NodeApiKeyStore apiKeyStore) {
        super(plugin);
        this.config = config;
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
        this.circuitBreaker = circuitBreaker;
        this.schemaManager = schemaManager;
        this.apiKeyStore = apiKeyStore;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Constructor without an explicit {@link NodeApiKeyStore} (kept for source compatibility).
     */
    public SystemApiHandler(Syncmoney plugin, SyncmoneyConfig config,
                            RedisManager redisManager,
                            DatabaseManager databaseManager,
                            EconomicCircuitBreaker circuitBreaker,
                            noietime.syncmoney.schema.SchemaManager schemaManager) {
        this(plugin, config, redisManager, databaseManager, circuitBreaker, schemaManager, new NodeApiKeyStore());
    }

    /**
     * Legacy constructor without SyncmoneyConfig (kept for source compatibility).
     */
    public SystemApiHandler(Syncmoney plugin, RedisManager redisManager,
                            DatabaseManager databaseManager,
                            EconomicCircuitBreaker circuitBreaker) {
        this(plugin, null, redisManager, databaseManager, circuitBreaker, null, new NodeApiKeyStore());
    }

    /**
     * Register all system API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.get("api/system/status",  exchange -> handleGetStatus(exchange));
        router.get("api/system/redis",   exchange -> handleGetRedisStatus(exchange));
        router.get("api/system/breaker", exchange -> handleGetBreakerStatus(exchange));
        router.get("api/system/metrics", exchange -> handleGetMetrics(exchange));
        router.get("api/nodes", exchange -> handleGetNodes(exchange));
        router.get("api/nodes/status", exchange -> handleGetNodesStatus(exchange));
    }

    /**
     * GET /api/system/status
     * Returns nested structure matching the frontend SystemStatus TypeScript type.
     */
    private void handleGetStatus(HttpServerExchange exchange) {
        Map<String, Object> data = new LinkedHashMap<>();

        Map<String, Object> pluginInfo = new LinkedHashMap<>();
        pluginInfo.put("name", plugin.getDescription().getName());
        pluginInfo.put("version", plugin.getDescription().getVersion());



        int dbVersion = 0;
        if (schemaManager != null) {
            dbVersion = schemaManager.getDatabaseVersion();
        } else if (databaseManager != null) {
            dbVersion = databaseManager.getSchemaVersion();
        }
        pluginInfo.put("dbVersion", dbVersion);
        String economyMode = config != null ? config.getEconomyMode().name() : "UNKNOWN";
        pluginInfo.put("mode", economyMode);
        pluginInfo.put("uptime", System.currentTimeMillis() - startTimeMs);
        data.put("plugin", pluginInfo);

        Map<String, Object> economyInfo = new LinkedHashMap<>();
        economyInfo.put("mode", economyMode);
        economyInfo.put("currencyName", config != null ? config.display().getCurrencyName() : "dollars");
        data.put("economy", economyInfo);

        Map<String, Object> redisInfo = new LinkedHashMap<>();
        boolean redisConnected = redisManager != null && redisManager.isConnected();
        redisInfo.put("connected", redisConnected);
        redisInfo.put("enabled", redisManager != null);
        data.put("redis", redisInfo);

        Map<String, Object> dbInfo = new LinkedHashMap<>();
        boolean dbConnected = databaseManager != null && databaseManager.isConnected();
        dbInfo.put("connected", dbConnected);
        dbInfo.put("enabled", databaseManager != null);
        dbInfo.put("type", config != null ? config.database().getDatabaseType() : "none");
        data.put("database", dbInfo);

        Map<String, Object> cbInfo = new LinkedHashMap<>();
        if (circuitBreaker != null) {
            cbInfo.put("enabled", true);
            cbInfo.put("state", circuitBreaker.getState().name());
            cbInfo.put("lastTrigger", null);
        } else {
            cbInfo.put("enabled", false);
            cbInfo.put("state", "NORMAL");
            cbInfo.put("lastTrigger", null);
        }
        data.put("circuitBreaker", cbInfo);

        data.put("serverName", config != null ? config.getServerName() : "unknown");
        data.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
        data.put("maxPlayers", plugin.getServer().getMaxPlayers());

        sendJson(exchange, ApiResponse.success(data));
    }

    /**
     * GET /api/system/redis
     */
    private void handleGetRedisStatus(HttpServerExchange exchange) {
        Map<String, Object> status = new HashMap<>();

        if (redisManager != null) {
            status.put("connected", redisManager.isConnected());
            status.put("enabled", true);
        } else {
            status.put("connected", false);
            status.put("enabled", false);
        }

        sendJson(exchange, ApiResponse.success(status));
    }

    /**
     * GET /api/system/breaker
     */
    private void handleGetBreakerStatus(HttpServerExchange exchange) {
        Map<String, Object> status = new HashMap<>();

        if (circuitBreaker != null) {
            status.put("enabled", true);
            status.put("state", circuitBreaker.getState().name());
        } else {
            status.put("enabled", false);
            status.put("state", "UNKNOWN");
        }

        sendJson(exchange, ApiResponse.success(status));
    }

    /**
     * GET /api/system/metrics
     */
    private void handleGetMetrics(HttpServerExchange exchange) {
        Map<String, Object> metrics = new HashMap<>();

        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory  = runtime.freeMemory();
        long usedMemory  = totalMemory - freeMemory;

        Map<String, Object> memory = new HashMap<>();
        memory.put("total", totalMemory);
        memory.put("free", freeMemory);
        memory.put("used", usedMemory);
        memory.put("max", runtime.maxMemory());
        metrics.put("memory", memory);

        metrics.put("threads", Thread.activeCount());

        try {
            double[] tpsArray = plugin.getServer().getTPS();
            double currentTps = tpsArray.length > 0 ? tpsArray[0] : 20.0;
            metrics.put("tps", Math.min(20.0, Math.round(currentTps * 100.0) / 100.0));
        } catch (Exception e) {
            metrics.put("tps", 20.0);
        }

        sendJson(exchange, ApiResponse.success(metrics));
    }

    /**
     * GET /api/nodes
     * Obtain server node information (used to discover nodes in the central management panel).
     *
     * When central-mode=true, returns config.getNodes() list with status information.
     * When central-mode=false, returns only this server's information.
     */
    private void handleGetNodes(HttpServerExchange exchange) {
        if (config != null && config.isCentralMode()) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (SyncmoneyConfig.NodeConfig node : config.getNodes()) {
                Map<String, Object> nodeData = new LinkedHashMap<>();
                nodeData.put("name", node.name);
                nodeData.put("url", node.url);
                nodeData.put("apiKey", node.apiKey);
                nodeData.put("enabled", node.enabled);
                nodeData.put("status", getNodeStatus(node));
                nodes.add(nodeData);
            }
            sendJson(exchange, ApiResponse.success(nodes));
        } else {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serverName", config != null ? config.getServerName() : "unknown");
            data.put("serverId", plugin.getServer().getName());
            data.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
            data.put("maxPlayers", plugin.getServer().getMaxPlayers());

            if (config != null) {
                data.put("economyMode", config.getEconomyMode().name());
            }

            String selfUrl = config.getConfig().getString("web-admin.central-node.url", null);
            data.put("selfUrl", selfUrl);
            data.put("status", "online");
            data.put("centralMode", false);

            sendJson(exchange, ApiResponse.success(data));
        }
    }

    /**
     * GET /api/nodes/status
     * Returns node status information (onlinePlayers, maxPlayers, etc.) for each configured node.
     * When central-mode=true, returns aggregated status from all nodes.
     * When central-mode=false, returns only this server's information.
     */
    private void handleGetNodesStatus(HttpServerExchange exchange) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (config != null && config.isCentralMode()) {
            
            for (SyncmoneyConfig.NodeConfig node : config.getNodes()) {
                if (!node.enabled) {
                    continue;
                }
                try {
                    Map<String, Object> nodeStatus = fetchNodeStatus(node);
                    result.put(node.url, nodeStatus);
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to fetch status from node " + node.name + ": " + e.getMessage());
                    Map<String, Object> offlineStatus = new LinkedHashMap<>();
                    offlineStatus.put("status", "offline");
                    offlineStatus.put("onlinePlayers", 0);
                    offlineStatus.put("maxPlayers", 0);
                    offlineStatus.put("economyMode", "UNKNOWN");
                    offlineStatus.put("serverName", node.name);
                    result.put(node.url, offlineStatus);
                }
            }
        } else {
            
            Map<String, Object> selfStatus = new LinkedHashMap<>();
            selfStatus.put("serverName", config != null ? config.getServerName() : "unknown");
            selfStatus.put("serverId", plugin.getServer().getName());
            selfStatus.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
            selfStatus.put("maxPlayers", plugin.getServer().getMaxPlayers());
            selfStatus.put("status", "online");
            if (config != null) {
                selfStatus.put("economyMode", config.getEconomyMode().name());
            }
            result.put("self", selfStatus);
        }

        sendJson(exchange, ApiResponse.success(result));
    }

    /**
     * Fetch status from a remote node.
     */
    private Map<String, Object> fetchNodeStatus(SyncmoneyConfig.NodeConfig node) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("serverName", node.name);
        status.put("status", "online");
        status.put("onlinePlayers", 0);
        status.put("maxPlayers", 0);
        status.put("economyMode", "UNKNOWN");

        try {
            String apiKey = decryptNodeApiKey(node.apiKey);
            String statusUrl = node.url + "/api/system/status";

            java.net.http.HttpResponse<String> response = HttpClientUtil.getWithBearer(statusUrl, apiKey);

            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, Object> data = mapper.readValue(response.body(), java.util.Map.class);

                
                Object serverInfo = data.get("serverName");
                if (data.containsKey("onlinePlayers")) {
                    status.put("onlinePlayers", ((Number) data.get("onlinePlayers")).intValue());
                }
                if (data.containsKey("maxPlayers")) {
                    status.put("maxPlayers", ((Number) data.get("maxPlayers")).intValue());
                }
                if (data.containsKey("economy")) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> economy = (java.util.Map<String, Object>) data.get("economy");
                    if (economy != null && economy.containsKey("mode")) {
                        status.put("economyMode", economy.get("mode"));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to fetch status from node " + node.name + ": " + e.getMessage());
            status.put("status", "offline");
        }

        return status;
    }

    /**
     * Decrypt node API key if encrypted.
     *
     * <p>Uses the injected {@link NodeApiKeyStore} (JCA / AES-256-GCM) directly instead of
     * reflectively loading the class and invoking a non-existent static method.</p>
     */
    private String decryptNodeApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        if (apiKey.startsWith("enc:")) {
            String masterKey = config != null ?
                    config.getConfig().getString("web-admin.security.api-key", "default-master-key") :
                    "default-master-key";
            try {
                return apiKeyStore.decrypt(apiKey, masterKey);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to decrypt node API key: " + e.getMessage());
                return apiKey;
            }
        }
        return apiKey;
    }

    /**
     * Get the status of a node based on health check results.
     * This is a placeholder - actual status is maintained by NodeHealthChecker.
     */
    private String getNodeStatus(SyncmoneyConfig.NodeConfig node) {
        return "unknown";
    }
}
