package noietime.syncmoney.web.api.system;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API handler for system status endpoints.
 * Provides access to plugin, economy, Redis, database, and circuit-breaker status.
 */
public class SystemApiHandler {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final DatabaseManager databaseManager;
    private final EconomicCircuitBreaker circuitBreaker;
    private final long startTimeMs;

    public SystemApiHandler(Syncmoney plugin, SyncmoneyConfig config,
                            RedisManager redisManager,
                            DatabaseManager databaseManager,
                            EconomicCircuitBreaker circuitBreaker) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
        this.circuitBreaker = circuitBreaker;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Legacy constructor without SyncmoneyConfig (kept for source compatibility).
     */
    public SystemApiHandler(Syncmoney plugin, RedisManager redisManager,
                            DatabaseManager databaseManager,
                            EconomicCircuitBreaker circuitBreaker) {
        this(plugin, null, redisManager, databaseManager, circuitBreaker);
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



        int dbVersion = databaseManager != null ? databaseManager.getSchemaVersion() : 0;
        pluginInfo.put("dbVersion", dbVersion);
        String economyMode = config != null ? config.getEconomyMode().name() : "UNKNOWN";
        pluginInfo.put("mode", economyMode);
        pluginInfo.put("uptime", System.currentTimeMillis() - startTimeMs);
        data.put("plugin", pluginInfo);

        Map<String, Object> economyInfo = new LinkedHashMap<>();
        economyInfo.put("mode", economyMode);
        economyInfo.put("currencyName", config != null ? config.getCurrencyName() : "dollars");
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
        dbInfo.put("type", config != null ? config.getDatabaseType() : "none");
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
     * Get the status of a node based on health check results.
     * This is a placeholder - actual status is maintained by NodeHealthChecker.
     */
    private String getNodeStatus(SyncmoneyConfig.NodeConfig node) {
        return "unknown";
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8"
        );
        exchange.getResponseSender().send(json);
    }
}
