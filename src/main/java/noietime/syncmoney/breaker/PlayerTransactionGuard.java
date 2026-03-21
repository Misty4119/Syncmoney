package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.FormatUtil;
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
 * PlayerTransactionGuard - Per-player protection system.
 *
 * Protection layers:
 * - L1: Rate Limiting (per second/minute transaction limits)
 * - L2: Anomaly Detection (WARNING state for suspicious behavior)
 * - L3: Player Lock + Auto Unlock (LOCKED state with automatic recovery)
 * - L4: Global Lock (locks entire server economy when inflation threshold exceeded)
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class PlayerTransactionGuard {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final NotificationService notificationService;
    private final RedisManager redisManager;

    private final ConcurrentMap<UUID, PlayerProtectionState> playerStates;

    private final ScheduledExecutorService scheduler;

    private volatile boolean testModeBypass = false;

    private volatile boolean globalLocked = false;
    private volatile String globalLockReason = null;
    private volatile long globalLockTime = 0;

    private volatile BigDecimal lastKnownTotalSupply = null;
    private volatile long lastSupplyCheckTime = 0;
    private static final long SUPPLY_CHECK_INTERVAL_MS = 60000;

    private static final Logger logger = Logger.getLogger(PlayerTransactionGuard.class.getName());

    public PlayerTransactionGuard(Plugin plugin, SyncmoneyConfig config,
                                 NotificationService notificationService, RedisManager redisManager) {
        this.plugin = plugin;
        this.config = config;
        this.notificationService = notificationService;
        this.redisManager = redisManager;
        this.playerStates = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Syncmoney-PlayerTransactionGuard");
            t.setDaemon(true);
            return t;
        });

        startAutoUnlockTask();
        startCleanupTask();
        if (config.playerProtection().isPlayerProtectionGlobalLockEnabled()) {
            startGlobalInflationMonitor();
        }
    }

    /**
     * Check transaction against protection rules.
     * This is the main entry point for per-player transaction validation.
     *
     * @param uuid Player UUID
     * @param currentBalance Current player balance (before transaction)
     * @param amount Transaction amount
     * @param eventType Event type (DEPOSIT/WITHDRAW)
     * @return CheckResult indicating if transaction is allowed
     */
    public CheckResult checkTransaction(UUID uuid, BigDecimal currentBalance, BigDecimal amount, EconomyEvent.EventType eventType) {
        return checkTransaction(uuid, currentBalance, amount, eventType, null);
    }

    /**
     * Check transaction against protection rules with event source awareness.
     * System operations (ADMIN_GIVE, ADMIN_TAKE, etc.) bypass rate limiting.
     *
     * @param uuid Player UUID
     * @param currentBalance Current player balance (before transaction)
     * @param amount Transaction amount
     * @param eventType Event type (DEPOSIT/WITHDRAW)
     * @param source Event source (for system operation bypass)
     * @return CheckResult indicating if transaction is allowed
     */
    public CheckResult checkTransaction(UUID uuid, BigDecimal currentBalance, BigDecimal amount,
            EconomyEvent.EventType eventType, EconomyEvent.EventSource source) {

        if (testModeBypass) {
            return CheckResult.ALLOWED;
        }

        if (globalLocked) {
            return new CheckResult(false, "Global economy locked: " + globalLockReason, ProtectionState.GLOBAL_LOCKED);
        }

        if (!config.playerProtection().isPlayerProtectionEnabled()) {
            return CheckResult.ALLOWED;
        }

        boolean systemOp = isSystemOperation(source);

        PlayerProtectionState state = playerStates.computeIfAbsent(uuid, k -> new PlayerProtectionState());

        if (eventType == EconomyEvent.EventType.WITHDRAW && state.isTransferLocked()) {
            return new CheckResult(false, "Transfer temporarily locked: " + state.getTransferLockReason(), ProtectionState.LOCKED);
        }

        if (state.getState() == ProtectionState.LOCKED) {
            if (shouldAutoUnlock(state)) {
                if (tryAutoUnlock(state, uuid)) {
                    plugin.getLogger().fine("PlayerTransactionGuard: Auto-unlocked player " + uuid);
                } else {
                    return new CheckResult(false, "Account temporarily locked", ProtectionState.LOCKED);
                }
            } else {
                return new CheckResult(false, "Account temporarily locked", ProtectionState.LOCKED);
            }
        }

        if (!systemOp) {
            if (!state.incrementTransactionsPerSecond()) {
                notificationService.sendRateLimitNotification(uuid, "per-second");
                return new CheckResult(false, "Transaction too fast, please wait", ProtectionState.NORMAL);
            }

            if (!state.incrementTransactionsPerMinute(amount)) {
                notificationService.sendRateLimitNotification(uuid, "per-minute");
                return new CheckResult(false, "Transaction limit reached", ProtectionState.NORMAL);
            }

            if (config.playerProtection().getPlayerProtectionMaxAmountPerMinute() > 0) {
                if (state.getAmountPerMinute().add(amount).compareTo(BigDecimal.valueOf(config.playerProtection().getPlayerProtectionMaxAmountPerMinute())) > 0) {
                    notificationService.sendRateLimitNotification(uuid, "amount-limit");
                    return new CheckResult(false, "Transaction amount limit reached", ProtectionState.NORMAL);
                }
            }
        }

        int transactionCount = state.getTransactionCountInWindow();
        int warningThreshold = config.playerProtection().getPlayerProtectionWarningThreshold();

        if (!systemOp && transactionCount > warningThreshold && state.getState() == ProtectionState.NORMAL) {
            state.setState(ProtectionState.WARNING);
            notificationService.sendWarningNotification(uuid, transactionCount, warningThreshold, state.getAmountPerMinute());
            plugin.getLogger().warning("PlayerTransactionGuard: Player " + uuid + " entered WARNING state. Transactions: " + transactionCount);
        }

        if (!systemOp && state.getPreviousBalance() != null && state.getPreviousBalance().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = amount.abs().divide(state.getPreviousBalance(), 4, java.math.RoundingMode.HALF_UP).doubleValue();
            if (ratio > config.playerProtection().getPlayerProtectionBalanceChangeThreshold()) {
                state.setState(ProtectionState.LOCKED);
                state.setLockExtensionCount(state.getLockExtensionCount() + 1);
                state.setUnlockTime(System.currentTimeMillis() + (config.playerProtection().getPlayerProtectionLockDurationMinutes() * 60 * 1000L));
                notificationService.sendLockedNotification(uuid, "Abnormal balance change detected (ratio: " + FormatUtil.formatPercentRaw(ratio) + ")");
                plugin.getLogger().severe("PlayerTransactionGuard: Player " + uuid + " LOCKED due to abnormal balance change. Ratio: " + ratio);
                return new CheckResult(false, "Account locked due to abnormal behavior", ProtectionState.LOCKED);
            }
        }

        if (!systemOp) {
            state.updateBalance(currentBalance);
        }

        if (!systemOp && state.getState() == ProtectionState.WARNING) {
            int warningCount = state.incrementWarningCount();
            if (warningCount >= 3) {
                state.setState(ProtectionState.LOCKED);
                state.setLockExtensionCount(state.getLockExtensionCount() + 1);
                state.setUnlockTime(System.currentTimeMillis() + (config.playerProtection().getPlayerProtectionLockDurationMinutes() * 60 * 1000L));
                notificationService.sendLockedNotification(uuid, "Continued suspicious activity");
                plugin.getLogger().severe("PlayerTransactionGuard: Player " + uuid + " LOCKED due to continued warnings");
                return new CheckResult(false, "Account locked due to suspicious activity", ProtectionState.LOCKED);
            }
        }

        return CheckResult.ALLOWED;
    }

    /**
     * Check and apply transfer lock on receiver when receiving large transfers.
     * This is called after a successful deposit to lock the receiver temporarily.
     *
     * @param receiverUuid Receiver player UUID
     * @param amount Amount received
     */
    public void checkAndLockReceiverForTransfer(UUID receiverUuid, BigDecimal amount) {
        if (testModeBypass) {
            return;
        }

        if (!config.playerProtection().isPlayerProtectionEnabled()) {
            return;
        }

        if (!config.playerProtection().isPlayerProtectionLockReceiver()) {
            return;
        }

        long threshold = config.playerProtection().getPlayerProtectionReceiverLockThreshold();
        if (threshold <= 0) {
            return;
        }

        BigDecimal thresholdDecimal = BigDecimal.valueOf(threshold);
        if (amount.compareTo(thresholdDecimal) > 0) {
            PlayerProtectionState state = playerStates.computeIfAbsent(receiverUuid, k -> new PlayerProtectionState());
            int lockDurationSeconds = 30;
            String reason = "Large transfer received (" + FormatUtil.formatCurrency(amount) + ")";
            state.lockForTransfer(lockDurationSeconds, reason);
            plugin.getLogger().info("PlayerTransactionGuard: Player " + receiverUuid + " transfer-locked for " + lockDurationSeconds + "s due to receiving " + FormatUtil.formatCurrency(amount));
        }
    }

    /**
     * Check if player is currently transfer-locked.
     *
     * @param uuid Player UUID
     * @return true if player is transfer-locked
     */
    public boolean isTransferLocked(UUID uuid) {
        PlayerProtectionState state = playerStates.get(uuid);
        return state != null && state.isTransferLocked();
    }

    /**
     * Get transfer lock reason for a player.
     *
     * @param uuid Player UUID
     * @return Transfer lock reason or null if not locked
     */
    public String getTransferLockReason(UUID uuid) {
        PlayerProtectionState state = playerStates.get(uuid);
        return state != null ? state.getTransferLockReason() : null;
    }

    /**
     * Record successful transaction for unlock testing.
     */
    public void recordSuccessfulTransaction(UUID uuid) {
        PlayerProtectionState state = playerStates.get(uuid);
        if (state != null && state.getState() == ProtectionState.LOCKED) {
            int successCount = state.incrementSuccessfulTransactions();
            if (successCount >= config.playerProtection().getPlayerProtectionUnlockTestTransactions()) {
                state.setState(ProtectionState.NORMAL);
                state.setWarningCount(0);
                state.setSuccessfulTransactions(0);
                state.setUnlockTime(0);
                notificationService.sendUnlockedNotification(uuid, "auto");
                plugin.getLogger().fine("PlayerTransactionGuard: Player " + uuid + " unlocked after successful transactions");
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
            state.setPreviousBalance(null);
            state.setWindowStartTime(System.currentTimeMillis());
            state.setWindowTransactionCount(0);
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
     * Set test mode bypass.
     * When enabled, all rate limiting is disabled for stress testing.
     *
     * @param bypass true to enable bypass (disable rate limiting), false to disable
     */
    public void setTestModeBypass(boolean bypass) {
        this.testModeBypass = bypass;
        if (bypass) {
            plugin.getLogger().info("PlayerTransactionGuard: Test mode bypass ENABLED - rate limiting disabled");
        } else {
            plugin.getLogger().info("PlayerTransactionGuard: Test mode bypass DISABLED - rate limiting enabled");
        }
    }

    /**
     * Check if test mode bypass is enabled.
     */
    public boolean isTestModeBypass() {
        return testModeBypass;
    }

    /**
     * Check if should attempt auto-unlock.
     */
    private boolean shouldAutoUnlock(PlayerProtectionState state) {
        if (state.getUnlockTime() == 0) {
            return false;
        }

        if (System.currentTimeMillis() >= state.getUnlockTime()) {
            int maxExtensions = config.playerProtection().getPlayerProtectionMaxLockExtensions();
            if (maxExtensions > 0 && state.getLockExtensionCount() >= maxExtensions) {
                state.setUnlockTime(System.currentTimeMillis() + (config.playerProtection().getPlayerProtectionLockDurationMinutes() * 60 * 1000L));
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
        state.setState(ProtectionState.NORMAL);
        state.setWarningCount(0);
        state.setPreviousBalance(null);
        

        state.setWindowStartTime(System.currentTimeMillis());
        state.setWindowTransactionCount(0);
        
        plugin.getLogger().fine("PlayerTransactionGuard: Player " + uuid + " auto-unlocked successfully");
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
     * Start global inflation monitoring task.
     * Periodically checks total supply and triggers global lock if threshold exceeded.
     */
    private void startGlobalInflationMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performGlobalInflationCheck();
            } catch (Exception e) {
                logger.warning("Error during global inflation check: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);
        logger.info("PlayerTransactionGuard: Global inflation monitor started");
    }

    /**
     * Perform global inflation check.
     * Calculates total supply and checks growth rate against threshold.
     */
    private void performGlobalInflationCheck() {
        if (!config.playerProtection().isPlayerProtectionGlobalLockEnabled()) {
            return;
        }

        if (globalLocked) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSupplyCheckTime < SUPPLY_CHECK_INTERVAL_MS) {
            return;
        }

        BigDecimal currentTotal = calculateTotalSupply();
        if (currentTotal == null || currentTotal.compareTo(BigDecimal.ZERO) == 0) {
            lastSupplyCheckTime = now;
            return;
        }

        if (lastKnownTotalSupply != null && lastKnownTotalSupply.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal increase = currentTotal.subtract(lastKnownTotalSupply);
            BigDecimal increaseRatio = increase.abs().divide(lastKnownTotalSupply, 4, RoundingMode.HALF_UP);

            double threshold = config.playerProtection().getPlayerProtectionGlobalLockThreshold();
            if (increaseRatio.doubleValue() > threshold) {
                String reason = String.format("Global inflation detected: %.2f%% (threshold: %.2f%%)",
                        increaseRatio.doubleValue() * 100, threshold * 100);
                triggerGlobalLockdown(reason);
                return;
            }
        }

        lastKnownTotalSupply = currentTotal;
        lastSupplyCheckTime = now;
    }

    /**
     * Calculate total supply from Redis.
     */
    private BigDecimal calculateTotalSupply() {
        if (redisManager == null) {
            return BigDecimal.ZERO;
        }
        try {
            return redisManager.getTotalBalance();
        } catch (Exception e) {
            logger.warning("Failed to calculate total supply: " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Trigger global economy lockdown.
     * All non-admin transactions will be rejected.
     *
     * @param reason Reason for the lockdown
     */
    private void triggerGlobalLockdown(String reason) {
        globalLocked = true;
        globalLockReason = reason;
        globalLockTime = System.currentTimeMillis();

        logger.severe("PlayerTransactionGuard: GLOBAL LOCKDOWN triggered. Reason: " + reason);

        if (notificationService != null) {
            notificationService.sendGlobalLockNotification(reason);
        }
    }

    /**
     * Check if global lock is currently active.
     */
    public boolean isGlobalLocked() {
        return globalLocked;
    }

    /**
     * Get global lock reason.
     */
    public String getGlobalLockReason() {
        return globalLockReason;
    }

    /**
     * Get global lock timestamp.
     */
    public long getGlobalLockTime() {
        return globalLockTime;
    }

    /**
     * Reset global lockdown (admin action).
     */
    public void resetGlobalLockdown() {
        globalLocked = false;
        globalLockReason = null;
        globalLockTime = 0;
        lastKnownTotalSupply = null;
        lastSupplyCheckTime = 0;
        logger.info("PlayerTransactionGuard: Global lockdown reset by admin");
    }

    /**
     * Checks if the event source is a system operation that should bypass player protection.
     * System operations include admin give/take/set, command admin, and migration operations.
     */
    private boolean isSystemOperation(EconomyEvent.EventSource source) {
        if (source == null) {
            return false;
        }
        return source == EconomyEvent.EventSource.ADMIN_GIVE
                || source == EconomyEvent.EventSource.ADMIN_TAKE
                || source == EconomyEvent.EventSource.ADMIN_SET
                || source == EconomyEvent.EventSource.COMMAND_ADMIN
                || source == EconomyEvent.EventSource.MIGRATION
                || source == EconomyEvent.EventSource.SHADOW_SYNC;
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
        LOCKED,
        GLOBAL_LOCKED
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
        private volatile long transferLockUntil = 0;
        private volatile String transferLockReason = null;

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
            return ++windowTransactionCount;
        }

        public void updateBalance(BigDecimal currentBalance) {
            this.previousBalance = currentBalance;
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
        public void setPreviousBalance(BigDecimal balance) { this.previousBalance = balance; }

        public int getWindowTransactionCount() { return windowTransactionCount; }
        public void setWindowTransactionCount(int count) { this.windowTransactionCount = count; }

        public long getWindowStartTime() { return windowStartTime; }
        public void setWindowStartTime(long time) { this.windowStartTime = time; }

        public long getLastAccessTime() { return lastAccessTime; }

        public long getTransferLockUntil() { return transferLockUntil; }
        public void setTransferLockUntil(long time) { this.transferLockUntil = time; }

        public String getTransferLockReason() { return transferLockReason; }
        public void setTransferLockReason(String reason) { this.transferLockReason = reason; }

        public boolean isTransferLocked() {
            return transferLockUntil > System.currentTimeMillis();
        }

        public void lockForTransfer(int durationSeconds, String reason) {
            this.transferLockUntil = System.currentTimeMillis() + (durationSeconds * 1000L);
            this.transferLockReason = reason;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
}
