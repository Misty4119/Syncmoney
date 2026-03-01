package noietime.syncmoney;

import net.kyori.adventure.text.Component;
import noietime.syncmoney.command.CooldownManager;
import noietime.syncmoney.command.EconStatsCommand;
import noietime.syncmoney.command.MigrationCommand;
import noietime.syncmoney.command.MoneyCommand;
import noietime.syncmoney.command.MonitorCommand;
import noietime.syncmoney.command.PayCommand;
import noietime.syncmoney.command.ReloadCommand;
import noietime.syncmoney.command.TestCommand;
import noietime.syncmoney.command.SyncmoneyCommandRouter;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyEventConsumer;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyModeRouter;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DbWriterConsumer;
import noietime.syncmoney.sync.DebounceManager;
import noietime.syncmoney.sync.PubsubSubscriber;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import noietime.syncmoney.listener.PlayerJoinListener;
import noietime.syncmoney.listener.PlayerQuitListener;
import noietime.syncmoney.listener.CMIEconomyListener;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.guard.PlayerTransferGuard;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.command.AdminCommand;
import noietime.syncmoney.command.BreakerCommand;
import noietime.syncmoney.command.ShadowCommand;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.AuditCommand;
import noietime.syncmoney.audit.AuditLogCleanup;
import noietime.syncmoney.audit.AuditLogExporter;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.baltop.BaltopCommand;
import noietime.syncmoney.permission.AdminPermissionService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Syncmoney - Multi-server economic system plugin for Minecraft.
 *
 * Core initialization sequence:
 * 1. Config and messages loading
 * 2. Redis connection
 * 3. Cache layer initialization
 * 4. Database connection
 * 5. Async write queue setup
 * 6. Economy facade setup
 * 7. Vault integration
 * 8. Cross-server sync setup
 * 9. Command and listener registration
 *
 * [MainThread] onEnable/onDisable are called by server main thread.
 */
public final class Syncmoney extends JavaPlugin {


    private static Syncmoney instance;

    private SyncmoneyConfig syncmoneyConfig;
    private FileConfiguration messagesConfig;

    private final ConcurrentHashMap<String, Component> messageComponentCache = new ConcurrentHashMap<>();
    private static final int MESSAGE_COMPONENT_CACHE_MAX = 256;
    private RedisManager redisManager;
    private CacheManager cacheManager;
    private DatabaseManager databaseManager;
    private DbWriteQueue dbWriteQueue;
    private DbWriterConsumer dbWriterConsumer;

    private Thread dbWriterThread;

