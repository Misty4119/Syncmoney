package noietime.syncmoney.web.api.auth;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.server.WebAdminConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSessionTokenTest {

    @Test
    void tokenIsValidForLessThanSixtySecondsAndExpiresAtTheBoundary() {
        WsTokenHandler handler = new WsTokenHandler(mock(Syncmoney.class), mock(WebAdminConfig.class));
        long issuedAt = System.currentTimeMillis();
        String token = handler.issueToken(issuedAt);

        assertTrue(handler.validateToken(token, issuedAt));

        String expiringToken = handler.issueToken(issuedAt);
        assertFalse(handler.validateToken(expiringToken, issuedAt + 60_000L));
        assertFalse(handler.validateToken(expiringToken, issuedAt + 60_001L));
    }

    @Test
    void concurrentValidationConsumesTokenExactlyOnce() throws Exception {
        WsTokenHandler handler = new WsTokenHandler(mock(Syncmoney.class), mock(WebAdminConfig.class));
        long now = System.currentTimeMillis();
        String token = handler.issueToken(now);
        int contenders = 32;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
            @SuppressWarnings("unchecked")
            Future<?>[] results = new Future<?>[contenders];
            for (int i = 0; i < contenders; i++) {
                results[i] = executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("validation start latch timed out");
                    }
                    if (handler.validateToken(token, now)) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) {
                result.get(5, TimeUnit.SECONDS);
            }
        }

        assertEquals(1, accepted.get());
        assertFalse(handler.validateToken(token, now));
    }

}
