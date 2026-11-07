package noietime.syncmoney.testkit.scheduler;

import noietime.syncmoney.testkit.failure.FailureSchedule;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Models an entity owner that can retire before a queued callback is run. */
public final class FakeEntitySchedulerOwner {

    private final ManualExecutor executor;
    private final FailureSchedule failures;
    private final AtomicBoolean retired = new AtomicBoolean();

    public FakeEntitySchedulerOwner(ManualExecutor executor, FailureSchedule failures) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public void execute(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (retired.get()) return;
        executor.execute(() -> {
            if (failures.consume(FailureSchedule.Point.RETIRE_ENTITY)) retired.set(true);
            if (!retired.get()) callback.run();
        });
    }

    public void retire() {
        retired.set(true);
    }

    public boolean isRetired() {
        return retired.get();
    }
}
