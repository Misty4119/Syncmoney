package noietime.syncmoney.breaker;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;

/**
 * [SYNC-BRK-001] Unified manager for breaker/protection components.
 * Encapsulates EconomicCircuitBreaker, PlayerTransactionGuard, and NotificationService.
 */
public class BreakerManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final RedisManager redisManager;
    private final boolean useRedis;

    private NameResolver nameResolver;
    private EconomicCircuitBreaker circuitBreaker;
    private PlayerTransactionGuard playerTransactionGuard;
    private NotificationService notificationService;
    private DiscordWebhookNotifier discordNotifier;

    public BreakerManager(Syncmoney plugin, SyncmoneyConfig config,
                         EconomyFacade economyFacade, RedisManager redisManager) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.redisManager = redisManager;
        this.useRedis = config.getEconomyMode() != noietime.syncmoney.economy.EconomyMode.LOCAL;
    }

    /**
     * Initialize breaker layer components.
     */
    public void initialize() {
        this.circuitBreaker = new EconomicCircuitBreaker(
                plugin,
                config,
                economyFacade,
                redisManager,
                useRedis);


        this.discordNotifier = new DiscordWebhookNotifier(plugin, config);

        if (config.playerProtection().isPlayerProtectionEnabled()) {
            this.notificationService = new NotificationService(plugin, config);
            this.playerTransactionGuard = new PlayerTransactionGuard(plugin, config, notificationService, redisManager);

            if (economyFacade != null) {
                economyFacade.setPlayerTransactionGuard(playerTransactionGuard);
                economyFacade.setCircuitBreaker(circuitBreaker);
            }

            plugin.getLogger().fine("Player transaction guard enabled");
        } else {
            this.playerTransactionGuard = null;
            this.notificationService = null;

            if (economyFacade != null) {
                economyFacade.setCircuitBreaker(circuitBreaker);
            }
            plugin.getLogger().fine("Player transaction guard disabled");
        }

        plugin.getLogger().fine("Breaker layer initialized");
    }

    /**
     * Shutdown breaker layer components.
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down breaker layer...");

        if (playerTransactionGuard != null) {
            playerTransactionGuard.shutdown();
        }

        if (notificationService != null) {
            notificationService.shutdown();
        }

        if (circuitBreaker != null) {
            circuitBreaker.shutdown();
        }

        if (circuitBreaker != null && circuitBreaker.getConnectionStateManager() != null) {
            circuitBreaker.getConnectionStateManager().shutdown();
        }

        if (circuitBreaker != null && circuitBreaker.getResourceMonitor() != null) {
            circuitBreaker.getResourceMonitor().shutdown();
        }

        plugin.getLogger().fine("Breaker layer shutdown complete");
    }

    public EconomicCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public PlayerTransactionGuard getPlayerTransactionGuard() {
        return playerTransactionGuard;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    /**
     * Set NameResolver for player name resolution in notifications.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
        if (notificationService != null) {
            notificationService.setNameResolver(nameResolver);
        }
        if (discordNotifier != null) {
            discordNotifier.setNameResolver(nameResolver);
        }
    }

    /**
     * Set SSE manager for broadcasting breaker events to web clients.
     */
    public void setSseManager(noietime.syncmoney.web.websocket.SseManager sseManager) {
        if (circuitBreaker != null) {
            circuitBreaker.setSseManager(sseManager);
        }
        if (notificationService != null) {
            notificationService.setSseManager(sseManager);
        }
        if (playerTransactionGuard != null) {

        }

        if (circuitBreaker != null && circuitBreaker.getResourceMonitor() != null) {
            circuitBreaker.getResourceMonitor().setSseManager(sseManager);
        }
    }

    /**
     * Set Discord webhook notifier for resource alerts.
     */
    public void setDiscordWebhookNotifier(DiscordWebhookNotifier discordNotifier) {


        if (circuitBreaker != null && circuitBreaker.getResourceMonitor() != null) {
            circuitBreaker.getResourceMonitor().setDiscordWebhookNotifier(discordNotifier);
        }
        if (circuitBreaker != null) {
            circuitBreaker.setDiscordWebhookNotifier(discordNotifier);
        }
    }

    /**
     * Get Discord webhook notifier.
     */
    public DiscordWebhookNotifier getDiscordWebhookNotifier() {
        return discordNotifier;
    }
}
