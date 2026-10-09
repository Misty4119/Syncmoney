package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.PlayerLookupUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Handles /syncmoney version support-report commands. */
public final class VersionCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());
    private static final Duration REPORT_RETENTION = Duration.ofDays(30);

    private final Syncmoney plugin;

    public VersionCommand(Syncmoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String mode = args.length == 0 ? "basic" : args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "basic" -> sendReport(sender, VersionDiagnostics.basic(plugin));
            case "full", "save" -> {
                if (!sender.hasPermission("syncmoney.admin")) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
                    return true;
                }
                boolean save = mode.equals("save");
                sender.sendMessage("Syncmoney support report: collecting read-only diagnostics...");
                VersionDiagnostics.full(plugin).whenComplete((report, error) -> {
                    if (error != null) {
                        VersionReport failed = VersionDiagnostics.basic(plugin)
                                .add("report.scope", "FULL")
                                .add("report.collection_status", "ERROR")
                                .add("report.collection_error", error.getClass().getSimpleName());
                        deliver(sender, failed.lines());
                        return;
                    }

                    if (save) {
                        saveReport(report);
                    }
                    deliver(sender, report.lines());
                });
            }
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("Usage: /" + label + " version [full|save]");
        sender.sendMessage("version: basic plugin/server/Java information");
        sender.sendMessage("version full: admin-only read-only Redis/database diagnostics");
        sender.sendMessage("version save: admin-only diagnostics saved under plugins/Syncmoney/reports/");
    }

    private void saveReport(VersionReport report) {
        String filename = "syncmoney-report-" + FILE_TIME.format(Instant.now()) + ".txt";
        Path reportDirectory = plugin.getDataFolder().toPath().resolve("reports");
        String relativePath = "plugins/Syncmoney/reports/" + filename;
        try {
            Files.createDirectories(reportDirectory);
            pruneReports(reportDirectory);
            report.add("report.file", relativePath);
            Files.writeString(reportDirectory.resolve(filename), report.render() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            report.add("report.file", "ERROR");
            report.add("report.file_error", e.getClass().getSimpleName());
        }
    }

    private void pruneReports(Path reportDirectory) {
        Instant cutoff = Instant.now().minus(REPORT_RETENTION);
        try (var files = Files.list(reportDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("syncmoney-report-"))
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .filter(path -> isBefore(path, cutoff))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A stale report is not allowed to make a new report fail.
                        }
                    });
        } catch (IOException ignored) {
            // Retention is best effort; collection and report saving still proceed.
        }
    }

    private boolean isBefore(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private void sendReport(CommandSender sender, VersionReport report) {
        deliver(sender, report.lines());
    }

    private void deliver(CommandSender sender, List<String> lines) {
        Runnable action = () -> lines.forEach(sender::sendMessage);
        if (sender instanceof Player player) {
            PlayerLookupUtil.runOnPlayerScheduler(plugin, player, action);
        } else {
            action.run();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = sender.hasPermission("syncmoney.admin")
                ? List.of("basic", "full", "save")
                : List.of("basic");
        return options.stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
    }
}
