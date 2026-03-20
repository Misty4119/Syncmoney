package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * High-value notification system.
 * Notifies administrators when large transactions, abnormal changes, circuit breaker events, etc. occur.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class HighValueNotification {

    private static final long NOTIFICATION_COOLDOWN_MS = 300000;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final ScheduledExecutorService scheduler;
    private DiscordWebhookNotifier discordNotifier;

    private final Set<String> notifiedAdmins;

    private final ConcurrentHashMap<UUID, Long> highValueNotifications;

    public HighValueNotification(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Syncmoney-HighValueNotification");
            t.setDaemon(true);
            return t;
        });
        this.notifiedAdmins = new HashSet<>();
        this.highValueNotifications = new ConcurrentHashMap<>();

        startCleanupTask();
    }

    /**
     * Start cleanup task.
     */
    private void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            highValueNotifications.entrySet().removeIf(entry ->
                    now - entry.getValue() > NOTIFICATION_COOLDOWN_MS);
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Set Discord webhook notifier.
     */
    public void setDiscordWebhookNotifier(DiscordWebhookNotifier discordNotifier) {
        this.discordNotifier = discordNotifier;
    }

    /**
     * Notify high-value transaction.
     * @param playerId Player UUID
     * @param amount Transaction amount
     * @param type Transaction type
     */
    public void notifyHighValueTransaction(UUID playerId, BigDecimal amount, String type) {
        Long lastNotification = highValueNotifications.get(playerId);
        if (lastNotification != null &&
                System.currentTimeMillis() - lastNotification < NOTIFICATION_COOLDOWN_MS) {
            return;
        }

        highValueNotifications.put(playerId, System.currentTimeMillis());

        String[] admins = getAdminList();
        if (admins.length == 0) {
            return;
        }

        String message = plugin.getMessage("high-value-notification.high-value-transaction")
                .replace("{player}", playerId.toString())
                .replace("{type}", type)
                .replace("{amount}", FormatUtil.formatCurrency(amount));

        for (String admin : admins) {
            sendMessage(admin, message);
        }


        if (discordNotifier != null) {
            discordNotifier.sendHighValueTransactionEvent(playerId, amount, type);
        }
    }

    /**
     * Notify abnormal balance change.
     */
    public void notifySuddenChange(UUID playerId, double ratio, BigDecimal amount) {
        String[] admins = getAdminList();
        if (admins.length == 0) {
            return;
        }

        String message = plugin.getMessage("high-value-notification.sudden-change")
                .replace("{player}", playerId.toString())
                .replace("{ratio}", FormatUtil.formatPercentRaw(ratio))
                .replace("{amount}", FormatUtil.formatCurrency(amount));

        for (String admin : admins) {
            sendMessage(admin, message);
        }

        broadcastToAdmins(plugin.getMessage("breaker.notification.abnormal-change"));
    }

    /**
     * Notify rapid inflation (total supply spike).
     */
    public void notifyRapidInflation(BigDecimal previous, BigDecimal current, BigDecimal ratio) {
        String[] admins = getAdminList();
        if (admins.length == 0) {
            return;
        }

        String message = plugin.getMessage("high-value-notification.rapid-inflation")
                .replace("{ratio}", FormatUtil.formatPercent(ratio.doubleValue() * 100))
                .replace("{previous}", FormatUtil.formatCurrency(previous))
                .replace("{current}", FormatUtil.formatCurrency(current));

        for (String admin : admins) {
            sendMessage(admin, message);
        }

        broadcastToAdmins(plugin.getMessage("breaker.notification.supply-spike"));
    }

    /**
     * Notify system lockdown.
     */
    public void notifyLockdown(String reason) {
        String[] admins = getAdminList();

        String message = buildMessage("lockdown",
                plugin.getMessage("breaker.notification.locked").replace("{reason}", reason));

        for (String admin : admins) {
            sendMessage(admin, message);
        }

        broadcastToAdmins(plugin.getMessage("breaker.notification.locked-broadcast")
                .replace("{reason}", reason));
    }

    /**
     * Notify resource warning.
     */
    public void notifyResourceWarning(String resourceType, String details) {
        String[] admins = getAdminList();
        if (admins.length == 0) {
            return;
        }

        String message = plugin.getMessage("high-value-notification.resource-warning")
                .replace("{resource}", resourceType)
                .replace("{details}", details);

        for (String admin : admins) {
            sendMessage(admin, message);
        }
    }

    /**
     * Get admin list.
     */
    private String[] getAdminList() {
        return plugin.getConfig().getStringList("notify-admins").toArray(new String[0]);
    }

    /**
     * Build message.
     */
    private String buildMessage(String type, String content) {
        return plugin.getMessage("breaker.notification.build-message")
                .replace("{type}", type)
                .replace("{content}", content);
    }

    /**
     * Send message to specific admin.
     */
    private void sendMessage(String adminName, String message) {
        Player player = Bukkit.getServer().getPlayer(adminName);
        if (player != null && player.isOnline()) {
            MessageHelper.sendMessage(player, message);
        }
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
     * Shutdown notification service.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
