package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PlayerTransactionGuard - Per-player protection system.
 * 
 * Protection layers:
 * - L1: Rate Limiting (per second/minute transaction limits)
 * - L2: Anomaly Detection (WARNING state for suspicious behavior)
 * - L3: Player Lock + Auto Unlock (LOCKED state with automatic recovery)
 * 
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class PlayerTransactionGuard {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final NotificationService notificationService;

    private final ConcurrentMap<UUID, PlayerProtectionState> playerStates;

    private final ScheduledExecutorService scheduler;

    public PlayerTransactionGuard(Plugin plugin, SyncmoneyConfig config, NotificationService notificationService) {
        this.plugin = plugin;
        this.config = config;
        this.notificationService = notificationService;
        this.playerStates = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Syncmoney-PlayerTransactionGuard");
            t.setDaemon(true);
            return t;
        });

        startAutoUnlockTask();
        startCleanupTask();
    }

    /**
     * Check transaction against protection rules.
     * This is the main entry point for per-player transaction validation.
     * 
     * @param uuid Player UUID
     * @param amount Transaction amount
     * @param eventType Event type (DEPOSIT/WITHDRAW)
     * @return CheckResult indicating if transaction is allowed
     */
    public CheckResult checkTransaction(UUID uuid, BigDecimal amount, EconomyEvent.EventType eventType) {
        if (!config.isPlayerProtectionEnabled()) {
            return CheckResult.ALLOWED;
        }

        PlayerProtectionState state = playerStates.computeIfAbsent(uuid, k -> new PlayerProtectionState());

        if (state.getState() == ProtectionState.LOCKED) {
            if (shouldAutoUnlock(state)) {
                if (tryAutoUnlock(state, uuid)) {
                    plugin.getLogger().info("PlayerTransactionGuard: Auto-unlocked player " + uuid);
                } else {
                    return new CheckResult(false, "Account temporarily locked", ProtectionState.LOCKED);
                }
            } else {
                return new CheckResult(false, "Account temporarily locked", ProtectionState.LOCKED);
            }
        }

        if (!state.incrementTransactionsPerSecond()) {
            notificationService.sendRateLimitNotification(uuid, "per-second");
            return new CheckResult(false, "Transaction too fast, please wait", ProtectionState.NORMAL);
        }

        if (!state.incrementTransactionsPerMinute(amount)) {
            notificationService.sendRateLimitNotification(uuid, "per-minute");
            return new CheckResult(false, "Transaction limit reached", ProtectionState.NORMAL);
        }

        if (config.getPlayerProtectionMaxAmountPerMinute() > 0) {
            if (state.getAmountPerMinute().add(amount).compareTo(BigDecimal.valueOf(config.getPlayerProtectionMaxAmountPerMinute())) > 0) {
                notificationService.sendRateLimitNotification(uuid, "amount-limit");
                return new CheckResult(false, "Transaction amount limit reached", ProtectionState.NORMAL);
            }
        }

        int transactionCount = state.getTransactionCountInWindow();
        int warningThreshold = config.getPlayerProtectionWarningThreshold();

        if (transactionCount > warningThreshold && state.getState() == ProtectionState.NORMAL) {
            state.setState(ProtectionState.WARNING);
            notificationService.sendWarningNotification(uuid, transactionCount, warningThreshold, state.getAmountPerMinute());
            plugin.getLogger().warning("PlayerTransactionGuard: Player " + uuid + " entered WARNING state. Transactions: " + transactionCount);
        }

        if (state.getPreviousBalance() != null && state.getPreviousBalance().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = amount.abs().divide(state.getPreviousBalance(), 4, java.math.RoundingMode.HALF_UP).doubleValue();
            if (ratio > config.getPlayerProtectionBalanceChangeThreshold()) {
                state.setState(ProtectionState.LOCKED);
                state.setLockExtensionCount(state.getLockExtensionCount() + 1);
                state.setUnlockTime(System.currentTimeMillis() + (config.getPlayerProtectionLockDurationMinutes() * 60 * 1000L));
                notificationService.sendLockedNotification(uuid, "Abnormal balance change detected (ratio: " + FormatUtil.formatPercentRaw(ratio) + ")");
                plugin.getLogger().severe("PlayerTransactionGuard: Player " + uuid + " LOCKED due to abnormal balance change. Ratio: " + ratio);
                return new CheckResult(false, "Account locked due to abnormal behavior", ProtectionState.LOCKED);
            }
        }

        state.updateBalance(amount);

        if (state.getState() == ProtectionState.WARNING) {
            int warningCount = state.incrementWarningCount();
            if (warningCount >= 3) { // 3 warnings within window triggers lock
                state.setState(ProtectionState.LOCKED);
                state.setLockExtensionCount(state.getLockExtensionCount() + 1);
                state.setUnlockTime(System.currentTimeMillis() + (config.getPlayerProtectionLockDurationMinutes() * 60 * 1000L));
                notificationService.sendLockedNotification(uuid, "Continued suspicious activity");
                plugin.getLogger().severe("PlayerTransactionGuard: Player " + uuid + " LOCKED due to continued warnings");
                return new CheckResult(false, "Account locked due to suspicious activity", ProtectionState.LOCKED);
            }
        }

        return CheckResult.ALLOWED;
    }

    /**
     * Record successful transaction for unlock testing.
     */
    public void recordSuccessfulTransaction(UUID uuid) {
        PlayerProtectionState state = playerStates.get(uuid);
        if (state != null && state.getState() == ProtectionState.LOCKED) {
            int successCount = state.incrementSuccessfulTransactions();
            if (successCount >= config.getPlayerProtectionUnlockTestTransactions()) {
                state.setState(ProtectionState.NORMAL);
                state.setWarningCount(0);
                state.setSuccessfulTransactions(0);
                state.setUnlockTime(0);
                notificationService.sendUnlockedNotification(uuid, "auto");
                plugin.getLogger().info("PlayerTransactionGuard: Player " + uuid + " unlocked after successful transactions");
            }
        }
    }

    /**
     * Manually unlock a player.
     */
    public boolean unlockPlayer(UUID uuid) {
        PlayerProtectionState state = playerStates.get(uuid);
        if (state != null) {
            state.setState(ProtectionState.NORMAL);
            state.setWarningCount(0);
            state.setSuccessfulTransactions(0);
            state.setUnlockTime(0);
            state.setLockExtensionCount(0);
            notificationService.sendUnlockedNotification(uuid, "admin");
            return true;
        }
        return false;
    }

    /**
     * Get player protection state.
     */
    public ProtectionState getPlayerState(UUID uuid) {
        PlayerProtectionState state = playerStates.get(uuid);
        return state != null ? state.getState() : ProtectionState.NORMAL;
    }

    /**
     * Get player protection details.
     */
    public PlayerProtectionState getProtectionState(UUID uuid) {
        return playerStates.get(uuid);
    }

    /**
     * Check if should attempt auto-unlock.
     */
    private boolean shouldAutoUnlock(PlayerProtectionState state) {
        if (state.getUnlockTime() == 0) {
            return false;
        }

        if (System.currentTimeMillis() >= state.getUnlockTime()) {
            int maxExtensions = config.getPlayerProtectionMaxLockExtensions();
            if (maxExtensions > 0 && state.getLockExtensionCount() >= maxExtensions) {
                state.setUnlockTime(System.currentTimeMillis() + (config.getPlayerProtectionLockDurationMinutes() * 60 * 1000L));
                plugin.getLogger().warning("PlayerTransactionGuard: Player lock extended, max extensions reached");
                return false;
            }
            return true;
        }

        return false;
    }

    /**
     * Try to auto-unlock player.
     */
    private boolean tryAutoUnlock(PlayerProtectionState state, UUID uuid) {
        state.setSuccessfulTransactions(0);
        state.setUnlockTime(0);
        state.setState(ProtectionState.WARNING);
        plugin.getLogger().info("PlayerTransactionGuard: Player " + uuid + " entering test mode for auto-unlock");
        return true;
    }

    /**
     * Start auto-unlock checker task.
     */
    private void startAutoUnlockTask() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (var entry : playerStates.entrySet()) {
                PlayerProtectionState state = entry.getValue();
                if (state.getState() == ProtectionState.LOCKED && state.getUnlockTime() > 0 && now >= state.getUnlockTime()) {
                    if (shouldAutoUnlock(state)) {
                        tryAutoUnlock(state, entry.getKey());
                    }
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Start cleanup task for old entries.
     */
    private void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long expirationTime = now - (30 * 60 * 1000);

            playerStates.entrySet().removeIf(entry -> {
                PlayerProtectionState state = entry.getValue();
                return state.getLastAccessTime() < expirationTime && state.getState() == ProtectionState.NORMAL;
            });
        }, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Shutdown the guard.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Player protection state.
     */
    public enum ProtectionState {
        NORMAL,
        WARNING,
        LOCKED
    }

    /**
     * Transaction check result.
     */
    public record CheckResult(boolean allowed, String reason, ProtectionState state) {
        public static final CheckResult ALLOWED = new CheckResult(true, null, ProtectionState.NORMAL);
    }

    /**
     * Internal player protection state.
     */
    public static class PlayerProtectionState {
        private volatile ProtectionState state = ProtectionState.NORMAL;
        private volatile long lastTransactionSecond = 0;
        private volatile int transactionsPerSecond = 0;
        private volatile long lastTransactionMinute = 0;
        private volatile int transactionsPerMinute = 0;
        private volatile BigDecimal amountPerMinute = BigDecimal.ZERO;
        private volatile int windowTransactionCount = 0;
        private volatile long windowStartTime = 0;
        private volatile int warningCount = 0;
        private volatile int successfulTransactions = 0;
        private volatile long unlockTime = 0;
        private volatile int lockExtensionCount = 0;
        private volatile BigDecimal previousBalance = null;
        private volatile long lastAccessTime = System.currentTimeMillis();

        public synchronized boolean incrementTransactionsPerSecond() {
            long currentSecond = System.currentTimeMillis() / 1000;
            if (currentSecond != lastTransactionSecond) {
                lastTransactionSecond = currentSecond;
                transactionsPerSecond = 0;
            }
            transactionsPerSecond++;
            return transactionsPerSecond <= 5;
        }

        public synchronized boolean incrementTransactionsPerMinute(BigDecimal amount) {
            long currentMinute = System.currentTimeMillis() / 60000;
            if (currentMinute != lastTransactionMinute) {
                lastTransactionMinute = currentMinute;
                transactionsPerMinute = 0;
                amountPerMinute = BigDecimal.ZERO;
            }
            transactionsPerMinute++;
            amountPerMinute = amountPerMinute.add(amount);
            return transactionsPerMinute <= 50;
        }

        public int getTransactionCountInWindow() {
            long now = System.currentTimeMillis();
            if (now - windowStartTime > 30000) {
                windowStartTime = now;
                windowTransactionCount = 1;
                return 1;
            }
            return windowTransactionCount++;
        }

        public void updateBalance(BigDecimal delta) {
            this.previousBalance = delta;
            this.lastAccessTime = System.currentTimeMillis();
        }

        public ProtectionState getState() { return state; }
        public void setState(ProtectionState state) { this.state = state; this.lastAccessTime = System.currentTimeMillis(); }

        public int getTransactionsPerSecond() { return transactionsPerSecond; }
        public int getTransactionsPerMinute() { return transactionsPerMinute; }
        public BigDecimal getAmountPerMinute() { return amountPerMinute; }

        public int getWarningCount() { return warningCount; }
        public int incrementWarningCount() { return ++warningCount; }
        public void setWarningCount(int count) { this.warningCount = count; }

        public int getSuccessfulTransactions() { return successfulTransactions; }
        public int incrementSuccessfulTransactions() { return ++successfulTransactions; }
        public void setSuccessfulTransactions(int count) { this.successfulTransactions = count; }

        public long getUnlockTime() { return unlockTime; }
        public void setUnlockTime(long time) { this.unlockTime = time; }

        public int getLockExtensionCount() { return lockExtensionCount; }
        public void setLockExtensionCount(int count) { this.lockExtensionCount = count; }

        public BigDecimal getPreviousBalance() { return previousBalance; }

        public long getLastAccessTime() { return lastAccessTime; }
    }
}
