package noietime.syncmoney.storage.recovery;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class LegacyOverflowAmbiguityTest {
    @Test
    void legacySetRecordIsParsedButNeverTreatedAsSafeToReplay() throws Exception {
        String raw = "1728290000000|2ecbc4d9-82f4-4cbe-a73b-b702669ec865|SET_BALANCE|ADMIN_SET|5.00|legacy-set-1";
        Object result = parse(raw);

        assertEquals("AMBIGUOUS", String.valueOf(invoke(result, "classification")));
        assertFalse((boolean) invoke(result, "replaySafe"),
                "legacy lines have no execution stage or confirmed absolute SET target");
        assertEquals(raw, invoke(result, "originalLine"));
        assertEquals("SET_BALANCE", invoke(result, "eventType"));
    }

    @Test
    void malformedLegacyRecordRemainsInvalidRatherThanPartiallyReplaying() throws Exception {
        String raw = "not-a-record|keep-me";
        Object result = parse(raw);

        assertEquals("INVALID", String.valueOf(invoke(result, "classification")));
        assertFalse((boolean) invoke(result, "replaySafe"));
        assertEquals(raw, invoke(result, "originalLine"));
    }

    private Object parse(String raw) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("noietime.syncmoney.storage.recovery.LegacyOverflowParser");
        } catch (ClassNotFoundException missing) {
            fail("LegacyOverflowParser must classify legacy records without replaying them", missing);
            throw new AssertionError(missing);
        }
        try {
            return type.getMethod("parse", String.class).invoke(null, raw);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
