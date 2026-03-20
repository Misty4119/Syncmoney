package noietime.syncmoney.baltop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [SYNC-BAL-001] Baltop command handler.
 * Provides /baltop command implementation.
 */
public final class BaltopCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final BaltopManager baltopManager;
    private final noietime.syncmoney.economy.EconomyFacade economyFacade;

    private static final int ENTRIES_PER_PAGE = 10;

    public BaltopCommand(Syncmoney plugin, SyncmoneyConfig config, BaltopManager baltopManager, noietime.syncmoney.economy.EconomyFacade economyFacade) {
        this.plugin = plugin;
        this.config = config;
        this.baltopManager = baltopManager;
        this.economyFacade = economyFacade;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.money")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        int page = 1;

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("me")) {
                if (sender instanceof Player player) {
                    return showPlayerRank(player);
                } else {
                    MessageHelper.sendMessage(sender, plugin.getMessage("baltop.me-console"));
                    return true;
                }
            }

            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) {
                    page = 1;
                }
            } catch (NumberFormatException e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("baltop.invalid-page"));
                return true;
            }
        }

        int entriesPerPage = config.getBaltopEntriesPerPage();
        List<RankEntry> top = baltopManager.getTopRank(page * entriesPerPage);

        if (top.isEmpty()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("baltop.empty"));
            return true;
        }

        int totalPlayers = baltopManager.getTotalPlayers();
        int totalPages = Math.max(1, (totalPlayers + entriesPerPage - 1) / entriesPerPage);

        if (page > totalPages) {
            page = totalPages;
        }

        MessageHelper.sendMessage(sender, plugin.getMessageComponent("baltop.header"));

        int start = (page - 1) * entriesPerPage;
        int end = Math.min(start + entriesPerPage, top.size());

        for (int i = start; i < end; i++) {
            RankEntry entry = top.get(i);

            Component entryComponent = buildEntryComponent(entry, page, totalPages);
            MessageHelper.sendMessage(sender, entryComponent);
        }

        sendPageFooter(sender, page, totalPages);

        double totalSupply = baltopManager.getTotalSupply();
        String totalFormatted = baltopManager.formatNumberSmart(totalSupply);
        MessageHelper.sendMessage(sender, plugin.getMessageComponent("baltop.total-supply",
                Map.of("amount", totalFormatted)));

        if (sender instanceof Player player) {
            MessageHelper.sendMessage(sender, plugin.getMessage("baltop.hint-self"));
        }

        return true;
    }

    /**
     * [SYNC-BAL-002] Builds baltop entry component (with HoverEvent).
     */
    private Component buildEntryComponent(RankEntry entry, int currentPage, int totalPages) {
        String balanceStr = formatBalance(entry.balance());
        String playerName = entry.name() != null ? entry.name() : "?";

        Component hoverContent = buildHoverContent(entry, playerName);

        String clickCommandTemplate = plugin.getMessage("baltop.click-command");
        String clickCommand = clickCommandTemplate.replace("{player}", playerName);

        return plugin.getMessageComponent("baltop.entry",
                Map.of("rank", String.valueOf(entry.rank()),
                        "player", playerName,
                        "balance", balanceStr))
                .hoverEvent(HoverEvent.showText(hoverContent))
                .clickEvent(ClickEvent.suggestCommand(clickCommand));
    }

    /**
     * [SYNC-BAL-003] Build hover content from messages.yml.
     */
    private Component buildHoverContent(RankEntry entry, String playerName) {
        String playerTemplate = plugin.getMessage("baltop.hover.player")
                .replace("{player}", playerName);
        String balanceTemplate = plugin.getMessage("baltop.hover.balance")
                .replace("{balance}", FormatUtil.formatCurrency(entry.balance()));
        String hintTemplate = plugin.getMessage("baltop.hover.hint");

        Component playerComponent = MessageHelper.getComponent(playerTemplate);
        Component balanceComponent = MessageHelper.getComponent(balanceTemplate);
        Component hintComponent = MessageHelper.getComponent(hintTemplate);

        return Component.text()
                .append(playerComponent)
                .append(Component.newline())
                .append(balanceComponent)
                .append(Component.newline())
                .append(hintComponent)
                .build();
    }

    /**
     * [SYNC-BAL-004] Sends page footer (with pagination buttons).
     */
    private void sendPageFooter(CommandSender sender, int page, int totalPages) {
        String prevText = plugin.getMessage("baltop.pagination.prev");
        String prevDisabledText = plugin.getMessage("baltop.pagination.prev-disabled");
        String nextText = plugin.getMessage("baltop.pagination.next");
        String nextDisabledText = plugin.getMessage("baltop.pagination.next-disabled");
        String pageInfoText = plugin.getMessage("baltop.pagination.page-info")
                .replace("{page}", String.valueOf(page))
                .replace("{total}", String.valueOf(totalPages));
        String hoverPrevText = plugin.getMessage("baltop.pagination.hover-prev");
        String hoverNextText = plugin.getMessage("baltop.pagination.hover-next");

        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();

        if (page > 1) {
            Component prevButton = MessageHelper.getComponent(prevText)
                    .clickEvent(ClickEvent.runCommand("/baltop " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(MessageHelper.getComponent(hoverPrevText)));
            builder.append(prevButton);
        } else {
            builder.append(MessageHelper.getComponent(prevDisabledText));
        }

        builder.append(Component.text("  "));

        builder.append(MessageHelper.getComponent(pageInfoText));

        builder.append(Component.text("  "));

        if (page < totalPages) {
            Component nextButton = MessageHelper.getComponent(nextText)
                    .clickEvent(ClickEvent.runCommand("/baltop " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(MessageHelper.getComponent(hoverNextText)));
            builder.append(nextButton);
        } else {
            builder.append(MessageHelper.getComponent(nextDisabledText));
        }

        sender.sendMessage(builder.build());
    }

    /**
     * [SYNC-BAL-005] Displays player's personal ranking.
     */
    private boolean showPlayerRank(Player player) {
        UUID uuid = player.getUniqueId();
        int rank = baltopManager.getPlayerRank(uuid);
        double balance = economyFacade.getBalance(player.getUniqueId()).doubleValue();

        if (rank <= 0) {
            MessageHelper.sendMessage(player, plugin.getMessage("baltop.not-ranked"));
            return true;
        }

        MessageHelper.sendMessage(player, plugin.getMessage("baltop.self-header"));
        MessageHelper.sendMessage(player, plugin.getMessage("baltop.self-rank")
                .replace("{rank}", String.valueOf(rank))
                .replace("{balance}", formatBalance(balance)));

        if (rank > 1) {
            List<RankEntry> topEntries = baltopManager.getTopRank(rank - 1);
            if (!topEntries.isEmpty()) {
                RankEntry above = topEntries.get(rank - 2);
                if (above != null) {
                    double diff = above.balance() - balance;
                    MessageHelper.sendMessage(player, plugin.getMessage("baltop.self-gap")
                            .replace("{amount}", formatBalance(diff))
                            .replace("{player}", above.name() != null ? above.name() : "?"));
                }
            }
        }

        return true;
    }

    /**
     * [SYNC-BAL-006] Gets economy facade balance.
     */
    private double getPlayerBalance(UUID uuid) {
        try {
            var facade = plugin.getEconomyFacade();
            if (facade != null) {
                return facade.getBalance(uuid).doubleValue();
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    /**
     * [SYNC-BAL-007] Formats balance based on config.
     */
    private String formatBalance(double balance) {
        String format = config.getBaltopFormat();
        return switch (format.toLowerCase()) {
            case "smart" -> baltopManager.formatNumberSmart(balance);
            case "abbreviated" -> baltopManager.formatNumberAbbreviated(balance);
            default -> FormatUtil.formatCurrency(balance);
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> pages = new ArrayList<>();

            int entriesPerPage = config.getBaltopEntriesPerPage();
            int totalPages = Math.max(1, (baltopManager.getTotalPlayers() + entriesPerPage - 1) / entriesPerPage);
            for (int i = 1; i <= Math.min(totalPages, 10); i++) {
                pages.add(String.valueOf(i));
            }

            if (sender instanceof Player) {
                pages.add("me");
            }

            return pages.stream()
                    .filter(p -> p.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
