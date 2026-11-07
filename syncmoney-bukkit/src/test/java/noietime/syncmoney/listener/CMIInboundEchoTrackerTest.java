package noietime.syncmoney.listener;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CMIInboundEchoTrackerTest {

    @Test
    void consumesOnlyTheExpectedFromAndToPair() {
        UUID uuid = UUID.randomUUID();
        CMIInboundEchoTracker tracker = new CMIInboundEchoTracker(1_000, () -> 10L);
        tracker.expect(uuid, 100.0, 80.0);

        assertFalse(tracker.consume(uuid, 100.0, 70.0));
        assertTrue(tracker.consume(uuid, 100.0, 80.0));
        assertFalse(tracker.consume(uuid, 100.0, 80.0));
    }

    @Test
    void anExpiredExpectedWriteDoesNotSuppressALaterLocalChange() {
        UUID uuid = UUID.randomUUID();
        AtomicLong ticker = new AtomicLong(10L);
        CMIInboundEchoTracker tracker = new CMIInboundEchoTracker(100L, ticker::get);
        tracker.expect(uuid, 100.0, 80.0);

        ticker.set(110L);

        assertFalse(tracker.consume(uuid, 100.0, 80.0));
    }

    @Test
    void clearingAnUnconsumedWritePreventsItFromSuppressingALaterLocalChange() {
        UUID uuid = UUID.randomUUID();
        CMIInboundEchoTracker tracker = new CMIInboundEchoTracker(1_000, () -> 10L);

        Runnable clear = tracker.expect(uuid, 100.0, 80.0);
        clear.run();

        assertFalse(tracker.consume(uuid, 100.0, 80.0));
    }

    @Test
    void clearingAnOlderRegistrationDoesNotRemoveANewerWriteForTheSameAccount() {
        UUID uuid = UUID.randomUUID();
        CMIInboundEchoTracker tracker = new CMIInboundEchoTracker(1_000, () -> 10L);

        Runnable clearOlder = tracker.expect(uuid, 100.0, 80.0);
        Runnable clearNewer = tracker.expect(uuid, 100.0, 80.0);
        clearOlder.run();

        assertTrue(tracker.consume(uuid, 100.0, 80.0));
        clearNewer.run();
    }
}
