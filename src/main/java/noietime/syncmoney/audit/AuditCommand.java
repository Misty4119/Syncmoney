package noietime.syncmoney.audit;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.*;

/**
 * Audit log query command.
 * Provides /syncmoney audit command for querying transaction records.
 */
public final class AuditCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final AuditLogger auditLogger;
    private final NameResolver nameResolver;
    private final LocalEconomyHandler localEconomyHandler;


    private static final int ENTRIES_PER_PAGE = 10;

    public AuditCommand(Syncmoney plugin, SyncmoneyConfig config,
                       AuditLogger auditLogger, NameResolver nameResolver,
                       LocalEconomyHandler localEconomyHandler) {
        this.plugin = plugin;
        this.config = config;
        this.auditLogger = auditLogger;
        this.nameResolver = nameResolver;
        this.localEconomyHandler = localEconomyHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.audit")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "search" -> handleSearch(sender, args);
            case "cleanup" -> handleCleanup(sender, args);
            case "stats" -> handleStats(sender);
            default -> handlePlayerSearch(sender, args);
        }

        return true;
    }

    /**
     * Handles player search query.
     */
    private void handlePlayerSearch(CommandSender sender, String[] args) {
        String playerName = args[0];
        int page = 1;

        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("audit.invalid-page"));
                return;
            }
        }

        UUID uuid = nameResolver.resolveUUID(playerName);
        if (uuid == null) {
            try {
                uuid = UUID.fromString(playerName);
            } catch (IllegalArgumentException ex) {
                MessageHelper.sendMessage(sender, plugin.getMessage("audit.player-not-found").replace("{player}", playerName));
                return;
            }
        }

        String resolvedName = nameResolver.getName(uuid);
        if (resolvedName == null) {
            resolvedName = playerName;
        }

        if (localEconomyHandler != null) {
            List<Map<String, Object>> records = localEconomyHandler.getTransactionHistory(uuid, page * ENTRIES_PER_PAGE);

            if (records.isEmpty()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("audit.empty"));
                return;
            }

            MessageHelper.sendMessage(sender, plugin.getMessage("audit.header")
                    .replace("{player}", resolvedName));

            int start = (page - 1) * ENTRIES_PER_PAGE;
            int end = Math.min(start + ENTRIES_PER_PAGE, records.size());

            for (int i = start; i < end; i++) {
                Map<String, Object> record = records.get(i);
                String entry = formatLocalRecord(record, i + 1);
                MessageHelper.sendMessage(sender, entry);
            }

            int totalPages = (records.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE;
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.footer")
                    .replace("{page}", String.valueOf(page))
                    .replace("{total}", String.valueOf(totalPages)));
            return;
        }

        List<AuditRecord> records = auditLogger.getPlayerRecords(uuid, page * ENTRIES_PER_PAGE);

        if (records.isEmpty()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.empty"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("audit.header")
                .replace("{player}", resolvedName));

        int start = (page - 1) * ENTRIES_PER_PAGE;
        int end = Math.min(start + ENTRIES_PER_PAGE, records.size());

        for (int i = start; i < end; i++) {
            AuditRecord record = records.get(i);
            String entry = formatRecord(record, i + 1);
            MessageHelper.sendMessage(sender, entry);
        }

        int totalPages = (records.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE;
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.footer")
                .replace("{page}", String.valueOf(page))
                .replace("{total}", String.valueOf(totalPages)));
    }

    /**
     * Formats a local (SQLite) audit record for display.
     */
    private String formatLocalRecord(Map<String, Object> record, int index) {
        String type = (String) record.get("type");
        double amount = (double) record.get("amount");
        double balanceAfter = (double) record.get("balance_after");
        long timestamp = (long) record.get("timestamp");
        String source = (String) record.get("source");

        String typeIcon = switch (type) {
            case "DEPOSIT", "TRANSFER_IN" -> "<green>+";
            case "WITHDRAW", "TRANSFER_OUT" -> "<red>-";
            case "SET" -> "<yellow>=";
            default -> "<gray>?";
        };

        String typeName = switch (type) {
            case "DEPOSIT" -> "Deposit";
            case "WITHDRAW" -> "Withdraw";
            case "TRANSFER_IN" -> "Receive";
            case "TRANSFER_OUT" -> "Transfer";
            case "SET" -> "Set";
            default -> type;
        };

        String timeStr = formatTimestampLocal(timestamp);

        return " <#38BDF8>" + index + ". <#94A3B8>" + typeIcon + FormatUtil.formatCurrency(amount) +
                " <#475569>⦊ <#F8FAFC>" + typeName +
                " <#475569>⦊ <#A78BFA>" + FormatUtil.formatCurrency(balanceAfter) +
                " <#475569>│ <#94A3B8>" + timeStr +
                " <#475569>│ <#94A3B8>" + source;
    }

    /**
     * Formats timestamp for local records.
     */
    private String formatTimestampLocal(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return dateTime.format(formatter);
    }

    /**
     * Handles search command.
     */
    private void handleSearch(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.search-usage"));
            return;
        }

        UUID playerUuid = null;
        long startTime = 0;
        long endTime = 0;
        AuditRecord.AuditType type = null;
        int limit = 100;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--player" -> {
                    if (i + 1 < args.length) {
                        String targetName = args[++i];
                        playerUuid = nameResolver.resolveUUID(targetName);
                        if (playerUuid == null) {
                            try {
                                playerUuid = UUID.fromString(targetName);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
                case "--type" -> {
                    if (i + 1 < args.length) {
                        try {
                            type = AuditRecord.AuditType.valueOf(args[++i].toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                case "--start" -> {
                    if (i + 1 < args.length) {
                        try {
                            startTime = Long.parseLong(args[++i]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                case "--end" -> {
                    if (i + 1 < args.length) {
                        try {
                            endTime = Long.parseLong(args[++i]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                case "--limit" -> {
                    if (i + 1 < args.length) {
                        try {
                            limit = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        if (playerUuid == null && startTime == 0 && type == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.at-least-one-condition"));
            return;
        }

        if (localEconomyHandler != null) {
            if (playerUuid != null) {
                List<Map<String, Object>> records = localEconomyHandler.getTransactionHistory(playerUuid, limit);

                if (records.isEmpty()) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("audit.empty"));
                    return;
                }

                MessageHelper.sendMessage(sender, plugin.getMessage("audit.found-records")
                        .replace("{count}", String.valueOf(records.size())));

                int count = 1;
                for (Map<String, Object> record : records) {
                    String entry = formatLocalRecord(record, count++);
                    MessageHelper.sendMessage(sender, entry);
                }
            } else {
                List<Map<String, Object>> records = localEconomyHandler.getAllTransactions(0, limit);

                if (records.isEmpty()) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("audit.empty"));
                    return;
                }

                MessageHelper.sendMessage(sender, plugin.getMessage("audit.found-records")
                        .replace("{count}", String.valueOf(records.size())));

                int count = 1;
                for (Map<String, Object> record : records) {
                    String entry = formatLocalRecord(record, count++);
                    MessageHelper.sendMessage(sender, entry);
                }
            }
            return;
        }

        var criteria = new AuditLogger.AuditSearchCriteria(playerUuid, startTime, endTime, type, limit);
        List<AuditRecord> records = auditLogger.search(criteria);

        if (records.isEmpty()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.empty"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("audit.found-records")
                .replace("{count}", String.valueOf(records.size())));

        for (int i = 0; i < Math.min(20, records.size()); i++) {
            AuditRecord record = records.get(i);
            String entry = formatRecord(record, i + 1);
            MessageHelper.sendMessage(sender, entry);
        }
    }

    /**
     * Handles cleanup command.
     */
    private void handleCleanup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.full")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("audit.cleanup-feature"));
    }

    /**
     * Handles stats command.
     */
    private void handleStats(CommandSender sender) {
        if (localEconomyHandler != null) {
            int playerCount = localEconomyHandler.getTotalPlayerCount();
            BigDecimal totalBalance = localEconomyHandler.getTotalBalance();

            MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-local-mode")
                    .replace("{mode}", "<yellow>LOCAL (SQLite)"));
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-local-players")
                    .replace("{count}", String.valueOf(playerCount)));
            MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-local-total")
                    .replace("{total}", FormatUtil.formatCurrency(totalBalance)));
            return;
        }

        int bufferSize = auditLogger.getBufferSize();
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-buffer")
                .replace("{buffer}", String.valueOf(bufferSize)));
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.stats-enabled")
                .replace("{enabled}", auditLogger.isEnabled() ? "<green>Enabled" : "<red>Disabled"));
    }

    /**
     * Formats record for display.
     */
    private String formatRecord(AuditRecord record, int number) {
        String typeStr = switch (record.type()) {
            case DEPOSIT -> "<green>+</green>";
            case WITHDRAW -> "<red>-</red>";
            case SET_BALANCE -> "<yellow>=</yellow>";
            case TRANSFER -> "<aqua>→</aqua>";
            case CRITICAL_FAILURE -> "<red>!</red>";
        };

        String amountStr;
        if (record.isMerged()) {
            amountStr = "(merged " + record.mergedCount() + ")";
        } else {
            amountStr = record.getFormattedAmount();
        }

        return "  <yellow>#" + number + " <gray>- <green>" + record.getFormattedTime() +
                " <gray>| " + typeStr + " <yellow>" + amountStr +
                " <gray>| <aqua>" + record.playerName() +
                " <gray>→ <yellow>" + record.balanceAfter().toPlainString();
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.usage-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.usage-command1"));
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.usage-command2"));
        MessageHelper.sendMessage(sender, plugin.getMessage("audit.usage-command3"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.audit")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("search", "stats", "cleanup")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("search")) {
            return Arrays.asList("--player", "--type", "--start", "--end", "--limit")
                    .stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("--type")) {
            return Arrays.stream(AuditRecord.AuditType.values())
                    .map(Enum::name)
                    .map(String::toLowerCase)
                    .filter(s -> s.startsWith(args[2].toLowerCase()))
                    .toList();
        }

        return Collections.emptyList();
    }
}
