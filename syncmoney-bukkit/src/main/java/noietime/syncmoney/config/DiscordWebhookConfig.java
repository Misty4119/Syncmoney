package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SYNC-CONFIG] Discord webhook configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class DiscordWebhookConfig {

    private final FileConfiguration config;

    public DiscordWebhookConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-087] Whether to enable Discord webhook notifications.
     */
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord-webhook.enabled", false);
    }

    /**
     * [SYNC-CONFIG-088] Gets Discord webhook embed color (hex).
     */
    public String getDiscordWebhookEmbedColor() {
        return config.getString("discord-webhook.embed.color", "FF5555");
    }

    /**
     * [SYNC-CONFIG-089] Whether to show player name in embed.
     */
    public boolean isDiscordWebhookShowPlayerName() {
        return config.getBoolean("discord-webhook.embed.show-player-name", true);
    }

    /**
     * [SYNC-CONFIG-090] Whether to show timestamp in embed.
     */
    public boolean isDiscordWebhookShowTimestamp() {
        return config.getBoolean("discord-webhook.embed.show-timestamp", true);
    }

    /**
     * [SYNC-CONFIG-091] Gets Discord webhook bot username.
     */
    public String getDiscordWebhookUsername() {
        return config.getString("discord-webhook.embed.username", "Syncmoney Alert");
    }

    /**
     * [SYNC-CONFIG-092] Gets all webhook configurations.
     */
    public List<Map<String, Object>> getDiscordWebhooks() {
        List<Map<String, Object>> webhooks = new ArrayList<>();
        var list = config.getMapList("discord-webhook.webhooks");
        if (list != null) {
            for (var item : list) {
                if (item instanceof Map) {
                    webhooks.add(new HashMap<>((Map<String, Object>) item));
                }
            }
        }
        return webhooks;
    }
}
