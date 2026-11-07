package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [SYNC-TEST-001] Stress testing command for validating data consistency under concurrent multi-threaded operations.
 */
public final class TestCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;

    public TestCommand(Syncmoney plugin, EconomyFacade economyFacade, RedisManager redisManager) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.test")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }


        if (plugin.getSyncmoneyConfig().getEconomyMode() == EconomyMode.LOCAL) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.unavailable-local-mode"));
            return true;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "concurrent-pay" -> handleConcurrentPay(sender, args);
            case "total-supply" -> handleTotalSupplyCheck(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    private void handleConcurrentPay(CommandSender sender, String[] args) {
        if (args.length < 3) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.usage"));
            return;
        }

        int threads;
        int iterations;
        try {
            threads = Integer.parseInt(args[1]);
            iterations = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.invalid-number"));
            return;
        }

        if (threads < 1 || threads > 50) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.invalid-threads"));
            return;
        }

        if (iterations < 1 || iterations > 10000) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.invalid-iterations"));
            return;
        }

        long totalTransfers = (long) threads * iterations;
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.starting")
                .replace("{threads}", String.valueOf(threads))
                .replace("{iterations}", String.valueOf(iterations)));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.note")
                .replace("{total}", String.valueOf(totalTransfers)));

        UUID userA = UUID.nameUUIDFromBytes("syncmoney-test-a".getBytes());
        UUID userB = UUID.nameUUIDFromBytes("syncmoney-test-b".getBytes());
        MessageHelper.SenderContext replyContext = MessageHelper.captureSender(sender);
        var guard = plugin.getPlayerTransactionGuard();
        if (guard != null) {
            guard.setTestModeBypass(true);
            MessageHelper.sendMessage(sender, plugin.getMessage("test.mode-enabled"));
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                BigDecimal initialAmount = BigDecimal.valueOf(1000000);
                BigDecimal balanceA = economyFacade.setBalance(userA, initialAmount, EconomyEvent.EventSource.TEST);
                BigDecimal balanceB = economyFacade.setBalance(userB, initialAmount, EconomyEvent.EventSource.TEST);
                if (balanceA == null || balanceA.signum() < 0 || balanceB == null || balanceB.signum() < 0) {
                    throw new IllegalStateException("Virtual account initialization was rejected");
                }
                plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                        delayed -> runConcurrentPay(replyContext, threads, iterations, userA, userB,
                                initialAmount, guard), 500L, TimeUnit.MILLISECONDS);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Concurrent pay test initialization failed");
                finishTestMode(replyContext, guard);
                replyContext.sendMessage(plugin, plugin.getMessage("test.concurrent-pay.start-failed"));
            }
        });
    }

    private void runConcurrentPay(MessageHelper.SenderContext replyContext, int threads, int iterations,
            UUID userA, UUID userB, BigDecimal initialAmount, PlayerTransactionGuard guard) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        ArrayList<CompletableFuture<Void>> workers = new ArrayList<>(threads);
        long start = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            CompletableFuture<Void> worker = new CompletableFuture<>();
            workers.add(worker);
            try {
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                    try {
                        for (int j = 0; j < iterations; j++) {
                            try {
                                BigDecimal withdrawResult = economyFacade.withdraw(userA, BigDecimal.ONE,
                                        EconomyEvent.EventSource.TEST);
                                if (withdrawResult != null && withdrawResult.compareTo(BigDecimal.ZERO) >= 0) {
                                    BigDecimal depositResult = economyFacade.deposit(userB, BigDecimal.ONE,
                                            EconomyEvent.EventSource.TEST);
                                    if (depositResult != null && depositResult.compareTo(BigDecimal.ZERO) >= 0) {
                                        successCount.incrementAndGet();
                                    } else {
                                        economyFacade.deposit(userA, BigDecimal.ONE, EconomyEvent.EventSource.TEST);
                                        failCount.incrementAndGet();
                                    }
                                } else {
                                    failCount.incrementAndGet();
                                }
                            } catch (RuntimeException failure) {
                                failCount.incrementAndGet();
                            }
                        }
                        worker.complete(null);
                    } catch (RuntimeException failure) {
                        worker.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException rejected) {
                worker.completeExceptionally(rejected);
            }
        }

        CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            try {
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> finishConcurrentPay(replyContext, initialAmount, userA, userB, start,
                                successCount, failCount, guard, failure));
            } catch (RuntimeException schedulingFailure) {
                plugin.getLogger().warning("Concurrent pay test could not schedule result processing");
                finishTestMode(replyContext, guard);
            }
        });
    }

    private void finishConcurrentPay(MessageHelper.SenderContext replyContext, BigDecimal initialAmount,
            UUID userA, UUID userB, long start, AtomicInteger successCount, AtomicInteger failCount,
            PlayerTransactionGuard guard, Throwable failure) {
        if (failure != null) {
            plugin.getLogger().warning("Concurrent pay test worker failed");
            finishTestMode(replyContext, guard);
            replyContext.sendMessage(plugin, plugin.getMessage("test.concurrent-pay.start-failed"));
            return;
        }

        try {
            BigDecimal initialTotal = initialAmount.multiply(BigDecimal.valueOf(2));
            BigDecimal finalA = economyFacade.getBalance(userA);
            BigDecimal finalB = economyFacade.getBalance(userB);
            BigDecimal finalTotal = finalA.add(finalB);
            long elapsedMillis = System.currentTimeMillis() - start;
            boolean zeroLoss = finalTotal.compareTo(initialTotal) == 0;

            resetTestMode(guard);
            List<String> output = new ArrayList<>(12);
            output.add(plugin.getMessage("test.concurrent-pay.result.header"));
            output.add(plugin.getMessage("test.concurrent-pay.result.time")
                    .replace("{time}", String.valueOf(elapsedMillis)));
            output.add(plugin.getMessage("test.concurrent-pay.result.success")
                    .replace("{success}", String.valueOf(successCount.get()))
                    .replace("{fail}", String.valueOf(failCount.get())));
            output.add(plugin.getMessage("test.concurrent-pay.result.balance-header"));
            output.add(plugin.getMessage("test.concurrent-pay.result.account-a")
                    .replace("{balance_a}", FormatUtil.formatCurrency(finalA)));
            output.add(plugin.getMessage("test.concurrent-pay.result.account-b")
                    .replace("{balance_b}", FormatUtil.formatCurrency(finalB)));
            output.add(plugin.getMessage("test.concurrent-pay.result.total-header"));
            output.add(plugin.getMessage("test.concurrent-pay.result.initial-total")
                    .replace("{initial_total}", FormatUtil.formatCurrency(initialTotal)));
            output.add(plugin.getMessage("test.concurrent-pay.result.final-total")
                    .replace("{final_total}", FormatUtil.formatCurrency(finalTotal)));
            output.add(zeroLoss ? plugin.getMessage("test.concurrent-pay.result.zero-loss-pass")
                    : plugin.getMessage("test.concurrent-pay.result.zero-loss-fail")
                            .replace("{difference}", FormatUtil.formatCurrency(finalTotal.subtract(initialTotal))));
            if (guard != null) output.add(plugin.getMessage("test.mode-disabled"));
            replyContext.sendMessages(plugin, output);
        } catch (RuntimeException failureDuringResult) {
            finishTestMode(replyContext, guard);
            plugin.getLogger().warning("Concurrent pay test result processing failed");
            replyContext.sendMessage(plugin, plugin.getMessage("test.concurrent-pay.start-failed"));
        }
    }

    private void finishTestMode(MessageHelper.SenderContext replyContext,
            PlayerTransactionGuard guard) {
        if (guard != null) {
            resetTestMode(guard);
            replyContext.sendMessage(plugin, plugin.getMessage("test.mode-disabled"));
        }
    }

    private static void resetTestMode(PlayerTransactionGuard guard) {
        if (guard != null) guard.setTestModeBypass(false);
    }

    /**
     * Check current system's Total Supply.
     */
    private void handleTotalSupplyCheck(CommandSender sender) {
        if (redisManager == null || redisManager.isDegraded()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.total-supply.unavailable"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("test.total-supply.checking"));
        MessageHelper.SenderContext replyContext = MessageHelper.captureSender(sender);

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            BigDecimal total = redisManager.getTotalBalance();
            int cachedPlayers = economyFacade.getCachedPlayerCount();
            replyContext.sendMessages(plugin, List.of(
                    plugin.getMessage("test.total-supply.result.header"),
                    plugin.getMessage("test.total-supply.result.total")
                            .replace("{amount}", FormatUtil.formatCurrency(total)),
                    plugin.getMessage("test.total-supply.result.cached-players")
                            .replace("{count}", String.valueOf(cachedPlayers))));
        });
    }

    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("test.usage.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.usage.concurrent-pay"));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.usage.total-supply"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.test")) {
            return Collections.emptyList();
        }


        if (plugin.getSyncmoneyConfig().getEconomyMode() == EconomyMode.LOCAL) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("concurrent-pay", "total-supply")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("concurrent-pay")) {
            return Arrays.asList("1", "5", "10", "20", "50")
                    .stream()
                    .filter(s -> s.startsWith(args[1]))
                    .toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("concurrent-pay")) {
            return Arrays.asList("100", "500", "1000", "5000", "10000")
                    .stream()
                    .filter(s -> s.startsWith(args[2]))
                    .toList();
        }

        return Collections.emptyList();
    }
}
