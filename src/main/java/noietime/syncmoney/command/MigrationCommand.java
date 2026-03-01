package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.migration.*;
import noietime.syncmoney.migration.CMIDatabaseReader.CMIPlayerData;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Migration command handler.
 * Handles /syncmoney migrate command.
 *
 * Usage:
 * /syncmoney migrate cmi [-force] [-backup] [-resume]
 * /syncmoney migrate local-to-sync [-force]
 *
 * Note: CMIDatabaseReader is created and initialized when migration command is executed.
 */
public final class MigrationCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final MigrationTask migrationTask;
    private final MigrationCheckpoint checkpoint;
    private final LocalToSyncMigrationTask localToSyncMigrationTask;

    private CMIDatabaseReader cmiReader;
    private CMIMultiServerReader cmiMultiServerReader;

    public MigrationCommand(Syncmoney plugin, SyncmoneyConfig config,
                            EconomyFacade economyFacade,
                            DatabaseManager databaseManager, NameResolver nameResolver) {
        this.plugin = plugin;
        this.config = config;
        this.checkpoint = new MigrationCheckpoint(plugin);
        MigrationBackup backup = new MigrationBackup(plugin);
        CMIEconomyDisabler economyDisabler = new CMIEconomyDisabler((JavaPlugin) plugin);

        this.migrationTask = new MigrationTask(
                plugin, config, null, economyFacade,
                databaseManager, nameResolver, checkpoint, backup, economyDisabler
        );

        this.localToSyncMigrationTask = new LocalToSyncMigrationTask(
                plugin, config, economyFacade, databaseManager, checkpoint, backup
        );

        migrationTask.setProgressCallback(new MigrationTask.MigrationProgressCallback() {
            @Override
            public void onProgress(int current, int total, double percent) {
                String message = plugin.getMessage("migration.progress")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total))
                        .replace("{percent}", FormatUtil.formatPercentRaw(percent));
                plugin.getServer().getOnlinePlayers().forEach(p -> MessageHelper.sendMessage(p, message));
            }

            @Override
            public void onComplete(int successCount, int failedCount) {
                String message = plugin.getMessage("migration.completed")
                        .replace("{count}", String.valueOf(successCount))
                        .replace("{total}", FormatUtil.formatCurrency(checkpoint.getTotalBackupAmount()));
                plugin.getServer().getOnlinePlayers().forEach(p -> MessageHelper.sendMessage(p, message));
            }

            @Override
            public void onError(String error) {
                String message = plugin.getMessage("migration.error")
                        .replace("{error}", error);
                plugin.getServer().getOnlinePlayers().forEach(p -> MessageHelper.sendMessage(p, message));
            }
        });

        localToSyncMigrationTask.setProgressCallback(new LocalToSyncMigrationTask.LocalToSyncProgressCallback() {
            @Override
            public void onProgress(int current, int total, double percent) {
                String message = plugin.getMessage("migration.progress")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total))
                        .replace("{percent}", FormatUtil.formatPercentRaw(percent));
                plugin.getServer().getOnlinePlayers().forEach(p -> MessageHelper.sendMessage(p, message));
            }

            @Override
            public void onComplete(int successCount, int failedCount) {
                String message = plugin.getMessage("migration.local-to-sync-completed")
                        .replace("{count}", String.valueOf(successCount))
                        .replace("{failed}", String.valueOf(failedCount));
                plugin.getServer().getOnlinePlayers().forEach(p -> MessageHelper.sendMessage(p, message));
            }

            @Override
            public void onError(String error) {
                String message = plugin.getMessage("migration.error")
                        .replace("{error}", error);
                plugin.getServer().getOnlinePlayers().forEach(p -> MessageHelper.sendMessage(p, message));
            }
        });
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
            case "cmi" -> handleCMIMigration(sender, args);
            case "local-to-sync" -> handleLocalToSyncMigration(sender, args);
            case "status" -> handleStatus(sender);
            case "stop" -> handleStop(sender);
            case "resume" -> handleResume(sender);
            case "clear" -> handleClear(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Handles CMI migration.
     */
    private void handleCMIMigration(CommandSender sender, String[] args) {
        boolean preview = Arrays.asList(args).contains("-preview");

        if (cmiReader == null) {
            cmiReader = new CMIDatabaseReader(plugin, config);
            migrationTask.setCMIReader(cmiReader);
        }

        boolean useMultiServer = config.isCMIMultiServerEnabled();

        if (useMultiServer) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.multi-server-start"));

            try {
                cmiMultiServerReader = new CMIMultiServerReader(plugin, config);
                cmiMultiServerReader.initialize();

                if (!cmiMultiServerReader.testConnections()) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.cmi-connection-failed")
                            .replace("{error}", "Failed to connect to some databases"));
                    return;
                }

                MessageHelper.sendMessage(sender, plugin.getMessage("migration.multi-server-db-count")
                        .replace("{count}", String.valueOf(cmiMultiServerReader.getDbPaths().size())));
                for (String path : cmiMultiServerReader.getDbPaths()) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.multi-server-db-path")
                            .replace("{path}", path));
                }
                MessageHelper.sendMessage(sender, plugin.getMessage("migration.multi-server-strategy")
                        .replace("{strategy}", cmiMultiServerReader.getMergeStrategy().name()));

                if (preview) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-header"));
                    List<CMIDatabaseReader.CMIPlayerData> players = cmiMultiServerReader.readAndMergePlayers();
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-player-count")
                            .replace("{count}", String.valueOf(players.size())));
                    int count = 0;
                    BigDecimal total = BigDecimal.ZERO;
                    for (CMIDatabaseReader.CMIPlayerData p : players) {
                        if (count < 10) {
                            MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-player-line")
                                    .replace("{player}", p.playerName())
                                    .replace("{balance}", p.balance().toString()));
                        }
                        total = total.add(p.balance());
                        count++;
                    }
                    if (count > 10) {
                        MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-more-players")
                                .replace("{count}", String.valueOf(count - 10)));
                    }
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-total")
                            .replace("{total}", total.toString()));
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-execute-hint"));
                    return;
                }

            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("migration.cmi-connection-failed")
                        .replace("{error}", e.getMessage()));
                plugin.getLogger().warning("CMI Multi-Server Reader initialization failed: " + e.getMessage());
                return;
            }
        } else {
            try {
                cmiReader.initialize();

                if (preview) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-header"));
                    int totalPlayers = cmiReader.getTotalPlayerCount();
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.single-db-player-count")
                            .replace("{count}", String.valueOf(totalPlayers)));

                    List<CMIDatabaseReader.CMIPlayerData> players = cmiReader.readPlayers(0, Math.min(10, totalPlayers));
                    BigDecimal total = BigDecimal.ZERO;
                    for (CMIDatabaseReader.CMIPlayerData p : players) {
                        MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-player-line")
                                .replace("{player}", p.playerName())
                                .replace("{balance}", p.balance().toString()));
                        total = total.add(p.balance());
                    }
                    if (totalPlayers > 10) {
                        MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-more-players")
                                .replace("{count}", String.valueOf(totalPlayers - 10)));
                    }
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.single-db-preview-total")
                            .replace("{total}", total.toString()));
                    MessageHelper.sendMessage(sender, plugin.getMessage("migration.preview-execute-hint"));
                    return;
                }

            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("migration.cmi-connection-failed")
                        .replace("{error}", e.getMessage()));
                plugin.getLogger().warning("CMI Reader initialization failed: " + e.getMessage());
                return;
            }
        }

        boolean force = Arrays.asList(args).contains("-force");
        boolean backup = !Arrays.asList(args).contains("-no-backup") && config.isMigrationAutoBackup();

        if (migrationTask.isRunning()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.already-running"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("migration.started"));
        migrationTask.start(force, backup, useMultiServer ? cmiMultiServerReader : null);
    }

    /**
     * Handles local-to-sync migration.
     */
    private void handleLocalToSyncMigration(CommandSender sender, String[] args) {
        boolean force = Arrays.asList(args).contains("-force");
        boolean backup = !Arrays.asList(args).contains("-no-backup") && config.isMigrationAutoBackup();

        if (localToSyncMigrationTask.isRunning()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.already-running"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("migration.local-to-sync-started"));
        localToSyncMigrationTask.start(force, backup);
    }

    /**
     * Handles status query.
     */
    private void handleStatus(CommandSender sender) {
        MigrationCheckpoint.MigrationState state = checkpoint.getState();

        MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-state")
                .replace("{state}", getStateColor(state) + state.name()));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-total-players")
                .replace("{count}", String.valueOf(checkpoint.getTotalPlayers())));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-migrated")
                .replace("{count}", String.valueOf(checkpoint.getMigratedCount())));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-failed")
                .replace("{count}", String.valueOf(checkpoint.getFailedCount())));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-progress")
                .replace("{percent}", FormatUtil.formatPercentRaw(checkpoint.getProgressPercent())));

        if (checkpoint.getStartTime() != null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-start-time")
                    .replace("{time}", checkpoint.getStartTime().toString()));
        }

        if (checkpoint.getEstimatedRemainingSeconds() > 0) {
            long seconds = checkpoint.getEstimatedRemainingSeconds();
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-remaining")
                    .replace("{seconds}", String.valueOf(seconds)));
        }

        if (!checkpoint.getFailures().isEmpty()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.status-failures")
                    .replace("{count}", String.valueOf(checkpoint.getFailures().size())));
        }
    }

    /**
     * Handles stop migration.
     */
    private void handleStop(CommandSender sender) {
        if (!migrationTask.isRunning()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.not-running"));
            return;
        }

        migrationTask.stop();
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.stopped"));
    }

    /**
     * Handles resume migration.
     */
    private void handleResume(CommandSender sender) {
        if (migrationTask.isRunning()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.already-running"));
            return;
        }

        if (!checkpoint.resume()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("migration.no-checkpoint"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("migration.resuming"));
        migrationTask.resume();
    }

    /**
     * Handles checkpoint clearing.
     */
    private void handleClear(CommandSender sender) {
        checkpoint.clear();
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.cleared"));
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-cmi"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-local-to-sync"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-status"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-resume"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-stop"));
        MessageHelper.sendMessage(sender, plugin.getMessage("migration.usage-clear"));
    }

    /**
     * Gets state color.
     */
    private String getStateColor(MigrationCheckpoint.MigrationState state) {
        return switch (state) {
            case IDLE -> "<gray>";
            case RUNNING -> "<yellow>";
            case PAUSED -> "<gold>";
            case COMPLETED -> "<green>";
            case FAILED -> "<red>";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("cmi", "local-to-sync", "status", "resume", "stop", "clear")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("cmi")) {
            return Arrays.asList("-force", "-no-backup", "-preview")
                    .stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("local-to-sync")) {
            return Arrays.asList("-force", "-no-backup")
                    .stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
