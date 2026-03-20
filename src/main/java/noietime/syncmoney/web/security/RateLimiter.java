package noietime.syncmoney.web.security;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced in-memory rate limiter with burst capacity and IP whitelist support.
 * Uses sliding window algorithm to limit requests per client.
 */
public class RateLimiter {

    private final int maxRequests;
    private final int burstCapacity;
    private final ConcurrentMap<String, RateLimitEntry> entries = new ConcurrentHashMap<>();
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    private final AtomicLong callCount = new AtomicLong(0);
    private final ScheduledExecutorService cleanupExecutor;

    public RateLimiter(int maxRequestsPerMinute) {
        this(maxRequestsPerMinute, maxRequestsPerMinute / 2);
    }

    public RateLimiter(int maxRequestsPerMinute, int burstCapacity) {
        this.maxRequests = maxRequestsPerMinute;
        this.burstCapacity = Math.min(burstCapacity, maxRequestsPerMinute);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiter-Cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Add an IP to the whitelist (exempt from rate limiting).
     * @param ip IP address to whitelist
     */
    public void addToWhitelist(String ip) {
        if (ip != null && !ip.isBlank()) {
            whitelist.add(ip.trim());
        }
    }

    /**
     * Remove an IP from the whitelist.
     * @param ip IP address to remove from whitelist
     */
    public void removeFromWhitelist(String ip) {
        if (ip != null) {
            whitelist.remove(ip.trim());
        }
    }

    /**
     * Check if an IP is whitelisted.
     * @param ip IP address to check
     * @return true if whitelisted
     */
    public boolean isWhitelisted(String ip) {
        return ip != null && whitelist.contains(ip.trim());
    }

    /**
     * Clean up expired entries to prevent memory leaks.
     */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return (now - entry.getValue().windowStart) > 120_000;
            }
        });
    }

    /**
     * Check if a request from the client is allowed.
     *
     * @param clientId Unique identifier for the client (IP address, API key, etc.)
     * @return true if allowed, false if rate limit exceeded
     */
    public boolean isAllowed(String clientId) {
        if (isWhitelisted(clientId)) {
            return true;
        }

        RateLimitEntry entry = entries.computeIfAbsent(clientId, k -> new RateLimitEntry());


        long now = System.currentTimeMillis();

        if (now - entry.windowStart > 60000) {
            entry.windowStart = now;
            entry.count.set(0);
            entry.burstUsed.set(0);
        }

        if (entry.burstUsed.get() >= burstCapacity) {
            if (entry.count.get() >= maxRequests) {
                return false;
            }
        }

        if (entry.burstUsed.get() < burstCapacity) {
            entry.burstUsed.incrementAndGet();
        } else {
            entry.count.incrementAndGet();
        }
        return true;
    }

    /**
     * Get remaining requests for a client.
     * Uses AtomicInteger for lock-free thread safety.
     */
    public int getRemainingRequests(String clientId) {
        if (isWhitelisted(clientId)) {
            return Integer.MAX_VALUE;
        }

        RateLimitEntry entry = entries.get(clientId);
        if (entry == null) {
            return maxRequests + burstCapacity;
        }


        int burstRemaining = Math.max(0, burstCapacity - entry.burstUsed.get());
        int regularRemaining = Math.max(0, maxRequests - entry.count.get());
        return burstRemaining + regularRemaining;
    }

    /**
     * Clear all rate limit entries.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Clear all whitelist entries.
     */
    public void clearWhitelist() {
        whitelist.clear();
    }

    /**
     * Get the number of tracked clients.
     */
    public int getTrackedClients() {
        return entries.size();
    }

    /**
     * Get the number of whitelisted IPs.
     */
    public int getWhitelistSize() {
        return whitelist.size();
    }

    /**
     * Shutdown the rate limiter and cleanup executor.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class RateLimitEntry {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger burstUsed = new AtomicInteger(0);
        long windowStart = System.currentTimeMillis();
    }
}
