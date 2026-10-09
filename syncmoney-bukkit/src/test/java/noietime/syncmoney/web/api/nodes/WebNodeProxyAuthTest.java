package noietime.syncmoney.web.api.nodes;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
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

import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebNodeProxyAuthTest {

    @Test
    void nodeProxyUsesConfiguredNodeBearerKey() {
        HttpRequest request = NodeProxyHandler.buildNodeRequest(
                "https://node.example/api/system/status", "node-secret", "GET", null);

        assertEquals("Bearer node-secret", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("GET", request.method());
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
}
