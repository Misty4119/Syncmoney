package noietime.syncmoney.listener;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Matches only the exact CMI balance event emitted by an inbound remote write. */
final class CMIInboundEchoTracker {

    private static final int MAX_PENDING_WRITES = 10_000;
    private static final long DEFAULT_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private record ExpectedWrite(double from, double to, long expiresAtNanos, Object registrationToken) {
    }

    private final ConcurrentHashMap<UUID, ExpectedWrite> expectedWrites = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final LongSupplier ticker;

    CMIInboundEchoTracker() {
        this(DEFAULT_TTL_NANOS, System::nanoTime);
    }

    CMIInboundEchoTracker(long ttlNanos, LongSupplier ticker) {
        if (ttlNanos <= 0) {
            throw new IllegalArgumentException("ttlNanos must be positive");
        }
        this.ttlNanos = ttlNanos;
        this.ticker = ticker;
    }

    Runnable expect(UUID uuid, double from, double to) {
        if (uuid == null || !Double.isFinite(from) || !Double.isFinite(to) || Double.compare(from, to) == 0) {
            return () -> { };
        }

        long now = ticker.getAsLong();
        expectedWrites.entrySet().removeIf(entry -> expired(now, entry.getValue()));
        ExpectedWrite expected = new ExpectedWrite(from, to, now + ttlNanos, new Object());
        expectedWrites.put(uuid, expected);
        if (expectedWrites.size() > MAX_PENDING_WRITES) {
            expectedWrites.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().expiresAtNanos()))
                    .ifPresent(oldest -> expectedWrites.remove(oldest.getKey(), oldest.getValue()));
        }
        return () -> expectedWrites.remove(uuid, expected);
    }

    boolean consume(UUID uuid, double from, double to) {
        if (uuid == null) {
            return false;
        }
        ExpectedWrite expected = expectedWrites.get(uuid);
        if (expected == null) {
            return false;
        }
        if (expired(ticker.getAsLong(), expected)) {
            expectedWrites.remove(uuid, expected);
            return false;
        }
        if (Double.compare(expected.from(), from) != 0 || Double.compare(expected.to(), to) != 0) {
            return false;
        }
        return expectedWrites.remove(uuid, expected);
    }

    private static boolean expired(long now, ExpectedWrite expected) {
        return now - expected.expiresAtNanos() >= 0;
    }
}
