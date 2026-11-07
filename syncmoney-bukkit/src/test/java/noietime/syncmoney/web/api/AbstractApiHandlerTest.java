package noietime.syncmoney.web.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AbstractApiHandler} pagination boundary helpers
 * ({@code clampLimit} / {@code parseCursor}).
 */
class AbstractApiHandlerTest {

    /** Minimal concrete subclass exposing the protected helpers for testing. */
    private static final class TestHandler extends AbstractApiHandler {
        TestHandler() {
            super(null);
        }

        int clamp(int raw, int min, int max) {
            return clampLimit(raw, min, max);
        }

        long[] cursor(String c) {
            return parseCursor(c);
        }
    }

    private TestHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestHandler();
    }

    // ─── clampLimit ─────────────────────────────────────────────────────────

    @Test
    void clampReturnsValueWithinRange() {
        assertEquals(20, handler.clamp(20, 1, 100));
    }

    @Test
    void clampRaisesBelowMinimum() {
        assertEquals(1, handler.clamp(0, 1, 100));
        assertEquals(1, handler.clamp(-50, 1, 100));
    }

    @Test
    void clampCapsAboveMaximum() {
        assertEquals(100, handler.clamp(1000, 1, 100));
        assertEquals(100, handler.clamp(101, 1, 100));
    }

    @Test
    void clampHandlesBoundaryValues() {
        assertEquals(1, handler.clamp(1, 1, 100));
        assertEquals(100, handler.clamp(100, 1, 100));
    }

    // ─── parseCursor ────────────────────────────────────────────────────────

    @Test
    void parseCursorReturnsNullForNullOrEmpty() {
        assertNull(handler.cursor(null));
        assertNull(handler.cursor(""));
    }

    @Test
    void parseCursorReturnsNullForSinglePart() {
        assertNull(handler.cursor("12345"));
    }

    @Test
    void parseCursorReturnsNullForNonNumeric() {
        assertNull(handler.cursor("abc,def"));
        assertNull(handler.cursor("12345,xyz"));
    }

    @Test
    void parseCursorParsesTimestampAndSequence() {
        long[] result = handler.cursor("1700000000000,42");
        assertNotNull(result);
        assertEquals(2, result.length);
        assertEquals(1700000000000L, result[0]);
        assertEquals(42L, result[1]);
    }

    @Test
    void parseCursorTrimsWhitespace() {
        long[] result = handler.cursor(" 100 , 7 ");
        assertNotNull(result);
        assertEquals(100L, result[0]);
        assertEquals(7L, result[1]);
    }

    @Test
    void parseCursorIgnoresExtraParts() {
        long[] result = handler.cursor("100,7,extra");
        assertNotNull(result);
        assertEquals(100L, result[0]);
        assertEquals(7L, result[1]);
    }
}
