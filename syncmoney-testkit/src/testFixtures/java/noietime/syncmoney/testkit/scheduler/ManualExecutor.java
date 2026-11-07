package noietime.syncmoney.testkit.scheduler;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Bounded executor whose queued callbacks advance only when a test runs them. */
public final class ManualExecutor implements Executor, AutoCloseable {

    private static final int DEFAULT_CAPACITY = 1_024;
    private static final int DEFAULT_DRAIN_LIMIT = 10_000;

    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final int capacity;
    private boolean closed;

    public ManualExecutor() {
        this(DEFAULT_CAPACITY);
    }

    public ManualExecutor(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (closed) throw new RejectedExecutionException("manual executor is closed");
        if (tasks.size() >= capacity) throw new RejectedExecutionException("manual executor capacity reached");
        tasks.add(command);
    }

    public boolean runNext() {
        Runnable task;
        synchronized (this) {
            task = tasks.poll();
        }
        if (task == null) return false;
        task.run();
        return true;
    }

    public int drain() {
        return drain(DEFAULT_DRAIN_LIMIT);
    }

    public int drain(int maxTasks) {
        if (maxTasks < 1) throw new IllegalArgumentException("maxTasks must be positive");
        int count = 0;
        while (count < maxTasks && runNext()) count++;
        synchronized (this) {
            if (!tasks.isEmpty()) throw new IllegalStateException("manual executor drain limit reached");
        }
        return count;
    }

    public synchronized int queuedTaskCount() {
        return tasks.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        tasks.clear();
    }
}
