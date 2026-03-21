package noietime.syncmoney.command;

import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.PubsubSubscriber;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /pay command — entry point, argument validation, and tab completion.
 * Delegates transfer execution to {@link PayTransferExecutor} and the
 * large-transfer confirmation flow to {@link PayConfirmationManager}.
 *
 * [MainThread] Bukkit command execution on main thread
 */
public final class PayCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;
    private final FallbackEconomyWrapper fallbackWrapper;
    private final CooldownManager cooldownManager;
    private boolean allowInDegraded;

    private BigDecimal minAmount;
    private BigDecimal maxAmount;

    private final PayTransferExecutor transferExecutor;
    private final PayConfirmationManager confirmationManager;

    public PayCommand(Syncmoney plugin, SyncmoneyConfig config, EconomyFacade economyFacade,
            CacheManager cacheManager, RedisManager redisManager,
            NameResolver nameResolver, FallbackEconomyWrapper fallbackWrapper,
            TransferLockManager lockManager, CooldownManager cooldownManager,
            DbWriteQueue dbWriteQueue, EconomyWriteQueue writeQueue,
            PubsubSubscriber pubsubSubscriber,
            BaltopManager baltopManager,
            double minAmount, double maxAmount, boolean allowInDegraded,
            boolean localMode) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.nameResolver = nameResolver;
        this.fallbackWrapper = fallbackWrapper;
        this.cooldownManager = cooldownManager;
        this.allowInDegraded = allowInDegraded;
        this.minAmount = NumericUtil.normalize(minAmount);
        this.maxAmount = NumericUtil.normalize(maxAmount);

        PayLuaScriptManager luaScriptManager = new PayLuaScriptManager(plugin, redisManager);
        if (!localMode) {
            luaScriptManager.load();
        }

        this.transferExecutor = new PayTransferExecutor(
                plugin, config, economyFacade, cacheManager, redisManager, nameResolver,
                lockManager, dbWriteQueue, writeQueue, pubsubSubscriber, baltopManager,
                luaScriptManager, localMode);

        BigDecimal confirmThreshold = NumericUtil.normalize(config.pay().getPayConfirmThreshold());
        this.confirmationManager = new PayConfirmationManager(
                plugin, cooldownManager, transferExecutor, confirmThreshold);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String label, String[] args) {

        if (!(sender instanceof Player player)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.player-only"));
            return true;
        }

        if (!player.hasPermission("syncmoney.pay")) {
            MessageHelper.sendMessage(player, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            return confirmationManager.handleConfirmation(player);
        }

        if (args.length < 2) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.usage"));
            MessageHelper.sendMessage(player, plugin.getMessage("pay.hint-format"));
            return true;
        }

        if (fallbackWrapper.isDegraded() && !allowInDegraded) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.degraded-mode-message"));
            return true;
        }

        String targetName = args[0];
        BigDecimal amount;
        try {
            amount = NumericUtil.normalize(args[1]);
        } catch (NumberFormatException e) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.invalid-amount-format"));
            return true;
        }

        if (!validateTransfer(player, targetName, amount)) {
            return true;
        }

        if (amount.compareTo(confirmationManager.getConfirmThreshold()) >= 0) {
            return confirmationManager.requestConfirmation(player, targetName, amount);
        }

        transferExecutor.executeTransferAsync(player, targetName, amount);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();

            java.util.Set<String> suggestions = new java.util.HashSet<>();
            plugin.getServer().getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));
            suggestions.addAll(nameResolver.getAllCachedNames());

            return suggestions.stream()
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .sorted()
                    .toList();
        }
        if (args.length == 2) {
            java.util.List<String> suggestions = new java.util.ArrayList<>(
                    Arrays.asList("100", "1000", "10000", "100000", "1000000"));

            if (sender instanceof Player player) {
                try {
                    BigDecimal balance = economyFacade.getBalance(player.getUniqueId());
                    if (balance.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal quarter = balance.divide(BigDecimal.valueOf(4), 0, RoundingMode.DOWN);
                        BigDecimal half = balance.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN);
                        BigDecimal threeQuarters = balance.multiply(BigDecimal.valueOf(3))
                                .divide(BigDecimal.valueOf(4), 0, RoundingMode.DOWN);

                        if (quarter.compareTo(BigDecimal.valueOf(100)) > 0) {
                            suggestions.add(quarter.toPlainString());
                        }
                        if (half.compareTo(BigDecimal.valueOf(100)) > 0) {
                            suggestions.add(half.toPlainString());
                        }
                        if (threeQuarters.compareTo(BigDecimal.valueOf(100)) > 0) {
                            suggestions.add(threeQuarters.toPlainString());
                        }
                        suggestions.add(balance.toPlainString());
                    }
                } catch (Exception ignored) {
                }
            }

            return suggestions.stream()
                    .filter(s -> s.startsWith(args[1]))
                    .distinct()
                    .sorted()
                    .toList();
        }
        return Collections.emptyList();
    }

    private boolean validateTransfer(Player sender, String targetName, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.amount-must-be-positive"));
            return false;
        }
        if (amount.compareTo(minAmount) < 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.amount-too-low")
                    .replace("{min}", minAmount.toPlainString()));
            return false;
        }
        if (amount.compareTo(maxAmount) > 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.amount-too-high")
                    .replace("{max}", maxAmount.toPlainString()));
            return false;
        }

        if (sender.getName().equalsIgnoreCase(targetName)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.self-transfer"));
            return false;
        }

        if (!cooldownManager.checkAndUpdate(sender.getUniqueId())) {
            long remaining = cooldownManager.getRemainingSeconds(sender.getUniqueId());
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.cooldown")
                    .replace("{seconds}", String.valueOf(remaining)));
            return false;
        }

        return true;
    }

    /**
     * Hot-reloads pay configuration values from a new config instance.
     * Called by {@link CommandServiceManager#reload(SyncmoneyConfig)} after /syncmoney reload.
     */
    public void reload(SyncmoneyConfig newConfig) {
        this.config = newConfig;
        this.minAmount = NumericUtil.normalize(newConfig.pay().getPayMinAmount());
        this.maxAmount = NumericUtil.normalize(newConfig.pay().getPayMaxAmount());
        this.allowInDegraded = newConfig.isPayAllowedInDegraded();
    }
}
