package noietime.syncmoney.web;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;

/**
 * Web module configuration.
 * Separates web-admin configuration from the main configuration.
 *
 * @deprecated Superseded by
 *             {@link noietime.syncmoney.web.server.WebAdminConfig}.
 */
@Deprecated
public class WebModuleConfig {

    private boolean enabled;
    private String host;
    private int port;
    private String webPath;
    private boolean autoBuild;
    private String githubRepo;
    private String apiKey;
    private boolean rateLimitEnabled;
    private int rateLimitPerMinute;
    private String theme;
    private String language;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;

    public WebModuleConfig(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Load configuration from config.yml.
     */
    public void load() {
        FileConfiguration fc = config.getConfig();

        this.enabled = fc.getBoolean("web-admin.enabled", false);
        this.host = fc.getString("web-admin.server.host", "localhost");
        this.port = fc.getInt("web-admin.server.port", 8080);
        this.webPath = fc.getString("web-admin.web.path", "syncmoney-web");
        this.autoBuild = fc.getBoolean("web-admin.web.auto-build", false);
        this.githubRepo = fc.getString("web-admin.web.github-repo", "Misty4119/Syncmoney");
        this.apiKey = fc.getString("web-admin.security.api-key", "change-me-in-production");
        this.rateLimitEnabled = fc.getBoolean("web-admin.security.rate-limit.enabled", true);
        this.rateLimitPerMinute = fc.getInt("web-admin.security.rate-limit.requests-per-minute", 60);
        this.theme = fc.getString("web-admin.ui.theme", "dark");
        this.language = fc.getString("web-admin.ui.language", "zh-TW");

        plugin.getLogger().fine("WebModuleConfig loaded: enabled=" + enabled + ", port=" + port);
    }

    /**
     * Reload configuration.
     */
    public void reload() {
        load();
    }

    /**
     * Get web directory path.
     */
    public Path getWebDirectory() {
        return plugin.getDataFolder().toPath().resolve(webPath);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getWebPath() {
        return webPath;
    }

    public boolean isAutoBuild() {
        return autoBuild;
    }

    public String getGithubRepo() {
        return githubRepo;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public String getTheme() {
        return theme;
    }

    public String getLanguage() {
        return language;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    /**
     * Get all configuration as a map.
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("enabled", enabled);
        map.put("host", host);
        map.put("port", port);
        map.put("webPath", webPath);
        map.put("autoBuild", autoBuild);
        map.put("githubRepo", githubRepo);
        map.put("apiKey", apiKey);
        map.put("rateLimitEnabled", rateLimitEnabled);
        map.put("rateLimitPerMinute", rateLimitPerMinute);
        map.put("theme", theme);
        map.put("language", language);
        return map;
    }
}
