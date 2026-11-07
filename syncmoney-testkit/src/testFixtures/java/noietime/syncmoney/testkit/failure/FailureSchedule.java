package noietime.syncmoney.testkit.failure;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic, one-shot failure points for test-only backend and scheduler fakes. */
public final class FailureSchedule {

    public enum Point {
        FAIL_BEFORE_COMMIT,
        FAIL_AFTER_COMMIT,
        DROP_RESPONSE,
        DELIVER_DUPLICATE,
        DELIVER_OUT_OF_ORDER,
        RETIRE_ENTITY
    }

    public enum Outcome {
        NOT_COMMITTED,
        UNKNOWN
    }

    private final Map<Point, Integer> remaining = new EnumMap<>(Point.class);

    public synchronized void scheduleOnce(Point point) {
        remaining.merge(Objects.requireNonNull(point, "point"), 1, Integer::sum);
    }

    public synchronized boolean consume(Point point) {
        Objects.requireNonNull(point, "point");
        int count = remaining.getOrDefault(point, 0);
        if (count == 0) return false;
        if (count == 1) remaining.remove(point);
        else remaining.put(point, count - 1);
        return true;
    }

    public void failIfScheduled(Point point, Outcome outcome) {
        if (consume(point)) throw new InjectedFailure(point, outcome);
    }

    public static final class InjectedFailure extends RuntimeException {
        private final Point point;
        private final Outcome outcome;

        public InjectedFailure(Point point, Outcome outcome) {
            super("Scheduled test failure at " + point + " with outcome " + outcome);
            this.point = Objects.requireNonNull(point, "point");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
        }

        public Point point() {
            return point;
        }

        public Outcome outcome() {
            return outcome;
        }
    }
}
