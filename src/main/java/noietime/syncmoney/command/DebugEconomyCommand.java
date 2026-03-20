package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Debug command handler for diagnosing economy data issues.
 * Handles /syncmoney debug command.
 *
 * Usage:
 * /syncmoney debug <player> - View player's balance across all layers
 * /syncmoney debug system - View system status (degraded, Lua scripts, event drops)
 */
public final class DebugEconomyCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final DatabaseManager databaseManager;
    private final FallbackEconomyWrapper fallbackWrapper;

    public DebugEconomyCommand(Syncmoney plugin, EconomyFacade economyFacade,
            CacheManager cacheManager, RedisManager redisManager,
            DatabaseManager databaseManager, FallbackEconomyWrapper fallbackWrapper) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
        this.fallbackWrapper = fallbackWrapper;
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
            case "player" -> {
                if (args.length < 2) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("debug.usage"));
                    return true;
                }
                handlePlayerDebug(sender, args[1]);
            }
            case "system" -> handleSystemDebug(sender);
            default -> {

                handlePlayerDebug(sender, args[0]);
            }
        }

        return true;
    }

    /**
     * Handle player debug query.
     */
    private void handlePlayerDebug(CommandSender sender, String playerName) {

        UUID uuid = plugin.getServer().getPlayerUniqueId(playerName);
        if (uuid == null) {

            OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(playerName);
            if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                uuid = offlinePlayer.getUniqueId();
            } else {

                uuid = plugin.getNameResolver().resolveUUID(playerName);
            }
        }

        if (uuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.player-not-found")
                    .replace("{player}", playerName));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("debug.header")
                .replace("{player}", playerName));


        var memoryBalance = economyFacade.getBalance(uuid);
        MessageHelper.sendMessage(sender, plugin.getMessage("debug.memory-label")
                .replace("{balance}", FormatUtil.formatCurrency(memoryBalance)));


        if (redisManager != null && !redisManager.isDegraded()) {
            try {
                var redisBalance = cacheManager.getBalance(uuid);
                MessageHelper.sendMessage(sender, plugin.getMessage("debug.redis-label")
                        .replace("{balance}", FormatUtil.formatCurrency(redisBalance)));
            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("debug.redis-error")
                        .replace("{error}", e.getMessage()));
            }
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.redis-offline"));
        }


        if (databaseManager != null && databaseManager.isConnected()) {
            try {
                var dbRecord = databaseManager.getPlayer(uuid);
                if (dbRecord.isPresent()) {
                    var record = dbRecord.get();
                    MessageHelper.sendMessage(sender, plugin.getMessage("debug.db-label")
                            .replace("{balance}", FormatUtil.formatCurrency(record.balance())));
                } else {
                    MessageHelper.sendMessage(sender, plugin.getMessage("debug.db-no-record"));
                }
            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("debug.db-error")
                        .replace("{error}", e.getMessage()));
            }
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.db-offline"));
        }


        try {
            var version = cacheManager.getVersion(uuid);
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.version-available")
                    .replace("{version}", String.valueOf(version)));
        } catch (Exception e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.version-error"));
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("debug.tip-sync")
                .replace("{player}", playerName));
    }

    /**
     * Handle system debug query.
     */
    private void handleSystemDebug(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("debug.system-header"));


        boolean isDegraded = fallbackWrapper != null && fallbackWrapper.isDegraded();
        MessageHelper.sendMessage(sender, isDegraded ?
                plugin.getMessage("debug.degraded-active") :
                plugin.getMessage("debug.degraded-normal"));


        if (redisManager != null) {
            MessageHelper.sendMessage(sender, redisManager.isDegraded() ?
                    plugin.getMessage("debug.redis-status-degraded") :
                    plugin.getMessage("debug.redis-status-ok"));

            if (!redisManager.isDegraded()) {
                try {
                    int maxConn = redisManager.getMaxConnections();
                    int availConn = redisManager.getAvailableConnections();
                    MessageHelper.sendMessage(sender, plugin.getMessage("debug.redis-pool-normal")
                            .replace("{avail}", String.valueOf(availConn))
                            .replace("{max}", String.valueOf(maxConn)));
                } catch (Exception e) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("debug.redis-pool-error"));
                }
            }
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.redis-not-init"));
        }


        if (databaseManager != null) {
            MessageHelper.sendMessage(sender, databaseManager.isConnected() ?
                    plugin.getMessage("debug.db-status-ok") :
                    plugin.getMessage("debug.db-status-fail"));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("debug.db-not-init"));
        }


        boolean luaLoaded = cacheManager != null && isLuaScriptsLoaded();
        MessageHelper.sendMessage(sender, luaLoaded ?
                plugin.getMessage("debug.lua-loaded") :
                plugin.getMessage("debug.lua-not-loaded"));


        long droppedEvents = economyFacade.getDroppedEventCount();
        MessageHelper.sendMessage(sender, droppedEvents > 0 ?
                plugin.getMessage("debug.drop-abnormal").replace("{count}", String.valueOf(droppedEvents)) :
                plugin.getMessage("debug.drop-normal").replace("{count}", "0"));


        int cachedPlayers = economyFacade.getCachedPlayerCount();
        MessageHelper.sendMessage(sender, plugin.getMessage("debug.cached-players")
                .replace("{count}", String.valueOf(cachedPlayers)));
    }

    /**
     * Check if Lua scripts are loaded.
     */
    private boolean isLuaScriptsLoaded() {
        return cacheManager != null && cacheManager.areAllScriptsLoaded();
    }

    /**
     * Send usage information.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("debug.commands-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("debug.usage-line"));
        MessageHelper.sendMessage(sender, plugin.getMessage("debug.usage-line2"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("player", "system");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return null;
        }
        return Collections.emptyList();
    }
}
