package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
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
     * Send rate limit event to Discord.
     */
    public void sendRateLimitEvent(java.util.UUID playerId, String limitType) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = config.getDiscordWebhookEmbedColor();
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
                    "Player: " + (config.isDiscordWebhookShowPlayerName() ? playerId.toString() : "Hidden"),
                    "Limit Type: " + limitType,
                    config.isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send warning event to Discord.
     */
    public void sendWarningEvent(java.util.UUID playerId, int transactionCount, int threshold, java.math.BigDecimal totalAmount) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = config.getDiscordWebhookEmbedColor();
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
                    "Player: " + (config.isDiscordWebhookShowPlayerName() ? playerId.toString() : "Hidden"),
                    "Transactions: " + transactionCount + " / " + threshold + "\n" +
                    "Total Amount: " + noietime.syncmoney.util.FormatUtil.formatCurrency(totalAmount),
                    config.isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send locked event to Discord.
     */
    public void sendLockedEvent(java.util.UUID playerId, String reason, int durationMinutes) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FF5555"; // Red for locked
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
                    "Player: " + (config.isDiscordWebhookShowPlayerName() ? playerId.toString() : "Hidden"),
                    "Reason: " + reason + "\n" +
                    "Duration: " + durationMinutes + " minutes",
                    config.isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send unlocked event to Discord.
     */
    public void sendUnlockedEvent(java.util.UUID playerId, String reason) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "4ADE80"; // Green for unlocked
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
                    "Player: " + (config.isDiscordWebhookShowPlayerName() ? playerId.toString() : "Hidden"),
                    "Unlocked by: " + reason,
                    config.isDiscordWebhookShowTimestamp());
        }
    }

    /**
     * Send global lock event to Discord.
     */
    public void sendGlobalLockEvent(String reason) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }

        List<Map<String, Object>> webhooks = config.getDiscordWebhooks();
        if (webhooks.isEmpty()) {
            return;
        }

        String embedColor = "FF0000"; // Dark red for global lock
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
                    config.isDiscordWebhookShowTimestamp());
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
        String username = config.getDiscordWebhookUsername();

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
