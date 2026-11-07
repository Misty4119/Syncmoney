package noietime.syncmoney.testkit.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable JDK clock for deterministic expiry, retry, and rollback scenarios. */
public final class ManualClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public ManualClock(Instant initialInstant) {
        this(initialInstant, ZoneOffset.UTC);
    }

    public ManualClock(Instant initialInstant, ZoneId zone) {
        this(new AtomicReference<>(Objects.requireNonNull(initialInstant, "initialInstant")), zone);
    }

    private ManualClock(AtomicReference<Instant> instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public void set(Instant nextInstant) {
        instant.set(Objects.requireNonNull(nextInstant, "nextInstant"));
    }

    public Instant advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId nextZone) {
        return new ManualClock(instant, nextZone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
