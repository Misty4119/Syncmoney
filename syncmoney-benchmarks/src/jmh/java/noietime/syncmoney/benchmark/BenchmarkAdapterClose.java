package noietime.syncmoney.benchmark;

/** Runs adapter shutdown steps even when draining or an earlier cleanup action fails. */
public final class BenchmarkAdapterClose {
    private BenchmarkAdapterClose() { }

    @FunctionalInterface
    public interface Step {
        void run() throws Throwable;
    }

    public static void run(String failureMessage, Step drain, Step... cleanup) {
        Throwable failure = null;
        boolean interrupted = false;
        try {
            failure = capture(drain);
            if (failure instanceof InterruptedException) {
                interrupted = true;
                Thread.interrupted();
            }
            for (Step step : cleanup) {
                Throwable cleanupFailure = capture(step);
                if (cleanupFailure == null) continue;
                if (cleanupFailure instanceof InterruptedException) {
                    interrupted = true;
                    Thread.interrupted();
                }
                if (failure == null) failure = cleanupFailure;
                else if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) throw new IllegalStateException(failureMessage, failure);
    }

    private static Throwable capture(Step step) {
        try {
            step.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
