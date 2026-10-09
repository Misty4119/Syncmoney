package noietime.syncmoney.web.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.io.Sender;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.security.ApiKeyAuthFilter;
import noietime.syncmoney.web.websocket.WebSocketManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebRouteRegistryTest {

    @Test
    void authenticatedRestHandlersAreDispatchedOffUndertowIoThread() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        AtomicBoolean ioThread = new AtomicBoolean(true);
        when(exchange.isInIoThread()).thenAnswer(ignored -> ioThread.get());
        when(exchange.getRequestMethod()).thenReturn(new HttpString("GET"));
        when(exchange.getRelativePath()).thenReturn("/api/storage-backed");
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            ioThread.set(false);
            task.run();
            return exchange;
        }).when(exchange).dispatch(any(Runnable.class));

        WebAdminConfig config = mock(WebAdminConfig.class);
        when(config.getCorsAllowedOrigins()).thenReturn("https://admin.example");
        ApiKeyAuthFilter authFilter = mock(ApiKeyAuthFilter.class);
        when(authFilter.authenticate(exchange)).thenReturn(true);
        HttpHandlerRegistry router = new HttpHandlerRegistry();
        AtomicBoolean handlerRanOffIoThread = new AtomicBoolean();
        router.get("api/storage-backed", ignored -> handlerRanOffIoThread.set(!ioThread.get()));
        RouteRegistry registry = new RouteRegistry(mock(Syncmoney.class), config, router,
                authFilter, new CorsHandler(), mock(StaticFileHandler.class));

        registry.handle(exchange);

        assertTrue(handlerRanOffIoThread.get());
    }

    @Test
    void corsPreflightPreservesConfiguredOriginAndHeaders() {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        CorsHandler handler = new CorsHandler();

        handler.handlePreflight(exchange, "https://admin.example");

        assertTrue(responseHeaders.getFirst("Access-Control-Allow-Origin").equals("https://admin.example"));
        assertTrue(responseHeaders.getFirst("Access-Control-Allow-Methods").contains("OPTIONS"));
        assertTrue(responseHeaders.getFirst("Access-Control-Allow-Headers").contains("Authorization"));
    }
    @Test
    void optionsRequestToWebSocketEndpointReturnsUnavailableInsteadOfCorsPreflight() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        when(exchange.isInIoThread()).thenReturn(false);
        when(exchange.getRequestMethod()).thenReturn(new HttpString("OPTIONS"));
        when(exchange.getRelativePath()).thenReturn("/ws");
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        WebAdminConfig config = mock(WebAdminConfig.class);
        when(config.getCorsAllowedOrigins()).thenReturn("https://admin.example");
        RouteRegistry registry = new RouteRegistry(plugin, config, new HttpHandlerRegistry(),
                mock(ApiKeyAuthFilter.class), new CorsHandler(), mock(StaticFileHandler.class));
        registry.setWebSocketManager(new WebSocketManager(plugin));

        registry.handle(exchange);

        verify(exchange).setStatusCode(503);
        verify(exchange, never()).setStatusCode(204);
        verify(sender).send(org.mockito.ArgumentMatchers.contains("WEBSOCKET_UNAVAILABLE"));
    }
}
