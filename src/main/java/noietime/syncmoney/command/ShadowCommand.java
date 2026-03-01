package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shadow sync command handler.
 * Handles /syncmoney shadow command.
 *
 * Usage:
 * /syncmoney shadow status
 * /syncmoney shadow now
 * /syncmoney shadow logs
 */
public final class ShadowCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final ShadowSyncTask shadowSyncTask;

    public ShadowCommand(Syncmoney plugin, ShadowSyncTask shadowSyncTask) {
        this.plugin = plugin;
        this.shadowSyncTask = shadowSyncTask;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "status" -> handleStatus(sender);
            case "now" -> handleSyncNow(sender);
            case "logs" -> handleLogs(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Handles status query.
     */
    private void handleStatus(CommandSender sender) {
        ShadowSyncTask.SyncStatus status = shadowSyncTask.getStatus();

        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-header"));

        Map<String, String> runningVars = new HashMap<>();
        runningVars.put("running", status.running() ?
                plugin.getMessage("shadow.boolean.yes") : plugin.getMessage("shadow.boolean.no"));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-running"), runningVars);

        String target = plugin.getSyncmoneyConfig().getShadowSyncTarget();
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.sync-target")
                .replace("{target}", target));

        int queueSize = shadowSyncTask.getQueueSize();
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-queue")
                .replace("{count}", String.valueOf(queueSize)));

        if (status.lastSyncTime() > 0) {
            long secondsAgo = (System.currentTimeMillis() - status.lastSyncTime()) / 1000;
            String timeAgo;
            if (secondsAgo < 60) {
                timeAgo = plugin.getMessage("general.time-format.seconds-ago")
                        .replace("{seconds}", String.valueOf(secondsAgo));
            } else if (secondsAgo < 3600) {
                timeAgo = plugin.getMessage("general.time-format.minutes-ago")
                        .replace("{minutes}", String.valueOf(secondsAgo / 60));
            } else {
                timeAgo = plugin.getMessage("general.time-format.hours-ago")
                        .replace("{hours}", String.valueOf(secondsAgo / 3600));
            }
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-last-sync")
                    .replace("{time}", timeAgo));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-last-sync-never"));
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-today")
                .replace("{count}", String.valueOf(status.totalSync())));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-success")
                .replace("{count}", String.valueOf(status.successSync())));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-failed")
                .replace("{count}", String.valueOf(status.failedSync())));
    }

    /**
     * Handles immediate sync.
     */
    private void handleSyncNow(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.started"));

        try {
            shadowSyncTask.triggerImmediateSync();
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.sync-triggered"));
        } catch (Exception e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.sync-failed")
                    .replace("{error}", e.getMessage()));
        }
    }

    /**
     * Handles log query.
     */
    private void handleLogs(CommandSender sender) {
        var syncLog = shadowSyncTask.getSyncLog();
        var recentLogs = syncLog.getRecentLogs(10);

        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.logs-header"));

        if (recentLogs.isEmpty()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.logs-empty"));
            return;
        }

        for (var log : recentLogs) {
            String status = log.success() ?
                    plugin.getMessage("shadow.log-status.success") : plugin.getMessage("shadow.log-status.failed");
            String msg;
            if (log.success()) {
                msg = plugin.getMessage("shadow.log-entry")
                        .replace("{timestamp}", log.timestamp().substring(11, 19))
                        .replace("{player}", log.playerName())
                        .replace("{balance}", log.syncmoneyBalance())
                        .replace("{status}", status);
            } else {
                msg = plugin.getMessage("shadow.log-entry-failed")
                        .replace("{timestamp}", log.timestamp().substring(11, 19))
                        .replace("{player}", log.playerName())
                        .replace("{balance}", log.syncmoneyBalance())
                        .replace("{status}", status)
                        .replace("{reason}", log.reason() != null ? log.reason() : plugin.getMessage("shadow.log-status.unknown"));
            }

            MessageHelper.sendMessage(sender, msg);
        }
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.usage-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.usage-status"));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.usage-now"));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.usage-logs"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("status", "now", "logs")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
