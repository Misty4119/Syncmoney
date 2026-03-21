package noietime.syncmoney.config;

import noietime.syncmoney.economy.EconomyMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [SYNC-CONFIG-001] Configuration loader for config.yml.
 * Does not create connections; only parses config. Returns defaults for missing keys.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 *
 * Architecture: Sub-configs handle their own domain (RedisConfig handles redis.*, etc.).
 * Callers should use sub-config getters: config.redis().getRedisHost()
 * Direct getters are kept only for cross-cutting concerns and computed values.
 */
public final class SyncmoneyConfig {

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final ServerIdentityManager serverIdentityManager;

    // Sub-configs
    private final RedisConfig redisConfig;
    private final DatabaseConfig databaseConfig;
    private final MigrationConfig migrationConfig;
    private final ShadowSyncConfig shadowSyncConfig;
    private final CircuitBreakerConfig circuitBreakerConfig;
    private final PlayerProtectionConfig playerProtectionConfig;
    private final DiscordWebhookConfig discordWebhookConfig;
    private final AuditConfig auditConfig;
    private final CMIConfig cmiConfig;
    private final BaltopConfig baltopConfig;
    private final AdminPermissionConfig adminPermissionConfig;
    private final WebAdminConfig webAdminConfig;
    private final PayConfig payConfig;
    private final DisplayConfig displayConfig;
    private final TransferGuardConfig transferGuardConfig;
    private final VaultConfig vaultConfig;
    private final LocalConfig localConfig;
    private final CrossServerConfig crossServerConfig;

    public SyncmoneyConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        String customServerName = config.getString("server-name", "");
        this.serverIdentityManager = new ServerIdentityManager(plugin, customServerName);

