package noietime.syncmoney.benchmark.load;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAllocationTrackerTest {
    private static volatile byte[] allocationSink;

    @Test
    void measuresWorkerAllocationAcrossAnExistingThreadBoundary() throws Exception {
        ThreadAllocationTracker tracker = new ThreadAllocationTracker();
        CountDownLatch workerReady = new CountDownLatch(1);
        CountDownLatch beginWork = new CountDownLatch(1);
        Thread worker = new Thread(tracker.track(() -> {
            workerReady.countDown();
            try {
                if (!beginWork.await(5, TimeUnit.SECONDS)) throw new AssertionError("measurement start timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            byte[] allocation = new byte[1_048_576];
            allocation[0] = 1;
            allocationSink = allocation;
        }));

        worker.start();
        assertTrue(workerReady.await(5, TimeUnit.SECONDS));
        tracker.beginMeasurement();
        beginWork.countDown();
        worker.join(5_000);
        assertTrue(!worker.isAlive(), "allocation worker must finish");

        ThreadAllocationTracker.AllocationSnapshot snapshot = tracker.snapshot(1, 1);

        assertTrue(snapshot.available());
        assertTrue(snapshot.bytesPerOperation() >= 1_048_576d);
        assertTrue(!tracker.snapshot(1, 2).available(), "partial thread coverage must not be reported as measured");
    }
}
