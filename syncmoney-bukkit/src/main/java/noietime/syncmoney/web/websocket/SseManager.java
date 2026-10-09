package noietime.syncmoney.web.websocket;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.web.api.auth.WsTokenHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Server-Sent Events (SSE) connections for real-time notifications.
 *
 * SSE requires a persistent streaming HTTP connection. This implementation uses
 * exchange.startBlocking() + getOutputStream() so Undertow does NOT set
 * Content-Length or call endExchange() after the first write — both of which
 * would immediately close the connection when using ResponseSender.send(String).
 *
 * Thread model:
 *  - Each SSE connection is dispatched to a Undertow worker thread via exchange.dispatch().
 *  - That worker thread blocks in a keepalive loop, flushing a comment every 15 s.
 *  - Broadcast events (from the game event bus) write directly to the OutputStream
 *    under a per-connection lock.
 */
public class SseManager {

    private final Syncmoney plugin;
    private final Map<String, SseConnection> connections = new ConcurrentHashMap<>();
    private final Set<String> channelSubscriptions = new CopyOnWriteArraySet<>();
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private boolean initialized = false;
    private String apiKey = null;
    private WsTokenHandler tokenHandler = null;

    public static final String CHANNEL_TRANSACTION = "transaction";
    public static final String CHANNEL_AUDIT = "audit";
    public static final String CHANNEL_SYSTEM = "system";
    public static final String CHANNEL_BREAKER = "breaker";

