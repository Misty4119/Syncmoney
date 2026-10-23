package noietime.syncmoney.sync;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.initialization.ShutdownStepRunner;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private boolean versionCheckRunning;
    private volatile boolean stopped;
    private volatile ScheduledTask versionCheckTask;
    private final Object versionCheckLifecycleGate = new Object();

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
        this.debounceManager = new DebounceManager(plugin, config);

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
        stopInboundProducers();
        plugin.getLogger().fine("Sync layer shutdown complete");
    }

    /**
     * Stop remote and scheduled producers before accepted writes are drained.
     * The Redis owner remains open until storage shutdown.
     */
    public void stopInboundProducers() {
        ScheduledTask scheduled;
        synchronized (versionCheckLifecycleGate) {
            stopped = true;
            scheduled = versionCheckTask;
            versionCheckTask = null;
        }
        plugin.getLogger().fine("Shutting down sync layer...");
        ShutdownStepRunner.run(plugin.getLogger(),
                new ShutdownStepRunner.Step("periodic version check", () -> {
                    if (scheduled != null) scheduled.cancel();
                }),
                new ShutdownStepRunner.Step("Pub/Sub subscriber", () -> {
                    if (pubsubSubscriber != null) pubsubSubscriber.stopSubscription();
                }),
                new ShutdownStepRunner.Step("active version check", this::awaitVersionCheck));
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

        synchronized (versionCheckLifecycleGate) {
            if (stopped || versionCheckTask != null) {
                return;
            }
        }


        ScheduledTask scheduled = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!beginVersionCheck()) {
                if (stopped) task.cancel();
                return;
            }
            try {
                performVersionCheck();
            } catch (Exception e) {
                logger.warning("Version check failed: " + e.getMessage());
            } finally {
                finishVersionCheck();
            }
        }, 5, 5, TimeUnit.MINUTES);

        boolean cancelScheduled;
        synchronized (versionCheckLifecycleGate) {
            cancelScheduled = stopped || versionCheckTask != null;
            if (!cancelScheduled) {
                versionCheckTask = scheduled;
            }
        }
        if (cancelScheduled && scheduled != null) {
            scheduled.cancel();
        }

        if (!cancelScheduled) {
            logger.info("Periodic version check started (every 5 minutes)");
        }
    }

    /**
     * Perform version consistency check between local memory and Redis.
     * [AsyncScheduler] This runs on async scheduler, safe for Redis operations.
     */
    private void performVersionCheck() {
        Set<UUID> onlinePlayers = Set.copyOf(economyFacade.getOnlinePlayerUuids());
        if (stopped || onlinePlayers.isEmpty()) {
            return;
        }


        int syncCount = 0;
        for (UUID uuid : onlinePlayers) {
            if (stopped) return;

            try {
                EconomyState localState = economyFacade.getMemoryState(uuid);
                Optional<CacheManager.CachedBalance> redisSnapshot =
                        cacheManager.readAuthorityBalanceSnapshotStrict(uuid);
                if (stopped || redisSnapshot.isEmpty()) {
                    continue;
                }

                CacheManager.CachedBalance authority = redisSnapshot.get();
                if (localState != null && authority.version() <= localState.version()) {
                    continue;
                }

                if (economyFacade.updateMemoryState(uuid, authority.balance(), authority.version())) {
                    syncCount++;
                }
            } catch (Exception e) {
                logger.warning("Version sync failed for " + uuid + ": " + e.getMessage());
            }
        }

        if (syncCount > 0) {
            logger.info("Version check completed: synced " + syncCount + " players from Redis");
        }
    }

    private boolean beginVersionCheck() {
        synchronized (versionCheckLifecycleGate) {
            if (stopped || versionCheckRunning) {
                return false;
            }
            versionCheckRunning = true;
            return true;
        }
    }

    private void finishVersionCheck() {
        synchronized (versionCheckLifecycleGate) {
            versionCheckRunning = false;
            versionCheckLifecycleGate.notifyAll();
        }
    }

    private void awaitVersionCheck() {
        boolean interrupted = false;
        synchronized (versionCheckLifecycleGate) {
            while (versionCheckRunning) {
                try {
                    versionCheckLifecycleGate.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public PubsubSubscriber getPubsubSubscriber() {
        return pubsubSubscriber;
    }

    public DebounceManager getDebounceManager() {
        return debounceManager;
    }
}
