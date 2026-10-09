package noietime.syncmoney.web.nodes;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.nodes.NodesApiHandler;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.websocket.SseManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background health check service for Central Mode nodes.
 * Uses Folia's AsyncScheduler to periodically poll all configured nodes.
 *
 * Health checks run every 30 seconds by default.
 * Status changes are broadcast via SSE to connected web clients.
 *
 * Thread safety: All fields are accessed atomically or under proper synchronization.
 */
public class NodeHealthChecker implements Runnable {

    private static final long DEFAULT_INTERVAL_MS = 30_000L;
    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    private static final long READ_TIMEOUT_MS = 10_000L;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final NodeApiKeyStore apiKeyStore;
    private final SseManager sseManager;
    private final Map<String, NodeStatus> nodeStatuses = new ConcurrentHashMap<>();
    private final NodesApiHandler nodesApiHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long intervalMs = DEFAULT_INTERVAL_MS;

    private volatile boolean initialized = false;

    /**
     * Node health status enum.
     */
    public enum NodeStatus {
        ONLINE,
        OFFLINE,
        UNKNOWN,
        DISABLED
    }

    public NodeHealthChecker(Syncmoney plugin, SyncmoneyConfig config,
                           NodeApiKeyStore apiKeyStore, SseManager sseManager,
                           NodesApiHandler nodesApiHandler) {
        this.plugin = plugin;
        this.config = config;
        this.apiKeyStore = apiKeyStore;
        this.sseManager = sseManager;
        this.nodesApiHandler = nodesApiHandler;
    }

    /**
     * Initialize and start the health check scheduler.
     * Uses Folia's AsyncScheduler for background execution.
     *
     * Performs an immediate health check on startup to populate node status cache,
     * then schedules periodic checks at the configured interval.
     */
    public void init() {
        if (initialized) {
            return;
        }

        if (!config.isCentralMode()) {
            plugin.getLogger().fine("NodeHealthChecker: Central mode disabled, not starting health checks");
            return;
        }

        running.set(true);

        
        
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            performHealthCheck();
        });

        scheduleNextCheck();
        initialized = true;
        plugin.getLogger().fine("NodeHealthChecker initialized with interval: " + intervalMs + "ms");
    }

    /**
     * Schedule the next health check using Folia's AsyncScheduler.
     */
    private void scheduleNextCheck() {
        if (!running.get()) {
            return;
        }

        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (running.get()) {
                    performHealthCheck();
                    scheduleNextCheck();
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("NodeHealthChecker: Failed to schedule next check: " + e.getMessage());
        }
    }

    /**
     * Perform health check on all enabled nodes.
     */
    private void performHealthCheck() {
        if (!config.isCentralMode()) {
            return;
        }

        for (SyncmoneyConfig.NodeConfig node : config.getNodes()) {
            NodeStatus previousStatus = nodeStatuses.getOrDefault(node.name, NodeStatus.UNKNOWN);
            NodeStatus newStatus = checkNode(node);

            nodeStatuses.put(node.name, newStatus);

            
            
            syncStatusToCache(node.url, newStatus);

            if (previousStatus != newStatus) {
                plugin.getLogger().fine("NodeHealthChecker: " + node.name + " status changed from " +
                        previousStatus + " to " + newStatus);
                broadcastStatusChange(node.name, node.url, newStatus);
            }
        }
    }

    /**
     * Sync node status to NodesApiHandler's cache for fast API responses.
     */
    private void syncStatusToCache(String nodeUrl, NodeStatus status) {
        if (nodesApiHandler != null) {
            String statusStr = switch (status) {
                case ONLINE -> "online";
                case OFFLINE -> "offline";
                case DISABLED -> "disabled";
                case UNKNOWN -> "unknown";
            };
            nodesApiHandler.updateNodeStatus(nodeUrl, statusStr);
        }
    }

    /**
     * Check the health of a single node by calling its /api/system/status endpoint.
     */
    private NodeStatus checkNode(SyncmoneyConfig.NodeConfig node) {
        if (!node.enabled) {
            return NodeStatus.DISABLED;
        }

        try {
            String apiKey = node.apiKey;
            if (apiKeyStore != null && apiKey != null && apiKey.startsWith("enc:")) {
                apiKey = apiKeyStore.decrypt(apiKey.substring(4), getMasterKey());
            }

            URI baseUri = URI.create(node.url);
            String healthUrl = node.url.replace(baseUri.getPath(), "") + "/api/system/status";

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return NodeStatus.ONLINE;
            } else {
                return NodeStatus.OFFLINE;
            }

        } catch (Exception e) {
            plugin.getLogger().fine("NodeHealthChecker: Failed to check node " + node.name +
                    " at " + node.url + ": " + e.getMessage());
            return NodeStatus.OFFLINE;
        }
    }

    /**
     * Broadcast node status change via SSE.
     */
    private void broadcastStatusChange(String nodeName, String nodeUrl, NodeStatus status) {
        if (sseManager != null) {
            try {
                String jsonMessage = String.format(
                        "{\"type\":\"node_status\",\"event\":\"NodeStatusChange\",\"data\":{" +
                                "\"nodeName\":\"%s\"," +
                                "\"url\":\"%s\"," +
                                "\"status\":\"%s\"," +
                                "\"timestamp\":%d}}",
                        escapeJson(nodeName),
                        escapeJson(nodeUrl),
                        status.name().toLowerCase(),
                        System.currentTimeMillis()
                );
                sseManager.broadcastToChannel("system", jsonMessage);
            } catch (Exception e) {
                plugin.getLogger().fine("NodeHealthChecker: Failed to broadcast status change: " + e.getMessage());
            }
        }
    }

    /**
     * Get the master key for API key decryption.
     */
    private String getMasterKey() {
        return config.getConfig().getString("web-admin.security.api-key", "default-master-key");
    }

    /**
     * Get current status of a specific node.
     */
    public NodeStatus getNodeStatus(String nodeName) {
        return nodeStatuses.getOrDefault(nodeName, NodeStatus.UNKNOWN);
    }

    /**
     * Get all node statuses.
     */
    public Map<String, NodeStatus> getAllStatuses() {
        return Map.copyOf(nodeStatuses);
    }

    /**
     * Update health check interval.
     */
    public void setInterval(long intervalMs) {
        this.intervalMs = Math.max(5000, intervalMs);
    }

    /**
     * Get current interval.
     */
    public long getInterval() {
        return intervalMs;
    }

    /**
     * Stop the health check scheduler.
     */
    public void shutdown() {
        running.set(false);
        plugin.getLogger().fine("NodeHealthChecker shutdown");
    }

    /**
     * Run the health check (called by scheduler).
     */
    @Override
    public void run() {
        performHealthCheck();
    }

    /**
     * Escape JSON string.
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
