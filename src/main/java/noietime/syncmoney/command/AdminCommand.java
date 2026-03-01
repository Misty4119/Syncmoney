package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.PubsubSubscriber;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.Constants;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin command handler.
 * Handles /syncmoney set/give/take commands.
 *
 * Usage:
 * /syncmoney set <player> <amount> - Set player balance
 * /syncmoney give <player> <amount> - Give money to player
 * /syncmoney take <player> <amount> - Deduct money from player
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    private final PubsubSubscriber pubsubSubscriber;
    private final EconomicCircuitBreaker circuitBreaker;
    private final NameResolver nameResolver;
    private final BaltopManager baltopManager;
    private final AuditLogger auditLogger;

    private final BigDecimal confirmThreshold;

    private final java.util.Map<String, ConfirmInfo> pendingConfirmations = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Admin operation confirmation information.
     */
    private record ConfirmInfo(String subCommand, String playerName, BigDecimal amount, long timestamp) {}

    public AdminCommand(Syncmoney plugin, SyncmoneyConfig config,
                        EconomyFacade economyFacade, RedisManager redisManager,
                        PubsubSubscriber pubsubSubscriber,
                        EconomicCircuitBreaker circuitBreaker,
                        NameResolver nameResolver,
                        BaltopManager baltopManager,
                        AuditLogger auditLogger) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
        this.pubsubSubscriber = pubsubSubscriber;
        this.circuitBreaker = circuitBreaker;
        this.nameResolver = nameResolver;
        this.baltopManager = baltopManager;
        this.auditLogger = auditLogger;
        this.confirmThreshold = NumericUtil.normalize(config.getAdminConfirmThreshold());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            return handleConfirmation(sender);
        }

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String playerName = args.length > 1 ? args[1] : null;
        String amountStr = args.length > 2 ? args[2] : null;

        switch (subCommand) {
            case "set" -> {
                if (amountStr == null) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.set"));
                    return true;
                }
                handleSet(sender, playerName, amountStr);
            }
            case "give" -> {
                if (amountStr == null) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.give"));
                    return true;
                }
                handleGive(sender, playerName, amountStr);
            }
            case "take" -> {
                if (amountStr == null) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.take"));
                    return true;
                }
                handleTake(sender, playerName, amountStr);
            }
            case "reset" -> handleReset(sender, playerName);
            case "view" -> handleView(sender, playerName);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Handle admin operation confirmation reply.
     */
    private boolean handleConfirmation(CommandSender sender) {
        String key = sender.getName();
        ConfirmInfo info = pendingConfirmations.remove(key);

        if (info == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.confirm-expired"));
            return true;
        }

        switch (info.subCommand()) {
            case "set" -> handleSetConfirmed(sender, info.playerName(), info.amount());
            case "give" -> handleGiveConfirmed(sender, info.playerName(), info.amount());
            case "take" -> handleTakeConfirmed(sender, info.playerName(), info.amount());
            default -> MessageHelper.sendMessage(sender, plugin.getMessage("admin.confirm-expired"));
        }

        return true;
    }

    /**
     * Handle set balance command.
     */
    private void handleSet(CommandSender sender, String playerName, String amountStr) {
        if (!plugin.getPermissionService().canExecute(sender, "set")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.invalid-amount"));
            return;
        }

        if (amount.compareTo(confirmThreshold) >= 0) {
            requestConfirmation(sender, "set", playerName, amount);
            return;
        }

        executeSet(sender, playerName, amount);
    }

    /**
     * Handle set balance confirmation (confirmed).
     */
    private void handleSetConfirmed(CommandSender sender, String playerName, BigDecimal amount) {
        executeSet(sender, playerName, amount);
    }

    /**
     * Execute set balance.
     */
    private void executeSet(CommandSender sender, String playerName, BigDecimal amount) {
        UUID uuid = resolvePlayerUUID(playerName);
        if (uuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("money.player-not-found").replace("{player}", playerName));
            return;
        }

        BigDecimal newBalance = economyFacade.setBalance(uuid, amount, EconomyEvent.EventSource.ADMIN_SET);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.error"));
            return;
        }

        String message = plugin.getMessage("admin.set-success")
                .replace("{player}", playerName)
                .replace("{balance}", FormatUtil.formatCurrency(newBalance));
        MessageHelper.sendMessage(sender, message);

        if (baltopManager != null) {
            baltopManager.updatePlayerRank(uuid, newBalance.doubleValue());
        }

        if (auditLogger != null) {
            auditLogger.flush();
        }

        notifyPlayerIfOnline(uuid, plugin.getMessage("admin.balance-set-by-admin")
                .replace("{balance}", FormatUtil.formatCurrency(newBalance)));
    }

    /**
     * Request admin operation confirmation.
     */
    private void requestConfirmation(CommandSender sender, String subCommand, String playerName, BigDecimal amount) {
        String key = sender.getName();

        MessageHelper.sendMessage(sender, plugin.getMessage("admin.confirm-request"));
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.confirm-details")
                .replace("{subCommand}", subCommand.toUpperCase())
                .replace("{player}", playerName)
                .replace("{amount}", FormatUtil.formatCurrency(amount)));

        MessageHelper.sendMessage(sender, plugin.getMessage("admin.confirm-hint"));

        pendingConfirmations.put(key, new ConfirmInfo(subCommand, playerName, amount, System.currentTimeMillis()));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingConfirmations.remove(key);
        }, Constants.CONFIRM_TIMEOUT_TICKS);
    }

    /**
     * Handle give money command.
     */
    private void handleGive(CommandSender sender, String playerName, String amountStr) {
        if (!plugin.getPermissionService().canExecute(sender, "give")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.invalid-amount"));
            return;
        }

        if (!plugin.getPermissionService().checkDailyLimit(sender, "give", amount.doubleValue())) {
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.daily-limit-reached"));
            double remaining = plugin.getPermissionService().getRemainingLimit(sender, "give");
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.daily-remaining").replace("{remaining}", String.valueOf(remaining)));
            return;
        }

        if (amount.compareTo(confirmThreshold) >= 0) {
            requestConfirmation(sender, "give", playerName, amount);
            return;
        }

        executeGive(sender, playerName, amount);
    }

    /**
     * Handle give confirmation (confirmed).
     */
    private void handleGiveConfirmed(CommandSender sender, String playerName, BigDecimal amount) {
        executeGive(sender, playerName, amount);
    }

    /**
     * Execute give money.
     */
    private void executeGive(CommandSender sender, String playerName, BigDecimal amount) {
        UUID uuid = resolvePlayerUUID(playerName);
        if (uuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("money.player-not-found").replace("{player}", playerName));
            return;
        }

        if (circuitBreaker != null && config.isCircuitBreakerEnabled()) {
            var cbResult = circuitBreaker.checkTransaction(uuid, amount, EconomyEvent.EventSource.ADMIN_GIVE);
            if (!cbResult.allowed()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("admin.breaker-blocked")
                    .replace("{reason}", cbResult.reason()));
                return;
            }
        }

        BigDecimal currentBalance = economyFacade.getBalance(uuid);

        BigDecimal newBalance = economyFacade.deposit(uuid, amount, EconomyEvent.EventSource.ADMIN_GIVE);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.error"));
            return;
        }

        String message = plugin.getMessage("admin.give-success")
                .replace("{player}", playerName)
                .replace("{amount}", FormatUtil.formatCurrency(amount))
                .replace("{balance}", FormatUtil.formatCurrency(newBalance));
        MessageHelper.sendMessage(sender, message);

        plugin.getPermissionService().recordUsage(sender, "give", amount.doubleValue());

        if (baltopManager != null) {
            baltopManager.updatePlayerRank(uuid, newBalance.doubleValue());
        }

        if (auditLogger != null) {
            auditLogger.flush();
        }

        notifyPlayerIfOnline(uuid, plugin.getMessage("admin.money-received")
                .replace("{amount}", FormatUtil.formatCurrency(amount))
                .replace("{balance}", FormatUtil.formatCurrency(newBalance)));
    }

    /**
     * Handle deduct money command.
     */
    private void handleTake(CommandSender sender, String playerName, String amountStr) {
        if (!plugin.getPermissionService().canExecute(sender, "take")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.invalid-amount"));
            return;
        }

        if (!plugin.getPermissionService().checkDailyLimit(sender, "take", amount.doubleValue())) {
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.daily-limit-reached"));
            double remaining = plugin.getPermissionService().getRemainingLimit(sender, "take");
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.daily-remaining").replace("{remaining}", String.valueOf(remaining)));
            return;
        }

        if (amount.compareTo(confirmThreshold) >= 0) {
            requestConfirmation(sender, "take", playerName, amount);
            return;
        }

        executeTake(sender, playerName, amount);
    }

    /**
     * Handle take confirmation (confirmed).
     */
    private void handleTakeConfirmed(CommandSender sender, String playerName, BigDecimal amount) {
        executeTake(sender, playerName, amount);
    }

    /**
     * Execute deduct money.
     */
    private void executeTake(CommandSender sender, String playerName, BigDecimal amount) {
        UUID uuid = resolvePlayerUUID(playerName);
        if (uuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("money.player-not-found").replace("{player}", playerName));
            return;
        }

        if (circuitBreaker != null && config.isCircuitBreakerEnabled()) {
            var cbResult = circuitBreaker.checkTransaction(uuid, amount.negate(), EconomyEvent.EventSource.ADMIN_TAKE);
            if (!cbResult.allowed()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("admin.breaker-blocked")
                    .replace("{reason}", cbResult.reason()));
                return;
            }
        }

        BigDecimal newBalance = economyFacade.withdraw(uuid, amount, EconomyEvent.EventSource.ADMIN_TAKE);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal currentBalance = economyFacade.getBalance(uuid);
            MessageHelper.sendMessage(sender, plugin.getMessage("admin.insufficient-funds")
                    .replace("{player}", playerName)
                    .replace("{balance}", FormatUtil.formatCurrency(currentBalance)));
            return;
        }

        if (circuitBreaker != null && config.isCircuitBreakerEnabled()) {
            BigDecimal oldBalance = newBalance.add(amount);
            circuitBreaker.onTransactionComplete(uuid, oldBalance, newBalance);
        }

        String message = plugin.getMessage("admin.take-success")
                .replace("{player}", playerName)
                .replace("{amount}", FormatUtil.formatCurrency(amount))
                .replace("{balance}", FormatUtil.formatCurrency(newBalance));
        MessageHelper.sendMessage(sender, message);

        plugin.getPermissionService().recordUsage(sender, "take", amount.doubleValue());

        if (baltopManager != null) {
            baltopManager.updatePlayerRank(uuid, newBalance.doubleValue());
        }

        if (auditLogger != null) {
            auditLogger.flush();
        }

        notifyPlayerIfOnline(uuid, plugin.getMessage("admin.money-taken")
                .replace("{amount}", FormatUtil.formatCurrency(amount))
                .replace("{balance}", FormatUtil.formatCurrency(newBalance)));
    }

    /**
     * Handle reset balance command.
     */
    private void handleReset(CommandSender sender, String playerName) {
        if (!sender.hasPermission("syncmoney.admin.set")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return;
        }

        UUID uuid = resolvePlayerUUID(playerName);
        if (uuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("money.player-not-found").replace("{player}", playerName));
            return;
        }

        BigDecimal newBalance = economyFacade.setBalance(uuid, BigDecimal.ZERO, EconomyEvent.EventSource.ADMIN_SET);

        String message = plugin.getMessage("admin.reset-success")
                .replace("{player}", playerName);
        MessageHelper.sendMessage(sender, message);
    }

    /**
     * Handle view balance command.
     */
    private void handleView(CommandSender sender, String playerName) {
        if (!sender.hasPermission("syncmoney.money.others")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("money.no-permission"));
            return;
        }

        UUID uuid = resolvePlayerUUID(playerName);
        if (uuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("money.player-not-found").replace("{player}", playerName));
            return;
        }

        BigDecimal balance = economyFacade.getBalance(uuid);
        String message = plugin.getMessage("money.others")
                .replace("{player}", playerName)
                .replace("{balance}", FormatUtil.formatCurrency(balance));
        MessageHelper.sendMessage(sender, message);
    }

    /**
     * Parse amount string to BigDecimal.
     */
    private BigDecimal parseAmount(String amountStr) {
        try {
            String cleaned = amountStr.replace(",", "");
            BigDecimal amount = new BigDecimal(cleaned);
            return NumericUtil.normalize(amount);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolve player name to UUID.
     */
    private UUID resolvePlayerUUID(String playerName) {
        Optional<UUID> resolved = nameResolver.resolve(playerName);
        if (resolved.isPresent()) return resolved.get();

        Player player = plugin.getServer().getPlayer(playerName);
        if (player != null) {
            return player.getUniqueId();
        }

        var offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(playerName);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
            return offlinePlayer.getUniqueId();
        }

        return null;
    }

    /**
     * Publish balance change to cross-server channel.
     */
    private void publishBalanceChange(UUID uuid, BigDecimal newBalance) {
        if (config.isPubsubEnabled() && pubsubSubscriber != null) {
            pubsubSubscriber.publishBalanceChange(uuid, newBalance);
        }
    }

    /**
     * Send notification if player is online.
     */
    private void notifyPlayerIfOnline(UUID uuid, String message) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null) {
            MessageHelper.sendMessage(player, message);
        }
    }

    /**
     * Send usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.set"));
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.give"));
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.take"));
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.reset"));
        MessageHelper.sendMessage(sender, plugin.getMessage("admin.usage.view"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        // args[0] is now subcommand name (set/give/take/reset/view)
        if (args.length == 1) {
            return Arrays.asList("set", "give", "take", "reset", "view")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && (args[0].equalsIgnoreCase("set") ||
                args[0].equalsIgnoreCase("give") ||
                args[0].equalsIgnoreCase("take"))) {
            return Arrays.asList("100", "1000", "10000", "100000", "1000000")
                    .stream()
                    .filter(s -> s.startsWith(args[2]))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
