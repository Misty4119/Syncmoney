package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.migration.*;
import noietime.syncmoney.migration.CMIDatabaseReader.CMIPlayerData;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final noietime.syncmoney.breaker.DiscordWebhookNotifier discordNotifier;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private CMIDatabaseReader cmiReader;
    private CMIMultiServerReader cmiMultiServerReader;

    public MigrationCommand(Syncmoney plugin, SyncmoneyConfig config,
                            EconomyFacade economyFacade,
                            DatabaseManager databaseManager,
                            RedisManager redisManager,
                            NameResolver nameResolver) {
        this(plugin, config, economyFacade, databaseManager, redisManager, nameResolver, null);
    }

    public MigrationCommand(Syncmoney plugin, SyncmoneyConfig config,
                            EconomyFacade economyFacade,
                            DatabaseManager databaseManager,
                            RedisManager redisManager,
                            NameResolver nameResolver,
                            Supplier<? extends AutoCloseable> databaseUseLeaseSupplier) {
        this.plugin = plugin;
        this.config = config;
        this.discordNotifier = new noietime.syncmoney.breaker.DiscordWebhookNotifier(plugin, config);
        this.checkpoint = new MigrationCheckpoint(plugin);
        MigrationBackup backup = new MigrationBackup(plugin, plugin);
        CMIEconomyDisabler economyDisabler = new CMIEconomyDisabler((JavaPlugin) plugin);

        this.migrationTask = new MigrationTask(
                plugin, config, null, economyFacade,
                databaseManager, redisManager, nameResolver, checkpoint, backup, economyDisabler,
                databaseUseLeaseSupplier
        );

        this.localToSyncMigrationTask = new LocalToSyncMigrationTask(
                plugin, config, economyFacade, databaseManager, redisManager, checkpoint, backup,
                databaseUseLeaseSupplier
        );

        migrationTask.setProgressCallback(new MigrationTask.MigrationProgressCallback() {
            @Override
            public void onProgress(int current, int total, double percent) {
                String message = plugin.getMessage("migration.progress")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total))
                        .replace("{percent}", FormatUtil.formatPercentRaw(percent));
                noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, p -> MessageHelper.sendMessage(p, message));
            }

            @Override
            public void onComplete(int successCount, int failedCount) {
                String message = plugin.getMessage("migration.completed")
                        .replace("{count}", String.valueOf(successCount))
                        .replace("{total}", FormatUtil.formatCurrency(checkpoint.getTotalBackupAmount()));
                noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, p -> MessageHelper.sendMessage(p, message));


                if (discordNotifier != null) {
                    discordNotifier.sendMigrationCompleteEvent(successCount, failedCount);
                }
            }

            @Override
            public void onError(String error) {
                String message = plugin.getMessage("migration.error")
                        .replace("{error}", error);
                noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, p -> MessageHelper.sendMessage(p, message));


                if (discordNotifier != null) {
                    discordNotifier.sendMigrationFailedEvent(error);
                }
            }
        });

        localToSyncMigrationTask.setProgressCallback(new LocalToSyncMigrationTask.LocalToSyncProgressCallback() {
            @Override
            public void onProgress(int current, int total, double percent) {
                String message = plugin.getMessage("migration.progress")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total))
                        .replace("{percent}", FormatUtil.formatPercentRaw(percent));
                noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, p -> MessageHelper.sendMessage(p, message));
            }

            @Override
            public void onComplete(int successCount, int failedCount) {
                String message = plugin.getMessage("migration.local-to-sync-completed")
                        .replace("{count}", String.valueOf(successCount))
                        .replace("{failed}", String.valueOf(failedCount));
                noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, p -> MessageHelper.sendMessage(p, message));


                if (discordNotifier != null) {
                    discordNotifier.sendMigrationCompleteEvent(successCount, failedCount);
                }
            }

            @Override
            public void onError(String error) {
                String message = plugin.getMessage("migration.error")
                        .replace("{error}", error);
                noietime.syncmoney.util.PlayerLookupUtil.forEachOnlinePlayer(plugin, p -> MessageHelper.sendMessage(p, message));


                if (discordNotifier != null) {
                    discordNotifier.sendMigrationFailedEvent(error);
                }
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        SenderReplyTarget replyTarget = captureReplyTarget(sender);
        if (!sender.hasPermission("syncmoney.admin")) {
            sendMessage(replyTarget, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sendUsage(replyTarget);
            return true;
        }

        String[] commandArgs = args.clone();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (!shuttingDown.get()) {
                dispatchCommand(replyTarget, commandArgs);
            }
        });

        return true;
    }

    /** Stops both migration producers before their shared storage dependencies are closed. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        migrationTask.shutdown();
        localToSyncMigrationTask.stop();
    }

    private SenderReplyTarget captureReplyTarget(CommandSender sender) {
        if (sender instanceof Player player) {
            return new SenderReplyTarget(sender, player, null);
        }
        if (sender instanceof BlockCommandSender blockSender) {
            return new SenderReplyTarget(sender, null, blockSender.getBlock().getLocation().clone());
        }
        return new SenderReplyTarget(sender, null, null);
    }

    private void dispatchCommand(SenderReplyTarget replyTarget, String[] args) {
        switch (args[0].toLowerCase()) {
            case "cmi" -> handleCMIMigration(replyTarget, args);
            case "local-to-sync" -> handleLocalToSyncMigration(replyTarget, args);
            case "status" -> handleStatus(replyTarget);
            case "stop" -> handleStop(replyTarget);
            case "resume" -> handleResume(replyTarget);
            case "clear" -> handleClear(replyTarget);
            default -> sendUsage(replyTarget);
        }
    }

    private void sendMessage(SenderReplyTarget replyTarget, String message) {
        if (replyTarget.player() != null) {
            Player player = replyTarget.player();
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) {
                    MessageHelper.sendMessage(player, message);
                }
            }, null);
            return;
        }

        if (replyTarget.blockLocation() != null) {
            plugin.getServer().getRegionScheduler().run(plugin, replyTarget.blockLocation(),
                    task -> MessageHelper.sendMessage(replyTarget.sender(), message));
            return;
        }

        plugin.getServer().getGlobalRegionScheduler().run(plugin,
                task -> MessageHelper.sendMessage(replyTarget.sender(), message));
    }

    private record SenderReplyTarget(CommandSender sender, Player player, Location blockLocation) {
    }

    /**
     * Handles CMI migration.
     */
    private void handleCMIMigration(SenderReplyTarget replyTarget, String[] args) {
        if (shuttingDown.get()) {
            return;
        }
        boolean preview = Arrays.asList(args).contains("-preview");

        if (cmiReader == null) {
            cmiReader = new CMIDatabaseReader(plugin, config);
            migrationTask.setCMIReader(cmiReader);
        }

        boolean useMultiServer = config.migration().isCMIMultiServerEnabled();
        if (config.migration().isCMIAutoDetect()) {
            CMIDatabaseReader.StorageType detectedType = cmiReader.getDetectedStorageType();
            if (detectedType == CMIDatabaseReader.StorageType.MySQL) {
                if (useMultiServer) {
                    plugin.getLogger().fine("CMI auto-detect: MySQL detected, ignoring multi-server setting");
                }
                useMultiServer = false;
            }
        }

        if (useMultiServer) {
            sendMessage(replyTarget, plugin.getMessage("migration.multi-server-start"));

            try {
                cmiMultiServerReader = new CMIMultiServerReader(plugin, config);
                cmiMultiServerReader.initialize();
                if (shuttingDown.get()) {
                    return;
                }

                if (!cmiMultiServerReader.testConnections()) {
                    sendMessage(replyTarget, plugin.getMessage("migration.cmi-connection-failed")
                            .replace("{error}", "Failed to connect to some databases"));
                    return;
                }

                sendMessage(replyTarget, plugin.getMessage("migration.multi-server-db-count")
                        .replace("{count}", String.valueOf(cmiMultiServerReader.getDbPaths().size())));
                for (String path : cmiMultiServerReader.getDbPaths()) {
                    sendMessage(replyTarget, plugin.getMessage("migration.multi-server-db-path")
                            .replace("{path}", path));
                }
                sendMessage(replyTarget, plugin.getMessage("migration.multi-server-strategy")
                        .replace("{strategy}", cmiMultiServerReader.getMergeStrategy().name()));

                if (preview) {
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-header"));
                    List<CMIDatabaseReader.CMIPlayerData> players = cmiMultiServerReader.readAndMergePlayers();
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-player-count")
                            .replace("{count}", String.valueOf(players.size())));
                    int count = 0;
                    BigDecimal total = BigDecimal.ZERO;
                    for (CMIDatabaseReader.CMIPlayerData p : players) {
                        if (count < 10) {
                            sendMessage(replyTarget, plugin.getMessage("migration.preview-player-line")
                                    .replace("{player}", p.playerName())
                                    .replace("{balance}", p.balance().toString()));
                        }
                        total = total.add(p.balance());
                        count++;
                    }
                    if (count > 10) {
                        sendMessage(replyTarget, plugin.getMessage("migration.preview-more-players")
                                .replace("{count}", String.valueOf(count - 10)));
                    }
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-total")
                            .replace("{total}", total.toString()));
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-execute-hint"));
                    return;
                }

            } catch (Exception e) {
                sendMessage(replyTarget, plugin.getMessage("migration.cmi-connection-failed")
                        .replace("{error}", e.getMessage()));
                plugin.getLogger().warning("CMI Multi-Server Reader initialization failed: " + e.getMessage());
                return;
            }
        } else {
            try {
                cmiReader.initialize();
                if (shuttingDown.get()) {
                    return;
                }

                if (preview) {
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-header"));
                    int totalPlayers = cmiReader.getTotalPlayerCount();
                    sendMessage(replyTarget, plugin.getMessage("migration.single-db-player-count")
                            .replace("{count}", String.valueOf(totalPlayers)));

                    List<CMIDatabaseReader.CMIPlayerData> players = cmiReader.readPlayers(0, Math.min(10, totalPlayers));
                    BigDecimal total = BigDecimal.ZERO;
                    for (CMIDatabaseReader.CMIPlayerData p : players) {
                        sendMessage(replyTarget, plugin.getMessage("migration.preview-player-line")
                                .replace("{player}", p.playerName())
                                .replace("{balance}", p.balance().toString()));
                        total = total.add(p.balance());
                    }
                    if (totalPlayers > 10) {
                        sendMessage(replyTarget, plugin.getMessage("migration.preview-more-players")
                                .replace("{count}", String.valueOf(totalPlayers - 10)));
                    }
                    sendMessage(replyTarget, plugin.getMessage("migration.single-db-preview-total")
                            .replace("{total}", total.toString()));
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-execute-hint"));
                    return;
                }

            } catch (Exception e) {
                sendMessage(replyTarget, plugin.getMessage("migration.cmi-connection-failed")
                        .replace("{error}", e.getMessage()));
                plugin.getLogger().warning("CMI Reader initialization failed: " + e.getMessage());
                return;
            }
        }

        boolean force = Arrays.asList(args).contains("-force");
        boolean backup = !Arrays.asList(args).contains("-no-backup") && config.migration().isMigrationAutoBackup();

        if (migrationTask.isRunning()) {
            sendMessage(replyTarget, plugin.getMessage("migration.already-running"));
            return;
        }

        if (shuttingDown.get()) {
            return;
        }

        sendMessage(replyTarget, plugin.getMessage("migration.started"));
        migrationTask.start(force, backup, useMultiServer ? cmiMultiServerReader : null);
    }

    /**
     * Handles local-to-sync migration.
     */
    private void handleLocalToSyncMigration(SenderReplyTarget replyTarget, String[] args) {
        if (shuttingDown.get()) {
            return;
        }
        boolean preview = Arrays.asList(args).contains("-preview");
        boolean force = Arrays.asList(args).contains("-force");
        boolean backup = !Arrays.asList(args).contains("-no-backup") && config.migration().isMigrationAutoBackup();

        if (localToSyncMigrationTask.isRunning()) {
            sendMessage(replyTarget, plugin.getMessage("migration.already-running"));
            return;
        }

        if (preview) {
            sendMessage(replyTarget, plugin.getMessage("migration.preview-header"));
            LocalToSyncMigrationTask.PreviewResult result = localToSyncMigrationTask.preview();

            if (result == null) {
                sendMessage(replyTarget, plugin.getMessage("migration.preview-no-data"));
                return;
            }

            sendMessage(replyTarget, plugin.getMessage("migration.preview-player-count")
                    .replace("{count}", String.valueOf(result.playerCount())));
            sendMessage(replyTarget, plugin.getMessage("migration.preview-total")
                    .replace("{total}", result.totalBalance().toString()));

            int count = 0;
            for (LocalToSyncMigrationTask.LocalPlayerData player : result.samplePlayers()) {
                if (count < 10) {
                    sendMessage(replyTarget, plugin.getMessage("migration.preview-player-line")
                            .replace("{player}", player.name() != null ? player.name() : player.uuid().toString())
                            .replace("{balance}", player.balance().toString()));
                }
                count++;
            }

            if (result.playerCount() > 10) {
                sendMessage(replyTarget, plugin.getMessage("migration.preview-more-players")
                        .replace("{count}", String.valueOf(result.playerCount() - 10)));
            }

            sendMessage(replyTarget, plugin.getMessage("migration.preview-execute-hint"));
            return;
        }

        if (shuttingDown.get()) {
            return;
        }
        sendMessage(replyTarget, plugin.getMessage("migration.local-to-sync-started"));
        localToSyncMigrationTask.start(force, backup);
    }

    /**
     * Handles status query.
     */
    private void handleStatus(SenderReplyTarget replyTarget) {
        MigrationCheckpoint.MigrationState state = checkpoint.getState();

        sendMessage(replyTarget, plugin.getMessage("migration.status-header"));
        sendMessage(replyTarget, plugin.getMessage("migration.status-state")
                .replace("{state}", getStateColor(state) + state.name()));
        sendMessage(replyTarget, plugin.getMessage("migration.status-total-players")
                .replace("{count}", String.valueOf(checkpoint.getTotalPlayers())));
        sendMessage(replyTarget, plugin.getMessage("migration.status-migrated")
                .replace("{count}", String.valueOf(checkpoint.getMigratedCount())));
        sendMessage(replyTarget, plugin.getMessage("migration.status-failed")
                .replace("{count}", String.valueOf(checkpoint.getFailedCount())));
        sendMessage(replyTarget, plugin.getMessage("migration.status-progress")
                .replace("{percent}", FormatUtil.formatPercentRaw(checkpoint.getProgressPercent())));

        if (checkpoint.getStartTime() != null) {
            sendMessage(replyTarget, plugin.getMessage("migration.status-start-time")
                    .replace("{time}", checkpoint.getStartTime().toString()));
        }

        if (checkpoint.getEstimatedRemainingSeconds() > 0) {
            long seconds = checkpoint.getEstimatedRemainingSeconds();
            sendMessage(replyTarget, plugin.getMessage("migration.status-remaining")
                    .replace("{seconds}", String.valueOf(seconds)));
        }

        if (!checkpoint.getFailures().isEmpty()) {
            sendMessage(replyTarget, plugin.getMessage("migration.status-failures")
                    .replace("{count}", String.valueOf(checkpoint.getFailures().size())));
        }
    }

    /**
     * Handles stop migration.
     */
    private void handleStop(SenderReplyTarget replyTarget) {
        boolean stopped = false;
        if (migrationTask.isRunning()) {
            migrationTask.stop();
            stopped = true;
        }
        if (localToSyncMigrationTask.isRunning()) {
            stopped = localToSyncMigrationTask.stop() || stopped;
        }
        if (!stopped) {
            sendMessage(replyTarget, plugin.getMessage("migration.not-running"));
            return;
        }

        sendMessage(replyTarget, plugin.getMessage("migration.stop-requested"));
    }

    /**
     * Handles resume migration.
     */
    private void handleResume(SenderReplyTarget replyTarget) {
        if (migrationTask.isRunning() || localToSyncMigrationTask.isRunning()) {
            sendMessage(replyTarget, plugin.getMessage("migration.already-running"));
            return;
        }

        if (!migrationTask.resume()) {
            sendMessage(replyTarget, plugin.getMessage("migration.error")
                    .replace("{error}", "Could not safely resume migration; check the server log"));
            return;
        }

        sendMessage(replyTarget, plugin.getMessage("migration.resuming"));
    }

    /**
     * Handles checkpoint clearing.
     */
    private void handleClear(SenderReplyTarget replyTarget) {
        if (migrationTask.isRunning() || localToSyncMigrationTask.isRunning()) {
            sendMessage(replyTarget, plugin.getMessage("migration.already-running"));
            return;
        }

        checkpoint.clear();
        sendMessage(replyTarget, plugin.getMessage("migration.cleared"));
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(SenderReplyTarget replyTarget) {
        sendMessage(replyTarget, plugin.getMessage("migration.usage-header"));
        sendMessage(replyTarget, plugin.getMessage("migration.usage-cmi"));
        sendMessage(replyTarget, plugin.getMessage("migration.usage-local-to-sync"));
        sendMessage(replyTarget, plugin.getMessage("migration.usage-status"));
        sendMessage(replyTarget, plugin.getMessage("migration.usage-resume"));
        sendMessage(replyTarget, plugin.getMessage("migration.usage-stop"));
        sendMessage(replyTarget, plugin.getMessage("migration.usage-clear"));
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
            return Arrays.asList("-force", "-no-backup", "-preview")
                    .stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
