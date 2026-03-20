package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyServiceManager;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.sync.PubsubSubscriber;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.audit.AuditCommand;
import noietime.syncmoney.baltop.BaltopCommand;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;

/**
 * Unified command service manager.
 * Manages command registration, creation, and lifecycle.
 */
public class CommandServiceManager {

    private final Syncmoney plugin;
    private SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final StorageManager storageManager;
    private final EconomyServiceManager economyServiceManager;
    private final PubsubSubscriber pubsubSubscriber;
    private final BaltopManager baltopManager;
    private final AuditLogger auditLogger;
    private final HybridAuditManager hybridAuditManager;
    private final ShadowSyncTask shadowSyncTask;
    private final EconomicCircuitBreaker circuitBreaker;
    private final NameResolver nameResolver;

    private CooldownManager cooldownManager;
    private TransferLockManager transferLockManager;
    private SyncmoneyCommandRouter syncmoneyRouter;


    private MoneyCommand moneyCommand;
    private PayCommand payCommand;

    public CommandServiceManager(
            Syncmoney plugin,
            SyncmoneyConfig config,
            EconomyFacade economyFacade,
            StorageManager storageManager,
            EconomyServiceManager economyServiceManager,
            PubsubSubscriber pubsubSubscriber,
            BaltopManager baltopManager,
            AuditLogger auditLogger,
            HybridAuditManager hybridAuditManager,
            ShadowSyncTask shadowSyncTask,
            EconomicCircuitBreaker circuitBreaker,
            NameResolver nameResolver) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.storageManager = storageManager;
        this.economyServiceManager = economyServiceManager;
        this.pubsubSubscriber = pubsubSubscriber;
        this.baltopManager = baltopManager;
        this.auditLogger = auditLogger;
        this.hybridAuditManager = hybridAuditManager;
        this.shadowSyncTask = shadowSyncTask;
        this.circuitBreaker = circuitBreaker;
        this.nameResolver = nameResolver;
    }

    /**
     * Initialize all commands and register them.
     */
    public void initialize() {
        this.cooldownManager = new CooldownManager(plugin, config.getPayCooldownSeconds());

        this.transferLockManager = new TransferLockManager(
                plugin,
                storageManager.getRedisManager(),
                config.getEconomyMode() != EconomyMode.LOCAL);

        this.syncmoneyRouter = new SyncmoneyCommandRouter(plugin);

        registerAllCommands();

        plugin.getLogger().fine("Command layer initialized");
    }

    /**
     * Hot-reloads command configuration after a /syncmoney reload.
     * Updates the config reference, pay cooldown, and any snapshot values
     * that were baked into command constructors at startup.
     *
     * @param newConfig freshly created SyncmoneyConfig
     */
    public void reload(SyncmoneyConfig newConfig) {
        this.config = newConfig;

        if (cooldownManager != null) {
            cooldownManager.reload(newConfig.getPayCooldownSeconds());
        }

        if (payCommand != null) {
            payCommand.reload(newConfig);
        }

        if (moneyCommand != null) {
            moneyCommand.reload(newConfig);
        }

        plugin.getLogger().fine("Command layer reloaded (cooldown=" + newConfig.getPayCooldownSeconds()
                + "s, minPay=" + newConfig.getPayMinAmount()
                + ", maxPay=" + newConfig.getPayMaxAmount() + ")");
    }

    /**
     * Shutdown command service and cleanup resources.
     */
    public void shutdown() {
        plugin.getLogger().fine("Command layer shutdown");
    }

    private void registerAllCommands() {
        FallbackEconomyWrapper fallbackWrapper = economyServiceManager.getFallbackWrapper();
        EconomyWriteQueue economyWriteQueue = economyServiceManager.getEconomyWriteQueue();
        RedisManager redisManager = storageManager.getRedisManager();
        CacheManager cacheManager = storageManager.getCacheManager();
        DbWriteQueue dbWriteQueue = storageManager.getDbWriteQueue();

        this.moneyCommand = new MoneyCommand(
                plugin,
                economyFacade,
                nameResolver,
                fallbackWrapper,
                config.getCurrencyName(),
                config.getDecimalPlaces());
        register("money", moneyCommand, moneyCommand);

        this.payCommand = new PayCommand(
                plugin,
                config,
                economyFacade,
                cacheManager,
                redisManager,
                nameResolver,
                fallbackWrapper,
                transferLockManager,
                cooldownManager,
                dbWriteQueue,
                economyWriteQueue,
                pubsubSubscriber,
                baltopManager,
                config.getPayMinAmount(),
                config.getPayMaxAmount(),
                config.isPayAllowedInDegraded(),
                config.isLocalMode());
        register("pay", payCommand, payCommand);

        MigrationCommand migrationCommand = new MigrationCommand(
                plugin,
                config,
                economyFacade,
                storageManager.getDatabaseManager(),
                redisManager,
                nameResolver);
        syncmoneyRouter.register("migrate", migrationCommand);

        AuditCommand auditCommand = new AuditCommand(plugin, auditLogger, hybridAuditManager, nameResolver, economyServiceManager.getLocalEconomyHandler());
        syncmoneyRouter.register("audit", auditCommand);

        BaltopCommand baltopCommand = new BaltopCommand(plugin, config, baltopManager, economyFacade);
        register("baltop", baltopCommand);

        if (config.isCircuitBreakerEnabled() && circuitBreaker != null) {
            BreakerCommand breakerCommand = new BreakerCommand(plugin, circuitBreaker);
            syncmoneyRouter.register("breaker", breakerCommand);
        }

        AdminCommand adminCommand = new AdminCommand(
                plugin,
                config,
                economyFacade,
                redisManager,
                pubsubSubscriber,
                circuitBreaker,
                nameResolver,
                baltopManager,
                auditLogger);
        syncmoneyRouter.register("admin", adminCommand);

        WebCommand webCommand = new WebCommand(plugin);
        syncmoneyRouter.register("web", webCommand);

        if (config.isShadowSyncEnabled() && shadowSyncTask != null) {
            ShadowCommand shadowCommand = new ShadowCommand(plugin, shadowSyncTask);
            syncmoneyRouter.register("shadow", shadowCommand);
        }

        MonitorCommand monitorCommand = new MonitorCommand(
                plugin,
                economyFacade,
                redisManager,
                cacheManager,
                storageManager.getDatabaseManager(),
                dbWriteQueue);
        syncmoneyRouter.register("monitor", monitorCommand);

        DebugEconomyCommand debugCommand = new DebugEconomyCommand(
                plugin,
                economyFacade,
                cacheManager,
                redisManager,
                storageManager.getDatabaseManager(),
                fallbackWrapper);
        syncmoneyRouter.register("debug", debugCommand);

        SyncBalanceCommand syncBalanceCommand = new SyncBalanceCommand(
                plugin,
                economyFacade,
                cacheManager,
                storageManager.getDatabaseManager());
        syncmoneyRouter.register("sync-balance", syncBalanceCommand);

        TestCommand testCommand = new TestCommand(plugin, economyFacade, redisManager);
        syncmoneyRouter.register("test", testCommand);

        if (baltopManager != null) {
            EconStatsCommand econStatsCommand = new EconStatsCommand(plugin, redisManager, baltopManager);
            syncmoneyRouter.register("econstats", econStatsCommand);
        }

        ReloadCommand reloadCommand = new ReloadCommand(plugin);
        syncmoneyRouter.register("reload", reloadCommand);

        syncmoneyRouter.setDefaultHandler((sender, cmd, label, args) -> {
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.header"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.migrate"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.audit"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.monitor"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.econstats"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.reload"));
            if (config.isCircuitBreakerEnabled()) {
                noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.breaker"));
            }
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.admin"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.web"));
            noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.test"));
            if (config.isShadowSyncEnabled()) {
                noietime.syncmoney.util.MessageHelper.sendMessage(sender, plugin.getMessage("router.help.shadow"));
            }
            return true;
        });

        register("syncmoney", syncmoneyRouter, syncmoneyRouter);
    }

    /**
     * Register a command with executor only.
     */
    public void register(String name, CommandExecutor executor) {
        registerCommand(name, executor, null);
    }

    /**
     * Register a command with executor and tab completer.
     */
    public void register(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        registerCommand(name, executor, tabCompleter);
    }

    /**
     * Unregister a command.
     */
    public void unregister(String name) {
        unregisterCommand(name);
    }

    private void registerCommand(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        try {
            var method = plugin.getClass().getDeclaredMethod("registerCommand", String.class, CommandExecutor.class, TabCompleter.class);
            method.setAccessible(true);
            method.invoke(plugin, name, executor, tabCompleter);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register command " + name + ": " + e.getMessage());
        }
    }

    private void unregisterCommand(String name) {
        try {
            var method = plugin.getClass().getDeclaredMethod("unregisterCommand", String.class);
            method.setAccessible(true);
            method.invoke(plugin, name);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to unregister command " + name + ": " + e.getMessage());
        }
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public TransferLockManager getTransferLockManager() {
        return transferLockManager;
    }

    public SyncmoneyCommandRouter getSyncmoneyRouter() {
        return syncmoneyRouter;
    }
}
