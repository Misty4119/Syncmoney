package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * System monitoring command handler.
 * Handles /syncmoney monitor command.
 *
 * Usage:
 * /syncmoney monitor - View system real-time status
 * /syncmoney monitor redis - View Redis detailed status
 * /syncmoney monitor cache - View cache status
 * /syncmoney monitor db - View database status
 */
public final class MonitorCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    private final CacheManager cacheManager;
    private final DatabaseManager databaseManager;
    private final DbWriteQueue dbWriteQueue;

    public MonitorCommand(Syncmoney plugin, EconomyFacade economyFacade,
            RedisManager redisManager, CacheManager cacheManager,
            DatabaseManager databaseManager, DbWriteQueue dbWriteQueue) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
        this.cacheManager = cacheManager;
        this.databaseManager = databaseManager;
        this.dbWriteQueue = dbWriteQueue;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            handleOverview(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "overview", "status" -> handleOverview(sender);
            case "redis" -> handleRedis(sender);
            case "cache" -> handleCache(sender);
            case "db", "database" -> handleDatabase(sender);
            case "memory" -> handleMemory(sender);
            case "messages" -> handleMessages(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Handles overview query.
     */
    private void handleOverview(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.header"));

        boolean redisOk = redisManager != null && !redisManager.isDegraded();
        boolean dbOk = databaseManager != null && databaseManager.isConnected();

        var circuitBreaker = plugin.getCircuitBreaker();
        String breakerStatus = plugin.getMessage("monitor.overview.breaker").replace("{status}", plugin.getMessage("breaker.state-options.not-enabled"));
        if (circuitBreaker != null) {
            breakerStatus = plugin.getMessage("monitor.overview.breaker").replace("{status}", switch (circuitBreaker.getState()) {
                case NORMAL -> plugin.getMessage("breaker.state-options.normal");
                case WARNING -> plugin.getMessage("breaker.state-options.warning");
                case LOCKED -> plugin.getMessage("breaker.state-options.locked");
            });
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.system-status"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.redis")
                .replace("{status}", redisOk ? plugin.getMessage("monitor.status.online") : plugin.getMessage("monitor.status.offline")));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.database")
                .replace("{status}", dbOk ? plugin.getMessage("monitor.status.online") : plugin.getMessage("monitor.status.offline")));
        MessageHelper.sendMessage(sender, breakerStatus);
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.players")
                .replace("{players}", String.valueOf(plugin.getServer().getOnlinePlayers().size())));

        Runtime rt = Runtime.getRuntime();
        long totalMemory = rt.totalMemory() / 1024 / 1024;
        long freeMemory = rt.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        double usagePercent = (double) usedMemory / totalMemory * 100;

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.memory-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.memory-usage")
                .replace("{used}", String.valueOf(usedMemory))
                .replace("{total}", String.valueOf(totalMemory))
                .replace("{percent}", FormatUtil.formatPercentRaw(usagePercent)));

        if (economyFacade != null) {
            int cachedPlayers = economyFacade.getCachedPlayerCount();
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.cache-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.cached-players")
                    .replace("{count}", String.valueOf(cachedPlayers)));
        }

        if (dbWriteQueue != null) {
            int queueSize = dbWriteQueue.size();
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.queue")
                    .replace("{count}", String.valueOf(queueSize)));
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.overview.hint"));
    }

    /**
     * Handles Redis status query.
     */
    private void handleRedis(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.header"));

        if (redisManager == null || redisManager.isDegraded()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.offline"));
            return;
        }

        int maxConnections = redisManager.getMaxConnections();
        int availableConnections = redisManager.getAvailableConnections();

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.pool-header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.max-connections")
                .replace("{max}", String.valueOf(maxConnections)));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.available-connections")
                .replace("{available}", String.valueOf(availableConnections)));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.in-use")
                .replace("{in_use}", String.valueOf(maxConnections - availableConnections)));

        if (cacheManager != null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.stats-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.hits")
                    .replace("{hits}", String.valueOf(cacheManager.getCacheHits())));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.misses")
                    .replace("{misses}", String.valueOf(cacheManager.getCacheMisses())));

            long totalRequests = cacheManager.getCacheHits() + cacheManager.getCacheMisses();
            if (totalRequests > 0) {
                double hitRate = (double) cacheManager.getCacheHits() / totalRequests * 100;
                MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.hit-rate")
                        .replace("{hit_rate}", FormatUtil.formatHitRate(hitRate)));
            }
        }

        try {
            var totalBalance = redisManager.getTotalBalance();
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.economy-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.total-supply")
                    .replace("{total_supply}", FormatUtil.formatCurrency(totalBalance)));
        } catch (Exception e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.redis.error")
                    .replace("{error}", e.getMessage()));
        }
    }

    /**
     * Handles cache status query.
     */
    private void handleCache(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.header"));

        if (economyFacade != null) {
            int cachedPlayers = economyFacade.getCachedPlayerCount();
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.cached-players")
                    .replace("{count}", String.valueOf(cachedPlayers)));
        }

        if (cacheManager != null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.redis-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.redis-hits")
                    .replace("{hits}", String.valueOf(cacheManager.getCacheHits())));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.redis-misses")
                    .replace("{misses}", String.valueOf(cacheManager.getCacheMisses())));

            long totalRequests = cacheManager.getCacheHits() + cacheManager.getCacheMisses();
            if (totalRequests > 0) {
                double hitRate = (double) cacheManager.getCacheHits() / totalRequests * 100;
                MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.hit-rate")
                        .replace("{hit_rate}", FormatUtil.formatHitRate(hitRate)));
            }

            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.cache.expiration")
                    .replace("{minutes}", String.valueOf(cacheManager.getExpirationMinutes())));
        }
    }

    /**
     * Handles database status query.
     */
    private void handleDatabase(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.database.header"));

        if (databaseManager == null || !databaseManager.isConnected()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.database.offline"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.database.status"));

        if (dbWriteQueue != null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.database.queue-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.database.pending")
                    .replace("{pending}", String.valueOf(dbWriteQueue.size())));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.database.written")
                    .replace("{written}", String.valueOf(dbWriteQueue.getWrittenCount())));
        }
    }

    /**
     * Handles memory status query.
     */
    private void handleMemory(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.header"));

        Runtime rt = Runtime.getRuntime();
        long totalMemory = rt.totalMemory() / 1024 / 1024;
        long freeMemory = rt.freeMemory() / 1024 / 1024;
        long maxMemory = rt.maxMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.allocation")
                .replace("{allocated}", String.valueOf(totalMemory)));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.usage")
                .replace("{used}", String.valueOf(usedMemory))
                .replace("{percent}", FormatUtil.formatPercentRaw((double) usedMemory / totalMemory * 100)));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.max")
                .replace("{max}", String.valueOf(maxMemory)));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.available")
                .replace("{available}", String.valueOf(maxMemory - usedMemory)));

        if (economyFacade != null) {
            int cachedPlayers = economyFacade.getCachedPlayerCount();
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.app-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.memory.cached-players")
                    .replace("{count}", String.valueOf(cachedPlayers)));
        }
    }

    /**
     * Handles message Component cache status (for viewing placeholder/message parsing cache to prevent memory leaks).
     */
    private void handleMessages(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.messages.header"));

        int helperSize = MessageHelper.getComponentCacheSize();
        int pluginSize = plugin.getMessageComponentCacheSize();

        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.messages.helper-cache")
                .replace("{helper}", String.valueOf(helperSize)));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.messages.plugin-cache")
                .replace("{plugin}", String.valueOf(pluginSize)));

        long hits = MessageHelper.getComponentCacheHits();
        long misses = MessageHelper.getComponentCacheMisses();
        long total = hits + misses;
        if (total > 0) {
            double hitRate = (double) hits / total * 100;
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.messages.parse-header"));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.messages.hits-misses")
                    .replace("{hits}", String.valueOf(hits))
                    .replace("{misses}", String.valueOf(misses)));
            MessageHelper.sendMessage(sender, plugin.getMessage("monitor.messages.hit-rate")
                    .replace("{hit_rate}", FormatUtil.formatHitRate(hitRate)));
        }
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.overview"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.redis"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.cache"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.messages"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.db"));
        MessageHelper.sendMessage(sender, plugin.getMessage("monitor.usage.memory"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("overview", "redis", "cache", "db", "memory", "messages")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
