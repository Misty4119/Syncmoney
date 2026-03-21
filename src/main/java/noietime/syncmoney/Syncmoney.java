package noietime.syncmoney;

import net.kyori.adventure.text.Component;
import noietime.syncmoney.command.CommandServiceManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.economy.EconomyModeRouter;
import noietime.syncmoney.economy.EconomyServiceManager;
import noietime.syncmoney.economy.EventConsumerManager;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.StorageManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.schema.SchemaManager;
import noietime.syncmoney.listener.ListenerServiceManager;
import noietime.syncmoney.listener.PlayerJoinListener;
import noietime.syncmoney.listener.PlayerQuitListener;
import noietime.syncmoney.guard.PlayerTransferGuard;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.audit.AuditServiceManager;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.permission.PermissionManager;
import noietime.syncmoney.permission.AdminPermissionService;
import noietime.syncmoney.breaker.BreakerManager;
import noietime.syncmoney.sync.SyncManager;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.web.WebServiceManager;
import noietime.syncmoney.web.server.WebAdminServer;
import noietime.syncmoney.web.server.WebAdminConfig;
import noietime.syncmoney.util.ConfigMerger;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.MessageService;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import noietime.syncmoney.breaker.NotificationService;
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
import java.util.List;
import java.util.Map;
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

    private SyncmoneyConfig syncmoneyConfig;
    private MessageService messageService;

    private StorageManager storageManager;
    private EconomyServiceManager economyServiceManager;
    private ListenerServiceManager listenerServiceManager;
    private AuditServiceManager auditServiceManager;
    private CommandServiceManager commandServiceManager;
    private WebServiceManager webServiceManager;
    private BaltopManager baltopManager;

    private SyncManager syncManager;
    private BreakerManager breakerManager;
    private PermissionManager permissionManager;
    private EventConsumerManager eventConsumerManager;
    private SchemaManager schemaManager;

    private long startTime;
    private PluginContext pluginContext;

    /**
     * Logs a debug message when debug mode is enabled in config.
     *
     * @param message debug message to log
     */
    private void debug(String message) {
        if (syncmoneyConfig != null && syncmoneyConfig.isDebug()) {
            getLogger().fine(message);
        }
    }

    @Override
    public void onEnable() {
        this.startTime = System.currentTimeMillis();

        this.syncmoneyConfig = new SyncmoneyConfig(this);
        getLogger().log(Level.FINE, "Config loaded: server-name='" + syncmoneyConfig.getServerName()
                + "', queue-capacity=" + syncmoneyConfig.getQueueCapacity());

        try {
            syncmoneyConfig.validate();
        } catch (IllegalArgumentException e) {
            getLogger().severe("Configuration validation failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (syncmoneyConfig.getServerName() == null || syncmoneyConfig.getServerName().isBlank()) {
            getLogger().severe("CRITICAL: server-name is not configured! Please set server-name in config.yml");
            getLogger().severe("Plugin will disable. Please configure server-name and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (syncmoneyConfig.shadowSync().isShadowSyncEnabled()) {
            String target = syncmoneyConfig.shadowSync().getShadowSyncTarget();
            if (target.equals("cmi") || target.equals("all")) {
                String cmiHost = syncmoneyConfig.migration().getCMIDatabaseHost();
                if (cmiHost != null && cmiHost.equalsIgnoreCase("localhost")) {
                    debug("CMI database host is set to 'localhost'. If running in Docker, consider using host IP.");
                }
            }
        }

        ConfigMerger configMerger = new ConfigMerger(this, "config.yml", "messages.yml");
        List<String> mergedFiles = configMerger.mergeAll();
        if (!mergedFiles.isEmpty()) {
            getLogger().info("Config migration completed for: " + String.join(", ", mergedFiles));
            reloadConfig();
            this.syncmoneyConfig = new SyncmoneyConfig(this);
            getLogger().info("Config reloaded after migration.");
        }

        getLogger().log(Level.FINE, "Messages loaded.");
        this.messageService = new MessageService(this);
        messageService.loadMessages();

        this.storageManager = new StorageManager(this, syncmoneyConfig);
        storageManager.initialize();

        this.economyServiceManager = new EconomyServiceManager(this, syncmoneyConfig, storageManager);
        economyServiceManager.initialize();

        this.syncManager = new SyncManager(
                this,
                syncmoneyConfig,
                storageManager.getCacheManager(),
                economyServiceManager.getEconomyFacade(),
                storageManager.getRedisManager());
        syncManager.initialize();
        syncManager.startPeriodicVersionCheck();

        if (syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL) {
            getServer().getAsyncScheduler().runNow(this, (task) -> {
                syncManager.getPubsubSubscriber().startSubscription();
            });
        }

        this.breakerManager = new BreakerManager(
                this,
                syncmoneyConfig,
                economyServiceManager.getEconomyFacade(),
                storageManager.getRedisManager());
        breakerManager.initialize();

        if (breakerManager.getDiscordWebhookNotifier() != null) {
            economyServiceManager.setDiscordWebhookNotifier(breakerManager.getDiscordWebhookNotifier());
        }

        if (economyServiceManager.getNameResolver() != null) {
            breakerManager.setNameResolver(economyServiceManager.getNameResolver());
        }

        var economyFacade = economyServiceManager.getEconomyFacade();
        if (economyFacade != null) {
            economyFacade.setNameResolver(economyServiceManager.getNameResolver());
        }

        if (breakerManager.getCircuitBreaker() != null &&
                breakerManager.getCircuitBreaker().getConnectionStateManager() != null) {
            breakerManager.getCircuitBreaker().getConnectionStateManager()
                    .setCallback(new noietime.syncmoney.breaker.ConnectionStateManager.ConnectionStateCallback() {
                        @Override
                        public void onConnectionRestored() {
                            var wrapper = economyServiceManager.getFallbackWrapper();
                            if (wrapper != null) {
                                wrapper.updateDegradedState();
                            }
                        }

                        @Override
                        public void onConnectionLost(long disconnectDuration) {
                        }
                    });
        }

        if (syncmoneyConfig.getEconomyMode() != EconomyMode.LOCAL_REDIS || storageManager.getRedisManager() != null) {
            this.baltopManager = new BaltopManager(
                    this,
                    syncmoneyConfig,
                    storageManager.getRedisManager(),
                    economyServiceManager.getNameResolver(),
                    storageManager.getDatabaseManager() != null ? storageManager.getDatabaseManager().getDataSource()
                            : null,
                    economyServiceManager.getLocalEconomyHandler());
            getLogger().fine("BaltopManager initialized");

            if (economyServiceManager.getLocalEconomyHandler() != null) {
                economyServiceManager.getLocalEconomyHandler().setBaltopManager(baltopManager);
            }
        }

        if (syncmoneyConfig.isCMIMode()) {
            CMIEconomyHandler cmiHandler = new CMIEconomyHandler(
                    this,
                    syncmoneyConfig,
                    storageManager.getRedisManager(),
                    economyServiceManager.getCrossServerSyncManager());
            economyServiceManager.getEconomyModeRouter().setCmiHandler(cmiHandler);
            getLogger().fine("CMI Economy Handler initialized");
        }

        this.listenerServiceManager = new ListenerServiceManager(this, economyServiceManager);
        listenerServiceManager.initialize();
        getLogger().log(Level.FINE, "Player lifecycle listeners registered.");

        this.auditServiceManager = new AuditServiceManager(
                this, syncmoneyConfig,
                storageManager.getRedisManager(),
                storageManager.getDatabaseManager() != null ? storageManager.getDatabaseManager().getDataSource()
                        : null);
        auditServiceManager.initialize();

        if (storageManager.getDatabaseManager() != null) {
            this.schemaManager = new SchemaManager(
                    this,
                    storageManager.getDatabaseManager().getDataSource(),
                    syncmoneyConfig.database().getDatabaseType());
            getLogger().fine("SchemaManager initialized (version " + this.schemaManager.getDatabaseVersion() + ")");
        }

        this.eventConsumerManager = new EventConsumerManager(
                this,
                syncmoneyConfig,
                economyServiceManager.getEconomyWriteQueue(),
                storageManager.getCacheManager(),
                storageManager.getRedisManager(),
                storageManager.getDbWriteQueue(),
                auditServiceManager.getAuditLogger(),
                auditServiceManager.getHybridAuditManager(),
                economyServiceManager.getNameResolver(),
                baltopManager,
                economyServiceManager.getShadowSyncTask(),
                economyServiceManager.getOverflowLog(),
                storageManager.getDatabaseManager());
        eventConsumerManager.initialize();

        Thread eventConsumerThread = new Thread(eventConsumerManager.getEconomyEventConsumer(), "Syncmoney-EventConsumer");
        eventConsumerThread.start();
        getLogger().info("EconomyEventConsumer started");

        this.commandServiceManager = new CommandServiceManager(
                this,
                syncmoneyConfig,
                economyServiceManager.getEconomyFacade(),
                storageManager,
                economyServiceManager,
                syncManager.getPubsubSubscriber(),
                baltopManager,
                auditServiceManager.getAuditLogger(),
                auditServiceManager.getHybridAuditManager(),
                economyServiceManager.getShadowSyncTask(),
                breakerManager.getCircuitBreaker(),
                economyServiceManager.getNameResolver());
        commandServiceManager.initialize();
        getLogger().log(Level.FINE, "Commands registered: /money, /pay, /syncmoney migrate");

        this.permissionManager = new PermissionManager(this, syncmoneyConfig);
        permissionManager.initialize();
        getLogger().log(Level.FINE, "Permission service initialized.");

        registerCommand("syncmoney", commandServiceManager.getSyncmoneyRouter(),
                commandServiceManager.getSyncmoneyRouter());
        getLogger().log(Level.FINE, "Syncmoney command router initialized.");

        SyncmoneyEventBus.init(this);
        getLogger().fine("Event Bus initialized.");

        this.webServiceManager = new WebServiceManager(this, syncmoneyConfig,
                economyServiceManager.getEconomyFacade(), baltopManager,
                auditServiceManager.getAuditLogger(), auditServiceManager.getHybridAuditManager());
        webServiceManager.setDependencies(
                storageManager.getRedisManager(),
                storageManager.getDatabaseManager(),
                breakerManager.getCircuitBreaker(),
                this.schemaManager);
        webServiceManager.setNameResolver(economyServiceManager.getNameResolver());
        webServiceManager.setLocalEconomyHandler(economyServiceManager.getLocalEconomyHandler());
        webServiceManager.initialize();

        var hybridAuditManager = auditServiceManager.getHybridAuditManager();
        var webAdminServer = getWebAdminServer();
        if (hybridAuditManager != null && webAdminServer != null && webAdminServer.getSseManager() != null) {
            hybridAuditManager.setSseManager(webAdminServer.getSseManager());
            getLogger().fine("SseManager injected into HybridAuditManager (real-time audit SSE enabled).");
        }

        var localEconomyHandler = economyServiceManager.getLocalEconomyHandler();
        if (localEconomyHandler != null && webAdminServer != null && webAdminServer.getSseManager() != null) {
            localEconomyHandler.setSseManager(webAdminServer.getSseManager());
            getLogger().fine("SseManager injected into LocalEconomyHandler (LOCAL mode real-time audit SSE enabled).");
        }

        if (breakerManager != null && webAdminServer != null && webAdminServer.getSseManager() != null) {
            breakerManager.setSseManager(webAdminServer.getSseManager());
            getLogger().fine("SseManager injected into BreakerManager (circuit breaker & security alerts enabled).");
        }

        if (breakerManager != null) {
            breakerManager.setDiscordWebhookNotifier(breakerManager.getDiscordWebhookNotifier());
            getLogger().fine("DiscordWebhookNotifier injected into BreakerManager (resource alerts enabled).");
        }

        initializePluginContext();

        getLogger().log(Level.FINE, "Syncmoney Economy system enabled.");
        getLogger().info("Syncmoney enabled successfully.");

        initBstats();
    }

    @Override
    public void onDisable() {

        if (webServiceManager != null) {
            webServiceManager.shutdown();
        }

        if (commandServiceManager != null) {
            commandServiceManager.shutdown();
        }

        if (permissionManager != null) {
            permissionManager.shutdown();
        }

        if (listenerServiceManager != null) {
            listenerServiceManager.shutdown();
        }

        if (auditServiceManager != null) {
            auditServiceManager.shutdown();
        }

        if (breakerManager != null) {
            breakerManager.shutdown();
        }

        if (syncManager != null) {
            syncManager.shutdown();
        }

        if (economyServiceManager != null) {
            economyServiceManager.shutdown();
        }

        if (eventConsumerManager != null) {
            eventConsumerManager.shutdown();
        }

        if (baltopManager != null) {
            baltopManager.saveToDatabase();
        }

        if (storageManager != null) {
            storageManager.shutdown();
        }

        if (SyncmoneyEventBus.isInitialized()) {
            SyncmoneyEventBus.getInstance().clearAll();
        }

        getLogger().info("Syncmoney disabled.");
    }

    /**
     * Initializes bStats metrics (uses reflection to avoid class loading
     * conflicts).
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
        return storageManager != null ? storageManager.getRedisManager() : null;
    }

    public CacheManager getCacheManager() {
        return storageManager != null ? storageManager.getCacheManager() : null;
    }

    public DatabaseManager getDatabaseManager() {
        return storageManager != null ? storageManager.getDatabaseManager() : null;
    }

    public DbWriteQueue getDbWriteQueue() {
        return storageManager != null ? storageManager.getDbWriteQueue() : null;
    }

    public EconomyFacade getEconomyFacade() {
        return economyServiceManager != null ? economyServiceManager.getEconomyFacade() : null;
    }

    public NameResolver getNameResolver() {
        return economyServiceManager != null ? economyServiceManager.getNameResolver() : null;
    }

    public FallbackEconomyWrapper getFallbackWrapper() {
        return economyServiceManager != null ? economyServiceManager.getFallbackWrapper() : null;
    }

    public SyncmoneyVaultProvider getVaultProvider() {
        return economyServiceManager != null ? economyServiceManager.getVaultProvider() : null;
    }

    public CrossServerSyncManager getCrossServerSyncManager() {
        return economyServiceManager != null ? economyServiceManager.getCrossServerSyncManager() : null;
    }

    public EconomyModeRouter getEconomyModeRouter() {
        return economyServiceManager != null ? economyServiceManager.getEconomyModeRouter() : null;
    }

    public SyncManager getSyncManager() {
        return syncManager;
    }

    public BreakerManager getBreakerManager() {
        return breakerManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public PlayerJoinListener getPlayerJoinListener() {
        return listenerServiceManager != null ? listenerServiceManager.getPlayerJoinListener() : null;
    }

    public PlayerQuitListener getPlayerQuitListener() {
        return listenerServiceManager != null ? listenerServiceManager.getPlayerQuitListener() : null;
    }

    public PlayerTransferGuard getPlayerTransferGuard() {
        return listenerServiceManager != null ? listenerServiceManager.getPlayerTransferGuard() : null;
    }

    public ShadowSyncTask getShadowSyncTask() {
        return economyServiceManager != null ? economyServiceManager.getShadowSyncTask() : null;
    }

    public AuditLogger getAuditLogger() {
        return auditServiceManager != null ? auditServiceManager.getAuditLogger() : null;
    }

    public BaltopManager getBaltopManager() {
        return baltopManager;
    }

    public SchemaManager getSchemaManager() {
        return schemaManager;
    }

    public AdminPermissionService getPermissionService() {
        return permissionManager != null ? permissionManager.getPermissionService() : null;
    }

    public EconomicCircuitBreaker getCircuitBreaker() {
        return breakerManager != null ? breakerManager.getCircuitBreaker() : null;
    }

    /**
     * Returns the plugin start time in milliseconds.
     * Used for uptime calculation in SystemApiHandler.
     */
    public long getStartTime() {
        return startTime;
    }

    public WebAdminServer getWebAdminServer() {
        return webServiceManager != null ? webServiceManager.getWebAdminServer() : null;
    }

    public WebAdminConfig getWebAdminConfig() {
        return webServiceManager != null ? webServiceManager.getWebAdminConfig() : null;
    }

    public noietime.syncmoney.breaker.PlayerTransactionGuard getPlayerTransactionGuard() {
        return breakerManager != null ? breakerManager.getPlayerTransactionGuard() : null;
    }

    public noietime.syncmoney.breaker.NotificationService getNotificationService() {
        return breakerManager != null ? breakerManager.getNotificationService() : null;
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
        return messageService != null ? messageService.getMessage(key) : key;
    }

    /**
     * Gets the parsed Component (supports minimessage format).
     * Uses message key cache to avoid repeated parsing causing
     * TextColorReplacerImpl object accumulation.
     *
     * @param key message key
     * @return parsed Component
     */
    public Component getMessageComponent(String key) {
        return messageService != null ? messageService.getMessageComponent(key) : Component.empty();
    }

    /**
     * Gets the parsed Component with variable replacement (supports minimessage
     * format).
     * Uses cached template Component for variable replacement to avoid repeated
     * parsing.
     *
     * @param key       message key
     * @param variables variable map
     * @return parsed Component
     */
    public Component getMessageComponent(String key, java.util.Map<String, String> variables) {
        return messageService != null ? messageService.getMessageComponent(key, variables) : Component.empty();
    }

    /**
     * Reloads messages.yml configuration.
     *
     * @return true if successful
     */
    public boolean reloadMessagesConfig() {
        boolean result = messageService != null && messageService.reload();
        if (result) {
            getLogger().fine("Messages configuration reloaded.");
        }
        return result;
    }

    /**
     * Returns the current number of message Component cache entries (for monitoring
     * purposes).
     */
    public int getMessageComponentCacheSize() {
        return messageService != null ? messageService.getCacheSize() : 0;
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
     * Also propagates the new config to subsystems that cache snapshot values
     * (command cooldowns, pay limits, display settings, etc.).
     */
    public void reloadSyncmoneyConfig() {
        this.syncmoneyConfig = new SyncmoneyConfig(this);

        if (commandServiceManager != null) {
            commandServiceManager.reload(syncmoneyConfig);
        }

        getLogger().fine("SyncmoneyConfig reloaded.");
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

            SimpleCommandMap simpleCommandMap = (SimpleCommandMap) commandMap;
            java.lang.reflect.Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(simpleCommandMap);
            knownCommands.remove(name);
            knownCommands.remove("syncmoney:" + name);
            getLogger().log(Level.FINE, "Command unregistered: /" + name);
        } catch (Exception e) {
            getLogger().warning("Failed to unregister command " + name + ": " + e.getMessage());
        }
    }

    /**
     * Reloads permission service.
     */
    public void reloadPermissionService() {
        if (permissionManager != null) {
            permissionManager.reload();
            getLogger().fine("Permission service reloaded.");
        }
    }

    /**
     * Reloads economy facade related services.
     */
    public void reloadEconomyFacade() {
        if (economyServiceManager != null) {
            var vaultProvider = economyServiceManager.getVaultProvider();
            if (vaultProvider != null) {
                vaultProvider.setConfig(syncmoneyConfig);
            }
            var crossServerSyncManager = economyServiceManager.getCrossServerSyncManager();
            if (crossServerSyncManager != null) {
                crossServerSyncManager.shutdown();
                getLogger().warning("CrossServerSyncManager config changed, some changes require server restart.");
            }
            getLogger().fine("Economy facade services config updated.");
        }
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

    /**
     * [RED-05 FIX] Initialize PluginContext after all managers are ready.
     * This provides a centralized way to access all plugin services.
     */
    private void initializePluginContext() {
        this.pluginContext = new PluginContext.Builder()
                .setConfig(syncmoneyConfig)
                .setRedisManager(storageManager != null ? storageManager.getRedisManager() : null)
                .setCacheManager(storageManager != null ? storageManager.getCacheManager() : null)
                .setDatabaseManager(storageManager != null ? storageManager.getDatabaseManager() : null)
                .setDbWriteQueue(storageManager != null ? storageManager.getDbWriteQueue() : null)
                .setEconomyFacade(economyServiceManager != null ? economyServiceManager.getEconomyFacade() : null)
                .setNameResolver(economyServiceManager != null ? economyServiceManager.getNameResolver() : null)
                .setFallbackWrapper(economyServiceManager != null ? economyServiceManager.getFallbackWrapper() : null)
                .setVaultProvider(economyServiceManager != null ? economyServiceManager.getVaultProvider() : null)
                .setCrossServerSyncManager(economyServiceManager != null ? economyServiceManager.getCrossServerSyncManager() : null)
                .setEconomyModeRouter(economyServiceManager != null ? economyServiceManager.getEconomyModeRouter() : null)
                .setSyncManager(syncManager)
                .setBreakerManager(breakerManager)
                .setPermissionManager(permissionManager)
                .setPlayerJoinListener(listenerServiceManager != null ? listenerServiceManager.getPlayerJoinListener() : null)
                .setPlayerQuitListener(listenerServiceManager != null ? listenerServiceManager.getPlayerQuitListener() : null)
                .setPlayerTransferGuard(listenerServiceManager != null ? listenerServiceManager.getPlayerTransferGuard() : null)
                .setShadowSyncTask(economyServiceManager != null ? economyServiceManager.getShadowSyncTask() : null)
                .setAuditLogger(auditServiceManager != null ? auditServiceManager.getAuditLogger() : null)
                .setBaltopManager(baltopManager)
                .setPermissionService(permissionManager != null ? permissionManager.getPermissionService() : null)
                .setCircuitBreaker(breakerManager != null ? breakerManager.getCircuitBreaker() : null)
                .setWebAdminServer(webServiceManager != null ? webServiceManager.getWebAdminServer() : null)
                .setWebAdminConfig(webServiceManager != null ? webServiceManager.getWebAdminConfig() : null)
                .setNotificationService(breakerManager != null ? breakerManager.getNotificationService() : null)
                .build();

        getLogger().fine("PluginContext initialized");
    }

    /**
     * [RED-05 FIX] Get the PluginContext for centralized service access.
     * 
     * @return the PluginContext instance
     */
    public PluginContext getPluginContext() {
        return pluginContext;
    }
}
