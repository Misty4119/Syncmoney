package noietime.syncmoney.breaker;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import noietime.syncmoney.breaker.EconomicCircuitBreaker;

/**
 * Connection state manager.
 * Monitors Redis connection status, enters LOCKED mode after disconnection.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class ConnectionStateManager {

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final ScheduledExecutorService scheduler;
    private final EconomicCircuitBreaker circuitBreaker;

    private volatile long lastSuccessfulConnection = System.currentTimeMillis();

    private volatile long lastCheckTime = System.currentTimeMillis();

    private volatile boolean redisAvailable = true;

    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    private ConnectionStateCallback callback;
    private final boolean redisRequired;

    public ConnectionStateManager(Plugin plugin, SyncmoneyConfig config, RedisManager redisManager, boolean redisRequired, EconomicCircuitBreaker circuitBreaker) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.redisRequired = redisRequired;
        this.circuitBreaker = circuitBreaker;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Syncmoney-ConnectionStateManager");
            t.setDaemon(true);
            return t;
        });

        if (!redisRequired) {
            plugin.getLogger().fine("ConnectionStateManager: Skipping Redis monitoring in LOCAL mode");
            return;
        }

        startMonitoring();
    }

    /**
     * Start monitoring task.
     */
    private void startMonitoring() {
        scheduler.scheduleAtFixedRate(this::checkConnection, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Trigger circuit breaker lockdown.
     */
    private void triggerCircuitBreakerLockdown(String reason) {
        if (circuitBreaker != null) {
            circuitBreaker.triggerLockdown(reason);
        }
    }

    /**
     * Check Redis connection status.
     */
    private void checkConnection() {
        try {
            boolean isConnected = redisManager.isConnected();

            if (isConnected) {
                lastSuccessfulConnection = System.currentTimeMillis();
                consecutiveFailures.set(0);

                if (!redisAvailable) {
                    redisAvailable = true;
                    if (redisRequired) {
                        plugin.getLogger().fine("ConnectionStateManager: Redis connection restored");
                    }
                    if (redisManager.tryReconnect()) {
                        if (redisRequired) {
                            plugin.getLogger().fine("ConnectionStateManager: Successfully reconnected to Redis");
                        }
                        if (circuitBreaker != null && circuitBreaker.isLocked()
                                && circuitBreaker.getLockReason() != null
                                && circuitBreaker.getLockReason().contains("Redis")) {
                            circuitBreaker.reset();
                            plugin.getLogger().info("CircuitBreaker: Auto-reset after Redis reconnection");
                        }
                    }
                    if (callback != null) {
                        callback.onConnectionRestored();
                    }
                }
            } else {
                long failureCount = consecutiveFailures.incrementAndGet();
                long disconnectDuration = (System.currentTimeMillis() - lastSuccessfulConnection) / 1000;

                    if (disconnectDuration >= config.circuitBreaker().getCircuitBreakerRedisDisconnectLockSeconds()) {
                        if (redisAvailable) {
                            redisAvailable = false;
                            if (redisRequired) {
                                plugin.getLogger().severe("ConnectionStateManager: Redis disconnected for " +
                                        disconnectDuration + "s, triggering LOCKDOWN");
                            }
                            if (callback != null) {
                                callback.onConnectionLost(disconnectDuration);
                            }
                            triggerCircuitBreakerLockdown("Redis disconnected for " + disconnectDuration + "s");
                        }
                    }

                if (failureCount % 10 == 0) {
                    if (redisRequired) {
                        plugin.getLogger().warning("ConnectionStateManager: Redis connection check failed. " +
                                "Failures: " + failureCount + ", Duration: " + disconnectDuration + "s");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("ConnectionStateManager: Error checking connection: " + e.getMessage());
        }

        lastCheckTime = System.currentTimeMillis();
    }

    /**
     * Record a successful operation.
     */
    public void recordSuccess() {
        lastSuccessfulConnection = System.currentTimeMillis();
        consecutiveFailures.set(0);
    }

    /**
     * Record a failed operation.
     */
    public void recordFailure() {
        consecutiveFailures.incrementAndGet();
    }

    /**
     * Check if Redis is available.
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    /**
     * Get last successful connection time.
     */
    public long getLastSuccessfulConnection() {
        return lastSuccessfulConnection;
    }

    /**
     * Get last check time.
     */
    public long getLastCheckTime() {
        return lastCheckTime;
    }

    /**
     * Get consecutive failure count.
     */
    public long getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Set connection state callback.
     */
    public void setCallback(ConnectionStateCallback callback) {
        this.callback = callback;
    }

    /**
     * Shutdown monitoring service.
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
     * Connection state callback interface.
     */
    public interface ConnectionStateCallback {
        void onConnectionRestored();
        void onConnectionLost(long disconnectDuration);
        default void onTriggerLockdown(String reason) {
        }
    }
}
