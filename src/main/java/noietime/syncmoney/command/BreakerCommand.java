package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Circuit breaker command handler.
 * Handles /syncmoney breaker command.
 *
 * Usage:
 * /syncmoney breaker status
 * /syncmoney breaker reset
 * /syncmoney breaker info
 */
public final class BreakerCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final EconomicCircuitBreaker circuitBreaker;

    public BreakerCommand(Syncmoney plugin, EconomicCircuitBreaker circuitBreaker) {
        this.plugin = plugin;
        this.circuitBreaker = circuitBreaker;
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
            case "reset" -> handleReset(sender);
            case "info" -> handleInfo(sender);
            case "resources" -> handleResources(sender);
            case "player" -> handlePlayer(sender, args);
            case "unlock" -> handleUnlock(sender, args);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Handles status query.
     */
    private void handleStatus(CommandSender sender) {
        EconomicCircuitBreaker.CircuitState state = circuitBreaker.getState();

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.status-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.state")
                .replace("{state}", getStateColor(state) + state.name()));

        if (state == EconomicCircuitBreaker.CircuitState.LOCKED) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.locked-warning"));
        } else if (state == EconomicCircuitBreaker.CircuitState.WARNING) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.warning-state"));
        }
    }

    /**
     * Handles reset.
     */
    private void handleReset(CommandSender sender) {
        circuitBreaker.reset();
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.reset-success-full"));
    }

    /**
     * Handles detailed information.
     */
    private void handleInfo(CommandSender sender) {
        var connectionState = circuitBreaker.getConnectionStateManager();
        var resourceMonitor = circuitBreaker.getResourceMonitor();

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info-header"));

        if (connectionState == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-connection"));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-unavailable-local"));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.resources"));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.memory")
                    .replace("{percent}", FormatUtil.formatPercentRaw(resourceMonitor.getMemoryUsagePercent())));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.memory-available")
                    .replace("{mb}", String.valueOf(resourceMonitor.getAvailableMemoryMb())));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-connections-na"));

            Map<String, String> healthVars = new HashMap<>();
            healthVars.put("health", resourceMonitor.isHealthy() ?
                    plugin.getMessage("breaker.health.healthy") : plugin.getMessage("breaker.health.abnormal"));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.health-status"), healthVars);
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-connection"));

        Map<String, String> redisVars = new HashMap<>();
        redisVars.put("available", connectionState.isRedisAvailable() ?
                plugin.getMessage("breaker.enabled.active") : plugin.getMessage("breaker.enabled.inactive"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-available"), redisVars);

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-last-success")
                .replace("{time}", formatTimeAgo(connectionState.getLastSuccessfulConnection())));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-failures")
                .replace("{count}", String.valueOf(connectionState.getConsecutiveFailures())));

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.resources"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.memory")
                .replace("{percent}", FormatUtil.formatPercentRaw(resourceMonitor.getMemoryUsagePercent())));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.memory-available")
                .replace("{mb}", String.valueOf(resourceMonitor.getAvailableMemoryMb())));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.redis-connections")
                .replace("{count}", String.valueOf(resourceMonitor.getRedisAvailableConnections())));

        Map<String, String> healthVars = new HashMap<>();
        healthVars.put("health", resourceMonitor.isHealthy() ?
                plugin.getMessage("breaker.health.healthy") : plugin.getMessage("breaker.health.abnormal"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.info.health-status"), healthVars);
    }

    /**
     * Handles resource query.
     */
    private void handleResources(CommandSender sender) {
        var resourceMonitor = circuitBreaker.getResourceMonitor();

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.resources-header"));
        MessageHelper.sendMessage(sender, resourceMonitor.getStatusSummary());
    }

    /**
     * Handles player protection status query.
     */
    private void handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.not-found")
                    .replace("{player}", ""));
            return;
        }

        String playerName = args[1];
        var player = plugin.getServer().getPlayer(playerName);

        if (player == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.not-found")
                    .replace("{player}", playerName));
            return;
        }

        var guard = plugin.getPlayerTransactionGuard();
        if (guard == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.not-enabled"));
            return;
        }

        var state = guard.getPlayerState(player.getUniqueId());
        var protectionState = guard.getProtectionState(player.getUniqueId());

        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.player")
                .replace("{player}", player.getName()));

        String stateColor = switch (state) {
            case NORMAL -> "<green>";
            case WARNING -> "<yellow>";
            case LOCKED -> "<red>";
            case GLOBAL_LOCKED -> "<red><bold>";
        };
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.status")
                .replace("{state}", stateColor + state.name()));

        if (protectionState != null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.transactions-per-second")
                    .replace("{count}", String.valueOf(protectionState.getTransactionsPerSecond())));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.transactions-per-minute")
                    .replace("{count}", String.valueOf(protectionState.getTransactionsPerMinute())));
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.warning-count")
                    .replace("{count}", String.valueOf(protectionState.getWarningCount())));

            if (protectionState.getUnlockTime() > 0) {
                long remainingTime = (protectionState.getUnlockTime() - System.currentTimeMillis()) / 60000;
                if (remainingTime > 0) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("breaker.player-status.unlock-in")
                            .replace("{minutes}", String.valueOf(remainingTime)));
                }
            }
        }
    }

    /**
     * Handles manual player unlock.
     */
    private void handleUnlock(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.unlock.usage"));
            return;
        }

        String playerName = args[1];
        var player = plugin.getServer().getPlayer(playerName);

        if (player == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.unlock.player-not-found")
                    .replace("{player}", playerName));
            return;
        }

        var guard = plugin.getPlayerTransactionGuard();
        if (guard == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.unlock.system-not-enabled"));
            return;
        }

        boolean success = guard.unlockPlayer(player.getUniqueId());
        if (success) {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.unlock.success")
                    .replace("{player}", player.getName()));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("breaker.unlock.failed")
                    .replace("{player}", player.getName()));
        }
    }

    /**
     * Gets state color.
     */
    private String getStateColor(EconomicCircuitBreaker.CircuitState state) {
        return switch (state) {
            case NORMAL -> "<green>";
            case WARNING -> "<yellow>";
            case LOCKED -> "<red>";
        };
    }

    /**
     * Formats time.
     */
    private String formatTimeAgo(long timestamp) {
        if (timestamp == 0) {
            return plugin.getMessage("general.time-format.never");
        }

        long seconds = (System.currentTimeMillis() - timestamp) / 1000;
        if (seconds < 60) {
            return plugin.getMessage("general.time-format.seconds-ago")
                    .replace("{seconds}", String.valueOf(seconds));
        }
        if (seconds < 3600) {
            return plugin.getMessage("general.time-format.minutes-ago")
                    .replace("{minutes}", String.valueOf(seconds / 60));
        }
        return plugin.getMessage("general.time-format.hours-ago")
                .replace("{hours}", String.valueOf(seconds / 3600));
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-status"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-reset"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-info"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-resources"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-player"));
        MessageHelper.sendMessage(sender, plugin.getMessage("breaker.usage-unlock"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("status", "reset", "info", "resources", "player", "unlock")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }


        if (args.length == 2 && (args[0].equalsIgnoreCase("player") || args[0].equalsIgnoreCase("unlock"))) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
