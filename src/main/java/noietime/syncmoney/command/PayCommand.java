package noietime.syncmoney.command;

import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
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
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.Constants;
import org.bukkit.Bukkit;
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
import java.util.Optional;
import java.util.UUID;

/**
 * /pay command.
 * [MainThread] Bukkit command execution on main thread
 */
public final class PayCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final NameResolver nameResolver;
    private final FallbackEconomyWrapper fallbackWrapper;
    private final TransferLockManager lockManager;
    private final CooldownManager cooldownManager;
    private final DbWriteQueue dbWriteQueue;
    private final EconomyWriteQueue writeQueue;
    private final PubsubSubscriber pubsubSubscriber;
    private final BaltopManager baltopManager;
    private final boolean localMode;

    private String transferScriptSha;
    private volatile boolean transferScriptBlocked = false;

    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;
    private final BigDecimal confirmThreshold;
    private final boolean allowInDegraded;

    private final java.util.Map<UUID, ConfirmInfo> pendingConfirmations = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Transfer confirmation information.
     */
    private record ConfirmInfo(String targetName, BigDecimal amount, long timestamp) {}

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
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.nameResolver = nameResolver;
        this.fallbackWrapper = fallbackWrapper;
        this.lockManager = lockManager;
        this.cooldownManager = cooldownManager;
        this.dbWriteQueue = dbWriteQueue;
        this.writeQueue = writeQueue;
        this.pubsubSubscriber = pubsubSubscriber;
        this.baltopManager = baltopManager;
        this.localMode = localMode;
        this.minAmount = NumericUtil.normalize(minAmount);
        this.maxAmount = NumericUtil.normalize(maxAmount);
        this.confirmThreshold = NumericUtil.normalize(config.getPayConfirmThreshold());
        this.allowInDegraded = allowInDegraded;

        if (!localMode) {
            loadTransferScript();
        }
    }

    private void loadTransferScript() {
        if (redisManager == null || redisManager.isDegraded()) {
            plugin.getLogger().warning("Redis not available, skipping Lua script loading.");
            return;
        }
        try {
            String script = loadScript("atomic_transfer.lua");
            try (var jedis = redisManager.getResource()) {
                transferScriptSha = jedis.scriptLoad(script);
                transferScriptBlocked = false;
                plugin.getLogger().fine("Transfer Lua script loaded.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load transfer script: " + e.getMessage());
        }
    }

    /**
     * Reload Lua script (called when script is flagged as dangerous by Redis).
     */
    private synchronized void reloadTransferScript() {
        try {
            plugin.getLogger().warning("Reloading transfer Lua script due to previous blocking...");

            String script = loadScript("atomic_transfer.lua");
            try (var jedis = redisManager.getResource()) {
                transferScriptSha = jedis.scriptLoad(script);
                transferScriptBlocked = false;
                plugin.getLogger().fine("Transfer Lua script reloaded successfully.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload transfer script: " + e.getMessage());
        }
    }

    /**
     * Check and handle Lua script error.
     * @param errorMessage Error message
     * @return true if retry is needed, false if error is unrecoverable
     */
    private boolean handleTransferScriptError(String errorMessage) {
        if (errorMessage != null && errorMessage.contains("not allowed from script")) {
            plugin.getLogger().severe("Transfer Lua script blocked by Redis, will attempt to reload...");
            transferScriptBlocked = true;
            reloadTransferScript();
            return true;
        }
        return false;
    }

    private String loadScript(String filename) {
        try (var is = plugin.getResource("lua/" + filename);
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load script: " + filename, e);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
            @NotNull String label, String[] args) {

        if (!(sender instanceof Player player)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.player-only"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            return handleConfirmation(player);
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

        if (amount.compareTo(confirmThreshold) >= 0) {
            return requestConfirmation(player, targetName, amount);
        }

        executeTransferAsync(player, targetName, amount);

        return true;
    }

    /**
     * Request large transfer confirmation.
     */
    private boolean requestConfirmation(Player player, String targetName, BigDecimal amount) {
        UUID uuid = player.getUniqueId();

        MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-request"));
        MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-details")
                .replace("{player}", targetName)
                .replace("{amount}", FormatUtil.formatCurrency(amount)));

        MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-hint"));

        pendingConfirmations.put(uuid, new ConfirmInfo(targetName, amount, System.currentTimeMillis()));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingConfirmations.remove(uuid);
        }, Constants.CONFIRM_TIMEOUT_TICKS);

        return true;
    }

    /**
     * Handle confirmation reply.
     */
    private boolean handleConfirmation(Player player) {
        UUID uuid = player.getUniqueId();
        ConfirmInfo info = pendingConfirmations.remove(uuid);

        if (info == null) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-expired"));
            return true;
        }

        if (!cooldownManager.checkAndUpdate(uuid)) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.cooldown")
                    .replace("{seconds}", String.valueOf(cooldownManager.getRemainingSeconds(uuid))));
            pendingConfirmations.remove(uuid);
            return true;
        }

        executeTransferAsync(player, info.targetName(), info.amount());

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
            java.util.List<String> suggestions = new java.util.ArrayList<>(Arrays.asList("100", "1000", "10000", "100000", "1000000"));

            if (sender instanceof Player player) {
                try {
                    BigDecimal balance = economyFacade.getBalance(player.getUniqueId());
                    if (balance.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal quarter = balance.divide(BigDecimal.valueOf(4), 0, java.math.RoundingMode.DOWN);
                        BigDecimal half = balance.divide(BigDecimal.valueOf(2), 0, java.math.RoundingMode.DOWN);
                        BigDecimal threeQuarters = balance.multiply(BigDecimal.valueOf(3)).divide(BigDecimal.valueOf(4), 0, java.math.RoundingMode.DOWN);

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
     * Execute transfer in LOCAL mode (using EconomyFacade directly)
     */
    private void executeLocalTransfer(Player sender, String targetName, BigDecimal amount) {
        UUID senderUuid = sender.getUniqueId();
        String senderName = sender.getName();

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            Optional<UUID> targetUuidOpt = nameResolver.resolve(targetName);
            if (targetUuidOpt.isEmpty()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.target-not-found")
                        .replace("{player}", targetName));
                return;
            }

            UUID targetUuid = targetUuidOpt.get();

            if (!lockManager.acquireDualLock(senderUuid, targetUuid)) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.lock-failed"));
                return;
            }

            try {
                double senderBalance = economyFacade.getBalanceAsDouble(senderUuid);
                if (senderBalance < amount.doubleValue()) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("pay.insufficient-funds"));
                    return;
                }

                double senderNewBalance = economyFacade.withdraw(senderUuid, amount.doubleValue(),
                        EconomyEvent.EventSource.PLAYER_TRANSFER);
                double targetNewBalance = economyFacade.deposit(targetUuid, amount.doubleValue(),
                        EconomyEvent.EventSource.PLAYER_TRANSFER);

                if (senderNewBalance < 0 || targetNewBalance < 0) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                            .replace("{error}", "Database error"));
                    return;
                }

                if (baltopManager != null) {
                    baltopManager.updatePlayerRank(senderUuid, senderNewBalance);
                    baltopManager.updatePlayerRank(targetUuid, targetNewBalance);
                }

                notifyTransferSuccess(sender, targetName, amount, senderNewBalance);

                plugin.getLogger().info("Local Transfer: " + senderName + " -> " + targetName +
                        " " + amount.toPlainString() + " (sender new balance: " + senderNewBalance + ")");

            } catch (Exception e) {
                plugin.getLogger().severe("Local transfer error: " + e.getMessage());
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.error"));
            } finally {
                lockManager.releaseDualLock(senderUuid, targetUuid);
            }
        });
    }

    private void executeTransferAsync(Player sender, String targetName, BigDecimal amount) {
        if (localMode) {
            executeLocalTransfer(sender, targetName, amount);
            return;
        }

        UUID senderUuid = sender.getUniqueId();
        String senderName = sender.getName();

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            Optional<UUID> targetUuidOpt = resolveTargetPlayer(targetName);
            if (targetUuidOpt.isEmpty()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.target-not-found")
                        .replace("{player}", targetName));
                return;
            }

            UUID targetUuid = targetUuidOpt.get();

            if (!acquireTransferLock(senderUuid, targetUuid)) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.lock-failed"));
                return;
            }

            try {
                Object result = executeLuaTransfer(senderUuid, targetUuid, amount);

                if (result instanceof java.util.List) {
                    handleTransferSuccess(sender, senderName, targetName, amount, result);
                } else if (result instanceof String) {
                    handleTransferError(sender, result);
                }
            } catch (Exception e) {
                handleTransferException(sender, e);
            } finally {
                releaseTransferLock(senderUuid, targetUuid);
            }
        });
    }

    /**
     * Execute Lua transfer script.
     */
    private Object executeLuaTransfer(UUID senderUuid, UUID targetUuid, BigDecimal amount) throws Exception {
        String senderKey = "syncmoney:balance:" + senderUuid;
        String senderVersionKey = "syncmoney:version:" + senderUuid;
        String targetKey = "syncmoney:balance:" + targetUuid;
        String targetVersionKey = "syncmoney:version:" + targetUuid;

        try (var jedis = redisManager.getResource()) {
            return jedis.evalsha(
                    transferScriptSha,
                    4,
                    senderKey, senderVersionKey, targetKey, targetVersionKey,
                    amount.toPlainString());
        }
    }

    /**
     * Handle transfer success - process all post-transfer operations.
     */
    private void handleTransferSuccess(Player sender, String senderName, String targetName,
            BigDecimal amount, Object result) {
        @SuppressWarnings("unchecked")
        var list = (java.util.List<?>) result;

        if (list.size() < 4) {
            plugin.getLogger().severe("Lua script returned insufficient elements: " + list.size());
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", "Internal error: invalid response"));
            return;
        }

        BigDecimal senderNewBalanceBD = NumericUtil.normalize(list.get(0).toString());
        BigDecimal targetNewBalanceBD = NumericUtil.normalize(list.get(1).toString());
        double senderNewBalance = senderNewBalanceBD.doubleValue();
        double targetNewBalance = targetNewBalanceBD.doubleValue();
        long senderVersion = Long.parseLong(list.get(2).toString());
        long targetVersion = Long.parseLong(list.get(3).toString());

        UUID senderUuid = sender.getUniqueId();
        UUID targetUuid = sender.getServer().getPlayerUniqueId(targetName);
        if (targetUuid == null) {
            targetUuid = UUID.fromString(list.get(4).toString());
        }

        economyFacade.updateMemoryState(senderUuid, senderNewBalance, senderVersion);
        economyFacade.updateMemoryState(targetUuid, targetNewBalance, targetVersion);
        cacheManager.updateMemoryCache(senderUuid, senderNewBalance, senderVersion);
        cacheManager.updateMemoryCache(targetUuid, targetNewBalance, targetVersion);

        if (dbWriteQueue != null) {
            String serverName = config.getServerName();
            dbWriteQueue.offer(createDbTask(senderUuid, senderName, senderNewBalance, senderVersion, serverName));
            dbWriteQueue.offer(createDbTask(targetUuid, targetName, targetNewBalance, targetVersion, serverName));
        }

        notifyTransferSuccess(sender, targetName, amount, senderNewBalance);
        plugin.getLogger().info("Transfer: " + senderName + " -> " + targetName +
                " " + amount.toPlainString() + " (sender new balance: " + senderNewBalance + ")");

        publishTransferEvents(senderUuid, senderName, targetUuid, senderNewBalance, targetNewBalance, amount);

        if (baltopManager != null) {
            baltopManager.updatePlayerRank(senderUuid, senderNewBalance);
            baltopManager.updatePlayerRank(targetUuid, targetNewBalance);
        }

        if (writeQueue != null) {
            writeQueue.offer(createTransferEvent(senderUuid, amount.negate(), senderNewBalance, senderVersion, "TRANSFER_OUT"));
            writeQueue.offer(createTransferEvent(targetUuid, amount, targetNewBalance, targetVersion, "TRANSFER_IN"));
        }
    }

    /**
     * Create DB task for write queue.
     */
    private DbWriteQueue.DbWriteTask createDbTask(UUID uuid, String name, double balance, long version, String serverName) {
        return new DbWriteQueue.DbWriteTask(
                uuid, name, balance, version,
                serverName != null ? serverName : "unknown",
                java.time.Instant.now());
    }

    /**
     * Create transfer event for write queue.
     */
    private EconomyEvent createTransferEvent(UUID uuid, BigDecimal delta, double newBalance, long version, String type) {
        return new EconomyEvent(
                uuid, delta, BigDecimal.valueOf(newBalance), version,
                EconomyEvent.EventType.valueOf(type),
                EconomyEvent.EventSource.PLAYER_TRANSFER,
                UUID.randomUUID().toString(),
                System.currentTimeMillis());
    }

    /**
     * Publish transfer events via PubSub.
     */
    private void publishTransferEvents(UUID senderUuid, String senderName, UUID targetUuid,
            double senderNewBalance, double targetNewBalance, BigDecimal amount) {
        if (pubsubSubscriber != null && config.isPubsubEnabled()) {
            pubsubSubscriber.publishTransferEvent(senderUuid, BigDecimal.valueOf(senderNewBalance),
                    "TRANSFER_OUT", amount.doubleValue(), senderName);
            pubsubSubscriber.publishTransferEvent(targetUuid, BigDecimal.valueOf(targetNewBalance),
                    "TRANSFER_IN", amount.doubleValue(), senderName);
        }
    }

    /**
     * Handle transfer error.
     */
    private void handleTransferError(Player sender, Object result) {
        String error = result.toString();
        if (error.contains("INSUFFICIENT_FUNDS")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.insufficient-funds"));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", error));
        }
    }

    /**
     * Handle transfer exception.
     */
    private void handleTransferException(Player sender, Exception e) {
        String errorMsg = e.getMessage();
        if (handleTransferScriptError(errorMsg)) {
            plugin.getLogger().warning("Lua script error occurred, attempting to retry transfer...");
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", "Script reloaded, please retry"));
        } else {
            plugin.getLogger().severe("Transfer error: " + errorMsg);
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.error"));
        }
    }

    private void notifyTransferSuccess(Player sender, String targetName, BigDecimal amount, double newBalance) {
        String senderMsg = plugin.getMessage("pay.success-sender")
                .replace("{player}", targetName)
                .replace("{amount}", amount.toPlainString())
                .replace("{balance}", FormatUtil.formatCurrency(newBalance));

        sender.getScheduler().run(plugin, task -> MessageHelper.sendMessage(sender, senderMsg), null);

        Player target = Bukkit.getServer().getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            String receiverMsg = plugin.getMessage("pay.success-receiver")
                    .replace("{player}", sender.getName())
                    .replace("{amount}", amount.toPlainString());
            target.getScheduler().run(plugin, task -> MessageHelper.sendMessage(target, receiverMsg), null);
        }
    }

    /**
     * Resolve target player name to UUID.
     */
    private Optional<UUID> resolveTargetPlayer(String targetName) {
        return nameResolver.resolve(targetName);
    }

    /**
     * Acquire dual lock for transfer.
     */
    private boolean acquireTransferLock(UUID senderUuid, UUID targetUuid) {
        return lockManager.acquireDualLock(senderUuid, targetUuid);
    }

    /**
     * Release dual lock for transfer.
     */
    private void releaseTransferLock(UUID senderUuid, UUID targetUuid) {
        lockManager.releaseDualLock(senderUuid, targetUuid);
    }
}
