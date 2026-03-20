package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.web.websocket.SseManager;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

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
    private volatile SseManager sseManager;

    private volatile CircuitState state = CircuitState.NORMAL;
    private volatile String lockReason = null;

    private final ConcurrentMap<Long, AtomicInteger> transactionsPerSecond;

    private volatile BigDecimal lastTotalBalance = BigDecimal.ZERO;

    private volatile long lastInflationCheckTime = 0;

    private final ConcurrentMap<UUID, BalanceChangeRecord> balanceChangeRecords;

    private final ScheduledExecutorService cleanupScheduler;

    private static final Logger logger = Logger.getLogger(EconomicCircuitBreaker.class.getName());

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

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "syncmoney-breaker-cleanup");
            t.setDaemon(true);
            return t;
        });

        startCleanupTask();
        startPeriodicInflationCheck();

        if (redisRequired) {
            this.connectionStateManager = new ConnectionStateManager(plugin, config, redisManager, redisRequired, this);
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
            plugin.getLogger().warning("CircuitBreaker: Transaction rate limit exceeded, blocking transaction to prevent desync (Count: " + currentCount + ", Source: " + source + ")");
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
     * Starts the cleanup task for balance change records.
     * [MEM-02 FIX] Runs every 60 minutes to remove records older than 60 minutes.
     */
    private void startCleanupTask() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredBalanceRecords();
            } catch (Exception e) {
                logger.warning("Error cleaning up balance change records: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.MINUTES);
    }

    /**
     * Starts the periodic inflation check task.
     * Runs at the interval configured in config (inflation-check-interval-minutes).
     */
    private void startPeriodicInflationCheck() {
        long intervalMinutes = config.getCircuitBreakerInflationCheckIntervalMinutes();
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                performPeriodicCheck();
            } catch (Exception e) {
                logger.warning("Error performing periodic inflation check: " + e.getMessage());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        logger.info("CircuitBreaker: Periodic inflation check scheduled every " + intervalMinutes + " minutes");
    }

    /**
     * Removes balance change records older than 1 hour.
     * [MEM-02 FIX] Changed from 30 minutes to 1 hour per proposal requirements.
     */
    private void cleanupExpiredBalanceRecords() {
        long expirationTime = System.currentTimeMillis() - (60 * 60 * 1000);
        int initialSize = balanceChangeRecords.size();

        balanceChangeRecords.entrySet().removeIf(entry ->
            entry.getValue().timestamp() < expirationTime
        );

        int removed = initialSize - balanceChangeRecords.size();
        if (removed > 0) {
            logger.fine("Cleaned up " + removed + " expired balance change records");
        }
    }

    /**
     * Shuts down the cleanup scheduler.
     */
    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Triggers circuit breaker lockdown.
     */
    public void triggerLockdown(String reason) {
        state = CircuitState.LOCKED;
        lockReason = reason;
        plugin.getLogger().severe("CircuitBreaker: LOCKDOWN triggered. Reason: " + reason);
        notification.notifyLockdown(reason);
        broadcastCircuitBreakerEvent("LOCKDOWN", reason);
    }

    /**
     * Broadcast circuit breaker state change to SSE clients.
     */
    private void broadcastCircuitBreakerEvent(String state, String reason) {
        if (sseManager != null && sseManager.isEnabled()) {
            try {
                String json = String.format(
                    "{\"state\":\"%s\",\"reason\":\"%s\",\"timestamp\":%d}",
                    state, reason.replace("\"", "'"), System.currentTimeMillis()
                );
                sseManager.broadcastToChannel(SseManager.CHANNEL_BREAKER, json);
            } catch (Exception e) {
                logger.warning("Failed to broadcast circuit breaker event: " + e.getMessage());
            }
        }
    }

    /**
     * Set SSE manager for broadcasting events to web clients.
     */
    public void setSseManager(SseManager sseManager) {
        this.sseManager = sseManager;
    }

    /**
     * Set Discord webhook notifier for high value notifications.
     */
    public void setDiscordWebhookNotifier(DiscordWebhookNotifier discordNotifier) {
        if (notification != null) {
            notification.setDiscordWebhookNotifier(discordNotifier);
        }
    }

    /**
     * Get the high value notification service.
     */
    public HighValueNotification getNotification() {
        return notification;
    }

    /**
     * Resets circuit breaker.
     */
    public void reset() {
        String previousState = state.name();
        state = CircuitState.NORMAL;
        lockReason = null;
        transactionsPerSecond.clear();
        balanceChangeRecords.clear();
        logger.fine("CircuitBreaker: Reset to NORMAL state");
        broadcastCircuitBreakerEvent("NORMAL", "Manual reset");
    }

    /**
     * Checks if circuit breaker is in LOCKED state.
     */
    public boolean isLocked() {
        return state == CircuitState.LOCKED;
    }

    /**
     * Gets the reason for LOCKED state.
     */
    public String getLockReason() {
        return lockReason;
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
