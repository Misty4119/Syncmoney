package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Balance sync command handler.
 * Handles /syncmoney sync-balance command.
 *
 * Usage:
 * /syncmoney sync-balance <player> - Force sync player's balance to Redis and Database
 */
public final class SyncBalanceCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private final DatabaseManager databaseManager;

    public SyncBalanceCommand(Syncmoney plugin, EconomyFacade economyFacade,
            CacheManager cacheManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.cacheManager = cacheManager;
        this.databaseManager = databaseManager;
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

        String playerName = args[0];
        handleSyncBalance(sender, playerName);

        return true;
    }

    /**
     * Handle balance sync for a player.
     */
    private void handleSyncBalance(CommandSender sender, String playerName) {

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
            MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.player-not-found")
                    .replace("{player}", playerName));
            return;
        }


        BigDecimal currentBalance = economyFacade.getBalance(uuid);

        MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.player-line")
                .replace("{player}", playerName));
        MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.balance-line")
                .replace("{balance}", FormatUtil.formatCurrency(currentBalance)));


        economyFacade.setBalance(uuid, currentBalance, EconomyEvent.EventSource.COMMAND_ADMIN);

        MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.success"));
    }

    /**
     * Send usage information.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.usage"));
        MessageHelper.sendMessage(sender, plugin.getMessage("sync-balance.usage-line"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return null;
        }
        return Collections.emptyList();
    }
}