    /**
     * Debug-level log output.
     * Only outputs when debug: true is set.
     */
    private void debug(String message) {
        if (syncmoneyConfig != null && syncmoneyConfig.isDebug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private EconomyFacade economyFacade;
    private EconomyWriteQueue economyWriteQueue;
    private EconomyEventConsumer economyEventConsumer;
    private NameResolver nameResolver;
    private FallbackEconomyWrapper fallbackWrapper;
    private SyncmoneyVaultProvider vaultProvider;

    private PubsubSubscriber pubsubSubscriber;
    private DebounceManager debounceManager;

    private CrossServerSyncManager crossServerSyncManager;
    private EconomyModeRouter economyModeRouter;

    private MoneyCommand moneyCommand;
    private PayCommand payCommand;
    private MigrationCommand migrationCommand;
    private CooldownManager cooldownManager;
    private TransferLockManager transferLockManager;

    private PlayerJoinListener playerJoinListener;
    private PlayerQuitListener playerQuitListener;
    private CMIEconomyListener cmiListener;
    private CMIEconomyHandler cmiHandler;
    private PlayerTransferGuard playerTransferGuard;

    private ShadowSyncTask shadowSyncTask;

    private EconomicCircuitBreaker circuitBreaker;

    private AuditLogger auditLogger;
    private AuditCommand auditCommand;
    private AuditLogCleanup auditLogCleanup;
    private AuditLogExporter auditLogExporter;

    private BaltopManager baltopManager;
    private BaltopCommand baltopCommand;

    private AdminPermissionService permissionService;

    private SyncmoneyCommandRouter syncmoneyRouter;

    @Override
    public void onEnable() {
        instance = this;

        this.syncmoneyConfig = new SyncmoneyConfig(this);
        getLogger().log(Level.FINE, "Config loaded: server-name='" + syncmoneyConfig.getServerName()
                + "', queue-capacity=" + syncmoneyConfig.getQueueCapacity());

        if (syncmoneyConfig.getServerName() == null || syncmoneyConfig.getServerName().isBlank()) {
            getLogger().severe("CRITICAL: server-name is not configured! Please set server-name in config.yml");
            getLogger().severe("Plugin will disable. Please configure server-name and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (syncmoneyConfig.isShadowSyncEnabled()) {
            String target = syncmoneyConfig.getShadowSyncTarget();
            if (target.equals("cmi") || target.equals("all")) {
                String cmiHost = syncmoneyConfig.getCMIDatabaseHost();
                if (cmiHost != null && cmiHost.equalsIgnoreCase("localhost")) {
                    debug("CMI database host is set to 'localhost'. If running in Docker, consider using host IP.");
                }
            }
        }

        saveDefaultConfig();
        this.messagesConfig = getConfig("messages.yml");
        getLogger().log(Level.FINE, "Messages loaded.");

        this.redisManager = new RedisManager(this, syncmoneyConfig, syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL);

        this.cacheManager = new CacheManager(this, redisManager, syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL);

        EconomyMode mode = syncmoneyConfig.getEconomyMode();
        if (mode != EconomyMode.LOCAL && mode != EconomyMode.LOCAL_REDIS) {
            this.databaseManager = new DatabaseManager(this, syncmoneyConfig);
        } else {
            this.databaseManager = null;
            getLogger().fine("Database manager skipped (" + mode + " mode)");
        }

        this.dbWriteQueue = new DbWriteQueue(syncmoneyConfig.getQueueCapacity());

        this.dbWriterConsumer = new DbWriterConsumer(this, dbWriteQueue, databaseManager);

        this.dbWriterThread = new Thread(dbWriterConsumer, "Syncmoney-DbWriter");
        dbWriterThread.setDaemon(true);
        dbWriterThread.start();


        this.economyWriteQueue = new EconomyWriteQueue(syncmoneyConfig.getQueueCapacity(), getLogger());

        this.fallbackWrapper = new FallbackEconomyWrapper(this, redisManager, mode == EconomyMode.LOCAL);

        LocalEconomyHandler localHandler = null;
        if (mode == EconomyMode.LOCAL) {
            localHandler = new LocalEconomyHandler(this, syncmoneyConfig.getLocalSQLitePath());
            getLogger().fine("Local Economy Handler initialized (SQLite mode)");
        }

        if (localHandler != null) {
            this.economyFacade = new EconomyFacade(
                    this, syncmoneyConfig, cacheManager, redisManager,
                    databaseManager, localHandler, economyWriteQueue, fallbackWrapper);
        } else {
            this.economyFacade = new EconomyFacade(
                    this, syncmoneyConfig, cacheManager, redisManager,
                    databaseManager, economyWriteQueue, fallbackWrapper);
        }

        this.nameResolver = new NameResolver(this, cacheManager, databaseManager);

        this.shadowSyncTask = new ShadowSyncTask(
                this,
                syncmoneyConfig,
                economyFacade,
                cacheManager,
                databaseManager);
        shadowSyncTask.start();


        this.vaultProvider = new SyncmoneyVaultProvider(this, economyFacade, redisManager, nameResolver);

        if (vaultProvider.setupEconomy()) {
            getLogger().info("Syncmoney Vault Economy registered successfully.");
        } else {
            getLogger().warning("Vault Economy registration failed. Running without Vault integration.");
        }


        this.crossServerSyncManager = new CrossServerSyncManager(
                this, syncmoneyConfig, redisManager, economyFacade, cacheManager);

        vaultProvider.setSyncManager(crossServerSyncManager);
        vaultProvider.setConfig(syncmoneyConfig);
        getLogger().fine("CrossServerSyncManager initialized.");

        this.economyModeRouter = new EconomyModeRouter(this, syncmoneyConfig);

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

        economyModeRouter.initialize(
                wrapper,
                localHandler,
                null,
                crossServerSyncManager
        );
        getLogger().fine("EconomyModeRouter initialized: " + syncmoneyConfig.getEconomyMode());

        if (syncmoneyConfig.isCMIMode()) {
            this.cmiHandler = new CMIEconomyHandler(
                    this,
                    syncmoneyConfig,
                    redisManager,
                    crossServerSyncManager
            );
            economyModeRouter.setCmiHandler(cmiHandler);
            getLogger().fine("CMI Economy Handler initialized");

            this.cmiListener = new CMIEconomyListener(this, cmiHandler, syncmoneyConfig);
            getServer().getPluginManager().registerEvents(cmiListener, this);
            getLogger().fine("CMI Economy Listener registered");
        }


        this.debounceManager = new DebounceManager(this);

        this.pubsubSubscriber = new PubsubSubscriber(
                this,
                syncmoneyConfig,
                cacheManager,
                economyFacade,
                debounceManager,
                redisManager,
                syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL);

        getServer().getAsyncScheduler().runNow(this, (task) -> {
            if (syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL) {
                pubsubSubscriber.startSubscription();
            }
        });


        this.cooldownManager = new CooldownManager(this, syncmoneyConfig.getPayCooldownSeconds());

        this.transferLockManager = new TransferLockManager(this, redisManager, syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL);

        this.moneyCommand = new MoneyCommand(
                this,
                economyFacade,
                nameResolver,
                fallbackWrapper,
                syncmoneyConfig.getCurrencyName(),
                syncmoneyConfig.getDecimalPlaces());
        registerCommand("money", moneyCommand, moneyCommand);

        this.payCommand = new PayCommand(
                this,
                syncmoneyConfig,
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
                syncmoneyConfig.getPayMinAmount(),
                syncmoneyConfig.getPayMaxAmount(),
                syncmoneyConfig.isPayAllowedInDegraded(),
                syncmoneyConfig.isLocalMode());
        registerCommand("pay", payCommand, payCommand);


        this.migrationCommand = new MigrationCommand(
                this,
                syncmoneyConfig,
                economyFacade,
                databaseManager,
                redisManager,
                nameResolver);

        getLogger().log(Level.FINE, "Commands registered: /money, /pay, /syncmoney migrate");


        this.playerJoinListener = new PlayerJoinListener(
                this,
                economyFacade,
                nameResolver,
                baltopManager);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);

        this.playerQuitListener = new PlayerQuitListener(
                this,
                economyFacade,
                nameResolver);
        getServer().getPluginManager().registerEvents(playerQuitListener, this);

        if (syncmoneyConfig.isTransferGuardEnabled()) {
            this.playerTransferGuard = new PlayerTransferGuard(
                    this,
                    economyFacade,
                    economyWriteQueue);
            getServer().getPluginManager().registerEvents(playerTransferGuard, this);
            getLogger().log(Level.FINE,
                    "Transfer guard enabled (max wait: " + syncmoneyConfig.getTransferGuardMaxWaitMs() + "ms)");
        }

        getLogger().log(Level.FINE, "Player lifecycle listeners registered.");


        this.auditLogger = new AuditLogger(this, syncmoneyConfig,
                databaseManager != null ? databaseManager.getDataSource() : null);

        this.auditCommand = new AuditCommand(this, syncmoneyConfig, auditLogger, nameResolver, localHandler);

        getLogger().log(Level.FINE, "Audit commands registered: /syncmoney audit");

        if (databaseManager != null) {
            this.auditLogCleanup = new AuditLogCleanup(this, syncmoneyConfig, databaseManager.getDataSource());
            auditLogCleanup.start();

            this.auditLogExporter = new AuditLogExporter(this, syncmoneyConfig, databaseManager.getDataSource());
            auditLogExporter.start();
        }


        this.baltopManager = new BaltopManager(this, syncmoneyConfig,
                redisManager, nameResolver,
                databaseManager != null ? databaseManager.getDataSource() : null,
                localHandler);

        this.baltopCommand = new BaltopCommand(this, syncmoneyConfig, baltopManager, economyFacade);
        registerCommand("baltop", baltopCommand);


        this.economyEventConsumer = new EconomyEventConsumer(
                this, syncmoneyConfig, economyWriteQueue, cacheManager,
                redisManager, dbWriteQueue, auditLogger, nameResolver, baltopManager,
                shadowSyncTask);

        getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            if (economyEventConsumer != null && economyEventConsumer.isRunning()) {
                economyEventConsumer.processPending();
            }
        }, 1, 50, TimeUnit.MILLISECONDS);

        getLogger().log(Level.FINE, "EconomyEventConsumer started with Folia AsyncScheduler.");


        this.circuitBreaker = new EconomicCircuitBreaker(
                this,
                syncmoneyConfig,
                economyFacade,
                redisManager,
                syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL);

        if (circuitBreaker != null && circuitBreaker.getConnectionStateManager() != null) {
            circuitBreaker.getConnectionStateManager()
                    .setCallback(new noietime.syncmoney.breaker.ConnectionStateManager.ConnectionStateCallback() {
                        @Override
                        public void onConnectionRestored() {
                            if (fallbackWrapper != null) {
                                fallbackWrapper.updateDegradedState();
                            }
                        }

                        @Override
                        public void onConnectionLost(long disconnectDuration) {
                        }
                    });
        }

        getServer().getAsyncScheduler().runAtFixedRate(
                this,
                (task) -> circuitBreaker.performPeriodicCheck(),
                1,
                1,
                java.util.concurrent.TimeUnit.MILLISECONDS);

        getLogger().log(Level.FINE, "Economic circuit breaker initialized.");

        this.syncmoneyRouter = new SyncmoneyCommandRouter(this);

        if (syncmoneyConfig.isCircuitBreakerEnabled()) {
            BreakerCommand breakerCommand = new BreakerCommand(this, circuitBreaker);
            syncmoneyRouter.register("breaker", breakerCommand);
            getLogger().log(Level.FINE, "Breaker commands registered.");
        }

        getServer().getAsyncScheduler().runAtFixedRate(
                this,
                (task) -> {
                    if (economyFacade != null) {
                        economyFacade.cleanupExpiredEntries();
                    }
                },
                5,
                5,
                java.util.concurrent.TimeUnit.MINUTES);
        getLogger().log(Level.FINE, "Memory cleanup scheduler initialized.");

        getServer().getAsyncScheduler().runAtFixedRate(
                this,
                (task) -> {
                    if (baltopManager != null) {
                        baltopManager.saveToDatabase();
                    }
                },
                10,
                10,
                java.util.concurrent.TimeUnit.MINUTES);
        getLogger().log(Level.FINE, "Baltop database save scheduler initialized.");


        this.permissionService = new AdminPermissionService(syncmoneyConfig);

        AdminCommand adminCommand = new AdminCommand(
                this,
                syncmoneyConfig,
                economyFacade,
                redisManager,
                pubsubSubscriber,
                circuitBreaker,
                nameResolver,
                baltopManager,
                auditLogger);
        getLogger().log(Level.FINE, "Admin command registered: /syncmoney admin");


        syncmoneyRouter.register("admin", adminCommand);

        syncmoneyRouter.register("migrate", migrationCommand);

        syncmoneyRouter.register("audit", auditCommand);

        if (syncmoneyConfig.isShadowSyncEnabled()) {
            ShadowCommand shadowCommand = new ShadowCommand(this, shadowSyncTask);
            syncmoneyRouter.register("shadow", shadowCommand);
        }

        MonitorCommand monitorCommand = new MonitorCommand(
                this,
                economyFacade,
                redisManager,
                cacheManager,
                databaseManager,
                dbWriteQueue);
        syncmoneyRouter.register("monitor", monitorCommand);
        TestCommand testCommand = new TestCommand(this, economyFacade, redisManager);
        syncmoneyRouter.register("test", testCommand);

        getLogger().log(Level.FINE, "Monitor commands registered.");

        if (baltopManager != null) {
            EconStatsCommand econStatsCommand = new EconStatsCommand(this, redisManager, baltopManager);
            syncmoneyRouter.register("econstats", econStatsCommand);
            getLogger().log(Level.FINE, "EconStats commands registered.");
        }

        ReloadCommand reloadCommand = new ReloadCommand(this);
        syncmoneyRouter.register("reload", reloadCommand);
        getLogger().log(Level.FINE, "Reload commands registered.");

        syncmoneyRouter.setDefaultHandler((sender, cmd, label, args) -> {
            MessageHelper.sendMessage(sender, this.getMessage("router.help.header"));
            MessageHelper.sendMessage(sender, this.getMessage("router.help.migrate"));
            MessageHelper.sendMessage(sender, this.getMessage("router.help.audit"));
            MessageHelper.sendMessage(sender, this.getMessage("router.help.monitor"));
            MessageHelper.sendMessage(sender, this.getMessage("router.help.econstats"));
            MessageHelper.sendMessage(sender, this.getMessage("router.help.reload"));
            if (syncmoneyConfig.isCircuitBreakerEnabled()) {
                MessageHelper.sendMessage(sender, this.getMessage("router.help.breaker"));
            }
            MessageHelper.sendMessage(sender, this.getMessage("router.help.admin"));
            MessageHelper.sendMessage(sender, this.getMessage("router.help.test"));
            if (syncmoneyConfig.isShadowSyncEnabled()) {
                MessageHelper.sendMessage(sender, this.getMessage("router.help.shadow"));
            }
            return true;
        });

        registerCommand("syncmoney", syncmoneyRouter, syncmoneyRouter);

        getLogger().log(Level.FINE, "Syncmoney command router initialized.");

        getLogger().log(Level.FINE, "Syncmoney Economy system enabled.");
        getLogger().info("Syncmoney enabled successfully.");

        initBstats();
    }

