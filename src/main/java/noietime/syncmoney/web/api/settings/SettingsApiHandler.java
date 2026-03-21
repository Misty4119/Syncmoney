package noietime.syncmoney.web.api.settings;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.JsonParseUtil;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.WebAdminConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * API handler for user settings.
 * Provides endpoints for theme, language, and timezone preferences.
 */
public class SettingsApiHandler {

    private final Syncmoney plugin;
    private final WebAdminConfig config;
    private final SyncmoneyConfig syncmoneyConfig;


    private final ReentrantLock saveLock = new ReentrantLock();


    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SettingsApi-Debounce");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingSave = null;
    private static final long SAVE_DEBOUNCE_MS = 500;

    public SettingsApiHandler(Syncmoney plugin, WebAdminConfig config, SyncmoneyConfig syncmoneyConfig) {
        this.plugin = plugin;
        this.config = config;
        this.syncmoneyConfig = syncmoneyConfig;
    }

    /**
     * Save current UI settings to config file on disk.
     * Uses debouncing to batch multiple rapid changes into a single save.
     */
    private void saveToDisk() {

        if (pendingSave != null) {
            pendingSave.cancel(false);
        }

        pendingSave = debounceExecutor.schedule(() -> {
            saveToDiskInternal();
        }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Internal method that performs the actual disk save.
     * Must be called from the debounce executor thread.
     */
    private void saveToDiskInternal() {

        saveLock.lock();
        try {
            var cfg = plugin.getConfig();


            String theme = config.getTheme();
            String language = config.getLanguage();
            String timezone = config.getTimezone();

            if (theme == null) theme = "dark";
            if (language == null) language = "zh-TW";
            if (timezone == null) timezone = "UTC";


            cfg.set("web-admin.ui.theme", theme);
            cfg.set("web-admin.ui.language", language);
            cfg.set("web-admin.ui.timezone", timezone);


            plugin.saveConfig();
            plugin.getLogger().fine("UI settings saved to config.yml");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save UI settings to config: " + e.getMessage());
        } finally {
            saveLock.unlock();
        }
    }

    /**
     * Force immediate save (used during shutdown).
     */
    public void saveNow() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
        }
        saveToDiskInternal();
    }

