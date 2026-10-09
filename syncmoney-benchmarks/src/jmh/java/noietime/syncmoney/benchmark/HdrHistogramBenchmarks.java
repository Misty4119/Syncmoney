package noietime.syncmoney.benchmark;

import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** Small executable compatibility probe plus a measurement of raw and omission-corrected recording cost. */
public class HdrHistogramBenchmarks {
    @State(Scope.Thread)
    public static class Fixture {
        private Histogram raw;
        private Histogram corrected;

        @Setup(Level.Trial)
        public void setup() {
            raw = new Histogram(60_000_000L, 3);
            corrected = new Histogram(60_000_000L, 3);
            raw.recordValue(2_000L);
            corrected.recordValueWithExpectedInterval(2_000L, 1_000L);
            if (raw.getTotalCount() != 1L || corrected.getTotalCount() != 2L) {
                throw new IllegalStateException("HDR Histogram Java 25 smoke postcondition failed");
            }
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (raw.getTotalCount() < 1L || corrected.getTotalCount() < raw.getTotalCount()) {
                throw new IllegalStateException("HDR Histogram lost raw or corrected samples");
            }
        }
    }

    @Benchmark
    public long recordRawAndCorrected(Fixture fixture) {
        long sample = 2_000L;
        fixture.raw.recordValue(sample);
        fixture.corrected.recordValueWithExpectedInterval(sample, 1_000L);
        return fixture.corrected.getTotalCount();
    }
}
