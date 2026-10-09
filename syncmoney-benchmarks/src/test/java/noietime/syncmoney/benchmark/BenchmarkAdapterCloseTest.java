package noietime.syncmoney.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkAdapterCloseTest {
    @Test
    void cleanupRunsAfterDrainFailureAndDrainFailureRemainsPrimary() {
        List<String> completed = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                BenchmarkAdapterClose.run("adapter close failed",
                        () -> { throw new IllegalStateException("drain failed"); },
                        () -> completed.add("stop producer"),
                        () -> completed.add("close facade"),
                        () -> completed.add("close classloader")));

        assertEquals("adapter close failed", failure.getMessage());
        assertEquals("drain failed", failure.getCause().getMessage());
        assertEquals(List.of("stop producer", "close facade", "close classloader"), completed);
    }

    @Test
    void cleanupContinuesAfterAnEarlierCleanupFailure() {
        List<String> completed = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                BenchmarkAdapterClose.run("adapter close failed",
                        () -> completed.add("drain"),
                        () -> { throw new IllegalStateException("listener unregister failed"); },
                        () -> completed.add("close facade"),
                        () -> completed.add("close classloader")));

        assertEquals(List.of("drain", "close facade", "close classloader"), completed);
        assertEquals("listener unregister failed", failure.getCause().getMessage());
    }
}
