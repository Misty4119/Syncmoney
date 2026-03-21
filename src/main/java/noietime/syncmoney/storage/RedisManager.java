package noietime.syncmoney.storage;

import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.math.BigDecimal;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [SYNC-REDIS-001] Redis connection manager with connection pooling.
 * Implements connection pool, health checks, reconnection handling, and
 * degraded mode.
 *
 * [AsyncScheduler] All Redis operations should be called from async threads.
 */
public final class RedisManager implements AutoCloseable {

    private final Plugin plugin;
    private JedisPool jedisPool;
    private final JedisPoolConfig poolConfig;
    private volatile boolean degraded = false;
    private final boolean debug;
    private final boolean redisRequired;

    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final int redisDatabase;

    private final AtomicInteger connectionFailureCount = new AtomicInteger(0);
    private static final int MAX_FAILURES_BEFORE_WARNING = 5;

    /**
     * Debug-level log output.
     */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().fine(message);
        }
    }

    public RedisManager(Plugin plugin, SyncmoneyConfig config, boolean redisRequired) {
        this.plugin = plugin;
        this.debug = config.isDebug();
        this.redisRequired = redisRequired;

        if (!redisRequired) {
            this.redisHost = config.redis().getRedisHost();
            this.redisPort = config.redis().getRedisPort();
            this.redisPassword = config.redis().getRedisPassword();
            this.redisDatabase = config.redis().getRedisDatabase();
            this.poolConfig = new JedisPoolConfig();
            this.jedisPool = null;
            this.degraded = true;
            plugin.getLogger().fine("RedisManager initialized in LOCAL mode - Redis connection skipped.");
            return;
        }

        this.redisHost = config.redis().getRedisHost();
        this.redisPort = config.redis().getRedisPort();
        this.redisPassword = config.redis().getRedisPassword();
        this.redisDatabase = config.redis().getRedisDatabase();

        this.poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.redis().getRedisPoolSize());
        poolConfig.setMaxIdle(Math.min(10, config.redis().getRedisPoolSize()));
        poolConfig.setMinIdle(Math.min(5, config.redis().getRedisPoolSize() / 2));
        poolConfig.setMaxWaitMillis(3000);

        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTestOnCreate(true);

        int timeout = 5000;
        if (config.redis().getRedisPassword() != null && !config.redis().getRedisPassword().isEmpty()) {
            this.jedisPool = new JedisPool(
                    poolConfig,
                    config.redis().getRedisHost(),
                    config.redis().getRedisPort(),
                    timeout,
                    config.redis().getRedisPassword(),
                    config.redis().getRedisDatabase());
        } else {
            this.jedisPool = new JedisPool(
                    poolConfig,
                    config.redis().getRedisHost(),
                    config.redis().getRedisPort(),
                    timeout,
                    (String) null,
                    config.redis().getRedisDatabase());
        }

        if (!isConnected()) {
            this.degraded = true;
            plugin.getLogger().severe("Failed to connect to Redis on startup. Running in degraded mode.");
        } else {
            debug("Connected to Redis successfully.");
            warmUpPool();
        }
    }

    /**
     * Warms up connection pool.
     */
    private void warmUpPool() {
        try {
            int warmUpCount = Math.min(2, poolConfig.getMaxIdle());
            for (int i = 0; i < warmUpCount; i++) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.ping();
                }
            }
            plugin.getLogger().fine("Redis connection pool warmed up with " + warmUpCount + " connections.");
        } catch (Exception e) {
            debug("Failed to warm up Redis connection pool: " + e.getMessage());
        }
    }

    /**
     * Gets Jedis resource.
     * Must call {@link Jedis#close()} to return after use.
     */
    public Jedis getResource() {
        if (jedisPool == null) {
            throw new IllegalStateException("Redis pool is not initialized. Call connect() or ensure Redis is available.");
        }
        return jedisPool.getResource();
    }

    /**
     * Tests if Redis connection is healthy.
     */
    public boolean isConnected() {
        if (jedisPool == null) {
            return false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.ping();
            if ("PONG".equals(result)) {
                connectionFailureCount.set(0);
                return true;
            }
            return false;
        } catch (Exception e) {
            if (degraded) {
                return false;
            }
            int failures = connectionFailureCount.incrementAndGet();
            if (failures >= MAX_FAILURES_BEFORE_WARNING) {
                plugin.getLogger().warning("Redis connection issues detected (" + failures
                        + " failures). Consider checking Redis server.");
            }
            debug("Redis connection test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if in degraded mode.
     * Set to true when Redis connection fails.
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * Marks degraded status.
     * Called when connection interruption is detected.
     */
    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
        if (degraded) {
            if (redisRequired) {
                plugin.getLogger().warning("Redis degraded mode enabled.");
            } else {
                debug("Redis degraded mode enabled.");
            }
        } else {
            debug("Redis degraded mode disabled.");
        }
    }

    /**
     * Attempts to reconnect.
     * 
     * @return true if connection successful
     */
    public boolean tryReconnect() {
        if (jedisPool == null) {
            return false;
        }
        if (isConnected()) {
            setDegraded(false);
            plugin.getLogger().fine("Redis connection restored.");
            return true;
        }
        try {
            if (!jedisPool.isClosed()) {
                jedisPool.close();
            }
            if (redisPassword != null && !redisPassword.isEmpty()) {
                this.jedisPool = new JedisPool(
                        poolConfig,
                        redisHost,
                        redisPort,
                        5000,
                        redisPassword,
                        redisDatabase);
            } else {
                this.jedisPool = new JedisPool(
                        poolConfig,
                        redisHost,
                        redisPort,
                        5000,
                        (String) null,
                        redisDatabase);
            }
            return isConnected();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to reconnect to Redis: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets connection failure count.
     */
    public int getConnectionFailureCount() {
        return connectionFailureCount.get();
    }

    /**
     * Resets connection failure count.
     */
    public void resetConnectionFailureCount() {
        connectionFailureCount.set(0);
    }

    /**
     * Gets available connections in Redis connection pool.
     */
    public int getAvailableConnections() {
        if (jedisPool == null) {
            return 0;
        }
        try {
            return jedisPool.getNumIdle();
        } catch (Exception e) {
            debug("Failed to get available connections: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets active connection count.
     */
    public int getActiveConnections() {
        if (jedisPool == null) {
            return 0;
        }
        try {
            return jedisPool.getNumActive();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Gets maximum connection count.
     */
    public int getMaxConnections() {
        if (jedisPool == null) {
            return 0;
        }
        try {
            return jedisPool.getMaxTotal();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Gets connection pool health status description.
     */
    public String getPoolHealthStatus() {
        if (jedisPool == null) {
            return "LOCAL mode - Redis not available";
        }
        try {
            int max = jedisPool.getMaxTotal();
            int idle = jedisPool.getNumIdle();
            int active = jedisPool.getNumActive();
            int waiting = jedisPool.getNumWaiters();

            return String.format("Max: %d, Idle: %d, Active: %d, Waiting: %d",
                    max, idle, active, waiting);
        } catch (Exception e) {
            return "Unknown: " + e.getMessage();
        }
    }

    /**
     * Gets connection pool statistics.
     */
    public java.util.Map<String, Object> getPoolStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        if (jedisPool == null) {
            stats.put("error", "LOCAL mode - Redis not available");
            return stats;
        }
        try {
            stats.put("maxTotal", jedisPool.getMaxTotal());
            stats.put("idle", jedisPool.getNumIdle());
            stats.put("active", jedisPool.getNumActive());
            stats.put("waiting", jedisPool.getNumWaiters());
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    /**
     * Gets total economy balance.
     * Uses SCAN to scan all balance keys and calculate sum (non-blocking).
     */
    public BigDecimal getTotalBalance() {
        if (jedisPool == null) {
            debug("getTotalBalance skipped - LOCAL mode (no Redis).");
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("syncmoney:balance:*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;

            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                for (String key : scanResult.getResult()) {
                    try {
                        String value = jedis.get(key);
                        if (value != null) {
                            total = total.add(new BigDecimal(value));
                        }
                    } catch (Exception ignored) {
                    }
                }
                cursor = scanResult.getCursor();
            } while (!cursor.equals("0"));

        } catch (Exception e) {
            debug("Failed to calculate total balance: " + e.getMessage());
        }
        return total;
    }

    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            debug("Redis connection pool closed.");
        }
    }
}
