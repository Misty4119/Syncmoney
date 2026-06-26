package noietime.syncmoney.web.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the bundled Vue SPA static files from the web root.
 *
 * <p>Includes hardened path-traversal protection: the user-supplied path is
 * sanitised, checked for explicit {@code ..} segments, then resolved and
 * normalised; the final canonical path must still be a descendant of the web
 * root or the request is rejected with 403.</p>
 */
public final class StaticFileHandler {

    private final WebAdminConfig config;
    private final CorsHandler corsHandler;
    private volatile Path webRoot;

    private final LRUCache<Path, byte[]> fileCache = new LRUCache<>(100);

    public StaticFileHandler(WebAdminConfig config, CorsHandler corsHandler) {
        this.config = config;
        this.corsHandler = corsHandler;
    }

    public void setWebRoot(Path webRoot) {
        this.webRoot = webRoot;
    }

    public void clearCache() {
        fileCache.clear();
    }

    /**
     * Serve a static file for the given (raw, possibly URL-encoded) request path.
     */
    public void serve(HttpServerExchange exchange, String path) throws IOException {
        String decodedPath;
        try {
            decodedPath = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("Bad Request: Invalid URL encoding");
            return;
        }

        String sanitizedPath = sanitize(decodedPath);

        Path file = resolveSafe(webRoot, sanitizedPath);
        if (file == null) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Forbidden");
            return;
        }

        if (!Files.exists(file) || Files.isDirectory(file)) {
            // SPA fallback: serve index.html for unknown client-side routes.
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
     * Sanitise a decoded request path into a relative path, defaulting to index.html.
     * Mirrors the original sanitisation rules exactly.
     */
    static String sanitize(String decodedPath) {
        String sanitized = decodedPath.replaceAll("[^a-zA-Z0-9._/-]", "");
        if (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.isEmpty() || sanitized.equals("/")) {
            sanitized = "index.html";
        }
        return sanitized;
    }

    /**
     * Resolve a sanitised relative path against the web root, enforcing that the
     * final normalised/canonical path stays within the root (path-traversal guard).
     *
     * @return the resolved absolute path, or {@code null} if the path escapes the
     *         root, contains a {@code ..} segment, or the inputs are null
     */
    static Path resolveSafe(Path webRoot, String relativePath) {
        if (webRoot == null || relativePath == null) {
            return null;
        }
        for (String segment : relativePath.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        Path root = webRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    /**
     * Serve file content to the response, using the pre-loaded LRU cache to avoid
     * blocking IO on the Undertow IO thread.
     */
    private void serveFileContent(HttpServerExchange exchange, Path file, String path) throws IOException {
        String contentType = guessContentType(path);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);

        corsHandler.applyStaticCors(exchange, config.getCorsAllowedOrigins());

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

    private void sendFileResponse(HttpServerExchange exchange, byte[] data, boolean isBinary) {
        if (isBinary) {
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(data));
        } else {
            exchange.getResponseSender().send(new String(data, StandardCharsets.UTF_8));
        }
    }

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
     * Simple LRU Cache implementation using LinkedHashMap.
     * [SEC-09 FIX] Prevents memory exhaustion from unbounded cache.
     */
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxSize;

        LRUCache(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }
}
