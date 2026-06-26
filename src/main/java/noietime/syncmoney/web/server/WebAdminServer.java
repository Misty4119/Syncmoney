package noietime.syncmoney.web.server;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.builder.WebBuilder;
import noietime.syncmoney.web.builder.WebDownloader;
import noietime.syncmoney.web.builder.WebVersionChecker;
import noietime.syncmoney.web.security.ApiKeyAuthFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Web Admin HTTP server bootstrap (Undertow lifecycle).
 *
 * <p>This class now focuses purely on bootstrapping: starting/stopping Undertow,
 * extracting bundled web files, the startup version check/auto-build, and wiring
 * the collaborating components together. Request dispatch is delegated to
 * {@link RouteRegistry}, CORS to {@link CorsHandler}, and static file serving
 * (with path-traversal protection) to {@link StaticFileHandler}.</p>
 */
public final class WebAdminServer {

    private final Syncmoney plugin;
    private final WebAdminConfig config;
    private final HttpHandlerRegistry router;
    private final ApiKeyAuthFilter authFilter;

    private final CorsHandler corsHandler;
    private final StaticFileHandler staticFileHandler;
    private final RouteRegistry routeRegistry;

    private Undertow server;
    private Path webRoot;
    private noietime.syncmoney.web.websocket.WebSocketManager webSocketManager;
    private noietime.syncmoney.web.websocket.SseManager sseManager;
    private noietime.syncmoney.web.api.settings.SettingsApiHandler settingsHandler;

    public WebAdminServer(Syncmoney plugin, WebAdminConfig config, ApiKeyAuthFilter authFilter) {
        this.plugin = plugin;
        this.config = config;
        this.router = new HttpHandlerRegistry();
        this.authFilter = authFilter;
        this.corsHandler = new CorsHandler();
        this.staticFileHandler = new StaticFileHandler(config, corsHandler);
        this.routeRegistry = new RouteRegistry(plugin, config, router, authFilter, corsHandler, staticFileHandler);
    }

    /**
     * Start the HTTP server.
     */
    public void start() throws IOException {
        if (!config.isEnabled()) {
            plugin.getLogger().fine(plugin.getMessage("web.server.disabled"));
            return;
        }

        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        plugin.getLogger().fine("WebAdminServer.start: dataFolder=" + dataFolder);

        /* webRoot points to the dist/ subdirectory where built static files reside. */
        webRoot = config.getWebPath(dataFolder).resolve("dist").toAbsolutePath().normalize();
        staticFileHandler.setWebRoot(webRoot);
        plugin.getLogger().fine("WebAdminServer.start: webRoot=" + webRoot);

        if (!Files.exists(webRoot)) {
            Files.createDirectories(webRoot);
            plugin.getLogger()
                    .fine(plugin.getMessage("web.server.directory-created").replace("{path}", webRoot.toString()));
        } else {
            plugin.getLogger().fine("WebAdminServer.start: webRoot already exists");
        }

        extractDefaultWebFiles(webRoot);

        performVersionCheck();

        registerRoutes();

        server = Undertow.builder()
                .addHttpListener(config.getPort(), config.getHost())
                .setHandler(routeRegistry::handle)
                .build();

        server.start();
        plugin.getLogger().fine(plugin.getMessage("web.server.started")
                .replace("{host}", config.getHost())
                .replace("{port}", String.valueOf(config.getPort())));
    }

    /**
     * Extract default web files from JAR resources to data folder.
     * Simplified: directly extracts individual files (no tar.gz needed).
     */
    private void extractDefaultWebFiles(Path webRoot) throws IOException {
        Path webDir = webRoot.getParent();

        plugin.getLogger().info("Checking web files: webDir=" + webDir + ", webRoot=" + webRoot);
        plugin.getLogger().info("webDir exists: " + Files.exists(webDir) + ", webRoot exists: " + Files.exists(webRoot));


        if (Files.exists(webDir) && Files.exists(webRoot)) {
            boolean hasFiles = hasAnyFiles(webRoot);
            plugin.getLogger().info("webRoot has files: " + hasFiles);
            if (hasFiles) {
                plugin.getLogger().info("Web files already exist, skipping extraction");
                return;
            }
        }


        if (!Files.exists(webDir)) {
            Files.createDirectories(webDir);
            plugin.getLogger().info("Created web directory: " + webDir);
        }
        if (!Files.exists(webRoot)) {
            Files.createDirectories(webRoot);
            plugin.getLogger().info("Created web root directory: " + webRoot);
        }


        plugin.getLogger().info("Extracting web files from JAR resources...");
        var classLoader = plugin.getClass().getClassLoader();
        extractIndividualFiles(classLoader, webRoot);
        plugin.getLogger().info("Web frontend extracted successfully to: " + webDir);
    }

