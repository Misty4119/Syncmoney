package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.shadow.storage.ShadowSyncRecord;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shadow sync command handler.
 * Handles /syncmoney shadow command.
 *
 * Usage:
 * /syncmoney shadow status
 * /syncmoney shadow now
 * /syncmoney shadow logs
 * /syncmoney shadow history [player] [page]
 * /syncmoney shadow export [player] [startDate] [endDate]
 */
public final class ShadowCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final ShadowSyncTask shadowSyncTask;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int RECORDS_PER_PAGE = 10;

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
            case "history" -> handleHistory(sender, args);
            case "export" -> handleExport(sender, args);
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
                plugin.getMessage("shadow.enabled.running") : plugin.getMessage("shadow.enabled.stopped"));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.status-running"), runningVars);

        String target = plugin.getSyncmoneyConfig().shadowSync().getShadowSyncTarget();
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
     * Handles history query.
     * Usage: /syncmoney shadow history [player] [page]
     */
    private void handleHistory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.history.usage"));
            return;
        }

        String playerName = args[1];
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("shadow.history.invalid-page"));
                return;
            }
        }

        List<ShadowSyncRecord> records = shadowSyncTask.getHistoryByPlayerName(playerName, RECORDS_PER_PAGE * page);

        if (records.isEmpty()) {
            Map<String, String> vars = new HashMap<>();
            vars.put("player", playerName);
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.history.not-found"), vars);
            return;
        }

        int startIndex = (page - 1) * RECORDS_PER_PAGE;
        int endIndex = Math.min(startIndex + RECORDS_PER_PAGE, records.size());
        List<ShadowSyncRecord> pageRecords = records.subList(startIndex, endIndex);

        Map<String, String> headerVars = new HashMap<>();
        headerVars.put("player", playerName);
        headerVars.put("page", String.valueOf(page));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.history.header-page"), headerVars);

        for (ShadowSyncRecord record : pageRecords) {
            String status = record.isSuccess() ?
                    plugin.getMessage("shadow.history.status-success") : plugin.getMessage("shadow.history.status-failed");
            String timestamp = record.getTimestamp().atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            Map<String, String> entryVars = new HashMap<>();
            entryVars.put("timestamp", timestamp);
            entryVars.put("balance", record.getBalance().toPlainString());
            entryVars.put("target", record.getSyncTarget());
            entryVars.put("status", status);

            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.history.entry"), entryVars);
        }

        Map<String, String> paginationVars = new HashMap<>();
        paginationVars.put("player", playerName);
        paginationVars.put("page", String.valueOf(page));
        paginationVars.put("nextPage", String.valueOf(page + 1));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.history.pagination-hint"), paginationVars);
    }

    /**
     * Handles export to JSONL.
     * Usage: /syncmoney shadow export [player] [startDate] [endDate]
     */
    private void handleExport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.usage"));
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.example"));
            return;
        }

        String playerName = args[1];
        String playerUuid = null;

        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(playerName);
        if (offlinePlayer != null) {
            playerUuid = offlinePlayer.getUniqueId().toString();
        }

        LocalDate startDate = null;
        LocalDate endDate = null;

        if (args.length >= 3) {
            try {
                startDate = LocalDate.parse(args[2], DATE_FORMAT);
            } catch (DateTimeParseException e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.invalid-start-date"));
                return;
            }
        }

        if (args.length >= 4) {
            try {
                endDate = LocalDate.parse(args[3], DATE_FORMAT);
            } catch (DateTimeParseException e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.invalid-end-date"));
                return;
            }
        }

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.start-after-end"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.exporting"));

        int exported = shadowSyncTask.exportHistory(playerUuid, startDate, endDate);

        if (exported > 0) {
            String jsonlPath = plugin.getSyncmoneyConfig().shadowSync().getShadowSyncStorageJsonlPath();
            Map<String, String> vars = new HashMap<>();
            vars.put("count", String.valueOf(exported));
            vars.put("path", jsonlPath);
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.success"), vars);
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("shadow.export.no-records"));
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
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.usage-history"));
        MessageHelper.sendMessage(sender, plugin.getMessage("shadow.usage-export"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("status", "now", "logs", "history", "export")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("history") || args[0].equalsIgnoreCase("export")) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
