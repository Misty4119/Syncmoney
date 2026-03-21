package noietime.syncmoney.web.server;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.builder.WebBuilder;
import noietime.syncmoney.web.builder.WebDownloader;
import noietime.syncmoney.web.builder.WebVersionChecker;
import noietime.syncmoney.web.security.ApiKeyAuthFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web Admin HTTP Server using Undertow.
 * Handles static files and REST API requests.
 */
public final class WebAdminServer {

    private final Syncmoney plugin;
    private final WebAdminConfig config;
    private final HttpHandlerRegistry router;
    private final ApiKeyAuthFilter authFilter;
    private Undertow server;
    private Path webRoot;
    private noietime.syncmoney.web.websocket.WebSocketManager webSocketManager;
    private noietime.syncmoney.web.websocket.SseManager sseManager;
    private noietime.syncmoney.web.api.settings.SettingsApiHandler settingsHandler;

    private final LRUCache<Path, byte[]> fileCache = new LRUCache<>(100);

    public WebAdminServer(Syncmoney plugin, WebAdminConfig config, ApiKeyAuthFilter authFilter) {
        this.plugin = plugin;
        this.config = config;
        this.router = new HttpHandlerRegistry();
        this.authFilter = authFilter;
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
                .setHandler(this::handleRequest)
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
            "AuditLogView-CKNjhOaC.js","AuditLogView-CKNjhOaC.js.map","AuditLogView-pgZF1GR4.css","Badge.vue_vue_type_script_setup_true_lang-Bvno6wpb.js","Badge.vue_vue_type_script_setup_true_lang-Bvno6wpb.js.map","Button.vue_vue_type_script_setup_true_lang-CqcN-6ZE.js","Button.vue_vue_type_script_setup_true_lang-CqcN-6ZE.js.map","Card-CmEoH9OM.js","Card-CmEoH9OM.js.map","Card-DwRmiknp.css","CentralDashboardView-B0oySPGk.js","CentralDashboardView-B0oySPGk.js.map","ConfigView-N-OIYDoZ.css","ConfigView-zTYpVSr6.js","ConfigView-zTYpVSr6.js.map","DashboardView-BJD92yfO.js","DashboardView-BJD92yfO.js.map","EmptyState.vue_vue_type_script_setup_true_lang-BIBaXbI1.js","EmptyState.vue_vue_type_script_setup_true_lang-BIBaXbI1.js.map","globe-DULhj_a5.js","globe-DULhj_a5.js.map","index-D7f23sMO.css","index-MvK20Lr7.js","index-MvK20Lr7.js.map","LoginView-DN6bcIbT.js","LoginView-DN6bcIbT.js.map","NodesManagementView-BGwNiV34.css","NodesManagementView-DOjyRCkp.js","NodesManagementView-DOjyRCkp.js.map","NotFoundView-D2U4f3bo.css","NotFoundView-DiX0QO3I.js","NotFoundView-DiX0QO3I.js.map","SettingsView-Dp-9T2ph.js","SettingsView-Dp-9T2ph.js.map","shield-VSZe3MPB.js","shield-VSZe3MPB.js.map","Skeleton.vue_vue_type_script_setup_true_lang-CHV-Xfe1.js","Skeleton.vue_vue_type_script_setup_true_lang-CHV-Xfe1.js.map","StatCard.vue_vue_type_script_setup_true_lang-DVViQwdk.js","StatCard.vue_vue_type_script_setup_true_lang-DVViQwdk.js.map","SystemStatusView-DrJoglIB.js","SystemStatusView-DrJoglIB.js.map","ui-vendor-B_yCxNdJ.js","ui-vendor-B_yCxNdJ.js.map","users-DRHaHHeb.js","users-DRHaHHeb.js.map","vue-vendor-BfI-xpX_.js","vue-vendor-BfI-xpX_.js.map"
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
        fileCache.clear();
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

        this.sseManager = new noietime.syncmoney.web.websocket.SseManager(plugin);
        this.sseManager.setApiKey(config.getApiKey());
        this.sseManager.setTokenHandler(wsTokenHandler);
        this.sseManager.init();

        plugin.getLogger().fine(
                plugin.getMessage("web.server.route-count").replace("{count}", String.valueOf(router.getRouteCount())));
    }

    /**
     * Handle incoming HTTP requests.
     */
    private void handleRequest(HttpServerExchange exchange) throws Exception {
        try {
            String path = exchange.getRelativePath();

            if ("OPTIONS".equals(exchange.getRequestMethod().toString())) {

                String allowedOrigins = config.getCorsAllowedOrigins();
                exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Origin"), allowedOrigins);
                exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Methods"),
                        "GET, POST, PUT, DELETE, OPTIONS");
                exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Headers"),
                        "Content-Type, Authorization");
                exchange.setStatusCode(204);
                return;
            }

            String pathForRoute = path.startsWith("/") ? path.substring(1) : path;