    @Override
    public void onDisable() {
        if (pubsubSubscriber != null) {
            pubsubSubscriber.stopSubscription();
        }

        if (economyEventConsumer != null) {
            economyEventConsumer.stop();
        }

        if (dbWriterConsumer != null) {
            dbWriterConsumer.stop();
        }
        if (dbWriterThread != null) {
            dbWriterThread.interrupt();
            try {
                dbWriterThread.join(3000);
            } catch (InterruptedException ignored) {
            }
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        if (shadowSyncTask != null) {
            shadowSyncTask.stop();
        }

        if (circuitBreaker != null && circuitBreaker.getConnectionStateManager() != null) {
            circuitBreaker.getConnectionStateManager().shutdown();
        }

        if (redisManager != null) {
            redisManager.close();
        }

        getLogger().info("Syncmoney disabled.");
    }

    /**
     * Initializes bStats metrics (uses reflection to avoid class loading conflicts).
     */
    private void initBstats() {
        try {
            Class<?> metricsClass = Class.forName("noietime.libs.bstats.bukkit.Metrics");
            java.lang.reflect.Constructor<?> constructor = metricsClass.getConstructor(org.bukkit.plugin.Plugin.class,
                    int.class);
            constructor.newInstance(this, 29672);
            getLogger().log(Level.FINE, "bStats initialized: pluginId=29672");
        } catch (Exception e) {
            getLogger().warning("Failed to initialize bStats: " + e.getMessage());
        }
    }


    public SyncmoneyConfig getSyncmoneyConfig() {
        return syncmoneyConfig;
    }


    public RedisManager getRedisManager() {
        return redisManager;
    }


    public CacheManager getCacheManager() {
        return cacheManager;
    }


    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }


