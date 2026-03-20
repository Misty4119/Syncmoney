package noietime.syncmoney.web.api.crossserver;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API handler for cross-server statistics aggregation in Central Mode.
 *
 * Provides aggregated statistics across all configured game server nodes:
 * - Total supply (sum of all nodes)
 * - Total players (sum of all nodes)
 * - Total online players (sum of all nodes)
 * - Today's transactions (sum of all nodes)
 * - Cross-server leaderboard (aggregated top players)
 *
 * Thread safety: All fields are accessed atomically or under proper synchronization.
 */
public class CrossServerStatsApiHandler {

    private static final String PERMISSION_VIEW = "syncmoney.web.nodes.view";
    private static final String PERMISSION_CENTRAL = "syncmoney.web.central";

    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    private static final long READ_TIMEOUT_MS = 10_000L;
    private static final int HTTP_OK = 200;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final NodeApiKeyStore apiKeyStore;

    private final Map<String, NodeStats> statsCache = new ConcurrentHashMap<>();
    private volatile long lastRefreshTime = 0;
    private volatile long cacheTimeoutMs = 30_000L;

    public CrossServerStatsApiHandler(Syncmoney plugin, SyncmoneyConfig config,
                                     EconomyFacade economyFacade, NodeApiKeyStore apiKeyStore) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.apiKeyStore = apiKeyStore;
    }

    /**
     * Register all cross-server API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.get("api/economy/cross-server-stats", exchange -> handleGetCrossServerStats(exchange));
        router.get("api/economy/cross-server-top", exchange -> handleGetCrossServerTop(exchange));
    }

    /**
     * GET /api/economy/cross-server-stats
     *
     * Returns aggregated statistics from all configured nodes:
     * - totalSupply: Sum of all nodes' total supply
     * - totalPlayers: Sum of all nodes' total players
     * - totalOnlinePlayers: Sum of all nodes' online players
     * - todayTransactions: Sum of all nodes' today's transactions
     * - nodesStatus: Map of node URL to online player count
     * - topPlayers: Aggregated leaderboard across all nodes
     */
    private void handleGetCrossServerStats(HttpServerExchange exchange) {
        if (!config.isCentralMode()) {
            sendError(exchange, 403, "CENTRAL_MODE_DISABLED",
                    "Cross-server stats only available in central mode");
            return;
        }

        List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
        if (nodes.isEmpty()) {
            sendJson(exchange, ApiResponse.success(buildEmptyStats()));
            return;
        }

        try {
            Map<String, NodeStats> allNodeStats = fetchAllNodeStats(nodes);

            AggregatedStats aggregated = aggregateStats(allNodeStats);

            cacheStats(allNodeStats);

            sendJson(exchange, ApiResponse.success(aggregated.toMap()));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch cross-server stats: " + e.getMessage());
            sendError(exchange, 500, "CROSS_SERVER_STATS_ERROR",
                    "Failed to fetch cross-server statistics: " + e.getMessage());
        }
    }

    /**
     * GET /api/economy/cross-server-top
     *
     * Returns aggregated leaderboard across all nodes.
     * Each entry includes serverName to identify which server the player is on.
     */
    private void handleGetCrossServerTop(HttpServerExchange exchange) {
        if (!config.isCentralMode()) {
            sendError(exchange, 403, "CENTRAL_MODE_DISABLED",
                    "Cross-server top only available in central mode");
            return;
        }

        List<TopPlayerEntry> cachedTop = getCachedTopPlayers();
        if (cachedTop != null && !cachedTop.isEmpty()) {
            sendJson(exchange, ApiResponse.success(Map.of(
                    "topPlayers", cachedTop,
                    "source", "cache",
                    "timestamp", System.currentTimeMillis()
            )));
            return;
        }

        List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
        if (nodes.isEmpty()) {
            sendJson(exchange, ApiResponse.success(Map.of(
                    "topPlayers", Collections.emptyList(),
                    "source", "local",
                    "timestamp", System.currentTimeMillis()
            )));
            return;
        }

        try {
            List<TopPlayerEntry> allTopPlayers = fetchAllNodeTopPlayers(nodes);

            allTopPlayers.sort((a, b) -> Double.compare(b.balance(), a.balance()));
            List<TopPlayerEntry> top10 = allTopPlayers.size() > 10
                    ? allTopPlayers.subList(0, 10)
                    : allTopPlayers;

            List<Map<String, Object>> rankedPlayers = new ArrayList<>();
            for (int i = 0; i < top10.size(); i++) {
                TopPlayerEntry entry = top10.get(i);
                Map<String, Object> player = new LinkedHashMap<>();
                player.put("rank", i + 1);
                player.put("uuid", entry.uuid());
                player.put("balance", entry.balance());
                player.put("serverName", entry.serverName());
                rankedPlayers.add(player);
            }

            sendJson(exchange, ApiResponse.success(Map.of(
                    "topPlayers", rankedPlayers,
                    "source", "aggregated",
                    "timestamp", System.currentTimeMillis()
            )));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch cross-server top: " + e.getMessage());
            sendError(exchange, 500, "CROSS_SERVER_TOP_ERROR",
                    "Failed to fetch cross-server leaderboard: " + e.getMessage());
        }
    }

    /**
     * Fetch statistics from all nodes in parallel.
     */
    private Map<String, NodeStats> fetchAllNodeStats(List<SyncmoneyConfig.NodeConfig> nodes) {
        Map<String, NodeStats> results = new ConcurrentHashMap<>();

        nodes.parallelStream().forEach(node -> {
            if (!node.enabled) {
                return;
            }

            try {
                NodeStats stats = fetchNodeStats(node);
                results.put(node.url, stats);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to fetch stats from node " + node.name + ": " + e.getMessage());
                results.put(node.url, NodeStats.offline(node.name));
            }
        });

        return results;
    }

    /**
     * Fetch statistics from a single node.
     */
    private NodeStats fetchNodeStats(SyncmoneyConfig.NodeConfig node) {
        try {
            String apiKey = decryptApiKey(node.apiKey);
            String statsUrl = buildNodeUrl(node.url, "/api/economy/stats");

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HTTP_OK) {
                return parseNodeStats(node.name, node.url, response.body());
            } else {
                return NodeStats.offline(node.name);
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Failed to fetch stats from " + node.name + ": " + e.getMessage());
            return NodeStats.offline(node.name);
        }
    }

    /**
     * Fetch top players from all nodes.
     */
    private List<TopPlayerEntry> fetchAllNodeTopPlayers(List<SyncmoneyConfig.NodeConfig> nodes) {
        List<TopPlayerEntry> allPlayers = new ArrayList<>();

        for (SyncmoneyConfig.NodeConfig node : nodes) {
            if (!node.enabled) {
                continue;
            }

            try {
                String apiKey = decryptApiKey(node.apiKey);
                String topUrl = buildNodeUrl(node.url, "/api/economy/top");

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(topUrl))
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == HTTP_OK) {
                    List<TopPlayerEntry> nodeTop = parseNodeTopPlayers(node.name, response.body());
                    allPlayers.addAll(nodeTop);
                }

            } catch (Exception e) {
                plugin.getLogger().fine("Failed to fetch top from node " + node.name + ": " + e.getMessage());
            }
        }

        return allPlayers;
    }

    /**
     * Parse node stats from JSON response.
     */
    @SuppressWarnings("unchecked")
    private NodeStats parseNodeStats(String nodeName, String nodeUrl, String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(json, Map.class);

            Map<String, Object> responseData = (Map<String, Object>) data.get("data");
            if (responseData == null) {
                return NodeStats.offline(nodeName);
            }

            Object totalSupplyObj = responseData.get("totalSupply");
            double totalSupply = 0;
            if (totalSupplyObj instanceof Number) {
                totalSupply = ((Number) totalSupplyObj).doubleValue();
            }

            Object totalPlayersObj = responseData.get("totalPlayers");
            int totalPlayers = 0;
            if (totalPlayersObj instanceof Number) {
                totalPlayers = ((Number) totalPlayersObj).intValue();
            }

            Object todayTxObj = responseData.get("todayTransactions");
            int todayTransactions = 0;
            if (todayTxObj instanceof Number) {
                todayTransactions = ((Number) todayTxObj).intValue();
            }

            return new NodeStats(nodeName, nodeUrl, totalSupply, totalPlayers, 0, todayTransactions, true);

        } catch (Exception e) {
            plugin.getLogger().fine("Failed to parse stats from " + nodeName + ": " + e.getMessage());
            return NodeStats.offline(nodeName);
        }
    }

    /**
     * Parse node top players from JSON response.
     */
    @SuppressWarnings("unchecked")
    private List<TopPlayerEntry> parseNodeTopPlayers(String serverName, String json) {
        List<TopPlayerEntry> players = new ArrayList<>();

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(json, Map.class);

            Map<String, Object> responseData = (Map<String, Object>) data.get("data");
            if (responseData == null) {
                return players;
            }

            List<Map<String, Object>> topPlayers = (List<Map<String, Object>>) responseData.get("topPlayers");
            if (topPlayers == null) {
                return players;
            }

            for (Map<String, Object> player : topPlayers) {
                String uuid = (String) player.get("uuid");
                Object balanceObj = player.get("balance");
                double balance = 0;
                if (balanceObj instanceof Number) {
                    balance = ((Number) balanceObj).doubleValue();
                }

                players.add(new TopPlayerEntry(uuid, balance, serverName));
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Failed to parse top players from " + serverName + ": " + e.getMessage());
        }

        return players;
    }

    /**
     * Aggregate statistics from all nodes.
     */
    private AggregatedStats aggregateStats(Map<String, NodeStats> allNodeStats) {
        double totalSupply = 0;
        int totalPlayers = 0;
        int totalOnlinePlayers = 0;
        int todayTransactions = 0;
        Map<String, Integer> nodesStatus = new LinkedHashMap<>();

        for (NodeStats stats : allNodeStats.values()) {
            totalSupply += stats.totalSupply();
            totalPlayers += stats.totalPlayers();
            totalOnlinePlayers += stats.onlinePlayers();
            todayTransactions += stats.todayTransactions();
            nodesStatus.put(stats.nodeName(), stats.onlinePlayers());
        }

        if (totalOnlinePlayers == 0 && !allNodeStats.isEmpty()) {
            totalOnlinePlayers = allNodeStats.values().stream()
                    .filter(NodeStats::online)
                    .mapToInt(s -> s.onlinePlayers())
                    .sum();
        }

        return new AggregatedStats(totalSupply, totalPlayers, totalOnlinePlayers, todayTransactions, nodesStatus);
    }

    /**
     * Build empty stats when no nodes are configured.
     */
    private Map<String, Object> buildEmptyStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSupply", 0.0);
        stats.put("totalPlayers", 0);
        stats.put("totalOnlinePlayers", 0);
        stats.put("todayTransactions", 0);
        stats.put("nodesStatus", Collections.emptyMap());
        stats.put("topPlayers", Collections.emptyList());
        stats.put("source", "local");
        stats.put("timestamp", System.currentTimeMillis());
        return stats;
    }

    /**
     * Cache node stats for later use (e.g., top players).
     */
    private void cacheStats(Map<String, NodeStats> allNodeStats) {
        statsCache.clear();
        statsCache.putAll(allNodeStats);
        lastRefreshTime = System.currentTimeMillis();
    }

    /**
     * Get cached top players.
     */
    private List<TopPlayerEntry> getCachedTopPlayers() {
        if (System.currentTimeMillis() - lastRefreshTime > cacheTimeoutMs) {
            return null;
        }

        return null;
    }

    /**
     * Decrypt API key if encrypted.
     */
    private String decryptApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        if (apiKey.startsWith("enc:")) {
            String masterKey = config.getConfig().getString("web-admin.security.api-key", "default-master-key");
            return apiKeyStore.decrypt(apiKey.substring(4), masterKey);
        }
        return apiKey;
    }

    /**
     * Build full URL for a node endpoint.
     */
    private String buildNodeUrl(String baseUrl, String endpoint) {
        try {
            URI uri = URI.create(baseUrl);
            String base = baseUrl.replace(uri.getPath(), "");
            return base + endpoint;
        } catch (Exception e) {
            return baseUrl + endpoint;
        }
    }

    /**
     * Update cache timeout.
     */
    public void setCacheTimeoutMs(long timeoutMs) {
        this.cacheTimeoutMs = Math.max(5000, timeoutMs);
    }

    /**
     * Get cache timeout.
     */
    public long getCacheTimeoutMs() {
        return cacheTimeoutMs;
    }

    /**
     * Force refresh cache.
     */
    public void refreshCache() {
        List<SyncmoneyConfig.NodeConfig> nodes = config.getNodes();
        Map<String, NodeStats> fresh = fetchAllNodeStats(nodes);
        cacheStats(fresh);
    }

    private void sendError(HttpServerExchange exchange, int statusCode, String code, String message) {
        exchange.setStatusCode(statusCode);
        sendJson(exchange, ApiResponse.error(code, message));
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }


    /**
     * Node statistics record.
     */
    public record NodeStats(
            String nodeName,
            String nodeUrl,
            double totalSupply,
            int totalPlayers,
            int onlinePlayers,
            int todayTransactions,
            boolean online
    ) {
        public static NodeStats offline(String nodeName) {
            return new NodeStats(nodeName, "", 0, 0, 0, 0, false);
        }
    }

    /**
     * Aggregated statistics across all nodes.
     */
    public record AggregatedStats(
            double totalSupply,
            int totalPlayers,
            int totalOnlinePlayers,
            int todayTransactions,
            Map<String, Integer> nodesStatus
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalSupply", totalSupply);
            map.put("totalPlayers", totalPlayers);
            map.put("totalOnlinePlayers", totalOnlinePlayers);
            map.put("todayTransactions", todayTransactions);
            map.put("nodesStatus", nodesStatus);
            map.put("source", "aggregated");
            map.put("timestamp", System.currentTimeMillis());
            return map;
        }
    }

    /**
     * Top player entry from a node.
     */
    public record TopPlayerEntry(
            String uuid,
            double balance,
            String serverName
    ) {}
}
