package noietime.syncmoney.web.api.nodes;

import com.sun.net.httpserver.HttpServer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.io.Sender;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;
import noietime.syncmoney.web.server.HttpHandlerRegistry;
import noietime.syncmoney.web.api.system.SystemApiHandler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebNodeProxyAuthTest {

    private static final int OVERSIZED_PROXY_RESPONSE_BYTES = 16 * 1024 * 1024;

    @Test
    void nodeProxyUsesConfiguredNodeBearerKey() {
        HttpRequest request = NodeProxyHandler.buildNodeRequest(
                "https://node.example/api/system/status", "node-secret", "GET", null);

        assertEquals("Bearer node-secret", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("GET", request.method());
    }

    @Test
    void nodeProxySerializesStructuredRequestBodiesAsJson() throws Exception {
        HttpRequest request = NodeProxyHandler.buildNodeRequest(
                "https://node.example/api/nodes/manage", "node-secret", "POST",
                Map.of("items", List.of("first", Map.of("count", 2))));

        assertEquals("{\"items\":[\"first\",{\"count\":2}]}", readBody(request));
    }

    @Test
    void nodeProxySerializesJsonStringBodiesAsJsonStrings() throws Exception {
        HttpRequest request = NodeProxyHandler.buildNodeRequest(
                "https://node.example/api/nodes/manage", "node-secret", "PATCH", "player-name");

        assertEquals("\"player-name\"", readBody(request));
    }

    @Test
    void nodeProxyMethodPermissionsSeparateReadAndMutationMethods() {
        assertTrue(NodeProxyHandler.hasProxyMethodPermission(
                "GET", permission -> permission.equals("syncmoney.web.nodes.view")));
        assertFalse(NodeProxyHandler.hasProxyMethodPermission(
                "POST", permission -> permission.equals("syncmoney.web.nodes.view")));
        assertTrue(NodeProxyHandler.hasProxyMethodPermission(
                "post", permission -> permission.equals("syncmoney.web.nodes.manage")));
        assertTrue(NodeProxyHandler.hasProxyMethodPermission(
                "DELETE", permission -> permission.equals("syncmoney.web.central")));
        assertThrows(IllegalArgumentException.class,
                () -> NodeProxyHandler.hasProxyMethodPermission("TRACE", ignored -> true));
    }

    @Test
    void endpointPermissionLookupUsesTheMostSpecificConfiguredPath() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getConfig()).thenReturn(new YamlConfiguration());
        PermissionChecker permissions = new PermissionChecker(plugin, config);

        assertEquals("syncmoney.web.nodes.manage",
                permissions.getRequiredPermission("api/nodes/manage"));
        assertEquals("syncmoney.web.nodes.view",
                permissions.getRequiredPermission("api/nodes/status"));
        assertTrue(permissions.getRequiredPermission("api/unmapped") == null);
    }

    @Test
    void viewOnlyPermissionCannotProxyMutatingRequestWithConfiguredNodeKey() throws Exception {
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger proxiedRequests = new AtomicInteger();
        remote.createContext("/", request -> {
            proxiedRequests.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, response.length);
            try (var output = request.getResponseBody()) {
                output.write(response);
            }
        });
        remote.start();

        try {
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("WebNodeProxyAuthTest"));
            SyncmoneyConfig config = mock(SyncmoneyConfig.class);
            SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
            node.name = "synthetic-node";
            node.url = "http://127.0.0.1:" + remote.getAddress().getPort();
            node.apiKey = "synthetic-node-key";
            node.enabled = true;
            when(config.getNodes()).thenReturn(List.of(node));

            PermissionChecker permissions = mock(PermissionChecker.class);
            when(permissions.hasPermission("syncmoney.web.nodes.view")).thenReturn(true);
            when(permissions.hasPermission("view-only", "syncmoney.web.nodes.view")).thenReturn(true);
            when(permissions.hasPermission("view-only", "syncmoney.web.nodes.manage")).thenReturn(false);
            when(permissions.hasPermission("view-only", "syncmoney.web.central")).thenReturn(false);

            HttpServerExchange exchange = mock(HttpServerExchange.class);
            HeaderMap requestHeaders = new HeaderMap();
            requestHeaders.put(Headers.AUTHORIZATION, "Bearer view-only");
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            when(exchange.getRelativePath()).thenReturn("/api/nodes/0/proxy");
            when(exchange.getInputStream()).thenReturn(new ByteArrayInputStream(
                    "{\"method\":\"POST\",\"path\":\"/api/system/status\",\"body\":{}}"
                            .getBytes(StandardCharsets.UTF_8)));
            when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
            when(exchange.getResponseSender()).thenReturn(mock(Sender.class));
            when(exchange.dispatch(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return exchange;
            });

            new NodeProxyHandler(plugin, config, permissions, null).handleNodeProxy(exchange);

            verify(exchange).setStatusCode(403);
            assertEquals(0, proxiedRequests.get());
        } finally {
            remote.stop(0);
        }
    }

    @Test
    void viewOnlyPermissionCannotProxyRemoteAdminReadPath() throws Exception {
        assertViewOnlyProxyDenied("/api/system/status", "syncmoney.admin");
    }

    @Test
    void viewOnlyPermissionCannotProxyUnmappedRemotePath() throws Exception {
        assertViewOnlyProxyDenied("/api/unmapped", null);
    }

    @Test
    void centralPermissionCanProxyMappedAdminAndUnmappedPaths() throws Exception {
        assertProxyPermission("/api/system/status", "syncmoney.admin", true, 200, 1);
        assertProxyPermission("/api/unmapped", null, true, 200, 1);
    }

    private static void assertViewOnlyProxyDenied(String targetPath, String requiredPermission)
            throws Exception {
        assertProxyPermission(targetPath, requiredPermission, false, 403, 0);
    }

    private static void assertProxyPermission(String targetPath, String requiredPermission,
            boolean hasCentralPermission, int expectedStatus, int expectedRequests) throws Exception {
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger proxiedRequests = new AtomicInteger();
        remote.createContext("/", request -> {
            proxiedRequests.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, response.length);
            try (var output = request.getResponseBody()) {
                output.write(response);
            }
        });
        remote.start();

        try {
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("WebNodeProxyAuthTest"));
            SyncmoneyConfig config = mock(SyncmoneyConfig.class);
            SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
            node.name = "synthetic-node";
            node.url = "http://127.0.0.1:" + remote.getAddress().getPort();
            node.apiKey = "enc:opaque-node-key";
            node.enabled = true;
            when(config.getNodes()).thenReturn(List.of(node));
            when(config.getConfig()).thenReturn(new YamlConfiguration());

            PermissionChecker permissions = mock(PermissionChecker.class);
            when(permissions.hasPermission("syncmoney.web.nodes.view")).thenReturn(true);
            when(permissions.hasPermission("view-only", "syncmoney.web.nodes.view")).thenReturn(true);
            when(permissions.hasPermission("view-only", "syncmoney.web.central"))
                    .thenReturn(hasCentralPermission);
            when(permissions.hasPermission("view-only", "syncmoney.admin")).thenReturn(false);
            when(permissions.getRequiredPermission(targetPath.substring(1))).thenReturn(requiredPermission);
            NodeApiKeyStore apiKeyStore = mock(NodeApiKeyStore.class);

            HttpServerExchange exchange = mock(HttpServerExchange.class);
            HeaderMap requestHeaders = new HeaderMap();
            requestHeaders.put(Headers.AUTHORIZATION, "Bearer view-only");
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            when(exchange.getRelativePath()).thenReturn("/api/nodes/0/proxy");
            when(exchange.getInputStream()).thenReturn(new ByteArrayInputStream(
                    ("{\"method\":\"GET\",\"path\":\"" + targetPath + "\"}")
                            .getBytes(StandardCharsets.UTF_8)));
            when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
            when(exchange.getResponseSender()).thenReturn(mock(Sender.class));
            when(exchange.dispatch(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return exchange;
            });

            new NodeProxyHandler(plugin, config, permissions, apiKeyStore).handleNodeProxy(exchange);

            verify(exchange).setStatusCode(expectedStatus);
            if (expectedStatus == 403) {
                verify(apiKeyStore, org.mockito.Mockito.never()).decrypt(any(), any());
            }
            assertEquals(expectedRequests, proxiedRequests.get());
        } finally {
            remote.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedProxyBodyIsRejectedBeforeCredentialUseOrRemoteDispatch(boolean contentLengthKnown)
            throws Exception {
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger proxiedRequests = new AtomicInteger();
        remote.createContext("/", request -> {
            proxiedRequests.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, response.length);
            try (var output = request.getResponseBody()) {
                output.write(response);
            }
        });
        remote.start();

        try {
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("WebNodeProxyAuthTest"));
            SyncmoneyConfig config = mock(SyncmoneyConfig.class);
            SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
            node.name = "synthetic-node";
            node.url = "http://127.0.0.1:" + remote.getAddress().getPort();
            node.apiKey = "enc:opaque-node-key";
            node.enabled = true;
            when(config.getNodes()).thenReturn(List.of(node));
            when(config.getConfig()).thenReturn(new YamlConfiguration());

            PermissionChecker permissions = mock(PermissionChecker.class);
            when(permissions.hasPermission("view-only", "syncmoney.web.nodes.view")).thenReturn(true);
            when(permissions.hasPermission("view-only", "syncmoney.web.central")).thenReturn(false);
            when(permissions.getRequiredPermission("api/nodes")).thenReturn("syncmoney.web.nodes.view");
            NodeApiKeyStore apiKeyStore = mock(NodeApiKeyStore.class);

            byte[] oversizedBody = new byte[1024 * 1024 + 1];
            java.util.Arrays.fill(oversizedBody, (byte) ' ');
            byte[] prefix = "{\"method\":\"GET\",\"path\":\"/api/nodes\"}".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(prefix, 0, oversizedBody, 0, prefix.length);
            AtomicInteger bytesRead = new AtomicInteger();
            ByteArrayInputStream input = new ByteArrayInputStream(oversizedBody) {
                @Override
                public synchronized int read(byte[] buffer, int offset, int length) {
                    int count = super.read(buffer, offset, length);
                    if (count > 0) bytesRead.addAndGet(count);
                    return count;
                }

                @Override
                public synchronized int read() {
                    int value = super.read();
                    if (value >= 0) bytesRead.incrementAndGet();
                    return value;
                }
            };

            HttpServerExchange exchange = mock(HttpServerExchange.class);
            HeaderMap requestHeaders = new HeaderMap();
            requestHeaders.put(Headers.AUTHORIZATION, "Bearer view-only");
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            when(exchange.getRelativePath()).thenReturn("/api/nodes/0/proxy");
            when(exchange.getRequestContentLength()).thenReturn(
                    contentLengthKnown ? (long) oversizedBody.length : -1L);
            when(exchange.getInputStream()).thenReturn(input);
            when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
            when(exchange.getResponseSender()).thenReturn(mock(Sender.class));
            when(exchange.dispatch(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return exchange;
            });

            new NodeProxyHandler(plugin, config, permissions, apiKeyStore).handleNodeProxy(exchange);

            verify(exchange).setStatusCode(413);
            verify(apiKeyStore, org.mockito.Mockito.never()).decrypt(any(), any());
            assertEquals(contentLengthKnown ? 0 : 1024 * 1024 + 1, bytesRead.get());
            assertEquals(0, proxiedRequests.get());
        } finally {
            remote.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedProxyResponseIsRejectedWithoutForwardingPartialBody(boolean contentLengthKnown)
            throws Exception {
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicLong bytesSent = new AtomicLong();
        CountDownLatch responseFinished = new CountDownLatch(1);
        remote.createContext("/api/nodes/status", request -> {
            try {
                request.getResponseHeaders().set("Content-Type", "application/json");
                request.sendResponseHeaders(200,
                        contentLengthKnown ? OVERSIZED_PROXY_RESPONSE_BYTES : 0);
                byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) 'x');
                try (var output = request.getResponseBody()) {
                    int remaining = OVERSIZED_PROXY_RESPONSE_BYTES;
                    while (remaining > 0) {
                        int length = Math.min(chunk.length, remaining);
                        output.write(chunk, 0, length);
                        output.flush();
                        bytesSent.addAndGet(length);
                        remaining -= length;
                    }
                }
            } catch (IOException clientClosedOversizedResponse) {
                // The proxy should close an over-limit upstream body promptly.
            } finally {
                responseFinished.countDown();
            }
        });
        remote.start();

        try {
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("WebNodeProxyAuthTest"));
            SyncmoneyConfig config = mock(SyncmoneyConfig.class);
            SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
            node.name = "synthetic-node";
            node.url = "http://127.0.0.1:" + remote.getAddress().getPort();
            node.apiKey = "synthetic-node-key";
            node.enabled = true;
            when(config.getNodes()).thenReturn(List.of(node));

            PermissionChecker permissions = mock(PermissionChecker.class);
            when(permissions.hasPermission("view-only", "syncmoney.web.nodes.view")).thenReturn(true);
            when(permissions.getRequiredPermission("api/nodes/status"))
                    .thenReturn("syncmoney.web.nodes.view");

            HttpServerExchange exchange = mock(HttpServerExchange.class);
            HeaderMap requestHeaders = new HeaderMap();
            requestHeaders.put(Headers.AUTHORIZATION, "Bearer view-only");
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            when(exchange.getRelativePath()).thenReturn("/api/nodes/0/proxy");
            when(exchange.getInputStream()).thenReturn(new ByteArrayInputStream(
                    "{\"method\":\"GET\",\"path\":\"/api/nodes/status\"}"
                            .getBytes(StandardCharsets.UTF_8)));
            when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
            Sender sender = mock(Sender.class);
            when(exchange.getResponseSender()).thenReturn(sender);
            when(exchange.dispatch(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return exchange;
            });

            new NodeProxyHandler(plugin, config, permissions, null).handleNodeProxy(exchange);

            assertTrue(responseFinished.await(5, TimeUnit.SECONDS));
            verify(exchange).setStatusCode(502);
            org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(sender).send(body.capture());
            assertTrue(body.getValue().contains("PROXY_RESPONSE_TOO_LARGE"));
            assertFalse(body.getValue().contains("xxxxxxxx"));
            assertTrue(bytesSent.get() < OVERSIZED_PROXY_RESPONSE_BYTES,
                    "proxy must close an oversized upstream response before reading it all");
        } finally {
            remote.stop(0);
        }
    }

    @Test
    void nodeProxyResolvesRootRelativeAndRelativeApiPathsAgainstConfiguredOrigin() {
        URI expected = URI.create("https://node.example/api/system/status?details=true");

        assertEquals(expected, NodeProxyHandler.resolveNodeTarget(
                "https://node.example/context", "/api/system/status?details=true"));
        assertEquals(expected, NodeProxyHandler.resolveNodeTarget(
                "https://node.example/context", "api/system/status?details=true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example/api/system/status",
            "//other.example/api/system/status",
            "/api/../admin",
            "/api/%2e%2e/admin",
            "/api/%2525252e%2525252e/admin",
            "/api/system/status#fragment"
    })
    void nodeProxyRejectsAbsoluteAuthorityFragmentAndTraversalPaths(String path) {
        assertThrows(IllegalArgumentException.class,
                () -> NodeProxyHandler.resolveNodeTarget("https://node.example", path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/nodes", "/api/nodes/status"})
    void laterNodesRoutesReplaceDuplicateSystemRoutes(String path) throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getNodes()).thenReturn(List.of());
        when(config.isCentralMode()).thenReturn(false);
        when(config.getConfig()).thenReturn(new YamlConfiguration());
        PermissionChecker permissions = mock(PermissionChecker.class);
        when(permissions.hasPermission("syncmoney.web.nodes.view")).thenReturn(true);

        HttpHandlerRegistry router = new HttpHandlerRegistry();
        new SystemApiHandler(plugin, config, null, null, null, null, new NodeApiKeyStore())
                .registerRoutes(router);
        new NodesApiHandler(plugin, config, permissions, new NodeApiKeyStore()).registerRoutes(router);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        when(exchange.getRequestMethod()).thenReturn(new HttpString("GET"));
        when(exchange.getRelativePath()).thenReturn(path);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        router.handle(exchange);

        verify(config).getNodes();
    }

    private static String readBody(HttpRequest request) throws Exception {
        CompletableFuture<byte[]> body = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable failure) {
                body.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                body.complete(output.toByteArray());
            }
        });
        return new String(body.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    }
}