            if (path.startsWith("sse") || pathForRoute.startsWith("sse") ||
                path.startsWith("api/sse") || pathForRoute.startsWith("api/sse")) {
                if (sseManager != null) {
                    sseManager.createHandler().handleRequest(exchange);
                }
            } else if (path.startsWith("ws") || pathForRoute.startsWith("ws")) {
                if (webSocketManager != null) {
                    webSocketManager.createHandler().handleRequest(exchange);
                }
            } else if (path.startsWith("api/") || pathForRoute.startsWith("api/")) {
                if (authFilter != null && !authFilter.authenticate(exchange)) {
                    authFilter.sendUnauthorized(exchange);
                    return;
                }
                router.handle(exchange);
            } else if (path.equals("health") || pathForRoute.equals("health")) {
                router.handle(exchange);
            } else {
                serveStatic(exchange, path);
            }
        } catch (Exception e) {
            plugin.getLogger().severe(plugin.getMessage("web.server.error-request").replace("{error}", e.getMessage()));
            try {
                if (!exchange.isComplete()) {
                    exchange.setStatusCode(500);
                    sendJson(exchange,
                            "{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal server error\"}}");
                }
            } catch (IllegalStateException ignored) {

            }
        }
    }

    /**
     * Serve static files from web root.
     */
    private void serveStatic(HttpServerExchange exchange, String path) throws IOException {
        String decodedPath;
        try {
            decodedPath = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("Bad Request: Invalid URL encoding");
            return;
        }


        String sanitizedPath = decodedPath.replaceAll("[^a-zA-Z0-9._/-]", "");


        if (sanitizedPath.startsWith("/")) {
            sanitizedPath = sanitizedPath.substring(1);
        }

        if (sanitizedPath.isEmpty() || sanitizedPath.equals("/")) {
            sanitizedPath = "index.html";
        }


        Path file = webRoot.resolve(sanitizedPath).normalize();


        if (!file.startsWith(webRoot)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Forbidden");
            return;
        }


        if (!Files.exists(file) || Files.isDirectory(file)) {

            Path indexFile = webRoot.resolve("index.html");
            if (Files.exists(indexFile) && !Files.isDirectory(indexFile)) {
                serveFileContent(exchange, indexFile, "index.html");
                return;
            }
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("Not Found: " + sanitizedPath);
            return;
        }


        serveFileContent(exchange, file, sanitizedPath);
    }

    /**
     * Serve file content to response.
     * Uses pre-loaded file cache to avoid UT000126 error on IO thread.
     */
    private void serveFileContent(HttpServerExchange exchange, Path file, String path) throws IOException {
        String contentType = guessContentType(path);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);

        String allowedOrigins = config.getCorsAllowedOrigins();
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Origin"), allowedOrigins);
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Methods"), "GET, OPTIONS");
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Allow-Headers"), "Content-Type, Authorization");


        byte[] cached = fileCache.get(file);
        if (cached != null) {
            sendFileResponse(exchange, cached, contentType.startsWith("image/") || contentType.startsWith("font/"));
            return;
        }


        try {
            byte[] bytes = Files.readAllBytes(file);

            fileCache.put(file, bytes);
            sendFileResponse(exchange, bytes, path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".gif") || path.endsWith(".ico") || path.endsWith(".woff2") ||
                    path.endsWith(".woff") || path.endsWith(".ttf"));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Error reading file: " + e.getMessage());
        }
    }

    /**
     * Send file response data.
     */
    private void sendFileResponse(HttpServerExchange exchange, byte[] data, boolean isBinary) {
        if (isBinary) {
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(data));
        } else {
            exchange.getResponseSender().send(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Guess content type from file extension.
     */
    private String guessContentType(String path) {
        if (path.endsWith(".html"))
            return "text/html;charset=UTF-8";
        if (path.endsWith(".css"))
            return "text/css;charset=UTF-8";
        if (path.endsWith(".js"))
            return "application/javascript;charset=UTF-8";
        if (path.endsWith(".json"))
            return "application/json;charset=UTF-8";
        if (path.endsWith(".png"))
            return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg"))
            return "image/jpeg";
        if (path.endsWith(".svg"))
            return "image/svg+xml";
        if (path.endsWith(".ico"))
            return "image/x-icon";
        if (path.endsWith(".woff"))
            return "font/woff";
        if (path.endsWith(".woff2"))
            return "font/woff2";
        if (path.endsWith(".ttf"))
            return "font/ttf";
        return "text/plain;charset=UTF-8";
    }

    /**
     * Send JSON response.
     */
    protected void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }

    /**
     * Send 401 Unauthorized response.
     */
    protected void sendUnauthorized(HttpServerExchange exchange) {
        exchange.setStatusCode(401);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(
                "{\"success\":false,\"error\":{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Missing or invalid authorization header\"}}");
    }

    /**
     * Extract path parameter from URL.
     */
    private String extractPathParam(HttpServerExchange exchange, String name) {
        return router.extractPathParam(exchange, name);
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

        if (!Files.exists(webRoot)) {
            Files.createDirectories(webRoot);
        }

        extractDefaultWebFiles(webRoot);
        plugin.getLogger().fine("Web root reloaded: " + webRoot);
    }

    /**
     * Simple LRU Cache implementation using LinkedHashMap.
     * [SEC-09 FIX] Prevents memory exhaustion from unbounded cache.
     */
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxSize;

        public LRUCache(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }
}
