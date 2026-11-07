package noietime.syncmoney.benchmark;

import noietime.syncmoney.economy.CurrentFacadeBenchmarkAdapter;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentFacadeBenchmarkAdapterDrainTest {
    @Test
    void emptyQueuePollDoesNotHoldTheDrainSnapshotMonitor() throws Exception {
        CurrentFacadeBenchmarkAdapter adapter = new CurrentFacadeBenchmarkAdapter();
        try {
            Thread drainer = (Thread) field(adapter, "drainer");
            Object drainStateLock = field(adapter, "drainStateLock");
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean pollWaitHeldSnapshotMonitor = false;
            boolean observedIdleWaitOutsideMonitor = false;

            while (System.nanoTime() < deadline && drainer.isAlive()) {
                ThreadInfo info = threadBean.getThreadInfo(new long[] {drainer.threadId()}, true, true)[0];
                if (info != null && info.getThreadState() == Thread.State.TIMED_WAITING) {
                    boolean holdsSnapshotMonitor = Arrays.stream(info.getLockedMonitors())
                            .mapToInt(MonitorInfo::getIdentityHashCode)
                            .anyMatch(identity -> identity == System.identityHashCode(drainStateLock));
                    boolean waitingInQueuePoll = Arrays.stream(info.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals("noietime.syncmoney.economy.EconomyWriteQueue")
                                    && frame.getMethodName().equals("poll"));
                    boolean sleepingBetweenPolls = Arrays.stream(info.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals("java.lang.Thread")
                                    && frame.getMethodName().equals("sleep"));

                    if (waitingInQueuePoll && holdsSnapshotMonitor) {
                        pollWaitHeldSnapshotMonitor = true;
                        break;
                    }
                    if (sleepingBetweenPolls && !holdsSnapshotMonitor) {
                        observedIdleWaitOutsideMonitor = true;
                        break;
                    }
                }
                Thread.sleep(1L);
            }

            assertFalse(pollWaitHeldSnapshotMonitor,
                    "the receipt drainer must not block on an empty queue while holding the snapshot monitor");
            assertTrue(observedIdleWaitOutsideMonitor,
                    "the receipt drainer should wait for an empty queue outside the snapshot monitor");
        } finally {
            adapter.close();
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
