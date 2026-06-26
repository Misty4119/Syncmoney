package noietime.syncmoney.sync;

import java.util.concurrent.atomic.AtomicLong;

public final class CMIVersioning {

    private static final long COUNTER_MODULO = 10000L;

    private CMIVersioning() {
    }

    public static long generateVersion(AtomicLong counter) {
        long millis = System.currentTimeMillis();
        long c = counter.incrementAndGet() % COUNTER_MODULO;
        return millis * COUNTER_MODULO + c;
    }

    public static boolean isNewer(long incoming, long current) {
        return incoming > current;
    }
}