    /**
     * Shutdown the debounce executor.
     */
    public void shutdown() {
        debounceExecutor.shutdown();
        try {
            if (!debounceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                debounceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            debounceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Register settings API routes.
     */
    public void registerRoutes(noietime.syncmoney.web.server.HttpHandlerRegistry router) {
        router.get("api/settings", this::handleGetSettings);
        router.post("api/settings/theme", this::handleUpdateTheme);
        router.post("api/settings/language", this::handleUpdateLanguage);
        router.post("api/settings/timezone", this::handleUpdateTimezone);
    }

    /**
     * Handle GET /api/settings request.
     * Returns current user settings.
     */
    private void handleGetSettings(HttpServerExchange exchange) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("theme", config.getTheme());
        settings.put("language", config.getLanguage());
        settings.put("timezone", config.getTimezone());


        settings.put("dedupServerWindowSeconds", syncmoneyConfig.audit().getAuditDeduplicationWindowSeconds());
        settings.put("dedupClientCacheSize", 1000);

        sendJson(exchange, ApiResponse.success(settings));
    }

    /**
     * Handle POST /api/settings/theme request.
     * Updates the theme preference (in-memory only).
     */
    private void handleUpdateTheme(HttpServerExchange exchange) {
        readRequestBody(exchange, body -> {
            try {
                Optional<String> themeOpt = JsonParseUtil.getString(body, "theme");

                String theme = themeOpt.orElse(null);

                if (theme == null || theme.isEmpty()) {
                    sendJson(exchange, ApiResponse.error("INVALID_THEME", "Theme is required"));
                    return;
                }

                if (!theme.equals("dark") && !theme.equals("light")) {
                    sendJson(exchange, ApiResponse.error("INVALID_THEME", "Theme must be 'dark' or 'light'"));
                    return;
                }


                config.setTheme(theme);
                saveToDisk();

                Map<String, Object> response = new HashMap<>();
                response.put("theme", theme);
                sendJson(exchange, ApiResponse.success(response));

                plugin.getLogger().fine("Theme updated to: " + theme);

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to update theme: " + e.getMessage());
                sendJson(exchange, ApiResponse.error("UPDATE_FAILED", e.getMessage()));
            }
        });
    }

    /**
     * Handle POST /api/settings/language request.
     * Updates the language preference (in-memory only).
     */
    private void handleUpdateLanguage(HttpServerExchange exchange) {
        readRequestBody(exchange, body -> {
            try {
                Optional<String> languageOpt = JsonParseUtil.getString(body, "language");

                String language = languageOpt.orElse(null);

                if (language == null || language.isEmpty()) {
                    sendJson(exchange, ApiResponse.error("INVALID_LANGUAGE", "Language is required"));
                    return;
                }

                if (!language.equals("zh-TW") && !language.equals("en-US")) {
                    sendJson(exchange, ApiResponse.error("INVALID_LANGUAGE", "Language must be 'zh-TW' or 'en-US'"));
                    return;
                }


                config.setLanguage(language);
                saveToDisk();

                Map<String, Object> response = new HashMap<>();
                response.put("language", language);
                sendJson(exchange, ApiResponse.success(response));

                plugin.getLogger().fine("Language updated to: " + language);

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to update language: " + e.getMessage());
                sendJson(exchange, ApiResponse.error("UPDATE_FAILED", e.getMessage()));
            }
        });
    }

    /**
     * Handle POST /api/settings/timezone request.
     * Updates the timezone preference (in-memory only).
     */
    private void handleUpdateTimezone(HttpServerExchange exchange) {
        readRequestBody(exchange, body -> {
            try {
                Optional<String> timezoneOpt = JsonParseUtil.getString(body, "timezone");

                String timezone = timezoneOpt.orElse(null);

                if (timezone == null || timezone.isEmpty()) {
                    sendJson(exchange, ApiResponse.error("INVALID_TIMEZONE", "Timezone is required"));
                    return;
                }


                if (!isValidTimezone(timezone)) {
                    sendJson(exchange, ApiResponse.error("INVALID_TIMEZONE", "Invalid timezone format"));
                    return;
                }


                config.setTimezone(timezone);
                saveToDisk();

                Map<String, Object> response = new HashMap<>();
                response.put("timezone", timezone);
                sendJson(exchange, ApiResponse.success(response));

                plugin.getLogger().fine("Timezone updated to: " + timezone);

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to update timezone: " + e.getMessage());
                sendJson(exchange, ApiResponse.error("UPDATE_FAILED", e.getMessage()));
            }
        });
    }

    /**
     * Validate timezone format.
     */
    private boolean isValidTimezone(String timezone) {

        if (timezone.equals("UTC")) return true;
        if (timezone.matches("^UTC[+-]\\d{1,2}$")) {
            int offset = Integer.parseInt(timezone.substring(4));
            return offset >= 0 && offset <= 12;
        }
        return false;
    }

    /**
     * Read request body as string.
     * Uses dispatch to run on worker thread instead of IO thread.
     */
    private void readRequestBody(HttpServerExchange exchange, Consumer<String> callback) {

        if (exchange.isResponseStarted()) {
            plugin.getLogger().fine("Request already handled, skipping duplicate dispatch");
            return;
        }
        exchange.dispatch(() -> {

            if (exchange.isResponseStarted()) {
                plugin.getLogger().fine("Response already started after dispatch, skipping");
                return;
            }
            try {
                exchange.startBlocking();
                byte[] bytes = exchange.getInputStream().readAllBytes();
                String body = new String(bytes, StandardCharsets.UTF_8);
                callback.accept(body);
            } catch (IOException e) {
                callback.accept(null);
            }
        });
    }

    /**
     * Send JSON response.
     */
    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }
}
