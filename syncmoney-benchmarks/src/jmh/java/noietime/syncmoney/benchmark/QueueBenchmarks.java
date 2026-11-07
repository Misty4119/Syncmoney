package noietime.syncmoney.benchmark;

import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.QueueBenchmarkAdapter;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.ThreadParams;

import java.math.BigDecimal;
import java.util.UUID;

public class QueueBenchmarks {

    @State(Scope.Benchmark)
    public static class QueueState {
        @Param({"32", "50000"})
        public int capacity;
        QueueBenchmarkAdapter adapter;

        @Setup(Level.Trial)
        public void setup() {
            adapter = new QueueBenchmarkAdapter(capacity);
        }

        @TearDown(Level.Trial)
        public void close() {
            adapter.close();
        }
    }

    @State(Scope.Benchmark)
    public static class FullQueueState {
        @Param({"32", "50000"})
        public int capacity;
        QueueBenchmarkAdapter adapter;
        int primed;

        @Setup(Level.Iteration)
        public void setup() {
            adapter = new QueueBenchmarkAdapter(capacity);
            primed = adapter.primeToCapacity(UUID.nameUUIDFromBytes(
                    ("full-queue-benchmark-" + capacity).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (primed == 0 || adapter.size() == 0) {
                throw new IllegalStateException("Could not fill queue to its configured admission capacity");
            }
        }

        @TearDown(Level.Iteration)
        public void close() {
            int drained = adapter.drain();
            if (drained != primed) throw new IllegalStateException("Full queue drain lost accepted entries");
            adapter.close();
        }
    }

    @State(Scope.Thread)
    public static class Actor {
        private int threadIndex;
        private UUID account;
        private long sequence;
        private final BigDecimal one = new BigDecimal("1.00");

        @Setup(Level.Trial)
        public void setup(ThreadParams threadParams) {
            threadIndex = threadParams.getThreadIndex();
            account = UUID.nameUUIDFromBytes(("queue-actor-" + threadIndex)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sequence = 0L;
        }

        EconomyEvent nextEvent() {
            long nextSequence = ++sequence;
            return new EconomyEvent(account, one, one, nextSequence,
                    EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                    "queue-actor-" + threadIndex + "-" + nextSequence,
                    nextSequence, "benchmark");
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counts {
        public long offered;
        public long accepted;
        public long rejected;
        public long completed;

        @Setup(Level.Iteration)
        public void reset() {
            offered = accepted = rejected = completed = 0L;
        }
    }

    @Benchmark
    public boolean queueOfferDequeueComplete(QueueState queue, Actor actor, Counts counts) {
        counts.offered++;
        if (!queue.adapter.offerPollComplete(actor.nextEvent())) {
            counts.rejected++;
            return false;
        }
        counts.accepted++;
        counts.completed++;
        return true;
    }

    @Benchmark
    public boolean queueFullCapacityAdmissionReject(FullQueueState queue, Actor actor, Counts counts) {
        counts.offered++;
        if (queue.adapter.attemptBlockingOffer(actor.nextEvent())) {
            counts.accepted++;
            counts.completed++;
            throw new IllegalStateException("A full-capacity probe unexpectedly reserved an additional slot");
        }
        counts.rejected++;
        return false;
    }
}
