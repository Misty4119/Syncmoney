package noietime.syncmoney.benchmark.load;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramQuantileConfidenceTest {
    @Test
    void doesNotCreateAConfidenceIntervalForAnEmptyHistogram() {
        Histogram histogram = new Histogram(3_600_000_000L, 3);

        assertThrows(IllegalArgumentException.class, () -> HistogramQuantileConfidence.p999(histogram));
    }

    @Test
    void reportsAnApproximate95PercentOrderStatisticIntervalAroundP999() {
        Histogram histogram = new Histogram(3_600_000_000L, 3);
        for (long value = 1; value <= 100_000; value++) histogram.recordValue(value);

        HistogramQuantileConfidence interval = HistogramQuantileConfidence.p999(histogram);

        assertEquals(95, interval.confidenceLevelPercent());
        assertTrue(interval.lowerRankPercentile() < 99.9d);
        assertTrue(interval.upperRankPercentile() > 99.9d);
        assertTrue(interval.lowerLatencyMicros() <= histogram.getValueAtPercentile(99.9d));
        assertTrue(interval.upperLatencyMicros() >= histogram.getValueAtPercentile(99.9d));
    }

    @Test
    void largerSamplesNarrowTheP999RankConfidenceInterval() {
        Histogram small = new Histogram(3_600_000_000L, 3);
        Histogram large = new Histogram(3_600_000_000L, 3);
        for (long value = 1; value <= 1_000; value++) small.recordValue(value);
        for (long value = 1; value <= 100_000; value++) large.recordValue(value);

        HistogramQuantileConfidence smallInterval = HistogramQuantileConfidence.p999(small);
        HistogramQuantileConfidence largeInterval = HistogramQuantileConfidence.p999(large);

        assertTrue(largeInterval.rankWidth() < smallInterval.rankWidth());
    }
}
