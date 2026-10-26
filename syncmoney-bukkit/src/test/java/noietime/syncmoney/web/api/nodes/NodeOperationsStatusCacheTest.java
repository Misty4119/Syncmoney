package noietime.syncmoney.web.api.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.security.NodeApiKeyStore;
import noietime.syncmoney.web.security.PermissionChecker;
import noietime.syncmoney.web.server.HttpHandlerRegistry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeOperationsStatusCacheTest {

    @Test
    void healthStatusUpdateIsVisibleInNodeListResponse() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        YamlConfiguration yaml = new YamlConfiguration();
        SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
        node.name = "remote";
        node.url = "https://node.example";
        node.apiKey = "node-key";
        node.enabled = true;
        when(config.getNodes()).thenReturn(List.of(node));
        when(config.isCentralMode()).thenReturn(true);
        when(config.getConfig()).thenReturn(yaml);
        PermissionChecker permissions = mock(PermissionChecker.class);
        when(permissions.hasPermission("syncmoney.web.nodes.view")).thenReturn(true);

        NodesApiHandler api = new NodesApiHandler(plugin, config, permissions, new NodeApiKeyStore());
        api.updateNodeStatus(node.url, "online");
        HttpHandlerRegistry router = new HttpHandlerRegistry();
        api.registerRoutes(router);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        when(exchange.getRequestMethod()).thenReturn(new HttpString("GET"));
        when(exchange.getRelativePath()).thenReturn("/api/nodes");
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        router.handle(exchange);

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(sender).send(response.capture());
        assertEquals("online", new ObjectMapper().readTree(response.getValue())
                .at("/data/nodes/0/status").asText());
    }

    @Test
    void nodeViewResponsesRedactUserInfoQueryAndFragmentFromLegacyUrls() throws Exception {
        String userInfo = "synthetic-user:synthetic-password";
        List<String> sensitiveValues = List.of(
                userInfo,
                "synthetic-node-query",
                "synthetic-node-fragment",
                "synthetic-self-query",
                "synthetic-self-fragment");
        String configuredUrl = "https://" + userInfo
                + "@node.example/base?token=synthetic-node-query#synthetic-node-fragment";
        String configuredSelfUrl = "https://central.example/admin"
                + "?token=synthetic-self-query#synthetic-self-fragment";
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("web-admin.central-node.url", configuredSelfUrl);
        SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
        node.name = "remote";
        node.url = configuredUrl;
        node.apiKey = "node-key";
        node.enabled = false;
        when(config.getNodes()).thenReturn(List.of(node));
        when(config.isCentralMode()).thenReturn(true);
        when(config.getConfig()).thenReturn(yaml);
        PermissionChecker permissions = mock(PermissionChecker.class);
        when(permissions.hasPermission("syncmoney.web.nodes.view")).thenReturn(true);

        NodesApiHandler api = new NodesApiHandler(plugin, config, permissions, new NodeApiKeyStore());
        HttpHandlerRegistry router = new HttpHandlerRegistry();
        api.registerRoutes(router);
        ObjectMapper mapper = new ObjectMapper();

        String listResponse = invoke(router, "GET", "/api/nodes");
        var list = mapper.readTree(listResponse);
        assertEquals("https://node.example/base", list.at("/data/nodes/0/url").asText());
        assertEquals("https://central.example/admin", list.at("/data/selfUrl").asText());

        String statusResponse = invoke(router, "GET", "/api/nodes/status");
        var statuses = mapper.readTree(statusResponse).get("data");
        assertTrue(statuses.has("https://node.example/base"));

        String pingResponse = invoke(router, "POST", "/api/nodes/0/ping");
        assertEquals("https://node.example/base", mapper.readTree(pingResponse).at("/data/url").asText());

        for (String sensitiveValue : sensitiveValues) {
            assertFalse(listResponse.contains(sensitiveValue));
            assertFalse(statusResponse.contains(sensitiveValue));
            assertFalse(pingResponse.contains(sensitiveValue));
        }
    }

    private static String invoke(HttpHandlerRegistry router, String method, String path) throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        when(exchange.getRequestMethod()).thenReturn(new HttpString(method));
        when(exchange.getRelativePath()).thenReturn(path);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        router.handle(exchange);

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(sender).send(response.capture());
        return response.getValue();
    }
}