    public SseManager(Syncmoney plugin) {
        this.plugin = plugin;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setTokenHandler(WsTokenHandler tokenHandler) {
        this.tokenHandler = tokenHandler;
    }

    /**
     * Validate API key or session token from request.
     * [SEC-01 FIX] Uses constant-time comparison to prevent timing attacks.
     */
    private boolean validateApiKey(HttpServerExchange exchange) {
        if (apiKey == null || apiKey.isEmpty()) {
            return true;
        }

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            if (tokenHandler != null && tokenHandler.validateToken(token)) {
                return true;
            }
            return constantTimeEquals(token, apiKey);
        }


        Deque<String> tokenValues = exchange.getQueryParameters().get("token");
        if (tokenValues != null && !tokenValues.isEmpty()) {
            String queryToken = tokenValues.getFirst();
            if (queryToken != null && !queryToken.isEmpty()) {
                if (tokenHandler != null && tokenHandler.validateToken(queryToken)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }

    public void init() {
        if (initialized) return;
        registerEventListeners();
        initialized = true;
        plugin.getLogger().fine("SseManager initialized");
    }

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

        plugin.getLogger().fine("SSE event listeners registered (transaction channel)");
    }

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
     * Create HTTP handler for SSE endpoint.
     * [P1-FIX] Lenient Accept header check — allow SSE if header is missing or contains event-stream.
     */
    public HttpHandler createHandler() {
        return exchange -> {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || accept.contains("text/event-stream")) {



                if (exchange.isInIoThread()) {
                    exchange.dispatch(() -> handleSseConnection(exchange));
                } else {
                    handleSseConnection(exchange);
                }
            } else {
                exchange.setStatusCode(503);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
                exchange.getResponseSender().send(
                    "{\"success\":false,\"error\":{\"code\":\"SSE_UNAVAILABLE\"," +
                    "\"message\":\"Please use EventSource for SSE connection\"}}");
            }
        };
    }

    /**
     * Handle an SSE connection request.
     *
     * Must be called on a worker thread (NOT the IO thread). Use exchange.dispatch()
     * before invoking this method to satisfy that requirement.
     *
     * Uses exchange.startBlocking() so the OutputStream writes do NOT set
     * Content-Length and do NOT call endExchange() — both of which would terminate
     * the streaming connection after the first write.
     */
    private void handleSseConnection(HttpServerExchange exchange) {
        boolean authenticated = validateApiKey(exchange);

        String remoteAddress = exchange.getDestinationAddress() != null
            ? exchange.getDestinationAddress().getAddress().getHostAddress()
            : "unknown";
        plugin.getLogger().info("SSE connection attempt from: " + remoteAddress
            + ", authenticated: " + authenticated);


        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream;charset=UTF-8");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
        exchange.getResponseHeaders().put(Headers.CONNECTION, "keep-alive");




        exchange.startBlocking();
        OutputStream outputStream = exchange.getOutputStream();

        String connId = "sse-" + connectionCounter.incrementAndGet();
        SseConnection connection = new SseConnection(connId, outputStream);
        connection.setWorkerThread(Thread.currentThread());


        if (!authenticated) {
            sendToConnection(connection, "error",
                "{\"code\":401,\"message\":\"Invalid or missing API key\",\"status\":\"unauthenticated\"}");
            plugin.getLogger().fine("SSE rejected (unauthenticated): " + connId);
            return;
        }

        connections.put(connId, connection);
        plugin.getLogger().fine("SSE connection established: " + connId);

        exchange.addExchangeCompleteListener((ex, nextListener) -> {
            connection.setClosed(true);
            connections.remove(connId);
            plugin.getLogger().fine("SSE connection closed (exchange complete): " + connId);
            nextListener.proceed();
        });


        sendToConnection(connection, "connected",
            "{\"clientId\":\"" + connId + "\",\"status\":\"connected\"}");
        sendToConnection(connection, "authenticated",
            "{\"status\":\"authenticated\",\"timestamp\":" + System.currentTimeMillis() + "}");

        if (connection.isClosed()) {
            connections.remove(connId);
            return;
        }



        while (!connection.isClosed()) {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (connection.isClosed()) break;
            sendRaw(connection, ": keepalive\n\n");
        }

        connections.remove(connId);
        plugin.getLogger().fine("SSE connection loop ended: " + connId);
    }

    /**
     * Send a named SSE event to a specific connection.
     */
    private void sendToConnection(SseConnection connection, String eventType, String data) {
        if (connection.isClosed()) {
            connections.remove(connection.getId());
            return;
        }
        String sseMessage = "event: " + eventType + "\n"
            + "data: " + data + "\n\n";
        sendRaw(connection, sseMessage);
    }

    /**
     * Write raw bytes to a connection's OutputStream.
     *
     * Synchronized on the connection object so that concurrent callers
     * (e.g. the keepalive loop and the game event bus thread) do not interleave.
     * IOException means the client has disconnected — mark closed and remove.
     */
    private void sendRaw(SseConnection connection, String rawMessage) {
        if (connection.isClosed()) return;

        synchronized (connection) {
            if (connection.isClosed()) return;
            try {
                connection.getOutputStream().write(rawMessage.getBytes(StandardCharsets.UTF_8));
                connection.getOutputStream().flush();
                connection.updateLastActivity();
            } catch (IOException e) {
                plugin.getLogger().fine("SSE write failed (client disconnected): " + connection.getId());
                connection.setClosed(true);
                connections.remove(connection.getId());
            }
        }
    }

    /**
     * Broadcast a named SSE event to all connected clients.
     */
    public void broadcast(String eventType, String message) {
        for (SseConnection connection : connections.values()) {
            sendToConnection(connection, eventType, message);
        }
        plugin.getLogger().fine("Broadcasting to " + connections.size()
            + " SSE connections: " + message);
    }

    /**
     * Broadcast to a named channel (all clients, channel filtering not yet implemented).
     */
    public void broadcastToChannel(String channelName, String message) {
        broadcast(channelName, message);
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public boolean isEnabled() {
        return true;
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * Shutdown SSE manager: mark all connections closed, interrupt their worker
     * threads (so the keepalive sleep exits promptly), and close the OutputStreams.
     */
    public void shutdown() {
        for (SseConnection connection : connections.values()) {
            connection.setClosed(true);
            Thread worker = connection.getWorkerThread();
            if (worker != null) {
                worker.interrupt();
            }
            try {
                connection.getOutputStream().close();
            } catch (IOException e) {

            }
        }
        connections.clear();
        channelSubscriptions.clear();
        plugin.getLogger().fine("SseManager shutdown complete");
    }





    /**
     * Represents a single active SSE connection.
     */
    private static class SseConnection {

        private final String id;
        private final OutputStream outputStream;
        private volatile boolean closed = false;
        private volatile long lastActivity = System.currentTimeMillis();
        private volatile Thread workerThread = null;

        public SseConnection(String id, OutputStream outputStream) {
            this.id = id;
            this.outputStream = outputStream;
        }

        public String getId() { return id; }

        public OutputStream getOutputStream() { return outputStream; }

        public boolean isClosed() { return closed; }

        public void setClosed(boolean closed) { this.closed = closed; }

        public void updateLastActivity() { this.lastActivity = System.currentTimeMillis(); }

        public Thread getWorkerThread() { return workerThread; }

        public void setWorkerThread(Thread thread) { this.workerThread = thread; }
    }
}
