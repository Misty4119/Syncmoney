package noietime.syncmoney.web.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RateLimiter.
 */
class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(5);
    }

    @Test
    void testAllowsRequestsWithinLimit() {
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.isAllowed("test-client"),
                    "Request " + (i + 1) + " should be allowed");
        }
    }

    // RateLimiter(5) => maxRequests=5, burstCapacity=min(5/2,5)=2.
    // Total allowance before blocking = burst(2) + regular(5) = 7 requests.
    private static final int TOTAL_ALLOWANCE = 7;

    @Test
    void testBlocksRequestsOverLimit() {
        for (int i = 0; i < TOTAL_ALLOWANCE; i++) {
            assertTrue(rateLimiter.isAllowed("test-client"),
                    "Request " + (i + 1) + " within burst+regular allowance should pass");
        }

        assertFalse(rateLimiter.isAllowed("test-client"),
                "Request over burst+regular allowance should be blocked");
    }

    @Test
    void testSeparateClientLimits() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed("client1");
        }

        assertTrue(rateLimiter.isAllowed("client2"),
                "Different client should have separate limit");
    }

    @Test
    void testMultipleClients() {
        for (int i = 0; i < TOTAL_ALLOWANCE; i++) {
            assertTrue(rateLimiter.isAllowed("clientA"));
        }
        assertFalse(rateLimiter.isAllowed("clientA"));

        for (int i = 0; i < TOTAL_ALLOWANCE; i++) {
            assertTrue(rateLimiter.isAllowed("clientB"));
        }
        assertFalse(rateLimiter.isAllowed("clientB"));
    }

    @Test
    void testEmptyClientId() {
        for (int i = 0; i < TOTAL_ALLOWANCE; i++) {
            assertTrue(rateLimiter.isAllowed(""));
        }
        assertFalse(rateLimiter.isAllowed(""));
    }

    @Test
    void testNullClientId() {
        // Null is treated as a single shared anonymous bucket (no NPE) and is
        // still rate-limited like any other client.
        for (int i = 0; i < TOTAL_ALLOWANCE; i++) {
            assertTrue(rateLimiter.isAllowed(null));
        }
        assertFalse(rateLimiter.isAllowed(null));
    }

    @Test
    void testVeryHighLimit() {
        RateLimiter highLimiter = new RateLimiter(1000);

        for (int i = 0; i < 100; i++) {
            assertTrue(highLimiter.isAllowed("test-client"),
                    "Request " + i + " should be allowed");
        }
    }

    @Test
    void testVeryLowLimit() {
        RateLimiter lowLimiter = new RateLimiter(1);

        assertTrue(lowLimiter.isAllowed("test-client"));
        assertFalse(lowLimiter.isAllowed("test-client"));
    }

    @Test
    void testZeroLimit() {
        RateLimiter zeroLimiter = new RateLimiter(0);

        assertFalse(zeroLimiter.isAllowed("test-client"));
    }

    @Test
    void testNegativeLimit() {
        RateLimiter negativeLimiter = new RateLimiter(-1);

        assertFalse(negativeLimiter.isAllowed("test-client"));
    }
}
