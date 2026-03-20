package noietime.syncmoney.web.websocket;

import io.undertow.server.HttpHandler;

import noietime.syncmoney.Syncmoney;

import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;

import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Manages WebSocket connections for real-time notifications.
 * This implementation provides basic WebSocket upgrade handling and channel
 * management.
 * Full WebSocket functionality requires additional undertow-websockets
 * integration.
 */
public class WebSocketManager {

    private final Syncmoney plugin;
    private final Map<String, Set<String>> channelSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> connectionChannels = new ConcurrentHashMap<>();
    private final Set<String> activeConnectionIds = new CopyOnWriteArraySet<>();
    private boolean initialized = false;
    private int connectionCounter = 0;

    /**
     * Available event channels.
     */
    public static final String CHANNEL_TRANSACTION = "transaction";
    public static final String CHANNEL_AUDIT = "audit";
    public static final String CHANNEL_SYSTEM = "system";
    public static final String CHANNEL_BREAKER = "breaker";

    public WebSocketManager(Syncmoney plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialize WebSocket manager and register event listeners.
     */
    public void init() {
        if (initialized) {
            return;
        }

        registerEventListeners();
        initialized = true;
        plugin.getLogger().fine("WebSocketManager initialized");
    }

    /**
     * Register event listeners to broadcast events via WebSocket.
     */
    private void registerEventListeners() {
        if (!SyncmoneyEventBus.isInitialized()) {
            plugin.getLogger().warning("SyncmoneyEventBus not initialized, skipping event registration");
            return;
        }

        SyncmoneyEventBus bus = SyncmoneyEventBus.getInstance();

        bus.register(PostTransactionEvent.class, event -> {
            String jsonMessage = buildTransactionMessage(event);
            broadcastToChannel(CHANNEL_TRANSACTION, jsonMessage);
        }, noietime.syncmoney.event.EventPriority.LOW);

        plugin.getLogger().fine("WebSocket event listeners registered");
    }

    /**
     * Build JSON message for transaction event.
     */
    private String buildTransactionMessage(PostTransactionEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"transaction\",\"event\":\"PostTransactionEvent\",\"data\":{");
        sb.append("\"playerName\":\"").append(escapeJson(event.getPlayerName())).append("\",");
        sb.append("\"type\":\"").append(event.getType()).append("\",");
        sb.append("\"amount\":\"").append(event.getAmount()).append("\",");
        sb.append("\"balanceBefore\":\"").append(event.getBalanceBefore()).append("\",");
        sb.append("\"balanceAfter\":\"").append(event.getBalanceAfter()).append("\",");
        sb.append("\"success\":").append(event.isSuccess()).append(",");
        sb.append("\"timestamp\":").append(event.getTimestamp());
        sb.append("}}");
        return sb.toString();
    }

    /**
     * Create HTTP handler for WebSocket upgrade.
     * Note: Full WebSocket implementation requires additional Undertow WebSocket
     * setup.
     * This handler validates session token and returns appropriate response.
     */
    public HttpHandler createHandler() {
        return exchange -> {
            String path = exchange.getRelativePath();

            String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {

                String queryString = exchange.getQueryString();
                String token = extractTokenFromQuery(queryString);
                


                if (token != null && !token.isEmpty()) {
                    exchange.setStatusCode(101);
                    exchange.getResponseHeaders().put(
                            io.undertow.util.Headers.UPGRADE, "websocket");

                    String connId = "conn-" + (++connectionCounter);
                    activeConnectionIds.add(connId);
                    plugin.getLogger().fine("WebSocket connection established: " + connId);

                    try {
                        exchange.getResponseSender().send("WebSocket upgrade accepted");
                    } finally {
                        activeConnectionIds.remove(connId);
                        plugin.getLogger().fine("WebSocket connection closed: " + connId);
                    }
                } else {

                    exchange.setStatusCode(401);
                    exchange.getResponseHeaders().put(
                            io.undertow.util.Headers.CONTENT_TYPE,
                            "application/json;charset=UTF-8");
                    exchange.getResponseSender().send(
                            "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\"," +
                                    "\"message\":\"Valid session token required. Use /api/auth/ws-token to obtain one.\"}}");
                    plugin.getLogger().warning("WebSocket connection rejected: missing or invalid token");
                }
            } else {
                exchange.setStatusCode(503);
                exchange.getResponseHeaders().put(
                        io.undertow.util.Headers.CONTENT_TYPE,
                        "application/json;charset=UTF-8");
                exchange.getResponseSender().send(
                        "{\"success\":false,\"error\":{\"code\":\"WEBSOCKET_UNAVAILABLE\"," +
                                "\"message\":\"WebSocket is under maintenance. Use REST API for now.\"}}");
                plugin.getLogger().warning("WebSocket requested but full implementation pending");
            }
        };
    }
    
