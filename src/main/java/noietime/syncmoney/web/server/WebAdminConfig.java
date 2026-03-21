package noietime.syncmoney.web.server;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;

/**
 * Web Admin Dashboard configuration holder.
 * Loads settings from config.yml.
 */
public class WebAdminConfig {

    private boolean enabled;
    private String bundledVersion;
    private String host;
    private int port;
    private String webPath;
    private boolean autoBuild;
    private boolean autoUpdate;
    private String githubRepo;
    private String apiKey;
    private boolean rateLimitEnabled;
    private int rateLimitPerMinute;
    private String corsAllowedOrigins;
    private String theme;
    private String language;
    private String timezone;

    /**
     * Load configuration from SyncmoneyConfig.
     */
    public void load(FileConfiguration fc) {
        this.enabled = fc.getBoolean("web-admin.enabled", false);
        this.bundledVersion = fc.getString("web-admin.bundled-version", "1.1.2");
        this.host = fc.getString("web-admin.server.host", "localhost");
        this.port = fc.getInt("web-admin.server.port", 8080);
        this.webPath = fc.getString("web-admin.web.path", "syncmoney-web");
        this.autoBuild = fc.getBoolean("web-admin.web.auto-build", false);
        this.autoUpdate = fc.getBoolean("web-admin.web.auto-update", false);
        this.githubRepo = fc.getString("web-admin.web.github-repo", "Misty4119/Syncmoney-web");
        this.apiKey = fc.getString("web-admin.security.api-key", "change-me-in-production");
        this.rateLimitEnabled = fc.getBoolean("web-admin.security.rate-limit.enabled", true);
        this.rateLimitPerMinute = fc.getInt("web-admin.security.rate-limit.requests-per-minute", 60);
        this.corsAllowedOrigins = fc.getString("web-admin.security.cors-allowed-origins", "*");
        this.theme = fc.getString("web-admin.ui.theme", "dark");
        this.language = fc.getString("web-admin.ui.language", "zh-TW");
        this.timezone = fc.getString("web-admin.ui.timezone", "UTC");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBundledVersion() {
        return bundledVersion;
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

    public boolean isAutoUpdate() {
        return autoUpdate;
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

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public String getTheme() {
        return theme;
    }

    public String getLanguage() {
        return language;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    /**
     * Save UI settings to config file.
     * Writes theme, language, and timezone to disk.
     *
     * @param fc The FileConfiguration to save to
     */
    public void save(FileConfiguration fc) {
        fc.set("web-admin.ui.theme", this.theme);
        fc.set("web-admin.ui.language", this.language);
        fc.set("web-admin.ui.timezone", this.timezone);
    }

    /**
     * Get the full web path relative to data folder.
     */
    public Path getWebPath(Path dataFolder) {
        return dataFolder.resolve(webPath);
    }
}
