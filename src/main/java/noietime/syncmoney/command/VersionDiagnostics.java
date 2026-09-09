package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.util.BuildVersion;
import noietime.syncmoney.util.ServerPlatformDetector;
import noietime.syncmoney.vault.VaultRuntimeDetector;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Collects sanitized, read-only support diagnostics. */
public final class VersionDiagnostics {

    static final long PROBE_TIMEOUT_MS = 3_000L;
    private static final int LATENCY_SAMPLES = 3;

    private VersionDiagnostics() {
    }

    public static VersionReport basic(Syncmoney plugin) {
        Server server = plugin.getServer();
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMb = runtime.maxMemory() / 1024 / 1024;

        VersionReport report = new VersionReport("BASIC");
        report.add("report.generated_at", Instant.now());
        report.add("plugin.name", plugin.getDescription().getName());
        report.add("plugin.version", BuildVersion.VERSION);
        report.add("server.software", server.getName());
        report.add("server.version", server.getVersion());
        report.add("server.bukkit_version", server.getBukkitVersion());
        report.add("server.platform", detectPlatform());
        report.add("server.async_scheduler", ServerPlatformDetector.hasAsyncScheduler());
        report.add("java.version", Runtime.version());
        report.add("java.vendor", System.getProperty("java.vendor", "UNKNOWN"));
        report.add("host.os", System.getProperty("os.name", "UNKNOWN"));
        report.add("host.os_version", System.getProperty("os.version", "UNKNOWN"));
        report.add("host.arch", System.getProperty("os.arch", "UNKNOWN"));
        report.add("host.cpu_count", runtime.availableProcessors());
        report.add("host.memory_mb", "used=" + usedMb + " max=" + maxMb);
        report.add("support.minimum_minecraft", "1.20.4");
        report.add("support.latest_verified_server", "26.2");
        return report;
    }

    public static CompletableFuture<VersionReport> full(Syncmoney plugin) {
        VersionReport report = basic(plugin).add("report.scope", "FULL");
        addRuntimeStatuses(report, plugin);

        SyncmoneyConfig config = plugin.getSyncmoneyConfig();
        boolean redisEnabled = config != null && config.isRedisEnabled();
        boolean databaseEnabled = config != null && config.isDatabaseEnabled();

        CompletableFuture<VersionReport.ProbeResult> redis = redisEnabled
                ? scheduleProbe(plugin, () -> probeRedis(plugin.getRedisManager()))
                : CompletableFuture.completedFuture(disabled("Redis is disabled"));
        CompletableFuture<VersionReport.ProbeResult> database = databaseEnabled
                ? scheduleProbe(plugin, () -> probeDatabase(plugin.getDatabaseManager()))
                : CompletableFuture.completedFuture(disabled("Database is disabled"));

        return CompletableFuture.allOf(redis, database)
                .thenApply(ignored -> {
                    VersionReport.ProbeResult redisResult = redis.join();
                    VersionReport.ProbeResult databaseResult = database.join();
                    report.addProbe("redis", redisResult);
                    report.addProbe("database", databaseResult);
                    report.add("report.collection_status",
                            isHealthy(redisResult) && isHealthy(databaseResult) ? "PASS" : "DEGRADED");
                    return report;
                });
    }

    private static boolean isHealthy(VersionReport.ProbeResult result) {
        return "PASS".equals(result.status()) || "DISABLED".equals(result.status());
    }

    private static void addRuntimeStatuses(VersionReport report, Syncmoney plugin) {
        SyncmoneyConfig config = plugin.getSyncmoneyConfig();
        PluginManager plugins = plugin.getServer().getPluginManager();

        Plugin vault = plugins.getPlugin("Vault");
        String vaultRuntime;
        try {
            vaultRuntime = VaultRuntimeDetector.detect(plugin).name();
        } catch (RuntimeException | LinkageError ignored) {
            vaultRuntime = "UNKNOWN";
        }
        report.add("dependency.vault", pluginStatus(vault) + " api=" + vaultRuntime);
        report.add("dependency.cmi", pluginStatus(plugins.getPlugin("CMI")));
        report.add("dependency.placeholder_api", pluginStatus(plugins.getPlugin("PlaceholderAPI")));

        if (config == null) {
            report.add("economy.mode", "UNKNOWN");
            return;
        }

        report.add("economy.mode", config.getEconomyMode());
        report.add("redis.configured", config.isRedisEnabled());
        report.add("database.configured", config.isDatabaseEnabled());
        report.add("pubsub.enabled", config.isPubsubEnabled());
        report.add("database.type", config.database().getDatabaseType());

        RedisManager redis = plugin.getRedisManager();
        if (redis != null) {
            report.add("redis.pool", "active=" + redis.getActiveConnections()
                    + " idle=" + redis.getAvailableConnections()
                    + " max=" + redis.getMaxConnections());
        }

        DatabaseManager database = plugin.getDatabaseManager();
        report.add("module.web_admin", config.getConfig().getBoolean("web-admin.enabled", false)
                + " running=" + (plugin.getWebAdminServer() != null));
        report.add("module.audit", config.audit().isAuditEnabled()
                + " running=" + (plugin.getAuditLogger() != null && plugin.getAuditLogger().isEnabled()));
        report.add("module.shadow_sync", config.shadowSync().isShadowSyncEnabled()
                + " running=" + (plugin.getShadowSyncTask() != null));
        report.add("module.circuit_breaker", config.circuitBreaker().isCircuitBreakerEnabled()
                + " state=" + (plugin.getCircuitBreaker() == null
                ? "NOT_INITIALIZED" : plugin.getCircuitBreaker().getState()));
        if (database != null) {
            HikariDataSource dataSource = database.getDataSource();
            report.add("database.pool", dataSource == null || dataSource.getHikariPoolMXBean() == null
                    ? "UNKNOWN"
                    : "active=" + dataSource.getHikariPoolMXBean().getActiveConnections()
                    + " idle=" + dataSource.getHikariPoolMXBean().getIdleConnections()
                    + " total=" + dataSource.getHikariPoolMXBean().getTotalConnections());
        }
    }

