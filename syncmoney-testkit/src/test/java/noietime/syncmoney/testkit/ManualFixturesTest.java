package noietime.syncmoney.testkit;

import noietime.syncmoney.testkit.scheduler.FakeEntitySchedulerOwner;
import noietime.syncmoney.testkit.scheduler.ManualExecutor;
import noietime.syncmoney.testkit.time.ManualClock;
import noietime.syncmoney.testkit.failure.FailureSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualFixturesTest {

    @Test
    void manualExecutorRunsOnlyWhenTheTestAdvancesIt() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> work = new ArrayList<>();

        executor.execute(() -> work.add(1));
        executor.execute(() -> work.add(2));

        assertEquals(2, executor.queuedTaskCount());
        assertTrue(executor.runNext());
        assertEquals(List.of(1), work);
        assertEquals(1, executor.drain());
        assertEquals(List.of(1, 2), work);
        assertFalse(executor.runNext());
    }

    @Test
    void manualClockMovesOnlyWhenTheTestAdvancesIt() {
        ManualClock clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));

        clock.advance(Duration.ofSeconds(5));

        assertEquals(Instant.parse("2026-01-01T00:00:05Z"), clock.instant());
    }

    @Test
    void manualClockZoneViewTracksAdvancesToTheSameTimeline() {
        ManualClock clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
        var taipeiClock = clock.withZone(ZoneId.of("Asia/Taipei"));

        clock.advance(Duration.ofSeconds(5));

        assertEquals(ZoneId.of("Asia/Taipei"), taipeiClock.getZone());
        assertEquals(clock.instant(), taipeiClock.instant());
    }

    @Test
    void scheduledFailureIsConsumedOnceAndEntityRetirementCancelsQueuedCallback() {
        ManualExecutor executor = new ManualExecutor();
        FailureSchedule failures = new FailureSchedule();
        FakeEntitySchedulerOwner owner = new FakeEntitySchedulerOwner(executor, failures);
        List<String> callbacks = new ArrayList<>();
        failures.scheduleOnce(FailureSchedule.Point.RETIRE_ENTITY);

        owner.execute(() -> callbacks.add("ran"));
        executor.drain();

        assertTrue(owner.isRetired());
        assertTrue(callbacks.isEmpty());
        assertFalse(failures.consume(FailureSchedule.Point.RETIRE_ENTITY));
    }
}
