package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.economy.CMIEconomyHandler;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * [SYNC-ECO-070] Unified economy service manager.
 * Manages EconomyFacade, Vault integration, CrossServerSync, and ShadowSync.
 */
public class EconomyServiceManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final StorageManager storageManager;

    private EconomyFacade economyFacade;
    private EconomyWriteQueue economyWriteQueue;
    private FallbackEconomyWrapper fallbackWrapper;
    private LocalEconomyHandler localHandler;
    private SyncmoneyVaultProvider vaultProvider;
    private CrossServerSyncManager crossServerSyncManager;
    private EconomyModeRouter economyModeRouter;
    private ShadowSyncTask shadowSyncTask;
    private NameResolver nameResolver;
    private PlayerTransactionGuard playerTransactionGuard;

    public EconomyServiceManager(Syncmoney plugin, SyncmoneyConfig config, StorageManager storageManager) {
        this.plugin = plugin;
        this.config = config;
        this.storageManager = storageManager;
    }

    /**
     * [SYNC-ECO-071] Initialize all economy components.
     */
    public void initialize() {
        EconomyMode mode = config.getEconomyMode();

        this.economyWriteQueue = new EconomyWriteQueue(config.getQueueCapacity(), plugin.getLogger());

        this.fallbackWrapper = new FallbackEconomyWrapper(
                plugin,
                storageManager.getRedisManager(),
                mode == EconomyMode.LOCAL
        );

        if (mode == EconomyMode.LOCAL) {
            this.localHandler = new LocalEconomyHandler(plugin, config.getLocalSQLitePath());
            plugin.getLogger().fine("Local Economy Handler initialized (SQLite mode)");
        }

        this.playerTransactionGuard = new PlayerTransactionGuard(plugin, config, null, storageManager.getRedisManager());


        OverflowLogInterface overflowLog = new RedisOverflowLog(plugin, storageManager.getRedisManager());

        if (localHandler != null) {
            this.economyFacade = new EconomyFacade(
                    plugin, config,
                    storageManager.getCacheManager(),
                    storageManager.getRedisManager(),
                    storageManager.getDatabaseManager(),
                    localHandler,
                    economyWriteQueue,
                    fallbackWrapper,
                    playerTransactionGuard,
                    overflowLog
            );
        } else {
            this.economyFacade = new EconomyFacade(
                    plugin, config,
                    storageManager.getCacheManager(),
                    storageManager.getRedisManager(),
                    storageManager.getDatabaseManager(),
                    null,
                    economyWriteQueue,
                    fallbackWrapper,
                    playerTransactionGuard,
                    overflowLog
            );
        }

        this.nameResolver = new NameResolver(
                plugin,
                storageManager.getCacheManager(),
                storageManager.getDatabaseManager()
        );

        this.shadowSyncTask = new ShadowSyncTask(
                plugin, config, economyFacade,
                storageManager.getCacheManager(),
                storageManager.getDatabaseManager()
        );
        shadowSyncTask.start();

        this.vaultProvider = new SyncmoneyVaultProvider(
                plugin,
                economyFacade,
                storageManager.getRedisManager(),
                nameResolver
        );
        if (vaultProvider.setupEconomy()) {
            plugin.getLogger().fine("Syncmoney Vault Economy registered successfully.");
        } else {
            plugin.getLogger().warning("Vault Economy registration failed. Running without Vault integration.");
        }

        this.crossServerSyncManager = new CrossServerSyncManager(
                plugin, config,
                storageManager.getRedisManager(),
                economyFacade,
                storageManager.getCacheManager()
        );

        crossServerSyncManager.startPeriodicVersionCheck();

        vaultProvider.setSyncManager(crossServerSyncManager);
        vaultProvider.setConfig(config);
        plugin.getLogger().fine("CrossServerSyncManager initialized.");

        this.economyModeRouter = new EconomyModeRouter(plugin, config);

        EconomyModeRouter.EconomyFacadeWrapper wrapper = new EconomyModeRouter.EconomyFacadeWrapper() {
            @Override
            public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
                return economyFacade.deposit(uuid, amount, source);
            }

            @Override
            public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
                return economyFacade.withdraw(uuid, amount, source);
            }

            @Override
            public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
                return economyFacade.setBalance(uuid, newBalance, source);
            }

            @Override
            public BigDecimal getBalance(UUID uuid) {
                return economyFacade.getBalance(uuid);
            }
        };

        economyModeRouter.initialize(wrapper, localHandler, null, crossServerSyncManager);

        plugin.getLogger().fine("Economy layer initialized");
    }

    /**
     * [SYNC-ECO-072] Shutdown economy components in reverse order.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down economy layer...");

        if (shadowSyncTask != null) {
            shadowSyncTask.stop();
        }

        if (crossServerSyncManager != null) {
            crossServerSyncManager.shutdown();
        }

        if (playerTransactionGuard != null) {
            playerTransactionGuard.shutdown();
        }

        if (economyFacade != null) {
            economyFacade.shutdown();
        }

        plugin.getLogger().fine("Economy layer shutdown complete");
    }

    public EconomyFacade getEconomyFacade() {
        return economyFacade;
    }

    public NameResolver getNameResolver() {
        return nameResolver;
    }

    public LocalEconomyHandler getLocalEconomyHandler() {
        return localHandler;
    }

    public ShadowSyncTask getShadowSyncTask() {
        return shadowSyncTask;
    }

    public SyncmoneyVaultProvider getVaultProvider() {
        return vaultProvider;
    }

    public CrossServerSyncManager getCrossServerSyncManager() {
        return crossServerSyncManager;
    }

    /**
     * Set Discord webhook notifier for cross-server notifications.
     */
    public void setDiscordWebhookNotifier(noietime.syncmoney.breaker.DiscordWebhookNotifier discordWebhookNotifier) {
        if (crossServerSyncManager != null) {
            crossServerSyncManager.setDiscordWebhookNotifier(discordWebhookNotifier);
        }
    }

    public EconomyWriteQueue getEconomyWriteQueue() {
        return economyWriteQueue;
    }

    public OverflowLogInterface getOverflowLog() {
        return economyFacade != null ? economyFacade.getOverflowLog() : null;
    }

    public PlayerTransactionGuard getPlayerTransactionGuard() {
        return playerTransactionGuard;
    }

    public FallbackEconomyWrapper getFallbackWrapper() {
        return fallbackWrapper;
    }

    public EconomyModeRouter getEconomyModeRouter() {
        return economyModeRouter;
    }

    public CMIEconomyHandler getCmiHandler() {
        return economyModeRouter != null ? economyModeRouter.getCmiHandler() : null;
    }
}
