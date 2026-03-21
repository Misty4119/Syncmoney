package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.web.websocket.SseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NotificationService - Unified notification system.
 * Handles in-game notifications and Discord webhook notifications.
 * 
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class NotificationService {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final DiscordWebhookNotifier discordNotifier;

    private final Set<String> notifiedAdmins;

    private volatile SseManager sseManager;
    private volatile NameResolver nameResolver;

    public NotificationService(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.discordNotifier = new DiscordWebhookNotifier(plugin, config);
        this.notifiedAdmins = ConcurrentHashMap.newKeySet();
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
    private String getPlayerName(UUID playerId) {
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
     * Send rate limit notification.
     */
    public void sendRateLimitNotification(UUID playerId, String limitType) {
        String playerName = getPlayerName(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            String message = plugin.getMessage("player-protection.rate-limited." + limitType);
            MessageHelper.sendMessage(player, message);
        }

        String adminMessage = plugin.getMessage("player-protection.rate-limited.broadcast")
                .replace("{player}", playerName);
        broadcastToAdmins(adminMessage);


        String title = "Rate Limit Exceeded";
        String content = String.format("Player %s exceeded %s limit", playerName, limitType);
        broadcastSystemAlert("rate_limit", title, content);

        discordNotifier.sendRateLimitEvent(playerId, limitType);
    }

    /**
     * Send warning notification.
     */
    public void sendWarningNotification(UUID playerId, int transactionCount, int threshold, BigDecimal totalAmount) {
        String playerName = getPlayerName(playerId);

        String title = plugin.getMessage("player-protection.warning.title")
                .replace("{player}", playerName);
        String content = plugin.getMessage("player-protection.warning.content")
                .replace("{count}", String.valueOf(transactionCount))
                .replace("{limit}", String.valueOf(threshold))
                .replace("{total}", FormatUtil.formatCurrency(totalAmount));

        String fullMessage = title + "\n" + content;
        broadcastToAdmins(fullMessage);


        broadcastSystemAlert("player_warning", title, content);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            String playerMessage = plugin.getMessage("player-protection.warning.player-notify");
            MessageHelper.sendMessage(player, playerMessage);
        }

        discordNotifier.sendWarningEvent(playerId, transactionCount, threshold, totalAmount);
    }

    /**
     * Send locked notification.
     */
    public void sendLockedNotification(UUID playerId, String reason) {
        String playerName = getPlayerName(playerId);

        String title = plugin.getMessage("player-protection.locked.title")
                .replace("{player}", playerName);
        String content = plugin.getMessage("player-protection.locked.content")
                .replace("{reason}", reason)
                .replace("{unlock_time}", FormatUtil.formatTimeAgo(config.playerProtection().getPlayerProtectionLockDurationMinutes() * 60 * 1000L));

        String fullMessage = title + "\n" + content;
        broadcastToAdmins(fullMessage);


        broadcastSystemAlert("player_locked", title, reason);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            String playerMessage = plugin.getMessage("player-protection.locked.player-message")
                    .replace("{minutes}", String.valueOf(config.playerProtection().getPlayerProtectionLockDurationMinutes()));
            MessageHelper.sendMessage(player, playerMessage);
        }

        discordNotifier.sendLockedEvent(playerId, reason, config.playerProtection().getPlayerProtectionLockDurationMinutes());
    }

    /**
     * Send unlocked notification.
     */
    public void sendUnlockedNotification(UUID playerId, String reason) {
        String playerName = getPlayerName(playerId);
        String messageKey = "player-protection.unlocked.by-" + reason;
        String message = plugin.getMessage(messageKey)
                .replace("{player}", playerName);

        broadcastToAdmins(message);


        broadcastSystemAlert("player_unlocked", "Player Unlocked", message);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            MessageHelper.sendMessage(player, message);
        }

        discordNotifier.sendUnlockedEvent(playerId, reason);
    }

    /**
     * Send global lock notification (from EconomicCircuitBreaker).
     */
    public void sendGlobalLockNotification(String reason) {
        String message = plugin.getMessage("breaker.notification.locked-broadcast")
                .replace("{reason}", reason);
        broadcastToAdmins(message);
        broadcastSystemAlert("global_lock", "System Locked", reason);
        discordNotifier.sendGlobalLockEvent(reason);
    }

    /**
     * Broadcast message to all online admins.
     */
    private void broadcastToAdmins(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("syncmoney.admin")) {
                MessageHelper.sendMessage(player, message);
            }
        }
    }

    /**
     * Get admin list from config.
     */
    private String[] getAdminList() {
        return plugin.getConfig().getStringList("notify-admins").toArray(new String[0]);
    }

    /**
     * Set SSE manager for broadcasting system alerts to web clients.
     */
    public void setSseManager(SseManager sseManager) {
        this.sseManager = sseManager;
    }

    /**
     * Broadcast system alert to SSE clients.
     */
    private void broadcastSystemAlert(String type, String title, String message) {
        if (sseManager != null && sseManager.isEnabled()) {
            try {
                String json = String.format(
                    "{\"type\":\"%s\",\"title\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
                    type, title.replace("\"", "'"), message.replace("\"", "'"), System.currentTimeMillis()
                );
                sseManager.broadcastToChannel("system", json);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to broadcast system alert: " + e.getMessage());
            }
        }
    }

    /**
     * Shutdown the notification service.
     */
    public void shutdown() {
        if (discordNotifier != null) {
            discordNotifier.shutdown();
        }
    }
}