    public DbWriteQueue getDbWriteQueue() {
        return dbWriteQueue;
    }


    public EconomyFacade getEconomyFacade() {
        return economyFacade;
    }


    public NameResolver getNameResolver() {
        return nameResolver;
    }


    public FallbackEconomyWrapper getFallbackWrapper() {
        return fallbackWrapper;
    }


    public SyncmoneyVaultProvider getVaultProvider() {
        return vaultProvider;
    }


    public CrossServerSyncManager getCrossServerSyncManager() {
        return crossServerSyncManager;
    }


    public EconomyModeRouter getEconomyModeRouter() {
        return economyModeRouter;
    }


    public PubsubSubscriber getPubsubSubscriber() {
        return pubsubSubscriber;
    }


    public DebounceManager getDebounceManager() {
        return debounceManager;
    }


    public PlayerJoinListener getPlayerJoinListener() {
        return playerJoinListener;
    }


    public PlayerQuitListener getPlayerQuitListener() {
        return playerQuitListener;
    }


    public PlayerTransferGuard getPlayerTransferGuard() {
        return playerTransferGuard;
    }


    public ShadowSyncTask getShadowSyncTask() {
        return shadowSyncTask;
    }


    public AuditLogger getAuditLogger() {
        return auditLogger;
    }


    public BaltopManager getBaltopManager() {
        return baltopManager;
    }


