package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DiscordWebhookNotifier - Handles Discord webhook notifications.
 * Sends async notifications to configured Discord webhooks.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class DiscordWebhookNotifier {

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

    /**
     * Send rate limit event to Discord.
     */
    public void sendRateLimitEvent(java.util.UUID playerId, String limitType) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String playerName = getPlayerName(playerId);
        String embedColor = config.discordWebhook().getDiscordWebhookEmbedColor();
        String eventName = "rate_limit";

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

            sendEmbedAsync(url, embedColor,
                    "Rate Limit Triggered",
                    "Player: " + (config.discordWebhook().isDiscordWebhookShowPlayerName() ? playerName : "Hidden"),
                    "Limit Type: " + limitType,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send warning event to Discord.
     */
    public void sendWarningEvent(java.util.UUID playerId, int transactionCount, int threshold, java.math.BigDecimal totalAmount) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String playerName = getPlayerName(playerId);
        String embedColor = config.discordWebhook().getDiscordWebhookEmbedColor();
        String eventName = "player_warning";

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

            sendEmbedAsync(url, embedColor,
                    "Player Warning",
                    "Player: " + (config.discordWebhook().isDiscordWebhookShowPlayerName() ? playerName : "Hidden"),
                    "Transactions: " + transactionCount + " / " + threshold + "\n" +
                    "Total Amount: " + noietime.syncmoney.util.FormatUtil.formatCurrency(totalAmount),
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send locked event to Discord.
     */
    public void sendLockedEvent(java.util.UUID playerId, String reason, int durationMinutes) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String playerName = getPlayerName(playerId);
        String embedColor = "FF5555";
        String eventName = "player_locked";

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

            sendEmbedAsync(url, embedColor,
                    "Player Locked",
                    "Player: " + (config.discordWebhook().isDiscordWebhookShowPlayerName() ? playerName : "Hidden"),
                    "Reason: " + reason + "\n" +
                    "Duration: " + durationMinutes + " minutes",
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send unlocked event to Discord.
     */
    public void sendUnlockedEvent(java.util.UUID playerId, String reason) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String playerName = getPlayerName(playerId);
        String embedColor = "4ADE80";
        String eventName = "player_unlocked";

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

            sendEmbedAsync(url, embedColor,
                    "Player Unlocked",
                    "Player: " + (config.discordWebhook().isDiscordWebhookShowPlayerName() ? playerName : "Hidden"),
                    "Unlocked by: " + reason,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send global lock event to Discord.
     */
    public void sendGlobalLockEvent(String reason) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FF0000";
        String eventName = "global_lock";

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

            sendEmbedAsync(url, embedColor,
                    "GLOBAL ECONOMY LOCK",
                    "Server: " + config.getServerName(),
                    "Reason: " + reason,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send schema upgrade event to Discord.
     */
    public void sendSchemaUpgradeEvent(int fromVersion, int toVersion) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "00FF00";
        String eventName = "schema_upgrade";

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

            sendEmbedAsync(url, embedColor,
                    "Database Schema Upgraded",
                    "Server: " + config.getServerName(),
                    "Version: " + fromVersion + " → " + toVersion,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send circuit breaker trigger event to Discord.
     */
    public void sendCircuitBreakerEvent(String state, String reason) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FF0000";
        String eventName = "circuit_breaker_trigger";

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

            sendEmbedAsync(url, embedColor,
                    "Circuit Breaker Triggered",
                    "Server: " + config.getServerName(),
                    "State: " + state + "\nReason: " + reason,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send high memory event to Discord.
     */
    public void sendMemoryHighEvent(double usagePercent, long usedMb, long maxMb) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FFAA00";
        String eventName = "memory_high";

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

            sendEmbedAsync(url, embedColor,
                    "High Memory Usage Warning",
                    "Server: " + config.getServerName(),
                    String.format("Memory: %.1f%% (%d/%d MB)", usagePercent, usedMb, maxMb),
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send Redis connection pool critical event to Discord.
     */
    public void sendRedisPoolCriticalEvent(int activeConnections, int maxConnections) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FF0000";
        String eventName = "redis_pool_critical";

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

            double usagePercent = (double) activeConnections / maxConnections * 100;
            sendEmbedAsync(url, embedColor,
                    "Redis Connection Pool Critical",
                    "Server: " + config.getServerName(),
                    String.format("Active: %d/%d (%.1f%%)", activeConnections, maxConnections, usagePercent),
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send high value transaction event to Discord.
     */
    public void sendHighValueTransactionEvent(java.util.UUID playerId, java.math.BigDecimal amount, String transactionType) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String playerName = getPlayerName(playerId);
        String embedColor = "FFAA00";
        String eventName = "high_value_transaction";

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

            sendEmbedAsync(url, embedColor,
                    "High Value Transaction",
                    "Player: " + (config.discordWebhook().isDiscordWebhookShowPlayerName() ? playerName : "Hidden"),
                    "Amount: " + noietime.syncmoney.util.FormatUtil.formatCurrency(amount) + "\nType: " + transactionType,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send migration complete event to Discord.
     */
    public void sendMigrationCompleteEvent(int successCount, int failedCount) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "4ADE80";
        String eventName = "migration_complete";

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

            sendEmbedAsync(url, embedColor,
                    "Migration Completed",
                    "Server: " + config.getServerName(),
                    "Success: " + successCount + "\nFailed: " + failedCount,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send migration failed event to Discord.
     */
    public void sendMigrationFailedEvent(String errorMessage) {
        if (!config.discordWebhook().isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.discordWebhook().getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FF0000";
        String eventName = "migration_failed";

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

            sendEmbedAsync(url, embedColor,
                    "Migration Failed",
                    "Server: " + config.getServerName(),
                    "Error: " + errorMessage,
                    config.discordWebhook().isDiscordWebhookShowTimestamp());
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
        connection.setDoOutput(true);

        String timestamp = showTimestamp ? "\"timestamp\":\"" + java.time.Instant.now().toString() + "\"," : "";
        String username = config.discordWebhook().getDiscordWebhookUsername();

        String json = String.format(
                "{\"username\":\"%s\",\"embeds\":[{" +
                        "\"title\":\"%s\"," +
                        "\"description\":\"%s\"," +
                        "\"color\":\"%s\"," +
                        "\"fields\":[{" +
                        "\"name\":\"Details\"," +
                        "\"value\":\"%s\"," +
                        "\"inline\":false" +
                        "}]," +
                        timestamp +
                        "\"footer\":{\"text\":\"Syncmoney Economy Protection\"}" +
                        "}]}",
                escapeJson(username),
                escapeJson(title),
                escapeJson(description),
                colorHex,
                escapeJson(fields)
        );

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
     * Escape special characters for JSON.
     */
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