    private static String pluginStatus(Plugin plugin) {
        if (plugin == null) {
            return "NOT_INSTALLED";
        }
        return (plugin.isEnabled() ? "ENABLED" : "DISABLED")
                + " v" + plugin.getDescription().getVersion();
    }

    private static String detectPlatform() {
        try {
            return ServerPlatformDetector.detect().name();
        } catch (RuntimeException | LinkageError ignored) {
            return "UNKNOWN";
        }
    }

    private static CompletableFuture<VersionReport.ProbeResult> scheduleProbe(
            Syncmoney plugin, Supplier<VersionReport.ProbeResult> probe) {
        CompletableFuture<VersionReport.ProbeResult> result = new CompletableFuture<>();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    result.complete(probe.get());
                } catch (Exception | LinkageError e) {
                    result.complete(failure(e));
                }
            });
        } catch (RuntimeException | LinkageError e) {
            return CompletableFuture.completedFuture(failure(e));
        }
        return result.completeOnTimeout(timeout(), PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static VersionReport.ProbeResult timeout() {
        return new VersionReport.ProbeResult("TIMEOUT", null, List.of(), "timeout_ms=" + PROBE_TIMEOUT_MS);
    }

    private static VersionReport.ProbeResult disabled(String detail) {
        return new VersionReport.ProbeResult("DISABLED", null, List.of(), detail);
    }

    private static VersionReport.ProbeResult failure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String type = cause.getClass().getSimpleName();
        String lower = type.toLowerCase(Locale.ROOT);
        String status = lower.contains("timeout") ? "TIMEOUT"
                : lower.contains("connect") || lower.contains("socket") || lower.contains("pool")
                ? "DISCONNECTED" : "ERROR";
        return new VersionReport.ProbeResult(status, null, List.of(), type);
    }

    private static VersionReport.ProbeResult probeRedis(RedisManager manager) {
        if (manager == null) {
            return disabled("Redis manager is not initialized");
        }

        List<Double> latency = new ArrayList<>(LATENCY_SAMPLES);
        try (Jedis jedis = manager.getResource()) {
            for (int i = 0; i < LATENCY_SAMPLES; i++) {
                long start = System.nanoTime();
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    return new VersionReport.ProbeResult("DISCONNECTED", null, latency, "PING did not return PONG");
                }
                latency.add(elapsedMs(start));
            }
            String version = parseRedisInfo(jedis.info("server"), "redis_version");
            return new VersionReport.ProbeResult("PASS", version, latency, null);
        } catch (Exception | LinkageError e) {
            return failure(e);
        }
    }

    private static VersionReport.ProbeResult probeDatabase(DatabaseManager manager) {
        if (manager == null) {
            return disabled("Database manager is not initialized");
        }

        List<Double> latency = new ArrayList<>(LATENCY_SAMPLES);
        try (Connection connection = manager.getConnection();
                Statement statement = connection.createStatement()) {
            for (int i = 0; i < LATENCY_SAMPLES; i++) {
                long start = System.nanoTime();
                try (ResultSet result = statement.executeQuery("SELECT 1")) {
                    if (!result.next()) {
                        return new VersionReport.ProbeResult("ERROR", null, latency, "SELECT 1 returned no row");
                    }
                }
                latency.add(elapsedMs(start));
            }
            var metadata = connection.getMetaData();
            String version = metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion();
            return new VersionReport.ProbeResult("PASS", version, latency, null);
        } catch (Exception | LinkageError e) {
            return failure(e);
        }
    }

    private static double elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static String parseRedisInfo(String info, String key) {
        if (info == null) {
            return "UNKNOWN";
        }
        for (String line : info.split("\\R")) {
            if (line.startsWith(key + ":")) {
                String value = line.substring(key.length() + 1).trim();
                return value.isEmpty() ? "UNKNOWN" : value;
            }
        }
        return "UNKNOWN";
    }
}
