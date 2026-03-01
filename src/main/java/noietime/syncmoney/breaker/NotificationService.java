package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
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

    public NotificationService(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.discordNotifier = new DiscordWebhookNotifier(plugin, config);
        this.notifiedAdmins = ConcurrentHashMap.newKeySet();
    }

    /**
     * Send rate limit notification.
     */
    public void sendRateLimitNotification(UUID playerId, String limitType) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            String message = plugin.getMessage("player-protection.rate-limited." + limitType);
            MessageHelper.sendMessage(player, message);
        }

        String adminMessage = plugin.getMessage("player-protection.rate-limited.broadcast")
                .replace("{player}", playerId.toString());
        broadcastToAdmins(adminMessage);

        discordNotifier.sendRateLimitEvent(playerId, limitType);
    }

    /**
     * Send warning notification.
     */
    public void sendWarningNotification(UUID playerId, int transactionCount, int threshold, BigDecimal totalAmount) {
        String title = plugin.getMessage("player-protection.warning.title")
                .replace("{player}", playerId.toString());
        String content = plugin.getMessage("player-protection.warning.content")
                .replace("{count}", String.valueOf(transactionCount))
                .replace("{limit}", String.valueOf(threshold))
                .replace("{total}", FormatUtil.formatCurrency(totalAmount));

        String fullMessage = title + "\n" + content;
        broadcastToAdmins(fullMessage);

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
        String title = plugin.getMessage("player-protection.locked.title")
                .replace("{player}", playerId.toString());
        String content = plugin.getMessage("player-protection.locked.content")
                .replace("{reason}", reason)
                .replace("{unlock_time}", FormatUtil.formatTimeAgo(config.getPlayerProtectionLockDurationMinutes() * 60 * 1000L));

        String fullMessage = title + "\n" + content;
        broadcastToAdmins(fullMessage);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            String playerMessage = plugin.getMessage("player-protection.locked.player-message")
                    .replace("{minutes}", String.valueOf(config.getPlayerProtectionLockDurationMinutes()));
            MessageHelper.sendMessage(player, playerMessage);
        }

        discordNotifier.sendLockedEvent(playerId, reason, config.getPlayerProtectionLockDurationMinutes());
    }

    /**
     * Send unlocked notification.
     */
    public void sendUnlockedNotification(UUID playerId, String reason) {
        String messageKey = "player-protection.unlocked.by-" + reason;
        String message = plugin.getMessage(messageKey)
                .replace("{player}", playerId.toString());

        broadcastToAdmins(message);

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

        Bukkit.broadcastMessage(message);

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
     * Shutdown the notification service.
     */
    public void shutdown() {
        if (discordNotifier != null) {
            discordNotifier.shutdown();
        }
    }
}
