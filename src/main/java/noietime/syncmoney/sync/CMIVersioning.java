package noietime.syncmoney.sync;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

public final class CMIVersioning {

    private static final long COUNTER_MODULO = 10000L;

    private CMIVersioning() {
    }

    public static long generateVersion(AtomicLong counter) {
        return generateVersion(counter, Clock.systemUTC());
    }

    static long generateVersion(AtomicLong counter, Clock clock) {
        long millis = clock.millis();
        long c = counter.incrementAndGet() % COUNTER_MODULO;
        return millis * COUNTER_MODULO + c;
    }

    public static boolean isNewer(long incoming, long current) {
        return incoming > current;
    }
}
