package noietime.syncmoney.benchmark.load;

import org.HdrHistogram.Histogram;

import java.util.Map;

/** Approximate binomial order-statistic confidence interval for a measured percentile. */
record HistogramQuantileConfidence(int confidenceLevelPercent, double targetPercentile,
        double lowerRankPercentile, double upperRankPercentile,
        long lowerLatencyMicros, long upperLatencyMicros) {
    private static final String METHOD = "approximate-binomial-order-statistic";
    private static final double P999 = 0.999d;
    private static final double Z_95 = 1.959963984540054d;

    static HistogramQuantileConfidence p999(Histogram histogram) {
        long count = histogram.getTotalCount();
        if (count == 0L) throw new IllegalArgumentException("p99.9 confidence interval needs at least one sample");

        RankBounds ranks = p999Ranks(count);
        return new HistogramQuantileConfidence(95, 99.9d, ranks.lowerPercentile(), ranks.upperPercentile(),
                histogram.getValueAtPercentile(ranks.lowerPercentile()),
                histogram.getValueAtPercentile(ranks.upperPercentile()));
    }

    static RankBounds p999Ranks(long count) {
        if (count <= 0L) throw new IllegalArgumentException("p99.9 confidence ranks need at least one sample");
        double standardDeviation = Math.sqrt(P999 * (1d - P999) / count);
        double lowerProbability = Math.max(0d, P999 - Z_95 * standardDeviation);
        double upperProbability = Math.min(1d, P999 + Z_95 * standardDeviation);
        long lowerRank = Math.max(1L, (long) Math.floor(lowerProbability * count));
        long upperRank = Math.min(count, (long) Math.ceil(upperProbability * count));
        double lowerPercentile = lowerRank * 100d / count;
        double upperPercentile = upperRank * 100d / count;
        return new RankBounds(lowerPercentile, upperPercentile);
    }

    double rankWidth() {
        return upperRankPercentile - lowerRankPercentile;
    }

    Map<String, Object> asMap() {
        return Map.of("method", METHOD, "confidenceLevelPercent", confidenceLevelPercent,
                "targetPercentile", targetPercentile, "lowerRankPercentile", lowerRankPercentile,
                "upperRankPercentile", upperRankPercentile, "lowerLatencyMicros", lowerLatencyMicros,
                "upperLatencyMicros", upperLatencyMicros);
    }

    record RankBounds(double lowerPercentile, double upperPercentile) { }
}
