package noietime.syncmoney.web.websocket;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.io.Sender;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.server.WebAdminConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SseWebContractTest {

    @Test
    void websocketUpgradeWithAnyNonemptyTokenReturnsUnavailableWithoutUpgrade() throws Exception {
        WebSocketManager manager = new WebSocketManager(mock(Syncmoney.class));
        assertFalse(manager.isEnabled());
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        when(exchange.getRelativePath()).thenReturn("/ws");
        when(exchange.getQueryString()).thenReturn("token=not-a-session-token");
        when(exchange.getRequestHeaders()).thenReturn(new HeaderMap().put(Headers.UPGRADE, "websocket"));
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        manager.createHandler().handleRequest(exchange);

        verify(exchange).setStatusCode(503);
        verify(exchange, never()).setStatusCode(101);
        verify(sender).send(org.mockito.ArgumentMatchers.contains("WEBSOCKET_UNAVAILABLE"));
    }

    @Test
    void missingApiKeyNeverAuthenticatesSse() {
        SseManager manager = new SseManager(mock(Syncmoney.class));
        HttpServerExchange exchange = exchangeWithBearer("operator-key");

        assertFalse(manager.validateApiKey(exchange));
        manager.setApiKey("");
        assertFalse(manager.validateApiKey(exchange));
    }

    @Test
    void sseAcceptsConfiguredBearerApiKey() {
        SseManager manager = new SseManager(mock(Syncmoney.class));
        manager.setApiKey("operator-key");
        HttpServerExchange apiKeyExchange = exchangeWithBearer("operator-key");
        assertTrue(manager.validateApiKey(apiKeyExchange));
    }

    @Test
    void unauthenticatedSseRequestReturns401AndKeepsTheExistingErrorEvent() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SseWebContractTest"));
        SseManager manager = new SseManager(plugin);
        manager.setApiKey("");
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap requestHeaders = new HeaderMap().put(Headers.ACCEPT, "text/event-stream");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.isInIoThread()).thenReturn(false);
        when(exchange.getOutputStream()).thenReturn((OutputStream) body);

        manager.createHandler().handleRequest(exchange);

        verify(exchange).setStatusCode(401);
        assertTrue(body.toString(java.nio.charset.StandardCharsets.UTF_8).contains("unauthenticated"));
    }

    @Test
    void keepaliveUsesTheDocumentedFifteenSecondCommentFrame() {
        SseManager manager = new SseManager(mock(Syncmoney.class));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SseManager.SseConnection connection = new SseManager.SseConnection("test", output);

        manager.sendKeepalive(connection);

        assertEquals(15_000L, SseManager.KEEPALIVE_INTERVAL_MILLIS);
        assertEquals(": keepalive\n\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static HttpServerExchange exchangeWithBearer(String bearer) {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headers = new HeaderMap();
        headers.put(Headers.AUTHORIZATION, "Bearer " + bearer);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        return exchange;
    }
}
