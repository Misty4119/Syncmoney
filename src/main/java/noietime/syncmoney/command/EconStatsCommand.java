package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Economy statistics command handler.
 * Handles /syncmoney econstats command.
 *
 * Usage:
 * /syncmoney econstats - View economy statistics
 * /syncmoney econstats supply - View currency supply
 * /syncmoney econstats players - View player statistics
 */
public final class EconStatsCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final RedisManager redisManager;
    private final BaltopManager baltopManager;

    public EconStatsCommand(Syncmoney plugin, RedisManager redisManager, BaltopManager baltopManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
        this.baltopManager = baltopManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.econstats")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            handleOverview(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "overview" -> handleOverview(sender);
            case "supply" -> handleSupply(sender);
            case "players" -> handlePlayers(sender);
            case "transactions" -> handleTransactions(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Handles overview query.
     */
    private void handleOverview(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.header"));

        double totalSupply = baltopManager.getTotalSupply();
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.supply-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.supply")
                .replace("{supply}", baltopManager.formatNumberSmart(totalSupply)));

        int totalPlayers = baltopManager.getTotalPlayers();
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.players-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.total-players")
                .replace("{total}", String.valueOf(totalPlayers)));

        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.online-players")
                .replace("{online}", String.valueOf(onlinePlayers)));

        List<noietime.syncmoney.baltop.RankEntry> top3 = baltopManager.getTopRank(3);
        if (!top3.isEmpty()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.top-header"));
            for (var entry : top3) {
                String entryMsg = plugin.getMessage("econstats.overview.top-entry")
                        .replace("{rank}", String.valueOf(entry.rank()))
                        .replace("{player}", entry.name())
                        .replace("{balance}", baltopManager.formatNumberSmart(entry.balance()));
                MessageHelper.sendMessage(sender, entryMsg);
            }
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.overview.hint"));
    }

    /**
     * Handles currency supply query.
     */
    private void handleSupply(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.header"));

        double totalSupply = baltopManager.getTotalSupply();
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.supply")
                .replace("{supply}", baltopManager.formatNumberSmart(totalSupply)));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.precise")
                .replace("{precise}", FormatUtil.formatCurrency(totalSupply)));

        int totalPlayers = baltopManager.getTotalPlayers();
        if (totalPlayers > 0) {
            double avgBalance = totalSupply / totalPlayers;
            MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.avg-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.avg-balance")
                    .replace("{avg}", baltopManager.formatNumberSmart(avgBalance)));
        }

        List<noietime.syncmoney.baltop.RankEntry> top100 = baltopManager.getTopRank(100);
        if (!top100.isEmpty()) {
            double top100Total = top100.stream().mapToDouble(noietime.syncmoney.baltop.RankEntry::balance).sum();
            double top100Percent = (top100Total / totalSupply) * 100;
            MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.distribution-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("econstats.supply.top100")
                    .replace("{percent}", FormatUtil.formatPercentRaw(top100Percent)));
        }
    }

    /**
     * Handles player statistics query.
     */
    private void handlePlayers(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.header"));

        int totalPlayers = baltopManager.getTotalPlayers();
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.total")
                .replace("{total}", String.valueOf(totalPlayers)));

        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.online")
                .replace("{online}", String.valueOf(onlinePlayers)));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.offline")
                .replace("{offline}", String.valueOf(totalPlayers - onlinePlayers)));

        if (totalPlayers > 0) {
            double totalSupply = baltopManager.getTotalSupply();
            MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.distribution-header"));

            List<noietime.syncmoney.baltop.RankEntry> top10 = baltopManager.getTopRank(10);
            if (!top10.isEmpty()) {
                double top10Total = top10.stream().mapToDouble(noietime.syncmoney.baltop.RankEntry::balance).sum();
                MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.top10")
                        .replace("{amount}", baltopManager.formatNumberSmart(top10Total))
                        .replace("{percent}", FormatUtil.formatPercentRaw((top10Total / totalSupply) * 100)));
            }

            List<noietime.syncmoney.baltop.RankEntry> top50 = baltopManager.getTopRank(50);
            if (!top50.isEmpty()) {
                double top50Total = top50.stream().mapToDouble(noietime.syncmoney.baltop.RankEntry::balance).sum();
                MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.top50")
                        .replace("{amount}", baltopManager.formatNumberSmart(top50Total))
                        .replace("{percent}", FormatUtil.formatPercentRaw((top50Total / totalSupply) * 100)));
            }
        }

        long richCount = 0;
        try {
            if (redisManager.isDegraded()) {
                plugin.getLogger().fine("Rich count skipped - LOCAL mode or Redis unavailable");
            } else {
                try (var jedis = redisManager.getResource()) {
                    var count = jedis.zcount("syncmoney:baltop", Double.POSITIVE_INFINITY, 100000000);
                    richCount = count;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to count rich players: " + e.getMessage());
        }
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.rich-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.players.millionaire")
                .replace("{count}", String.valueOf(richCount)));
    }

    /**
     * Handles transaction statistics query.
     */
    private void handleTransactions(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.transactions.header"));


        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.transactions.note"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.transactions.audit-hint"));
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.usage.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.usage.overview"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.usage.supply"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.usage.players"));
        MessageHelper.sendMessage(sender, plugin.getMessage("econstats.usage.transactions"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.econstats")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("overview", "supply", "players", "transactions")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
