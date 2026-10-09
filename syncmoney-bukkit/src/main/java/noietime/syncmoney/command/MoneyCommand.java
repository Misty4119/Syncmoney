package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyModeRouter;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.uuid.OnlinePlayerRegistry;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /money command.
 * [MainThread] Bukkit command execution on main thread
 */
public final class MoneyCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final EconomyFacade economyFacade;
    private final EconomyModeRouter economyModeRouter;
    private final NameResolver nameResolver;
    private final FallbackEconomyWrapper fallbackWrapper;

    private String currencyName;
    private int decimalPlaces;

    public MoneyCommand(Syncmoney plugin, EconomyFacade economyFacade, EconomyModeRouter economyModeRouter,
                       NameResolver nameResolver, FallbackEconomyWrapper fallbackWrapper,
                       String currencyName, int decimalPlaces) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.economyModeRouter = economyModeRouter;
        this.nameResolver = nameResolver;
        this.fallbackWrapper = fallbackWrapper;
        this.currencyName = currencyName;
        this.decimalPlaces = decimalPlaces;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                           @NotNull String label, String[] args) {


        if (!sender.hasPermission("syncmoney.money")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            if (args.length == 0) {
                MessageHelper.sendMessage(sender, plugin.getMessage("money.console-usage"));
                return true;
            }
            return showOtherBalanceFromConsole(sender, args[0]);
        }

        if (args.length == 0) {
            return showSelfBalance(player);
        }


        return showOtherBalance(player, args[0]);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                   @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            OnlinePlayerRegistry registry = plugin.getOnlinePlayerRegistry();
            if (registry != null) {
                return registry.suggestOnlinePlayerNames(args[0], true);
            }
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }

    /**
     * Query other player's balance from console.
     */
    private boolean showOtherBalanceFromConsole(CommandSender sender, String targetName) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            var targetUuid = nameResolver.resolve(targetName);
            if (targetUuid.isEmpty()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("money.player-not-found")
                        .replace("{player}", targetName));
                return;
            }

            BigDecimal balance = getBalance(targetUuid.get());

            String message = plugin.getMessage("money.others")
                    .replace("{player}", targetName)
                    .replace("{balance}", FormatUtil.formatCurrency(balance));

            if (fallbackWrapper.isDegraded()) {
                message += " " + plugin.getMessage("general.degraded-local");
            }

            MessageHelper.sendMessage(sender, message);
        });

        return true;
    }

    private boolean showSelfBalance(Player player) {
        UUID uuid = player.getUniqueId();

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            var balance = getBalance(uuid);

            player.getScheduler().run(plugin, (t) -> {
                String balanceStr = FormatUtil.formatCurrency(balance);

                String message = plugin.getMessage("money.self")
                        .replace("{balance}", balanceStr);

                MessageHelper.sendMessage(player, message);

                String visualBalance = plugin.getMessage("money.balance-visual")
                        .replace("{balance}", balanceStr)
                        .replace("{currency}", currencyName);
                MessageHelper.sendMessage(player, visualBalance);

                if (player.hasPermission("syncmoney.money.others")) {
                    MessageHelper.sendMessage(player, plugin.getMessage("money.hint-check-others"));
                }

                if (fallbackWrapper.isDegraded()) {
                    MessageHelper.sendMessage(player, " " + plugin.getMessage("general.degraded-local"));
                }
            }, null);
        });

        return true;
    }

    private boolean showOtherBalance(Player player, String targetName) {
        if (!player.hasPermission("syncmoney.money.others")) {
            MessageHelper.sendMessage(player, plugin.getMessage("money.no-permission-others"));
            MessageHelper.sendMessage(player, plugin.getMessage("money.hint-permission"));
            return true;
        }

        String targetPlayerName = targetName;

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            var targetUuid = nameResolver.resolve(targetName);
            if (targetUuid.isEmpty()) {
                player.getScheduler().run(plugin, (t) -> MessageHelper.sendMessage(player,
                        plugin.getMessage("money.player-not-found").replace("{player}", targetPlayerName)), null);
                return;
            }

            BigDecimal balance = getBalance(targetUuid.get());

            player.getScheduler().run(plugin, (t) -> {
                String message = plugin.getMessage("money.others")
                        .replace("{player}", targetPlayerName)
                        .replace("{balance}", FormatUtil.formatCurrency(balance));

                MessageHelper.sendMessage(player, message);

                if (fallbackWrapper.isDegraded()) {
                    MessageHelper.sendMessage(player, " " + plugin.getMessage("general.degraded-local"));
                }
            }, null);
        });

        return true;
    }

    /**
     * Hot-reloads display configuration values from a new config instance.
     * Called by {@link CommandServiceManager#reload(SyncmoneyConfig)} after /syncmoney reload.
     */
    public void reload(SyncmoneyConfig newConfig) {
        this.currencyName = newConfig.display().getCurrencyName();
        this.decimalPlaces = newConfig.display().getDecimalPlaces();
    }

    /**
     * Reads through the active economy-mode route. In CMI mode this reaches CMI's API first and
     * uses its Redis mirror only as a fallback, which keeps offline-player lookups correct before
     * the player has joined this server after a restart.
     */
    private BigDecimal getBalance(UUID uuid) {
        if (economyModeRouter != null) {
            return economyModeRouter.getBalance(uuid);
        }
        return economyFacade.getBalance(uuid);
    }
}
