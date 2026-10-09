package noietime.syncmoney.benchmark.load;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Measures allocations on a bounded set of long-lived load-runner threads. */
final class ThreadAllocationTracker {
    private static final String THREAD_SCOPE = "redis-workers-and-sql-batcher; excludes-arrival-scheduler-and-jvm-threads";

    private final com.sun.management.ThreadMXBean bean;
    private final String unavailableReason;
    private final Map<Long, ThreadCounter> counters = new LinkedHashMap<>();
    private boolean measurementStarted;

    ThreadAllocationTracker() {
        com.sun.management.ThreadMXBean candidate = null;
        String reason = "THREAD_ALLOCATION_COUNTER_UNAVAILABLE";
        try {
            java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
            if (platformBean instanceof com.sun.management.ThreadMXBean allocationBean
                    && allocationBean.isThreadAllocatedMemorySupported()) {
                if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                    allocationBean.setThreadAllocatedMemoryEnabled(true);
                }
                if (allocationBean.isThreadAllocatedMemoryEnabled()) {
                    candidate = allocationBean;
                    reason = null;
                } else {
                    reason = "THREAD_ALLOCATION_COUNTER_DISABLED";
                }
            }
        } catch (UnsupportedOperationException | SecurityException | LinkageError unavailable) {
            reason = "THREAD_ALLOCATION_COUNTER_UNAVAILABLE";
        }
        bean = candidate;
        unavailableReason = reason;
    }

    Runnable track(Runnable task) {
        if (bean == null) return task;
        return () -> {
            long threadId = Thread.currentThread().threadId();
            synchronized (counters) {
                ThreadCounter counter = counters.computeIfAbsent(threadId, ignored -> new ThreadCounter());
                if (measurementStarted && counter.startBytes < 0L) {
                    counter.startBytes = readAllocatedBytes(threadId);
                }
            }
            try {
                task.run();
            } finally {
                synchronized (counters) {
                    ThreadCounter counter = counters.get(threadId);
                    if (measurementStarted && counter != null) {
                        counter.endBytes = readAllocatedBytes(threadId);
                    }
                }
            }
        };
    }

    void beginMeasurement() {
        if (bean == null) return;
        synchronized (counters) {
            for (Map.Entry<Long, ThreadCounter> entry : counters.entrySet()) {
                long value = readAllocatedBytes(entry.getKey());
                if (value < 0L) return;
                entry.getValue().startBytes = value;
            }
            measurementStarted = !counters.isEmpty();
        }
    }

    AllocationSnapshot snapshot(long operationCount, int expectedThreadCount) {
        return snapshot(operationCount, operationCount, expectedThreadCount);
    }

    AllocationSnapshot snapshot(long offeredOperationCount, long acceptedOperationCount, int expectedThreadCount) {
        if (bean == null) return AllocationSnapshot.notMeasured(unavailableReason, THREAD_SCOPE, 0);
        if (offeredOperationCount <= 0L) return AllocationSnapshot.notMeasured("NO_OFFERED_OPERATIONS", THREAD_SCOPE, 0);

        synchronized (counters) {
            if (!measurementStarted || counters.isEmpty()) {
                return AllocationSnapshot.notMeasured("MEASUREMENT_WINDOW_UNAVAILABLE", THREAD_SCOPE, 0);
            }
            if (counters.size() != expectedThreadCount) {
                return AllocationSnapshot.notMeasured("THREAD_COUNT_MISMATCH", THREAD_SCOPE, counters.size());
            }
            long totalBytes = 0L;
            for (Map.Entry<Long, ThreadCounter> entry : counters.entrySet()) {
                ThreadCounter counter = entry.getValue();
                long endBytes = counter.endBytes >= 0L ? counter.endBytes : readAllocatedBytes(entry.getKey());
                if (counter.startBytes < 0L || endBytes < counter.startBytes) {
                    return AllocationSnapshot.notMeasured("THREAD_COUNTER_UNAVAILABLE", THREAD_SCOPE, counters.size());
                }
                try {
                    totalBytes = Math.addExact(totalBytes, endBytes - counter.startBytes);
                } catch (ArithmeticException overflow) {
                    return AllocationSnapshot.notMeasured("ALLOCATION_COUNTER_OVERFLOW", THREAD_SCOPE, counters.size());
                }
            }
            return new AllocationSnapshot("MEASURED", null, THREAD_SCOPE, counters.size(), totalBytes,
                    (double) totalBytes / offeredOperationCount,
                    acceptedOperationCount <= 0L ? null : (double) totalBytes / acceptedOperationCount,
                    offeredOperationCount, acceptedOperationCount);
        }
    }

    private long readAllocatedBytes(long threadId) {
        try {
            return bean.getThreadAllocatedBytes(threadId);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            return -1L;
        }
    }

    private static final class ThreadCounter {
        private long startBytes = -1L;
        private long endBytes = -1L;
    }

    record AllocationSnapshot(String status, String reason, String threadScope, int threadCount,
            Long allocatedBytes, Double bytesPerOfferedOperation, Double bytesPerAcceptedOperation,
            Long offeredOperations, Long acceptedOperations) {
        static AllocationSnapshot notMeasured(String reason, String threadScope, int threadCount) {
            return new AllocationSnapshot("NOT_MEASURED", reason, threadScope, threadCount,
                    null, null, null, null, null);
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("threadScope", threadScope);
            result.put("threadCount", threadCount);
            if (reason != null) result.put("reason", reason);
            if (allocatedBytes != null) result.put("allocatedBytes", allocatedBytes);
            if (bytesPerOfferedOperation != null) result.put("bytesPerOfferedOperation", bytesPerOfferedOperation);
            if (bytesPerAcceptedOperation != null) result.put("bytesPerAcceptedOperation", bytesPerAcceptedOperation);
            else if (available()) result.put("bytesPerAcceptedOperation", "NOT_DEFINED_ZERO_ACCEPTED");
            if (offeredOperations != null) result.put("offeredOperations", offeredOperations);
            if (acceptedOperations != null) result.put("acceptedOperations", acceptedOperations);
            return Map.copyOf(result);
        }

        boolean available() {
            return "MEASURED".equals(status);
        }

        double bytesPerOperation() {
            return bytesPerAcceptedOperation == null ? Double.NaN : bytesPerAcceptedOperation;
        }
    }
}
