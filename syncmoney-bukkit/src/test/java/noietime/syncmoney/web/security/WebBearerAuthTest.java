package noietime.syncmoney.web.security;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.io.Sender;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.server.WebAdminConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebBearerAuthTest {

    @Test
    void normalApiAuthenticationRequiresExactBearerKey() throws Exception {
        ApiKeyAuthFilter filter = newFilter(false, 60);
        try {
            assertTrue(filter.authenticate(exchange("Bearer configured-key", null, "192.0.2.4")));
            assertFalse(filter.authenticate(exchange("Basic configured-key", null, "192.0.2.4")));
            assertFalse(filter.authenticate(exchange("Bearer wrong-key", null, "192.0.2.4")));
        } finally {
            filter.getRateLimiter().shutdown();
        }
    }

    @Test
    void forwardedClientIdentityIsIgnoredUnlessTrustedProxyModeIsEnabled() throws Exception {
        ApiKeyAuthFilter directPeerFilter = newFilter(false, 1);
        ApiKeyAuthFilter trustedProxyFilter = newFilter(true, 1);
        try {
            assertTrue(directPeerFilter.authenticate(exchange("Bearer configured-key", "198.51.100.1", "192.0.2.4")));
            assertFalse(directPeerFilter.authenticate(exchange("Bearer configured-key", "198.51.100.2", "192.0.2.4")));

            assertTrue(trustedProxyFilter.authenticate(exchange("Bearer configured-key", "198.51.100.1", "192.0.2.4")));
            assertTrue(trustedProxyFilter.authenticate(exchange("Bearer configured-key", "198.51.100.2", "192.0.2.4")));
        } finally {
            directPeerFilter.getRateLimiter().shutdown();
            trustedProxyFilter.getRateLimiter().shutdown();
        }
    }

    private static ApiKeyAuthFilter newFilter(boolean trustProxy, int rateLimit) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("web-admin.security.api-key", "configured-key");
        yaml.set("web-admin.security.rate-limit.enabled", true);
        yaml.set("web-admin.security.rate-limit.requests-per-minute", rateLimit);
        yaml.set("web-admin.security.trust-proxy", trustProxy);

        WebAdminConfig config = new WebAdminConfig();
        config.load(yaml);
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getConfig()).thenReturn(yaml);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WebBearerAuthTest"));
        return new ApiKeyAuthFilter(plugin, config);
    }

    private static HttpServerExchange exchange(String authorization, String forwardedFor, String remoteIp) throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headers = new HeaderMap().put(Headers.AUTHORIZATION, authorization);
        if (forwardedFor != null) {
            headers.put(new HttpString("X-Forwarded-For"), forwardedFor);
        }
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getResponseSender()).thenReturn(mock(Sender.class));
        when(exchange.getSourceAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(remoteIp), 25565));
        return exchange;
    }
}