    public static Syncmoney getInstance() {
        return instance;
    }


    public AdminPermissionService getPermissionService() {
        return permissionService;
    }


    public EconomicCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Loads additional YAML configuration files.
     *
     * @param name configuration file name
     * @return FileConfiguration
     */
    private FileConfiguration getConfig(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        return null;
    }

    /**
     * Gets the message string.
     *
     * @param key message key (e.g., "migration.started")
     * @return message string, returns key if not found
     */
    public String getMessage(String key) {
        if (messagesConfig == null) {
            return key;
        }
        String message = messagesConfig.getString(key);
        if (message == null) {
            return key;
        }
        String prefix = messagesConfig.getString("prefix", "[Syncmoney] ");
        return message.replace("{prefix}", prefix);
    }

    /**
     * Gets the parsed Component (supports minimessage format).
     * Uses message key cache to avoid repeated parsing causing TextColorReplacerImpl object accumulation.
     *
     * @param key message key
     * @return parsed Component
     */
    public Component getMessageComponent(String key) {
        Component cached = messageComponentCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (messageComponentCache.size() >= MESSAGE_COMPONENT_CACHE_MAX) {
            messageComponentCache.clear();
        }
        String message = getMessage(key);
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        Component component = MessageHelper.getComponent(message);
        messageComponentCache.put(key, component);
        return component;
    }

