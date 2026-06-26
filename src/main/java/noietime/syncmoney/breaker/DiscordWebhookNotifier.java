package noietime.syncmoney.breaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DiscordWebhookNotifier - Handles Discord webhook notifications.
 * Sends async notifications to configured Discord webhooks.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class DiscordWebhookNotifier {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final ExecutorService executor;
    private volatile NameResolver nameResolver;

    public DiscordWebhookNotifier(Plugin plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Syncmoney-DiscordWebhook");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Set NameResolver for player name resolution.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * Get player name from UUID, with fallback to UUID string.
     */
    private String getPlayerName(java.util.UUID playerId) {
        if (nameResolver != null) {
            String name = nameResolver.getName(playerId);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            return player.getName();
        }

        return playerId.toString();
    }

    private String playerField(java.util.UUID playerId) {
        return config.discordWebhook().isDiscordWebhookShowPlayerName() ? getPlayerName(playerId) : "Hidden";
    }

    /**
     * Send rate limit event to Discord.
     */
    public void sendRateLimitEvent(java.util.UUID playerId, String limitType) {
        dispatch("rate_limit",
                "Rate Limit Triggered",
                "Player: " + playerField(playerId),
                "Limit Type: " + limitType,
                config.discordWebhook().getDiscordWebhookEmbedColor(),
                playerId);
    }

    /**
     * Send warning event to Discord.
     */
    public void sendWarningEvent(java.util.UUID playerId, int transactionCount, int threshold, java.math.BigDecimal totalAmount) {
        dispatch("player_warning",
                "Player Warning",
                "Player: " + playerField(playerId),
                "Transactions: " + transactionCount + " / " + threshold + "\n" +
                        "Total Amount: " + noietime.syncmoney.util.FormatUtil.formatCurrency(totalAmount),
                config.discordWebhook().getDiscordWebhookEmbedColor(),
                playerId);
    }

    /**
     * Send locked event to Discord.
     */
    public void sendLockedEvent(java.util.UUID playerId, String reason, int durationMinutes) {
        dispatch("player_locked",
                "Player Locked",
                "Player: " + playerField(playerId),
                "Reason: " + reason + "\n" +
                        "Duration: " + durationMinutes + " minutes",
                "FF5555",
                playerId);
    }

    /**
     * Send unlocked event to Discord.
     */
    public void sendUnlockedEvent(java.util.UUID playerId, String reason) {
        dispatch("player_unlocked",
                "Player Unlocked",
                "Player: " + playerField(playerId),
                "Unlocked by: " + reason,
                "4ADE80",
                playerId);
    }

    /**
     * Send global lock event to Discord.
     */
    public void sendGlobalLockEvent(String reason) {
        dispatch("global_lock",
                "GLOBAL ECONOMY LOCK",
                "Server: " + config.getServerName(),
                "Reason: " + reason,
                "FF0000",
                null);
    }

    /**
     * Send schema upgrade event to Discord.
     */
    public void sendSchemaUpgradeEvent(int fromVersion, int toVersion) {
        dispatch("schema_upgrade",
                "Database Schema Upgraded",
                "Server: " + config.getServerName(),
                "Version: " + fromVersion + " → " + toVersion,
                "00FF00",
                null);
    }

    /**
     * Send circuit breaker trigger event to Discord.
     */
    public void sendCircuitBreakerEvent(String state, String reason) {
        dispatch("circuit_breaker_trigger",
                "Circuit Breaker Triggered",
                "Server: " + config.getServerName(),
                "State: " + state + "\nReason: " + reason,
                "FF0000",
                null);
    }

    /**
     * Send high memory event to Discord.
     */
    public void sendMemoryHighEvent(double usagePercent, long usedMb, long maxMb) {
        dispatch("memory_high",
                "High Memory Usage Warning",
                "Server: " + config.getServerName(),
                String.format("Memory: %.1f%% (%d/%d MB)", usagePercent, usedMb, maxMb),
                "FFAA00",
                null);
    }

    /**
     * Send Redis connection pool critical event to Discord.
     */
    public void sendRedisPoolCriticalEvent(int activeConnections, int maxConnections) {
        double usagePercent = (double) activeConnections / maxConnections * 100;
        dispatch("redis_pool_critical",
                "Redis Connection Pool Critical",
                "Server: " + config.getServerName(),
                String.format("Active: %d/%d (%.1f%%)", activeConnections, maxConnections, usagePercent),
                "FF0000",
                null);
    }

    /**
     * Send high value transaction event to Discord.
     */
    public void sendHighValueTransactionEvent(java.util.UUID playerId, java.math.BigDecimal amount, String transactionType) {
        dispatch("high_value_transaction",
                "High Value Transaction",
                "Player: " + playerField(playerId),
                "Amount: " + noietime.syncmoney.util.FormatUtil.formatCurrency(amount) + "\nType: " + transactionType,
                "FFAA00",
                playerId);
    }

    /**
     * Send migration complete event to Discord.
     */
    public void sendMigrationCompleteEvent(int successCount, int failedCount) {
        dispatch("migration_complete",
                "Migration Completed",
                "Server: " + config.getServerName(),
                "Success: " + successCount + "\nFailed: " + failedCount,
                "4ADE80",
                null);
    }

    /**
     * Send migration failed event to Discord.
     */
    public void sendMigrationFailedEvent(String errorMessage) {
        dispatch("migration_failed",
                "Migration Failed",
                "Server: " + config.getServerName(),
                "Error: " + errorMessage,
                "FF0000",
                null);
    }

    /**
     * Central dispatch for all webhook events.
     * Performs the enabled/event-subscription/url filtering common to every notification
     * and queues an async send for each matching webhook.
     *
     * @param eventName   webhook event key used for per-webhook subscription filtering
     * @param title       embed title
     * @param description embed description
     * @param fields      embed "Details" field value
     * @param colorHex    embed color as a hex string (e.g. "FF0000")
     * @param playerId    related player UUID, or null for server-level events (reserved for context)
     */
    private void dispatch(String eventName, String title, String description, String fields,
                          String colorHex, java.util.UUID playerId) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        boolean showTimestamp = config.discordWebhook().isDiscordWebhookShowTimestamp();

        for (Map<String, Object> webhook : webhooks) {
            @SuppressWarnings("unchecked")
            List<String> events = (List<String>) webhook.get("events");
            if (events == null || !events.contains(eventName)) {
                continue;
            }

            String url = (String) webhook.get("url");
            if (url == null || url.isEmpty()) {
                continue;
            }

            sendEmbedAsync(url, colorHex, title, description, fields, showTimestamp);
        }
    }

    /**
     * Send embed to Discord webhook asynchronously.
     */
    private void sendEmbedAsync(String webhookUrl, String colorHex, String title, String description, String fields, boolean showTimestamp) {
        executor.execute(() -> {
            try {
                sendEmbed(webhookUrl, colorHex, title, description, fields, showTimestamp);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    /**
     * Send embed to Discord webhook synchronously.
     */
    private void sendEmbed(String webhookUrl, String colorHex, String title, String description, String fields, boolean showTimestamp) throws IOException {
        URL url = new URL(webhookUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);

        String json = buildPayload(colorHex, title, description, fields, showTimestamp);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = json.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 204 && responseCode != 200) {
            plugin.getLogger().warning("Discord webhook returned code: " + responseCode);
        }

        connection.disconnect();
    }

    /**
     * Build the Discord webhook JSON payload using Jackson, which guarantees correct
     * escaping of all special/control characters. Color is encoded as a decimal integer
     * as required by the Discord API.
     */
    private String buildPayload(String colorHex, String title, String description, String fields, boolean showTimestamp) throws IOException {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", "Details");
        field.put("value", fields == null ? "" : fields);
        field.put("inline", false);

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title == null ? "" : title);
        embed.put("description", description == null ? "" : description);
        embed.put("color", parseColor(colorHex));
        embed.put("fields", List.of(field));
        if (showTimestamp) {
            embed.put("timestamp", java.time.Instant.now().toString());
        }
        embed.put("footer", Map.of("text", "Syncmoney Economy Protection"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", config.discordWebhook().getDiscordWebhookUsername());
        payload.put("embeds", List.of(embed));

        return MAPPER.writeValueAsString(payload);
    }

    private int parseColor(String colorHex) {
        if (colorHex == null || colorHex.isBlank()) {
            return 0;
        }
        String normalized = colorHex.startsWith("#") ? colorHex.substring(1) : colorHex;
        try {
            return Integer.parseInt(normalized, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Shutdown the webhook notifier.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
