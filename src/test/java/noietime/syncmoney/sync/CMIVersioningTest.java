package noietime.syncmoney.sync;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the unified CMI cross-server version strategy.
 */
class CMIVersioningTest {

    @Test
    void generateVersion_isStrictlyIncreasing_forSameCounter() {
        AtomicLong counter = new AtomicLong(0L);
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < 1000; i++) {
            long version = CMIVersioning.generateVersion(counter);
            assertTrue(version > previous,
                    "version must be strictly increasing: " + version + " <= " + previous);
            previous = version;
        }
    }

    @Test
    void generateVersion_isPositive() {
        AtomicLong counter = new AtomicLong(0L);
        assertTrue(CMIVersioning.generateVersion(counter) > 0L);
    }

    @Test
    void isNewer_returnsTrue_onlyForStrictlyGreaterVersion() {
        assertTrue(CMIVersioning.isNewer(2L, 1L));
        assertFalse(CMIVersioning.isNewer(1L, 1L));
        assertFalse(CMIVersioning.isNewer(0L, 1L));
    }

    @Test
    void isNewer_firstUpdateAlwaysApplies_whenNoPriorVersion() {
        long noPriorVersion = 0L;
        long incoming = CMIVersioning.generateVersion(new AtomicLong(0L));
        assertTrue(CMIVersioning.isNewer(incoming, noPriorVersion));
    }
}