    /**
     * Gets the parsed Component with variable replacement (supports minimessage format).
     * Uses cached template Component for variable replacement to avoid repeated parsing.
     *
     * @param key       message key
     * @param variables variable map
     * @return parsed Component
     */
    public Component getMessageComponent(String key, java.util.Map<String, String> variables) {
        Component template = getMessageComponent(key);
        if (template == null || template.equals(Component.empty())) {
            return template;
        }
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        return MessageHelper.replaceVariables(template, variables);
    }

    /**
     * Reloads messages.yml configuration.
     *
     * @return true if successful
     */
    public boolean reloadMessagesConfig() {
        try {
            this.messagesConfig = getConfig("messages.yml");
            messageComponentCache.clear();
            MessageHelper.clearComponentCache();
            getLogger().fine("Messages configuration reloaded.");
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to reload messages: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns the current number of message Component cache entries (for monitoring purposes).
     */
    public int getMessageComponentCacheSize() {
        return messageComponentCache.size();
    }

    /**
     * Reloads configuration.
     */
    public boolean reloadPluginConfig() {
        try {
            reloadConfig();

            this.syncmoneyConfig = new SyncmoneyConfig(this);

            getLogger().fine("Configuration reloaded.");
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to reload config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reloads SyncmoneyConfig (includes all configuration).
     */
    public void reloadSyncmoneyConfig() {
        this.syncmoneyConfig = new SyncmoneyConfig(this);

        // Recreate CooldownManager with new cooldown seconds
        this.cooldownManager = new CooldownManager(this, syncmoneyConfig.getPayCooldownSeconds());
        getLogger().fine("CooldownManager reloaded with " + syncmoneyConfig.getPayCooldownSeconds() + "s cooldown.");

        // Recreate PayCommand with new configuration
        recreatePayCommand();

        getLogger().fine("SyncmoneyConfig fully reloaded (including commands).");
    }

    /**
     * Recreates PayCommand with fresh configuration.
     */
    private void recreatePayCommand() {
        // Unregister existing pay command first
        unregisterCommand("pay");

        // Create new PayCommand with updated config
        this.payCommand = new PayCommand(
                this,
                syncmoneyConfig,
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
                syncmoneyConfig.getPayMinAmount(),
                syncmoneyConfig.getPayMaxAmount(),
                syncmoneyConfig.isPayAllowedInDegraded(),
                syncmoneyConfig.isLocalMode());
        registerCommand("pay", payCommand, payCommand);
        getLogger().info("PayCommand recreated with min=" + syncmoneyConfig.getPayMinAmount()
                + ", max=" + syncmoneyConfig.getPayMaxAmount());
    }

    /**
     * Unregisters a command by name.
     */
    private void unregisterCommand(String name) {
        try {
            CommandMap commandMap = getCommandMap();
            if (commandMap == null) {
                getLogger().warning("CommandMap not available, cannot unregister: " + name);
                return;
            }

            // Get the known commands and remove this one
            SimpleCommandMap simpleCommandMap = (SimpleCommandMap) commandMap;
            java.lang.reflect.Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(simpleCommandMap);
            knownCommands.remove(name);
            knownCommands.remove("syncmoney:" + name); // Also remove namespaced version
            getLogger().log(Level.FINE, "Command unregistered: /" + name);
        } catch (Exception e) {
            getLogger().warning("Failed to unregister command " + name + ": " + e.getMessage());
        }
    }

    /**
     * Reloads permission service.
     */
    public void reloadPermissionService() {
        this.permissionService = new AdminPermissionService(syncmoneyConfig);
        getLogger().fine("Permission service reloaded.");
    }

    /**
     * Reloads economy facade related services.
     */
    public void reloadEconomyFacade() {
        if (vaultProvider != null) {
            vaultProvider.setConfig(syncmoneyConfig);
        }
        if (crossServerSyncManager != null) {
            crossServerSyncManager.shutdown();
            getLogger().warning("CrossServerSyncManager config changed, some changes require server restart.");
        }
        getLogger().info("Economy facade services config updated.");
    }

    /**
     * Gets CommandMap (via reflection).
     */
    private CommandMap getCommandMap() {
        try {
            Field commandMapField = getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            return (CommandMap) commandMapField.get(getServer());
        } catch (Exception e) {
            getLogger().severe("Failed to get CommandMap: " + e.getMessage());
            return null;
        }
    }

    /**
     * Safely registers command using CommandMap manual registration.
     */
    private void registerCommand(String name, CommandExecutor executor) {
        try {
            CommandMap commandMap = getCommandMap();
            if (commandMap == null) {
                getLogger().warning("CommandMap not available, using fallback getCommand()");
                registerCommandFallback(name, executor);
                return;
            }

            PluginCommand cmd = getCommand(name, executor, null);
            if (cmd != null) {
                commandMap.register(name, cmd);
                getLogger().log(Level.FINE, "Command registered via CommandMap: /" + name);
            } else {
                getLogger().warning("Failed to create command: " + name);
            }
        } catch (Exception e) {
            getLogger().severe("Error registering command " + name + ": " + e.getMessage());
        }
    }

    /**
     * Safely registers command (with Tab completion) using CommandMap.
     */
    private void registerCommand(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        try {
            CommandMap commandMap = getCommandMap();
            if (commandMap == null) {
                getLogger().warning("CommandMap not available, using fallback getCommand()");
                registerCommandFallback(name, executor, tabCompleter);
                return;
            }

            PluginCommand cmd = getCommand(name, executor, tabCompleter);
            if (cmd != null) {
                commandMap.register(name, cmd);
                getLogger().log(Level.FINE, "Command registered via CommandMap: /" + name);
            } else {
                getLogger().warning("Failed to create command: " + name);
            }
        } catch (Exception e) {
            getLogger().severe("Error registering command " + name + ": " + e.getMessage());
        }
    }

    /**
     * Creates command object.
     */
    private PluginCommand getCommand(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        try {
            java.lang.reflect.Constructor<PluginCommand> constructor = PluginCommand.class
                    .getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(name, this);
            cmd.setExecutor(executor);
            if (tabCompleter != null) {
                cmd.setTabCompleter(tabCompleter);
            }

            List<String> aliases = getCommandAliases(name);
            if (!aliases.isEmpty()) {
                cmd.setAliases(aliases);
            }

            return cmd;
        } catch (Exception e) {
            getLogger().severe("Error creating command " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets command aliases.
     */
    private List<String> getCommandAliases(String name) {
        try {
            File aliasFile = new File(getDataFolder(), "alias.yml");
            if (aliasFile.exists()) {
                FileConfiguration aliasConfig = YamlConfiguration.loadConfiguration(aliasFile);
                if (aliasConfig.contains(name.toLowerCase() + ".aliases")) {
                    List<String> aliases = aliasConfig.getStringList(name.toLowerCase() + ".aliases");
                    if (!aliases.isEmpty()) {
                        getLogger().log(Level.FINE, "Loaded aliases for " + name + ": " + aliases);
                        return aliases;
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load alias.yml: " + e.getMessage());
        }

        return switch (name.toLowerCase()) {
            case "money" -> List.of("balance", "bal", "balancetop");
            case "pay" -> List.of("transfer", "send", "paymoney");
            case "baltop" -> List.of("topmoney", "richest", "wealth");
            case "syncmoney" -> List.of("sync", "sm");
            default -> List.of();
        };
    }

    /**
     * Fallback: registers using getCommand() when CommandMap is not available.
     */
    private void registerCommandFallback(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
        } else {
            getLogger().warning("Command '" + name + "' not found in plugin.yml");
        }
    }

    /**
     * Fallback: registers using getCommand() (with Tab completion).
     */
    private void registerCommandFallback(String name, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (tabCompleter != null) {
                cmd.setTabCompleter(tabCompleter);
            }
        } else {
            getLogger().warning("Command '" + name + "' not found in plugin.yml");
        }
    }
}
