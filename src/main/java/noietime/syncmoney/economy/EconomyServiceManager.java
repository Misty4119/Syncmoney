package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import noietime.syncmoney.vault.VaultRuntimeDetector;
import noietime.syncmoney.vault.VaultUnlockedIntegrationLoader;
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
    private Object vaultUnlockedRegistrar;
    private CrossServerSyncManager crossServerSyncManager;
    private EconomyModeRouter economyModeRouter;
    private ShadowSyncTask shadowSyncTask;
    private NameResolver nameResolver;


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
            this.localHandler = new LocalEconomyHandler(plugin, config.local().getLocalSQLitePath());
            plugin.getLogger().fine("Local Economy Handler initialized (SQLite mode)");
        }



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
                    null,
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
                    null,
                    overflowLog
            );
        }

        this.nameResolver = new NameResolver(
                plugin,
                storageManager.getCacheManager(),
                storageManager.getDatabaseManager()
        );

        if (config.shadowSync().isShadowSyncEnabled()) {
        this.shadowSyncTask = new ShadowSyncTask(
                plugin, config, economyFacade,
                storageManager.getCacheManager(),
                storageManager.getDatabaseManager()
        );
        shadowSyncTask.start();
        }

        this.vaultProvider = new SyncmoneyVaultProvider(
                plugin,
                economyFacade,
                storageManager.getRedisManager(),
                nameResolver
        );
        if (mode == EconomyMode.CMI) {
            plugin.getLogger().info(
                    "CMI mode: skipping Vault/VaultUnlocked Economy registration (CMI remains the provider).");
        } else {
            initializeVaultIntegrations();
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
     * [SYNC-ECO-073] Selects the primary Vault API from runtime capabilities.
     *
     * <p>VaultUnlocked keeps the Bukkit plugin name "Vault", so the modern
     * net.milkbowl.vault2 API is detected by class capability. Its provider is
     * registered as the primary API while the legacy provider remains available
     * for plugins compiled against Vault 1.7.
     */
    private void initializeVaultIntegrations() {
        VaultRuntimeDetector.Runtime runtime = VaultRuntimeDetector.detect(plugin);

        if (runtime == VaultRuntimeDetector.Runtime.VAULT_UNLOCKED) {
            vaultUnlockedRegistrar = VaultUnlockedIntegrationLoader.register(
                    plugin, economyFacade, nameResolver, config);
            if (vaultUnlockedRegistrar != null) {
                plugin.getLogger().info(
                        "VaultUnlocked detected: Vault2 is the primary economy API; Vault 1.7 compatibility remains enabled.");
            } else {
                plugin.getLogger().warning(
                        "VaultUnlocked detected but Vault2 registration failed; falling back to Vault 1.7 compatibility.");
            }
        } else if (runtime == VaultRuntimeDetector.Runtime.VAULT) {
            plugin.getLogger().info("Legacy Vault detected: using the Vault 1.7 economy API.");
        } else {
            plugin.getLogger().warning("No enabled Vault-compatible runtime was detected.");
        }

        if (runtime != VaultRuntimeDetector.Runtime.NONE && vaultProvider.setupEconomy()) {
            plugin.getLogger().fine("Syncmoney Vault 1.7 Economy registered successfully.");
        } else if (runtime != VaultRuntimeDetector.Runtime.NONE) {
            plugin.getLogger().warning("Vault 1.7 Economy registration failed.");
        }
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

    public boolean isVaultUnlockedRegistered() {
        return vaultUnlockedRegistrar != null;
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
        return plugin.getPlayerTransactionGuard();
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
