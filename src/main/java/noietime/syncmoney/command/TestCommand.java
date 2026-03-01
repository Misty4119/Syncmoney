package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

        BigDecimal initialAmount = BigDecimal.valueOf(1000000);
        economyFacade.setBalance(userA, initialAmount, EconomyEvent.EventSource.TEST);
        economyFacade.setBalance(userB, initialAmount, EconomyEvent.EventSource.TEST);

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        BigDecimal initialTotal = initialAmount.multiply(BigDecimal.valueOf(2));
        BigDecimal expectedTotal = initialTotal;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);

        long start = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                for (int j = 0; j < iterations; j++) {
                    // A pays 1 to B - TEST mode does not trigger real economic events
                    try {
                        BigDecimal withdrawResult = economyFacade.withdraw(userA, BigDecimal.ONE,
                                EconomyEvent.EventSource.TEST);
                        if (withdrawResult.compareTo(BigDecimal.ZERO) >= 0) {
                            BigDecimal depositResult = economyFacade.deposit(userB, BigDecimal.ONE,
                                    EconomyEvent.EventSource.TEST);
                            if (depositResult.compareTo(BigDecimal.ZERO) >= 0) {
                                successCount.incrementAndGet();
                            } else {
                                economyFacade.deposit(userA, BigDecimal.ONE, EconomyEvent.EventSource.TEST);
                                failCount.incrementAndGet();
                            }
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
                latch.countDown();
            });
        }

        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        BigDecimal finalA = economyFacade.getBalance(userA);
        BigDecimal finalB = economyFacade.getBalance(userB);
        BigDecimal finalTotal = finalA.add(finalB);

        long end = System.currentTimeMillis();
        boolean isZeroLoss = finalTotal.compareTo(expectedTotal) == 0;

        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.time")
                .replace("{time}", String.valueOf(end - start)));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.success")
                .replace("{success}", String.valueOf(successCount.get()))
                .replace("{fail}", String.valueOf(failCount.get())));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.balance-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.account-a")
                .replace("{balance_a}", FormatUtil.formatCurrency(finalA)));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.account-b")
                .replace("{balance_b}", FormatUtil.formatCurrency(finalB)));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.total-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.initial-total")
                .replace("{initial_total}", FormatUtil.formatCurrency(initialTotal)));
        MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.final-total")
                .replace("{final_total}", FormatUtil.formatCurrency(finalTotal)));

        if (isZeroLoss) {
            MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.zero-loss-pass"));
        } else {
            BigDecimal difference = finalTotal.subtract(expectedTotal);
            MessageHelper.sendMessage(sender, plugin.getMessage("test.concurrent-pay.result.zero-loss-fail")
                    .replace("{difference}", FormatUtil.formatCurrency(difference)));
        }
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

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            BigDecimal total = redisManager.getTotalBalance();

            MessageHelper.sendMessage(sender, plugin.getMessage("test.total-supply.result.header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("test.total-supply.result.total")
                    .replace("{amount}", FormatUtil.formatCurrency(total)));

            int cachedPlayers = economyFacade.getCachedPlayerCount();
            MessageHelper.sendMessage(sender, plugin.getMessage("test.total-supply.result.cached-players")
                    .replace("{count}", String.valueOf(cachedPlayers)));
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
