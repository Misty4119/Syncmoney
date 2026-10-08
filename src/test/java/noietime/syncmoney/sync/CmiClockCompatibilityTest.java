package noietime.syncmoney.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmiClockCompatibilityTest {

    private static final long BASE_MILLIS = 1_791_043_200_000L;
    private static final long JAVASCRIPT_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    @Test
    void clockRollbackCanProduceALowerLegacyVersion() {
        AtomicLong counter = new AtomicLong();
        long beforeRollback = CMIVersioning.generateVersion(counter, fixed(BASE_MILLIS));
        long afterRollback = CMIVersioning.generateVersion(counter, fixed(BASE_MILLIS - 1));

        assertTrue(afterRollback < beforeRollback);
    }

    @Test
    void skewedNodeClockCanOrderAnOlderMutationAfterANewerOne() {
        long aheadNode = CMIVersioning.generateVersion(new AtomicLong(), fixed(BASE_MILLIS + 100));
        long behindNode = CMIVersioning.generateVersion(new AtomicLong(), fixed(BASE_MILLIS - 100));

        assertTrue(behindNode < aheadNode);
    }

    @Test
    void counterWrapCanMoveVersionBackwardsWithinOneMillisecond() {
        AtomicLong counter = new AtomicLong(9_998L);

        long beforeWrap = CMIVersioning.generateVersion(counter, fixed(BASE_MILLIS));
        long afterWrap = CMIVersioning.generateVersion(counter, fixed(BASE_MILLIS));

        assertTrue(afterWrap < beforeWrap);
    }

    @Test
    void restartingTheCounterInTheSameMillisecondRepeatsTheLegacyVersion() {
        long firstProcess = CMIVersioning.generateVersion(new AtomicLong(), fixed(BASE_MILLIS));
        long restartedProcess = CMIVersioning.generateVersion(new AtomicLong(), fixed(BASE_MILLIS));

        assertEquals(firstProcess, restartedProcess);
    }

    @Test
    void legacyNumericVersionRoundTripsExactlyAboveJavaScriptSafeInteger() {
        long version = CMIVersioning.generateVersion(new AtomicLong(), fixed(BASE_MILLIS));
        PubSubMessage message = new PubSubMessage("00000000-0000-0000-0000-000000000001",
                new BigDecimal("12.34"), version, "source", "message-1", BASE_MILLIS);

        String json = message.toJson();
        PubSubMessage decoded = PubSubMessage.fromJson(json);

        assertTrue(version > JAVASCRIPT_MAX_SAFE_INTEGER);
        assertTrue(json.contains("\"version\":" + version), "legacy version stays a JSON number");
        assertEquals(version, decoded.getVersion());
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }
}