    /**
     * Check if directory has any files.
     */
    private boolean hasAnyFiles(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extract individual files from JAR resources.
     */
    private void extractIndividualFiles(ClassLoader classLoader, Path webRoot) throws IOException {

        String[] rootFiles = {
            "favicon.png","index.html","manifest.json","manifest.webmanifest","mockServiceWorker.js","registerSW.js","sw.js","sw.js.map","version.json","workbox-daba6f28.js","workbox-daba6f28.js.map"
        };

        for (String fileName : rootFiles) {
            Path targetFile = webRoot.resolve(fileName);
            if (!Files.exists(targetFile)) {
                try (var inputStream = classLoader.getResourceAsStream("syncmoney-web/dist/" + fileName)) {
                    if (inputStream != null) {
                        Files.copy(inputStream, targetFile);
                    }
                }
            }
        }


        Path assetsDir = webRoot.resolve("assets");
        if (!Files.exists(assetsDir)) {
            Files.createDirectories(assetsDir);
        }
        String[] assetFiles = {
            "AuditLogView-1TqTLEDG.js","AuditLogView-1TqTLEDG.js.map","AuditLogView-ncafTB8a.css","Badge.vue_vue_type_script_setup_true_lang-ButpYfBf.js","Badge.vue_vue_type_script_setup_true_lang-ButpYfBf.js.map","Button.vue_vue_type_script_setup_true_lang-DGNMbe25.js","Button.vue_vue_type_script_setup_true_lang-DGNMbe25.js.map","Card-DwRmiknp.css","Card-QgPLeQ4W.js","Card-QgPLeQ4W.js.map","CentralDashboardView-CRyjfHZG.js","CentralDashboardView-CRyjfHZG.js.map","ConfigView-DoKUJKS3.js","ConfigView-DoKUJKS3.js.map","ConfigView-DOZ3o-6N.css","DashboardView-BA0KEiZr.js","DashboardView-BA0KEiZr.js.map","EmptyState.vue_vue_type_script_setup_true_lang-C59jgcsN.js","EmptyState.vue_vue_type_script_setup_true_lang-C59jgcsN.js.map","globe-BIR30XRw.js","globe-BIR30XRw.js.map","index-DJbJKhkS.js","index-DJbJKhkS.js.map","index-DTkctV0l.css","LoginView-DwalZz_V.js","LoginView-DwalZz_V.js.map","NodesManagementView-Bg7EP4A9.css","NodesManagementView-dKCssAEu.js","NodesManagementView-dKCssAEu.js.map","NotFoundView-BvfRyRzZ.css","NotFoundView-CcED0NHh.js","NotFoundView-CcED0NHh.js.map","SettingsView-DmFsEYE8.js","SettingsView-DmFsEYE8.js.map","shield-BlE-6dZQ.js","shield-BlE-6dZQ.js.map","Skeleton.vue_vue_type_script_setup_true_lang-K0ftSVyN.js","Skeleton.vue_vue_type_script_setup_true_lang-K0ftSVyN.js.map","StatCard.vue_vue_type_script_setup_true_lang-BFGf2dR8.js","StatCard.vue_vue_type_script_setup_true_lang-BFGf2dR8.js.map","SystemStatusView-B74dBDbB.js","SystemStatusView-B74dBDbB.js.map","ui-vendor-CM-HUc5O.js","ui-vendor-CM-HUc5O.js.map","users-Bnhhxj7p.js","users-Bnhhxj7p.js.map","vue-vendor-hiEirxk6.js","vue-vendor-hiEirxk6.js.map"
        };

        for (String fileName : assetFiles) {
            Path targetFile = assetsDir.resolve(fileName);
            if (!Files.exists(targetFile)) {
                try (var inputStream = classLoader.getResourceAsStream("syncmoney-web/dist/assets/" + fileName)) {
                    if (inputStream != null) {
                        Files.copy(inputStream, targetFile);
                    }
                }
            }
        }


        Path iconsDir = webRoot.resolve("icons");
        if (!Files.exists(iconsDir)) {
            Files.createDirectories(iconsDir);
        }

        String[] iconFiles = { "icon-192x192.png", "icon-512x512.png" };
        for (String fileName : iconFiles) {
            Path targetFile = iconsDir.resolve(fileName);
            if (!Files.exists(targetFile)) {
                try (var inputStream = classLoader.getResourceAsStream("syncmoney-web/dist/icons/" + fileName)) {
                    if (inputStream != null) {
                        Files.copy(inputStream, targetFile);
                    }
                }
            }
        }
    }

    /**
     * Stop the HTTP server.
     *
     * Undertow.stop() initiates an async XNIO worker shutdown and returns immediately.
     * The XNIO worker threads continue running their cleanup tasks for several seconds.
     * If the plugin classloader is unloaded before they finish, they cannot find
     * jboss-threads classes (e.g. ContextClassLoaderSavingRunnable), causing
     * NoClassDefFoundError and an ~11-second server shutdown hang.
     *
     * Fix: capture the XnioWorker before stop(), then block on awaitTermination()
     * so the classloader stays alive until all XNIO threads are completely done.
     */
    public void stop() {
        if (server != null) {

            if (settingsHandler != null) {
                try {
                    settingsHandler.saveNow();
                } catch (Exception ignored) {}
            }


            if (sseManager != null) {
                try {
                    sseManager.shutdown();
                } catch (Exception ignored) {}
            }
            if (webSocketManager != null) {
                try {
                    webSocketManager.shutdown();
                } catch (Exception ignored) {}
            }
            if (settingsHandler != null) {
                try {
                    settingsHandler.shutdown();
                } catch (Exception ignored) {}
            }


            org.xnio.XnioWorker worker = null;
            try {
                worker = server.getWorker();
            } catch (Exception ignored) {}


            server.stop();
            server = null;




            if (worker != null) {
                try {
                    worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {}
            }

            plugin.getLogger().fine(plugin.getMessage("web.server.stopped"));
        }
        staticFileHandler.clearCache();
    }

    /**
     * Version check logic on startup.
     * Runs asynchronously to avoid blocking the main thread.
     */
    private void performVersionCheck() {
        plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> {
            String localVersion = getLocalVersion();
            WebVersionChecker checker = new WebVersionChecker(plugin, config.getGithubRepo());

            if (config.isAutoUpdate()) {
                plugin.getLogger().fine(plugin.getMessage("web.server.version.checking"));
                String latestVersion = checker.checkLatestVersion();

                if (latestVersion != null && checker.hasNewerVersion(latestVersion, localVersion)) {
                    plugin.getLogger()
                            .fine(plugin.getMessage("web.server.version.downloading").replace("{version}", latestVersion));
                    try {
                        WebDownloader downloader = new WebDownloader(plugin, config.getGithubRepo(), webRoot);
                        if (downloader.downloadLatest()) {
                            plugin.getLogger().fine(plugin.getMessage("web.server.version.download-complete"));
                            try {
                                reloadWebRoot();
                                plugin.getLogger().fine("Web updated and reloaded to version: " + latestVersion);
                            } catch (IOException e) {
                                plugin.getLogger().warning("Web updated but reload failed: " + e.getMessage());
                            }
                        } else {
                            plugin.getLogger().warning(plugin.getMessage("web.server.version.download-failed"));
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning(
                                plugin.getMessage("web.server.version.download-error").replace("{error}", e.getMessage()));
                    }
                }
            } else {
                String latestVersion = checker.checkLatestVersion();

                if (latestVersion != null && checker.hasNewerVersion(latestVersion, localVersion)) {
                    plugin.getLogger().warning(plugin.getMessage("web.server.version.update-available")
                            .replace("{bundled}", config.getBundledVersion())
                            .replace("{current}", localVersion)
                            .replace("{latest}", latestVersion));
                } else {
                    plugin.getLogger()
                            .fine(plugin.getMessage("web.server.version.up-to-date").replace("{version}", localVersion));
                }
            }

            if (config.isAutoBuild()) {
                triggerAutoBuild();
            }
        });
    }

    /**
     * Trigger auto-build of web frontend if prerequisites are met.
     */
    private void triggerAutoBuild() {
        Path webDir = plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("syncmoney-web");
        WebBuilder builder = new WebBuilder(plugin, webDir);

        if (!builder.isBuildable()) {
            plugin.getLogger().fine("Web auto-build skipped: syncmoney-web directory or package.json not found");
            return;
        }

        plugin.getLogger().fine("Web auto-build triggered");
        try {
            if (builder.buildAll()) {
                plugin.getLogger().fine("Web auto-build completed successfully");
                try {
                    reloadWebRoot();
                    plugin.getLogger().fine("Web root reloaded after auto-build");
                } catch (IOException e) {
                    plugin.getLogger().warning("Web auto-build succeeded but reload failed: " + e.getMessage());
                }
            } else {
                plugin.getLogger().warning("Web auto-build failed");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Web auto-build error: " + e.getMessage());
        }
    }

    /**
     * Read local version from version.json.
     * Note: This is called during startup, so it's safe to use blocking IO.
     */
    private String getLocalVersion() {
        Path versionFile = webRoot.resolve("version.json");
        if (Files.exists(versionFile)) {
            try {

                List<String> lines = Files.readAllLines(versionFile);
                String content = String.join("", lines);
                int start = content.indexOf("\"version\":\"");
                if (start != -1) {
                    start += 11;
                    int end = content.indexOf("\"", start);
                    if (end != -1) {
                        return content.substring(start, end);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to read local version: " + e.getMessage());
            }
        }
        return config.getBundledVersion();
    }

    /**
     * Register all API routes.
     */
    private void registerRoutes() {
        router.get("health", exchange -> {
            String pluginVersion = plugin.getDescription().getVersion();
            sendJson(exchange, "{\"success\":true,\"data\":{\"status\":\"ok\",\"version\":\"" + pluginVersion + "\"}}");
        });


        settingsHandler = new noietime.syncmoney.web.api.settings.SettingsApiHandler(
                plugin, config, plugin.getSyncmoneyConfig());
        settingsHandler.registerRoutes(router);


        noietime.syncmoney.web.api.auth.WsTokenHandler wsTokenHandler = new noietime.syncmoney.web.api.auth.WsTokenHandler(plugin, config);
        wsTokenHandler.registerRoutes(router);

        this.webSocketManager = new noietime.syncmoney.web.websocket.WebSocketManager(plugin);
        this.webSocketManager.init();
        routeRegistry.setWebSocketManager(webSocketManager);

        this.sseManager = new noietime.syncmoney.web.websocket.SseManager(plugin);
        this.sseManager.setApiKey(config.getApiKey());
        this.sseManager.setTokenHandler(wsTokenHandler);
        this.sseManager.init();
        routeRegistry.setSseManager(sseManager);

        plugin.getLogger().fine(
                plugin.getMessage("web.server.route-count").replace("{count}", String.valueOf(router.getRouteCount())));
    }

    /**
     * Send JSON response (used for the unauthenticated health probe route).
     */
    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }

    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return server != null;
    }

    /**
     * Get the router for adding custom routes.
     */
    public HttpHandlerRegistry getRouter() {
        return router;
    }

    /**
     * Get the web root path (dist/ directory).
     */
    public Path getWebRoot() {
        return webRoot;
    }

    /**
     * Get the WebSocket manager.
     */
    public noietime.syncmoney.web.websocket.WebSocketManager getWebSocketManager() {
        return webSocketManager;
    }

    /**
     * Get the SSE manager.
     */
    public noietime.syncmoney.web.websocket.SseManager getSseManager() {
        return sseManager;
    }

    /**
     * Reload the web root path from config.
     * Re-reads the dist/ directory and extracts default files if needed.
     * This allows hot-reloading the web frontend after a rebuild.
     */
    public void reloadWebRoot() throws IOException {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        webRoot = config.getWebPath(dataFolder).resolve("dist").toAbsolutePath().normalize();
        staticFileHandler.setWebRoot(webRoot);

        if (!Files.exists(webRoot)) {
            Files.createDirectories(webRoot);
        }

        extractDefaultWebFiles(webRoot);
        plugin.getLogger().fine("Web root reloaded: " + webRoot);
    }
}
