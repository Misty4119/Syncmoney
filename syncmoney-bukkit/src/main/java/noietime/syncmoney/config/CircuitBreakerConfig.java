package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Circuit breaker configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class CircuitBreakerConfig {

    private final FileConfiguration config;

    public CircuitBreakerConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-061] Whether to enable economic circuit breaker mechanism.
     */
    public boolean isCircuitBreakerEnabled() {
        return config.getBoolean("circuit-breaker.enabled", true);
    }

    /**
     * [SYNC-CONFIG-062] Gets single transaction limit (blocks transactions exceeding this amount).
     */
    public long getCircuitBreakerMaxSingleTransaction() {
        return config.getLong("circuit-breaker.max-single-transaction", 100_000_000L);
    }

    /**
     * [SYNC-CONFIG-063] Gets transaction frequency limit (maximum transactions per second).
     */
    public int getCircuitBreakerMaxTransactionsPerSecond() {
        return config.getInt("circuit-breaker.max-transactions-per-second", 5);
    }

    /**
     * [SYNC-CONFIG-064] Gets rapid inflation threshold (total increase percentage within 5 minutes).
     */
    public double getCircuitBreakerRapidInflationThreshold() {
        return config.getDouble("circuit-breaker.rapid-inflation-threshold", 0.2);
    }

    /**
     * [SYNC-CONFIG-065] Gets rapid inflation check interval (minutes).
     */
    public int getCircuitBreakerInflationCheckIntervalMinutes() {
        return config.getInt("circuit-breaker.inflation-check-interval-minutes", 5);
    }

    /**
     * [SYNC-CONFIG-066] Gets abnormal large change detection threshold (balance change multiplier).
     */
    public double getCircuitBreakerSuddenChangeThreshold() {
        return config.getDouble("circuit-breaker.sudden-change-threshold", 100.0);
    }

    /**
     * [SYNC-CONFIG-067] Gets time before entering LOCKED mode after Redis disconnection (seconds).
     */
    public int getCircuitBreakerRedisDisconnectLockSeconds() {
        return config.getInt("circuit-breaker.redis-disconnect-lock-seconds", 5);
    }

    /**
     * [SYNC-CONFIG-068] Gets memory usage warning threshold (percentage).
     */
    public int getCircuitBreakerMemoryWarningThreshold() {
        return config.getInt("circuit-breaker.memory-warning-threshold", 80);
    }

    /**
     * [SYNC-CONFIG-069] Gets connection pool exhaustion warning threshold (remaining connections).
     */
    public int getCircuitBreakerPoolExhaustedWarning() {
        return config.getInt("circuit-breaker.pool-exhausted-warning", 2);
    }
}
