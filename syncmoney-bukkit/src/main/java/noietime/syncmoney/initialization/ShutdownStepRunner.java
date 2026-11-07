package noietime.syncmoney.initialization;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs ordered owner cleanup steps without allowing one failure to skip later owners. */
public final class ShutdownStepRunner {

    private ShutdownStepRunner() {
    }

    public static void run(Logger logger, Step... steps) {
        for (Step step : steps) {
            if (step == null || step.action() == null) {
                continue;
            }
            try {
                step.action().run();
            } catch (Throwable failure) {
                if (logger != null) {
                    logger.log(Level.SEVERE, "Shutdown step failed: " + step.owner(), failure);
                }
            }
        }
    }

    public record Step(String owner, Runnable action) {
    }
}
