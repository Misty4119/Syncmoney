package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EconomicCircuitBreaker - Multi-layer protection mechanism for economic transactions.
 *
 * Protection layers:
 * - Single transaction limit check
 * - Transaction rate limit (requests per second)
 * - Periodic total balance inflation check
 * - Sudden large change detection
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class EconomicCircuitBreaker {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    private final ConnectionStateManager connectionStateManager;
    private final ResourceMonitor resourceMonitor;
    private final HighValueNotification notification;


    private volatile CircuitState state = CircuitState.NORMAL;


    private final ConcurrentMap<Long, AtomicInteger> transactionsPerSecond;


    private volatile BigDecimal lastTotalBalance = BigDecimal.ZERO;


    private volatile long lastInflationCheckTime = 0;


    private final ConcurrentMap<UUID, BalanceChangeRecord> balanceChangeRecords;


    private record BalanceChangeRecord(BigDecimal previousBalance, long timestamp) {}

    public EconomicCircuitBreaker(Plugin plugin, SyncmoneyConfig config,
                                  EconomyFacade economyFacade, RedisManager redisManager,
                                  boolean redisRequired) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
        this.transactionsPerSecond = new ConcurrentHashMap<>();
        this.balanceChangeRecords = new ConcurrentHashMap<>();

        // Only create ConnectionStateManager if Redis is required (non-LOCAL mode)
        if (redisRequired) {
            this.connectionStateManager = new ConnectionStateManager(plugin, config, redisManager, redisRequired);
        } else {
            this.connectionStateManager = null;
        }
        this.resourceMonitor = new ResourceMonitor(plugin, config, redisManager);
        this.notification = new HighValueNotification((Syncmoney) plugin, config);
    }

    /**
     * [SYNC-SEC-001] Validate transaction against multiple protection rules.
     * This is the main entry point for transaction validation.
     *
     * @param uuid Player UUID
     * @param amount Transaction amount (positive = deposit, negative = withdraw)
     * @param source Event source
     * @return CheckResult indicating if transaction is allowed
     */
    public CheckResult checkTransaction(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        if (source == EconomyEvent.EventSource.TEST) {
            return CheckResult.ALLOWED;
        }

        if (!config.isCircuitBreakerEnabled()) {
            return CheckResult.ALLOWED;
        }

        if (state == CircuitState.LOCKED) {
            return new CheckResult(false, "System locked, rejecting all transactions");
        }

        BigDecimal absAmount = amount.abs();
        if (absAmount.compareTo(BigDecimal.valueOf(config.getCircuitBreakerMaxSingleTransaction())) > 0) {
            plugin.getLogger().warning("CircuitBreaker: Single transaction limit exceeded. Amount: " + amount);
            return new CheckResult(false, "Single transaction amount exceeds limit");
        }

        long currentSecond = System.currentTimeMillis() / 1000;
        AtomicInteger counter = transactionsPerSecond.computeIfAbsent(currentSecond, k -> new AtomicInteger(0));
        int currentCount = counter.incrementAndGet();

        if (currentCount > config.getCircuitBreakerMaxTransactionsPerSecond()) {
            plugin.getLogger().warning("CircuitBreaker: Transaction rate limit exceeded. Count: " + currentCount);
            return new CheckResult(false, "Transaction frequency too high, please try again later");
        }

        cleanupOldTransactionCounters(currentSecond);

        if (absAmount.compareTo(BigDecimal.ZERO) > 0) {
            BalanceChangeRecord record = balanceChangeRecords.get(uuid);
            if (record != null) {
                BigDecimal previousBalance = record.previousBalance();
                if (previousBalance.compareTo(BigDecimal.ZERO) > 0) {
                    double ratio = absAmount.divide(previousBalance, 4, RoundingMode.HALF_UP).doubleValue();
                    if (ratio > config.getCircuitBreakerSuddenChangeThreshold()) {
                        plugin.getLogger().severe("CircuitBreaker: Sudden change detected for " + uuid +
                                ". Ratio: " + ratio + ", Amount: " + amount);
                        notification.notifySuddenChange(uuid, ratio, amount);
                        return new CheckResult(false, "Abnormal balance change detected, account has been locked");
                    }
                }
            }
            balanceChangeRecords.put(uuid, new BalanceChangeRecord(absAmount, System.currentTimeMillis()));
        }

        return CheckResult.ALLOWED;
    }

    /**
     * Records transaction completion (for statistics).
     */
    public void onTransactionComplete(UUID uuid, BigDecimal oldBalance, BigDecimal newBalance) {
        balanceChangeRecords.put(uuid, new BalanceChangeRecord(newBalance, System.currentTimeMillis()));
    }

    /**
     * Performs periodic check (total inflation check).
     * Should be called by scheduled task.
     */
    public void performPeriodicCheck() {
        if (!config.isCircuitBreakerEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMs = config.getCircuitBreakerInflationCheckIntervalMinutes() * 60 * 1000L;

        if (now - lastInflationCheckTime < intervalMs) {
            return;
        }

        lastInflationCheckTime = now;

        BigDecimal currentTotal = calculateTotalBalance();
        if (currentTotal == null || currentTotal.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        if (lastTotalBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal increase = currentTotal.subtract(lastTotalBalance);
            BigDecimal increaseRatio = increase.divide(lastTotalBalance, 4, RoundingMode.HALF_UP);

            if (increaseRatio.doubleValue() > config.getCircuitBreakerRapidInflationThreshold()) {
                plugin.getLogger().severe("CircuitBreaker: Rapid inflation detected! " +
                        "Previous: " + lastTotalBalance + ", Current: " + currentTotal +
                        ", Ratio: " + increaseRatio);
                triggerLockdown("Total increased by " + (increaseRatio.doubleValue() * 100) + "% in " +
                        config.getCircuitBreakerInflationCheckIntervalMinutes() + " minutes");
                notification.notifyRapidInflation(lastTotalBalance, currentTotal, increaseRatio);
            }
        }

        lastTotalBalance = currentTotal;
    }

    /**
     * Calculates total balance.
     */
    private BigDecimal calculateTotalBalance() {
        try {
            return redisManager.getTotalBalance();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate total balance: " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Cleans up expired counters.
     */
    private void cleanupOldTransactionCounters(long currentSecond) {
        for (Long second : transactionsPerSecond.keySet()) {
            if (second < currentSecond - 10) {
                transactionsPerSecond.remove(second);
            }
        }
    }

    /**
     * Triggers circuit breaker lockdown.
     */
    public void triggerLockdown(String reason) {
        state = CircuitState.LOCKED;
        plugin.getLogger().severe("CircuitBreaker: LOCKDOWN triggered. Reason: " + reason);
        notification.notifyLockdown(reason);
    }

    /**
     * Resets circuit breaker.
     */
    public void reset() {
        state = CircuitState.NORMAL;
        transactionsPerSecond.clear();
        balanceChangeRecords.clear();
        plugin.getLogger().info("CircuitBreaker: Reset to NORMAL state");
    }

    /**
     * Gets current state.
     */
    public CircuitState getState() {
        return state;
    }

    /**
     * Gets connection state manager.
     */
    public ConnectionStateManager getConnectionStateManager() {
        return connectionStateManager;
    }

    /**
     * Gets resource monitor.
     */
    public ResourceMonitor getResourceMonitor() {
        return resourceMonitor;
    }

    /**
     * Circuit breaker state.
     */
    public enum CircuitState {
        NORMAL,
        WARNING,
        LOCKED
    }

    /**
     * Transaction check result.
     */
    public record CheckResult(boolean allowed, String reason) {
        public static final CheckResult ALLOWED = new CheckResult(true, null);
    }
}