        // Initialize sub-configs
        this.redisConfig = new RedisConfig(config);
        this.databaseConfig = new DatabaseConfig(config);
        this.migrationConfig = new MigrationConfig(config);
        this.shadowSyncConfig = new ShadowSyncConfig(config);
        this.circuitBreakerConfig = new CircuitBreakerConfig(config);
        this.playerProtectionConfig = new PlayerProtectionConfig(config);
        this.discordWebhookConfig = new DiscordWebhookConfig(config);
        this.auditConfig = new AuditConfig(config);
        this.cmiConfig = new CMIConfig(config);
        this.baltopConfig = new BaltopConfig(config);
        this.adminPermissionConfig = new AdminPermissionConfig(config);
        this.webAdminConfig = new WebAdminConfig(config);
        this.payConfig = new PayConfig(config);
        this.displayConfig = new DisplayConfig(config);
        this.transferGuardConfig = new TransferGuardConfig(config);
        this.vaultConfig = new VaultConfig(config);
        this.localConfig = new LocalConfig(config);
        this.crossServerConfig = new CrossServerConfig(config);
    }

    /**
     * [SYNC-CONFIG-013] Get the underlying FileConfiguration.
     * Used by WebAdminConfig to load web settings.
     */
    public FileConfiguration getConfig() {
        return config;
    }

    // ==================== Core Settings (direct config access) ====================

    public String getServerName() {
        return serverIdentityManager.getServerName();
    }

    public int getQueueCapacity() {
        return config.getInt("queue-capacity", 10000);
    }

    public boolean isPubsubEnabled() {
        return config.getBoolean("pubsub-enabled", true);
    }

    public boolean isDbEnabled() {
        return config.getBoolean("db-enabled", true);
    }

    public boolean isDebug() {
        return config.getBoolean("debug", false);
    }

    public boolean isPayAllowedInDegraded() {
        return config.getBoolean("pay.allow-in-degraded", false);
    }

    // ==================== Economy Mode Detection ====================

    /**
     * [SYNC-CONFIG-120] Whether Redis is enabled
     */
    public boolean isRedisEnabled() {
        String manualMode = config.getString("economy.mode", "auto");
        if (manualMode.equalsIgnoreCase("local")) {
            return false;
        }
        if (config.contains("redis.enabled")) {
            return config.getBoolean("redis.enabled", true);
        }
        return config.getBoolean("pubsub-enabled", true);
    }

    /**
     * [SYNC-CONFIG-121] Whether Database is enabled
     */
    public boolean isDatabaseEnabled() {
        String manualMode = config.getString("economy.mode", "auto");
        if (manualMode.equalsIgnoreCase("local")) {
            return false;
        }
        if (config.contains("database.enabled")) {
            return config.getBoolean("database.enabled", true);
        }
        return config.getBoolean("db-enabled", true);
    }

    /**
     * [SYNC-CONFIG-122] Gets economy mode
     */
    public EconomyMode getEconomyMode() {
        String manualMode = config.getString("economy.mode", "auto");
        if (!manualMode.equals("auto")) {
            try {
                return EconomyMode.valueOf(manualMode.toUpperCase());
            } catch (IllegalArgumentException e) {
            }
        }
        return detectEconomyMode();
    }

    /**
     * [SYNC-CONFIG-126] Automatically detects economy mode
     */
    public EconomyMode detectEconomyMode() {
        boolean hasRedisConfig = config.getBoolean("redis.enabled", true);
        boolean hasDatabaseConfig = config.getBoolean("database.enabled", true);

        if (!hasRedisConfig && !hasDatabaseConfig) {
            return EconomyMode.LOCAL;
        }

        String manualMode = config.getString("economy.mode", "auto");
        if (manualMode.equalsIgnoreCase("local")) {
            return EconomyMode.LOCAL;
        }
        if (manualMode.equalsIgnoreCase("sync")) {
            return EconomyMode.SYNC;
        }
        if (manualMode.equalsIgnoreCase("cmi")) {
            return EconomyMode.CMI;
        }
        if (manualMode.equalsIgnoreCase("local_redis")) {
            return EconomyMode.LOCAL_REDIS;
        }

        boolean hasRedis = isRedisEnabled();
        boolean hasDatabase = isDatabaseEnabled();

        if (hasRedis && hasDatabase) {
            return EconomyMode.SYNC;
        } else if (hasRedis) {
            String forceMode = config.getString("economy.mode", "auto");
            if (forceMode.equalsIgnoreCase("cmi")) {
                return EconomyMode.CMI;
            }
            return EconomyMode.LOCAL_REDIS;
        } else {
            return EconomyMode.LOCAL;
        }
    }

    public boolean isSyncMode() {
        return getEconomyMode() == EconomyMode.SYNC;
    }

    public boolean isLocalMode() {
        return getEconomyMode() == EconomyMode.LOCAL;
    }

    public boolean isCMIMode() {
        return getEconomyMode() == EconomyMode.CMI;
    }

    // ==================== CMI Balance Mode (computed, kept here) ====================

    /**
     * [SYNC-CONFIG-139] CMI balance operation mode.
     */
    public enum CMIBalanceMode {
        API,
        INTERNAL
    }

    /**
     * [SYNC-CONFIG-140] Gets CMI balance operation mode.
     * Converts string from CMIConfig to enum.
     */
    public CMIBalanceMode getCMIBalanceMode() {
        String mode = cmiConfig.getCMIBalanceModeString();
        try {
            return CMIBalanceMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CMIBalanceMode.INTERNAL;
        }
    }

    // ==================== Validation ====================

    /**
     * [SYNC-CONFIG-123] Validates configuration values.
     * Throws IllegalArgumentException if validation fails.
     */
    public void validate() throws IllegalArgumentException {
        double minAmount = payConfig.getPayMinAmount();
        double maxAmount = payConfig.getPayMaxAmount();

        if (minAmount < 0) {
            throw new IllegalArgumentException("pay.min-amount cannot be negative");
        }
        if (maxAmount <= 0) {
            throw new IllegalArgumentException("pay.max-amount must be positive");
        }
        if (minAmount > maxAmount) {
            throw new IllegalArgumentException("pay.min-amount must be less than or equal to pay.max-amount");
        }

        double confirmThreshold = payConfig.getPayConfirmThreshold();
        if (confirmThreshold > maxAmount) {
            throw new IllegalArgumentException("pay.confirm-threshold cannot exceed pay.max-amount");
        }

        long maxAmountPerMinute = playerProtectionConfig.getPlayerProtectionMaxAmountPerMinute();
        if (maxAmountPerMinute < 0) {
            throw new IllegalArgumentException("player-protection.rate-limit.max-amount-per-minute cannot be negative");
        }

        double balanceChangeThreshold = playerProtectionConfig.getPlayerProtectionBalanceChangeThreshold();
        if (balanceChangeThreshold <= 0) {
            throw new IllegalArgumentException(
                    "player-protection.anomaly-detection.balance-change-threshold must be positive");
        }

        boolean webEnabled = config.getBoolean("web-admin.enabled", false);
        if (webEnabled) {
            validateWebAdminConfig();
        }
    }

    /**
     * [SYNC-CONFIG-124] Validate web admin configuration.
     */
    private void validateWebAdminConfig() {
        int port = config.getInt("web-admin.server.port", 8080);
        if (port < 1024 || port > 65535) {
            getLogger().warning("Web Admin: port should be between 1024 and 65535, using default 8080");
        }

        int rateLimit = config.getInt("web-admin.security.rate-limit.requests-per-minute", 60);
        if (rateLimit < 1) {
            getLogger().warning("Web Admin: rate-limit.requests-per-minute must be at least 1, using default 60");
        }
        if (rateLimit > 10000) {
            getLogger().warning("Web Admin: rate-limit.requests-per-minute is very high (" + rateLimit
                    + "), consider reducing for security");
        }

        String apiKey = config.getString("web-admin.security.api-key", "change-me-in-production");
        if (apiKey == null || apiKey.isBlank()) {
            getLogger().severe("Web Admin: API key is not set! Please configure web-admin.security.api-key");
        } else if (apiKey.equals("change-me-in-production")) {
            getLogger().warning(
                    "Web Admin: Using default API key! Please change web-admin.security.api-key in production");
        } else if (apiKey.length() < 16) {
            getLogger().warning(
                    "Web Admin: API key is too short (less than 16 characters). Consider using a longer key for security");
        }
    }

    private java.util.logging.Logger getLogger() {
        return java.util.logging.Logger.getLogger("SyncmoneyConfig");
    }

    // ==================== Central Management Mode ====================

    /**
     * [SYNC-CONFIG-148] Whether central management mode is enabled
     */
    public boolean isCentralMode() {
        return config.getBoolean("web-admin.central-mode", false);
    }

    /**
     * [SYNC-CONFIG-149] Node configuration for central management mode
     */
    public static class NodeConfig {
        public String name;
        public String url;
        public String apiKey;
        public boolean enabled;
    }

    /**
     * [SYNC-CONFIG-150] Gets list of configured nodes for central management
     */
    public List<NodeConfig> getNodes() {
        List<NodeConfig> nodes = new ArrayList<>();
        var list = config.getMapList("web-admin.nodes");
        if (list != null) {
            for (var item : list) {
                if (item instanceof Map) {
                    NodeConfig node = new NodeConfig();
                    node.name = (String) ((Map) item).getOrDefault("name", "Unknown");
                    node.url = (String) ((Map) item).getOrDefault("url", "http://localhost:8080");
                    node.apiKey = (String) ((Map) item).getOrDefault("api-key", "");
                    node.enabled = (Boolean) ((Map) item).getOrDefault("enabled", true);
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    // ==================== Sub-Config Object Getters ====================

    /**
     * [SYNC-CONFIG-158] Gets the Redis configuration object.
     */
    public RedisConfig redis() {
        return redisConfig;
    }

    /**
     * [SYNC-CONFIG-159] Gets the Database configuration object.
     */
    public DatabaseConfig database() {
        return databaseConfig;
    }

    /**
     * [SYNC-CONFIG-160] Gets the Migration configuration object.
     */
    public MigrationConfig migration() {
        return migrationConfig;
    }

    /**
     * [SYNC-CONFIG-161] Gets the ShadowSync configuration object.
     */
    public ShadowSyncConfig shadowSync() {
        return shadowSyncConfig;
    }

    /**
     * [SYNC-CONFIG-162] Gets the CircuitBreaker configuration object.
     */
    public CircuitBreakerConfig circuitBreaker() {
        return circuitBreakerConfig;
    }

    /**
     * [SYNC-CONFIG-163] Gets the PlayerProtection configuration object.
     */
    public PlayerProtectionConfig playerProtection() {
        return playerProtectionConfig;
    }

    /**
     * [SYNC-CONFIG-164] Gets the DiscordWebhook configuration object.
     */
    public DiscordWebhookConfig discordWebhook() {
        return discordWebhookConfig;
    }

    /**
     * [SYNC-CONFIG-165] Gets the Audit configuration object.
     */
    public AuditConfig audit() {
        return auditConfig;
    }

    /**
     * [SYNC-CONFIG-166] Gets the CMI configuration object.
     */
    public CMIConfig cmi() {
        return cmiConfig;
    }

    /**
     * [SYNC-CONFIG-167] Gets the Baltop configuration object.
     */
    public BaltopConfig baltop() {
        return baltopConfig;
    }

    /**
     * [SYNC-CONFIG-168] Gets the AdminPermission configuration object.
     */
    public AdminPermissionConfig adminPermission() {
        return adminPermissionConfig;
    }

    /**
     * [SYNC-CONFIG-169] Gets the Pay configuration object.
     */
    public PayConfig pay() {
        return payConfig;
    }

    /**
     * [SYNC-CONFIG-170] Gets the Display configuration object.
     */
    public DisplayConfig display() {
        return displayConfig;
    }

    /**
     * [SYNC-CONFIG-171] Gets the TransferGuard configuration object.
     */
    public TransferGuardConfig transferGuard() {
        return transferGuardConfig;
    }

    /**
     * [SYNC-CONFIG-172] Gets the Vault configuration object.
     */
    public VaultConfig vault() {
        return vaultConfig;
    }

    /**
     * [SYNC-CONFIG-173] Gets the Local configuration object.
     */
    public LocalConfig local() {
        return localConfig;
    }

    /**
     * [SYNC-CONFIG-174] Gets the CrossServer configuration object.
     */
    public CrossServerConfig crossServer() {
        return crossServerConfig;
    }

    /**
     * [SYNC-CONFIG-175] Gets the WebAdmin configuration object.
     */
    public WebAdminConfig webAdmin() {
        return webAdminConfig;
    }
}