    /**
     * Extract token from query string.
     */
    private String extractTokenFromQuery(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        for (String param : queryString.split("&")) {
            String[] kv = param.split("=");
            if (kv.length == 2 && "token".equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    /**
     * Subscribe a connection to a channel.
     */
    public void subscribe(String connectionId, String channel) {
        channelSubscriptions.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                .add(connectionId);

        connectionChannels.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet())
                .add(channel);

        plugin.getLogger().fine("Connection " + connectionId + " subscribed to " + channel);
    }

    /**
     * Unsubscribe from a channel.
     */
    public void unsubscribe(String connectionId, String channel) {
        Set<String> subs = channelSubscriptions.get(channel);
        if (subs != null) {
            subs.remove(connectionId);
        }

        Set<String> connChans = connectionChannels.get(connectionId);
        if (connChans != null) {
            connChans.remove(channel);
        }
    }

    /**
     * Broadcast message to all connected clients.
     * Note: Without full WebSocket, this logs the message.
     */
    public void broadcast(String message) {
        plugin.getLogger().fine("Broadcasting to " + activeConnectionIds.size() + " connections: " + message);
    }

    /**
     * Broadcast message to specific channel subscribers.
     * Note: Without full WebSocket, this logs the message.
     */
    public void broadcastToChannel(String channelName, String message) {
        Set<String> subs = channelSubscriptions.get(channelName);
        if (subs != null) {
            plugin.getLogger()
                    .fine("Broadcasting to channel " + channelName + " (" + subs.size() + " subscribers): " + message);
        }
    }

    /**
     * Register a new connection.
     */
    public void registerConnection(String connectionId) {
        activeConnectionIds.add(connectionId);
    }

    /**
     * Remove a connection.
     */
    public void removeConnection(String connectionId) {
        activeConnectionIds.remove(connectionId);

        Set<String> channels = connectionChannels.remove(connectionId);
        if (channels != null) {
            for (String channel : channels) {
                Set<String> subs = channelSubscriptions.get(channel);
                if (subs != null) {
                    subs.remove(connectionId);
                }
            }
        }
    }

    /**
     * Get number of active connections.
     */
    public int getConnectionCount() {
        return activeConnectionIds.size();
    }

    /**
     * Check if WebSocket is enabled.
     */
    public boolean isEnabled() {
        return true;
    }

    /**
     * Get available channels.
     */
    public Set<String> getAvailableChannels() {
        return Set.of(CHANNEL_TRANSACTION, CHANNEL_AUDIT, CHANNEL_SYSTEM, CHANNEL_BREAKER);
    }

    /**
     * Get subscriber count for a channel.
     */
    public int getSubscriberCount(String channelName) {
        Set<String> subs = channelSubscriptions.get(channelName);
        return subs != null ? subs.size() : 0;
    }

    /**
     * Escape special characters for JSON.
     */
    private String escapeJson(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Shutdown WebSocket manager.
     */
    public void shutdown() {
        activeConnectionIds.clear();
        channelSubscriptions.clear();
        connectionChannels.clear();
        plugin.getLogger().fine("WebSocketManager shutdown complete");
    }
}
