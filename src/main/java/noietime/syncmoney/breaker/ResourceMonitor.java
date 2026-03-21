package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.web.websocket.SseManager;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resource monitor.
 * Monitors server resource usage:
 * - Memory usage
 * - Redis connection pool status
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class ResourceMonitor {

    private static final long WARNING_INTERVAL_MS = 300000;

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final Syncmoney syncMoneyPlugin;
    private final ScheduledExecutorService scheduler;
    private final MemoryMXBean memoryBean;
    private DiscordWebhookNotifier discordNotifier;

    private volatile double currentMemoryUsagePercent = 0;

    private volatile long availableMemoryMb = 0;

    private volatile int redisAvailableConnections = 0;

    private volatile long lastWarningTime = 0;

    private volatile SseManager sseManager;

    public ResourceMonitor(Plugin plugin, SyncmoneyConfig config, RedisManager redisManager) {
        this.plugin = plugin;
        this.syncMoneyPlugin = (plugin instanceof Syncmoney) ? (Syncmoney) plugin : null;
        this.config = config;
        this.redisManager = redisManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Syncmoney-ResourceMonitor");
            t.setDaemon(true);
            return t;
        });
        this.memoryBean = ManagementFactory.getMemoryMXBean();

        startMonitoring();
    }

    /**
     * Start monitoring task.
     */
    private void startMonitoring() {
        scheduler.scheduleAtFixedRate(this::checkResources, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Check resource usage.
     */
    private void checkResources() {
        checkMemory();

        checkRedisPool();
    }

    /**
     * Check memory usage.
     */
    private void checkMemory() {
        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            long usedMb = heapUsage.getUsed() / (1024 * 1024);
            long maxMb = heapUsage.getMax() / (1024 * 1024);
            long availableMb = maxMb - usedMb;

            double usagePercent = (double) usedMb / maxMb * 100;

            currentMemoryUsagePercent = usagePercent;
            availableMemoryMb = availableMb;

            if (usagePercent > config.circuitBreaker().getCircuitBreakerMemoryWarningThreshold()) {
                long now = System.currentTimeMillis();
                if (now - lastWarningTime > WARNING_INTERVAL_MS) {
                    plugin.getLogger().warning("ResourceMonitor: Memory usage is " +
                            FormatUtil.formatPercent(usagePercent) +
                            " (" + usedMb + "/" + maxMb + " MB). " +
                            "Threshold: " + config.circuitBreaker().getCircuitBreakerMemoryWarningThreshold() + "%");
                    lastWarningTime = now;

                    broadcastResourceAlert("memory_high",
                        "High Memory Usage",
                        String.format("Memory usage at %.1f%% (%d/%d MB)", usagePercent, usedMb, maxMb));

                    if (discordNotifier != null) {
                        discordNotifier.sendMemoryHighEvent(usagePercent, usedMb, maxMb);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("ResourceMonitor: Error checking memory: " + e.getMessage());
        }
    }

    /**
     * Check Redis connection pool.
     * Note: Since Jedis connection pool idle count may be inaccurate in fixed size mode,
     * we use simpler logic: only warn when usage is extremely high.
     */
    private void checkRedisPool() {
        try {
            int active = redisManager.getActiveConnections();
            int maxTotal = redisManager.getMaxConnections();


            int poolWarningThreshold = maxTotal - config.circuitBreaker().getCircuitBreakerPoolExhaustedWarning();
            if (active >= poolWarningThreshold && active >= maxTotal * 8 / 10) {
                long now = System.currentTimeMillis();
                if (now - lastWarningTime > WARNING_INTERVAL_MS) {
                    plugin.getLogger().severe("ResourceMonitor: Redis connection pool critical! " +
                            "Active: " + active + ", Max: " + maxTotal +
                            " (threshold: " + config.circuitBreaker().getCircuitBreakerPoolExhaustedWarning() + " available)");
                    lastWarningTime = now;

                    broadcastResourceAlert("redis_pool_critical",
                        "Redis Connection Pool Critical",
                        String.format("Active connections: %d/%d (%.1f%%)", active, maxTotal,
                            (double) active / maxTotal * 100));

                    if (discordNotifier != null) {
                        discordNotifier.sendRedisPoolCriticalEvent(active, maxTotal);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("ResourceMonitor: Error checking Redis pool: " + e.getMessage());
        }
    }

    /**
     * Get current memory usage percent.
     */
    public double getMemoryUsagePercent() {
        return currentMemoryUsagePercent;
    }

    /**
     * Get available memory (MB).
     */
    public long getAvailableMemoryMb() {
        return availableMemoryMb;
    }

    /**
     * Get Redis available connections.
     */
    public int getRedisAvailableConnections() {
        return redisAvailableConnections;
    }

    /**
     * Check if resources are healthy.
     */
    public boolean isHealthy() {
        boolean memoryOk = currentMemoryUsagePercent < config.circuitBreaker().getCircuitBreakerMemoryWarningThreshold();

        boolean redisOk = true;
        if (redisManager != null && !redisManager.isDegraded()) {
            int active = redisManager.getActiveConnections();
            int maxTotal = redisManager.getMaxConnections();
            redisOk = (maxTotal - active) > config.circuitBreaker().getCircuitBreakerPoolExhaustedWarning();
        }

        return memoryOk && redisOk;
    }

    /**
     * Get resource status summary.
     */
    public String getStatusSummary() {
        if (syncMoneyPlugin != null) {
            return syncMoneyPlugin.getMessage("resource-monitor.status-summary")
                    .replace("{memory}", FormatUtil.formatPercentRaw(currentMemoryUsagePercent))
                    .replace("{available}", String.valueOf(availableMemoryMb))
                    .replace("{redis_connections}", String.valueOf(redisAvailableConnections));
        }
        return String.format("Memory: %.1f%% (%d MB available), Redis connections: %d",
                currentMemoryUsagePercent, availableMemoryMb, redisAvailableConnections);
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
     * Set SSE manager for broadcasting resource alerts to web clients.
     */
    public void setSseManager(SseManager sseManager) {
        this.sseManager = sseManager;
    }

    /**
     * Set Discord webhook notifier for sending resource alerts to Discord.
     */
    public void setDiscordWebhookNotifier(DiscordWebhookNotifier discordNotifier) {
        this.discordNotifier = discordNotifier;
    }

    /**
     * Broadcast resource alert to SSE clients.
     */
    private void broadcastResourceAlert(String type, String title, String message) {
        if (sseManager != null && sseManager.isEnabled()) {
            try {
                String json = String.format(
                    "{\"type\":\"%s\",\"title\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
                    type, title.replace("\"", "'"), message.replace("\"", "'"), System.currentTimeMillis()
                );
                sseManager.broadcastToChannel("system", json);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to broadcast resource alert: " + e.getMessage());
            }
        }
    }
}
