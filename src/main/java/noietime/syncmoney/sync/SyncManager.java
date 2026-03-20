package noietime.syncmoney.sync;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Unified manager for sync-related components.
 * Encapsulates PubsubSubscriber and DebounceManager.
 */
public class SyncManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final CacheManager cacheManager;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    
    private PubsubSubscriber pubsubSubscriber;
    private DebounceManager debounceManager;
    private volatile boolean versionCheckRunning = false;

    private static final Logger logger = Logger.getLogger("Syncmoney.VersionCheck");

    public SyncManager(Syncmoney plugin, SyncmoneyConfig config,
                       CacheManager cacheManager, EconomyFacade economyFacade,
                       RedisManager redisManager) {
        this.plugin = plugin;
        this.config = config;
        this.cacheManager = cacheManager;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
    }

    /**
     * Initialize sync layer components.
     */
    public void initialize() {
        this.debounceManager = new DebounceManager(plugin);
        
        boolean redisRequired = config.getEconomyMode() != noietime.syncmoney.economy.EconomyMode.LOCAL;
        this.pubsubSubscriber = new PubsubSubscriber(
                plugin,
                config,
                cacheManager,
                economyFacade,
                debounceManager,
                redisManager,
                redisRequired);
        
        if (redisManager != null && redisRequired) {
            pubsubSubscriber.startSubscription();
            plugin.getLogger().fine("PubSub subscription started");
        } else {
            plugin.getLogger().fine("PubSub subscription skipped (no Redis or not required)");
        }
        
        plugin.getLogger().fine("Sync layer initialized");
    }

    /**
     * Shutdown sync layer components.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down sync layer...");
        
        if (pubsubSubscriber != null) {
            pubsubSubscriber.stopSubscription();
        }
        
        plugin.getLogger().fine("Sync layer shutdown complete");
    }

    /**
     * [SYNC-04 FIX] Periodic version check to ensure data consistency across servers.
     * Runs every 5 minutes to compare local memory versions with Redis versions.
     * If local version is behind Redis, sync the balance from Redis.
     */
    public void startPeriodicVersionCheck() {
        if (config.getEconomyMode() == noietime.syncmoney.economy.EconomyMode.LOCAL) {
            logger.info("Version check skipped: LOCAL mode does not require cross-server sync");
            return;
        }


        long intervalTicks = 5 * 60 * 20;

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!versionCheckRunning) {
                versionCheckRunning = true;
                try {
                    performVersionCheck();
                } catch (Exception e) {
                    logger.warning("Version check failed: " + e.getMessage());
                } finally {
                    versionCheckRunning = false;
                }
            }
        }, intervalTicks, intervalTicks);

        logger.info("Periodic version check started (every 5 minutes)");
    }

    /**
     * Perform version consistency check between local memory and Redis.
     * [AsyncScheduler] This runs on async scheduler, safe for Redis operations.
     */
    private void performVersionCheck() {
        Set<UUID> onlinePlayers = economyFacade.getOnlinePlayerUuids();
        if (onlinePlayers.isEmpty()) {
            return;
        }


        Map<UUID, Long> redisVersions = cacheManager.getAllVersions(onlinePlayers);
        if (redisVersions.isEmpty()) {
            return;
        }

        int syncCount = 0;
        for (Map.Entry<UUID, Long> entry : redisVersions.entrySet()) {
            UUID uuid = entry.getKey();
            long redisVersion = entry.getValue();

            EconomyState localState = economyFacade.getMemoryState(uuid);
            if (localState != null && localState.version() < redisVersion) {

                logger.info("Version sync: " + uuid + " local v" + localState.version() + " < Redis v" + redisVersion);


                BigDecimal redisBalance = cacheManager.getBalance(uuid);
                long redisVersionFromCache = cacheManager.getVersion(uuid);

                economyFacade.forceUpdateMemoryState(uuid, redisBalance, redisVersionFromCache);
                syncCount++;
            }
        }

        if (syncCount > 0) {
            logger.info("Version check completed: synced " + syncCount + " players from Redis");
        }
    }

    public PubsubSubscriber getPubsubSubscriber() {
        return pubsubSubscriber;
    }

    public DebounceManager getDebounceManager() {
        return debounceManager;
    }
}
