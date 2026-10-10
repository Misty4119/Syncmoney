package noietime.syncmoney.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict shape, sample, and artifact-binding checks for paired JMH reports. */
public final class PairedJmhReportValidator {
    private static final String BENCHMARK = "noietime.syncmoney.benchmark.TransactionBenchmarks.execute";
    private static final String JMH_VERSION = "1.37";
    private static final List<String> ALL_OPERATIONS = List.of("balance", "deposit", "withdraw", "set", "transfer",
            "reject-withdraw", "cancel-deposit", "cancel-withdraw");
    private static final List<String> ALL_ACCOUNT_COUNTS = List.of("1", "1000", "10000");
    private static final List<String> ALL_ACCESS_PATTERNS = List.of("uniform", "hot");
    private static final List<String> EXPECTED_JVM_ARGS = List.of(
            "-XX:+UseSerialGC", "-Dnet.bytebuddy.experimental=true", "-Xmx1g");
    private static final double P999 = 0.999d;
    private static final double Z_95 = 1.959963984540054d;
    private static final Type ROW_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();

    private PairedJmhReportValidator() { }

    /**
     * Validates the exact expected tuple matrix, per-fork raw-data shape, and sample histogram contents.
     * The returned p99.9 interval is an approximate binomial order-statistic interval and is omitted
     * for quick profiles or when the combined sample count is below {@code minimumP999Samples}.
     */
    public static Map<String, Object> validateRows(List<?> rows, List<String> requestedVariants,
            String operationFilter, boolean quick, int producerThreads, int expectedForks,
            int expectedMeasurementIterations, long minimumP999Samples) {
        if (rows == null) throw new IllegalArgumentException("Paired JMH result must be a JSON array");
        validateRequest(requestedVariants, quick, producerThreads, expectedForks,
                expectedMeasurementIterations, minimumP999Samples);

        List<String> operations = requestedOperations(operationFilter, quick);
        List<String> accountCounts = quick ? List.of("1000") : ALL_ACCOUNT_COUNTS;
        List<String> accessPatterns = quick ? List.of("uniform") : ALL_ACCESS_PATTERNS;
        Set<String> expectedTuples = expectedTuples(requestedVariants, operations, accountCounts, accessPatterns);
        Set<String> observedTuples = new LinkedHashSet<>();
        List<Map<String, Object>> sampleTailAssessment = new ArrayList<>();
        int sampleTimeRows = 0;

        for (Object candidate : rows) {
            Map<?, ?> row = objectMap(candidate, "Paired JMH result row must be an object");
            if (!BENCHMARK.equals(row.get("benchmark"))) {
                throw new IllegalArgumentException("Paired JMH result contains an unexpected benchmark row");
            }
            String mode = requiredString(row.get("mode"), "mode");
            if (!mode.equals("thrpt") && !mode.equals("sample")) {
                throw new IllegalArgumentException("Paired JMH result contains an unexpected mode");
            }
            Map<?, ?> params = objectMap(row.get("params"), "Paired JMH result row is missing params");
            String variant = requiredString(params.get("variant"), "params.variant");
            String operation = requiredString(params.get("operation"), "params.operation");
            String accountCount = requiredString(params.get("accountCount"), "params.accountCount");
            String accessPattern = requiredString(params.get("accessPattern"), "params.accessPattern");
            String tuple = tupleKey(variant, operation, accountCount, accessPattern, mode);
            if (!expectedTuples.contains(tuple)) {
                throw new IllegalArgumentException("Paired JMH result contains an unexpected tuple: " + tuple);
            }
            if (!observedTuples.add(tuple)) {
                throw new IllegalArgumentException("Paired JMH result contains a duplicate tuple: " + tuple);
            }

            if (exactInteger(row.get("threads"), "threads") != producerThreads) {
                throw new IllegalArgumentException("Paired JMH tuple has the wrong producer thread count: " + tuple);
            }
            if (exactInteger(row.get("forks"), "forks") != expectedForks) {
                throw new IllegalArgumentException("Paired JMH tuple has the wrong fork count: " + tuple);
            }
            if (!JMH_VERSION.equals(requiredString(row.get("jmhVersion"), "jmhVersion"))) {
                throw new IllegalArgumentException("Paired JMH tuple used an unexpected JMH version: " + tuple);
            }
            String jdkVersion = requiredString(row.get("jdkVersion"), "jdkVersion");
            if (!jdkVersion.startsWith("25.")) {
                throw new IllegalArgumentException("Paired JMH tuple must run on Java 25: " + tuple);
            }
            validateJvmArguments(row.get("jvmArgs"), tuple);

            Map<?, ?> primaryMetric = objectMap(row.get("primaryMetric"),
                    "Paired JMH tuple is missing primaryMetric: " + tuple);
            requireFiniteNumber(primaryMetric.get("score"), "primaryMetric.score", tuple);

            if (mode.equals("sample")) {
                sampleTimeRows++;
                List<?> rawHistograms = listValue(primaryMetric.get("rawDataHistogram"),
                        "primaryMetric.rawDataHistogram", tuple);
                List<Long> forkSampleCounts = validateHistograms(rawHistograms, expectedForks,
                        expectedMeasurementIterations, tuple);
                long sampleCount = 0L;
                TreeMap<Double, Long> mergedHistogram = new TreeMap<>();
                for (int fork = 0; fork < rawHistograms.size(); fork++) {
                    sampleCount = Math.addExact(sampleCount, forkSampleCounts.get(fork));
                    mergeHistogram(mergedHistogram, rawHistograms.get(fork), tuple);
                }
                boolean sampleMinimumMet = sampleCount >= minimumP999Samples;
                if (!quick && !sampleMinimumMet) {
                    throw new IllegalArgumentException("Paired JMH SampleTime tuple is below the p99.9 sample minimum: "
                            + tuple + " has " + sampleCount + " samples; requires " + minimumP999Samples);
                }
                Map<String, Object> assessment = new LinkedHashMap<>();
                assessment.put("variant", variant);
                assessment.put("operation", operation);
                assessment.put("accountCount", accountCount);
                assessment.put("accessPattern", accessPattern);
                assessment.put("forkSampleCounts", forkSampleCounts);
                assessment.put("sampleCount", sampleCount);
                assessment.put("sampleMinimum", minimumP999Samples);
                assessment.put("sampleMinimumMet", sampleMinimumMet);
                assessment.put("p999Confidence95", !quick && sampleMinimumMet
                        ? confidenceInterval(mergedHistogram, sampleCount) : null);
                sampleTailAssessment.add(assessment);
            } else {
                List<?> rawData = listValue(primaryMetric.get("rawData"), "primaryMetric.rawData", tuple);
                validateRawDataPartitions(rawData, expectedForks, expectedMeasurementIterations, tuple);
            }
        }

        Set<String> missingTuples = new LinkedHashSet<>(expectedTuples);
        missingTuples.removeAll(observedTuples);
        if (!missingTuples.isEmpty()) {
            throw new IllegalArgumentException("Paired JMH result matrix is missing expected tuples: "
                    + missingTuples.stream().limit(4).toList());
        }
        if (observedTuples.size() != rows.size() || rows.size() != expectedTuples.size()) {
            throw new IllegalArgumentException("Paired JMH result row count does not match its unique expected matrix");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("expectedRows", expectedTuples.size());
        summary.put("rowCount", rows.size());
        summary.put("sampleTimeRows", sampleTimeRows);
        summary.put("sampleTailAssessment", sampleTailAssessment);
        return summary;
    }

    /** Ensures the manifest binds both the exact result JSON bytes and their digest. */
    public static void verifyResultBinding(Map<?, ?> manifest, Path resultFile) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("Paired JMH manifest is missing");
        if (resultFile == null || !Files.isRegularFile(resultFile) || Files.size(resultFile) == 0L) {
            throw new IllegalArgumentException("Paired JMH result JSON is missing or empty");
        }
        Object digestValue = manifest.get("resultSHA256");
        if (!(digestValue instanceof String digest) || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Paired JMH manifest is missing a valid resultSHA256");
        }
        Object bytesValue = manifest.get("resultBytes");
        long resultBytes = exactLong(bytesValue, "resultBytes");
        if (resultBytes <= 0L || resultBytes != Files.size(resultFile)) {
            throw new IllegalArgumentException("Paired JMH manifest resultBytes does not match the JSON file");
        }
        String actualDigest = sha256(resultFile);
        if (!actualDigest.equals(digest)) {
            throw new IllegalArgumentException("Paired JMH manifest resultSHA256 does not match the JSON file digest");
        }
    }

    /** Validates required host facts while allowing explicitly disclosed unavailable OS observations. */
    public static void validateHostProvenance(Map<?, ?> host) {
        if (host == null) throw new IllegalArgumentException("Paired JMH manifest is missing hostProvenance");
        requiredString(host.get("osName"), "hostProvenance.osName");
        requiredString(host.get("osVersion"), "hostProvenance.osVersion");
        requiredString(host.get("osArch"), "hostProvenance.osArch");
        Object cpuModelValue = host.get("cpuModel");
        String cpuModel = requiredString(cpuModelValue, "hostProvenance.cpuModel");
        if (cpuModel.equals("NOT_CAPTURED")) {
            requiredString(host.get("cpuModelReason"), "hostProvenance.cpuModelReason");
        } else if (cpuModel.length() > 256 || cpuModel.indexOf('\n') >= 0 || cpuModel.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Paired JMH host cpuModel must be a single-line value of at most 256 characters");
        }
        if (exactInteger(host.get("availableProcessors"), "hostProvenance.availableProcessors") < 1) {
            throw new IllegalArgumentException("Paired JMH host availableProcessors must be positive");
        }
        Object memoryValue = host.get("totalPhysicalMemoryBytes");
        if ("NOT_CAPTURED".equals(memoryValue)) {
            requiredString(host.get("totalPhysicalMemoryReason"), "hostProvenance.totalPhysicalMemoryReason");
        } else if (exactLong(memoryValue, "hostProvenance.totalPhysicalMemoryBytes") <= 0L) {
            throw new IllegalArgumentException("Paired JMH host physical memory must be positive or explicitly NOT_CAPTURED");
        }
        validateCaptureStatus(host.get("forkAffinity"), "hostProvenance.forkAffinity");
        validateCaptureStatus(host.get("powerPlan"), "hostProvenance.powerPlan");
        Map<?, ?> powerPlan = objectMap(host.get("powerPlan"), "hostProvenance.powerPlan must disclose its capture status");
        if ("CAPTURED".equals(powerPlan.get("status"))
                && !requiredString(powerPlan.get("value"), "hostProvenance.powerPlan.value")
                        .matches("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Paired JMH host powerPlan.value must be a stable power-scheme GUID");
        }
    }

    /** Streaming SHA-256 avoids retaining a full JMH result file in memory. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("A paired JMH report validation mode is required");
        switch (args[0]) {
            case "summarize" -> summarize(args);
            case "verify-binding" -> verifyBinding(args);
            default -> throw new IllegalArgumentException("Unknown paired JMH report validation mode");
        }
    }

    private static void summarize(String[] args) throws IOException {
        if (args.length != 10) {
            throw new IllegalArgumentException("summarize requires result, output, variants, operation, quick, threads, forks, iterations, and p99.9 minimum");
        }
        List<Map<String, Object>> rows = readRows(Path.of(args[1]));
        List<String> variants = csvValues(args[3]);
        String operation = args[4].equals("all") ? null : args[4];
        Map<String, Object> summary = validateRows(rows, variants, operation, Boolean.parseBoolean(args[5]),
                Integer.parseInt(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]),
                Long.parseLong(args[9]));
        Path output = Path.of(args[2]);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, GSON.toJson(summary) + System.lineSeparator());
        System.out.println("Validated paired JMH matrix rows=" + summary.get("rowCount")
                + ", sampleTimeRows=" + summary.get("sampleTimeRows"));
    }

    private static void verifyBinding(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("verify-binding requires result and manifest paths");
        Path resultFile = Path.of(args[1]);
        Map<?, ?> manifest = readObject(Path.of(args[2]));
        verifyResultBinding(manifest, resultFile);
        validateHostProvenance(objectMap(manifest.get("hostProvenance"),
                "Paired JMH manifest is missing hostProvenance"));
        System.out.println("Verified paired JMH result digest, length, and host provenance.");
    }

    private static List<Map<String, Object>> readRows(Path file) throws IOException {
        JsonElement root;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            root = JsonParser.parseReader(reader);
        }
        if (!root.isJsonArray()) throw new IllegalArgumentException("Paired JMH result JSON must be an array");
        JsonArray array = root.getAsJsonArray();
        List<Map<String, Object>> rows = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Paired JMH result row must be an object");
            rows.add(GSON.fromJson(element, ROW_TYPE));
        }
        return rows;
    }

    private static Map<?, ?> readObject(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IllegalArgumentException("Paired JMH manifest JSON must be an object");
            return GSON.fromJson(root, ROW_TYPE);
        }
    }

    private static void validateRequest(List<String> variants, boolean quick, int threads,
            int forks, int iterations, long minimumSamples) {
        if (variants == null || variants.isEmpty() || variants.stream().anyMatch(variant ->
                !variant.equals("current") && !variant.equals("legacy132"))
                || new LinkedHashSet<>(variants).size() != variants.size()) {
            throw new IllegalArgumentException("Paired JMH variants must be a unique non-empty subset of current,legacy132");
        }
        if (threads != 1 && threads != 2 && threads != 8) {
            throw new IllegalArgumentException("Paired JMH producer thread count is outside the planned matrix");
        }
        if (forks != (quick ? 1 : 3) || iterations != (quick ? 1 : 10)) {
            throw new IllegalArgumentException("Paired JMH fork/measurement shape does not match the selected profile");
        }
        if (minimumSamples <= 0L) throw new IllegalArgumentException("Paired JMH p99.9 sample minimum must be positive");
    }

    private static List<String> requestedOperations(String filter, boolean quick) {
        if (filter == null || filter.isBlank()) return quick ? List.of("deposit") : ALL_OPERATIONS;
        if (!ALL_OPERATIONS.contains(filter)) {
            throw new IllegalArgumentException("Paired JMH operation filter is invalid");
        }
        return List.of(filter);
    }

    private static Set<String> expectedTuples(List<String> variants, List<String> operations,
            List<String> accountCounts, List<String> accessPatterns) {
        Set<String> tuples = new LinkedHashSet<>();
        for (String variant : variants) {
            for (String operation : operations) {
                for (String accountCount : accountCounts) {
                    for (String accessPattern : accessPatterns) {
                        tuples.add(tupleKey(variant, operation, accountCount, accessPattern, "thrpt"));
                        tuples.add(tupleKey(variant, operation, accountCount, accessPattern, "sample"));
                    }
                }
            }
        }
        return tuples;
    }

    private static String tupleKey(String variant, String operation, String accountCount,
            String accessPattern, String mode) {
        return String.join("/", variant, operation, accessPattern, accountCount, mode);
    }

    private static void validateJvmArguments(Object value, String tuple) {
        List<?> args = listValue(value, "jvmArgs", tuple);
        if (!args.containsAll(EXPECTED_JVM_ARGS)) {
            throw new IllegalArgumentException("Paired JMH tuple is missing the recorded SerialGC/ByteBuddy/heap flags: " + tuple);
        }
    }

    private static void validateRawDataPartitions(List<?> rawData, int expectedForks,
            int expectedMeasurements, String tuple) {
        if (rawData.size() != expectedForks) {
            throw new IllegalArgumentException("Paired JMH rawData must contain one partition per fork: " + tuple);
        }
        for (Object partitionValue : rawData) {
            List<?> partition = listValue(partitionValue, "primaryMetric.rawData fork partition", tuple);
            if (partition.size() != expectedMeasurements) {
                throw new IllegalArgumentException("Paired JMH rawData fork partition has the wrong iteration count: " + tuple);
            }
            for (Object measurement : partition) requireFiniteNumber(measurement, "rawData measurement", tuple);
        }
    }

    private static List<Long> validateHistograms(List<?> histograms, int expectedForks,
            int expectedMeasurements, String tuple) {
        if (histograms.size() != expectedForks) {
            throw new IllegalArgumentException("Paired JMH rawDataHistogram must contain one partition per fork: " + tuple);
        }
        List<Long> counts = new ArrayList<>(expectedForks);
        for (Object histogramValue : histograms) {
            List<?> measurementHistograms = listValue(histogramValue, "rawDataHistogram fork partition", tuple);
            if (measurementHistograms.size() != expectedMeasurements) {
                throw new IllegalArgumentException("Paired JMH rawDataHistogram fork partition has the wrong iteration count: " + tuple);
            }
            long count = 0L;
            for (Object measurementHistogramValue : measurementHistograms) {
                List<?> bins = listValue(measurementHistogramValue, "rawDataHistogram measurement partition", tuple);
                if (bins.isEmpty()) {
                    throw new IllegalArgumentException("Paired JMH rawDataHistogram has an empty measurement partition: " + tuple);
                }
                double previousValue = Double.NEGATIVE_INFINITY;
                for (Object binValue : bins) {
                    List<?> bin = listValue(binValue, "rawDataHistogram bucket", tuple);
                    if (bin.size() != 2) throw new IllegalArgumentException("Paired JMH rawDataHistogram bucket is malformed: " + tuple);
                    double latency = finiteNumber(bin.get(0), "rawDataHistogram latency", tuple);
                    long frequency = exactLong(bin.get(1), "rawDataHistogram frequency");
                    if (latency < 0d || frequency <= 0L || latency < previousValue) {
                        throw new IllegalArgumentException("Paired JMH rawDataHistogram bucket values are invalid: " + tuple);
                    }
                    previousValue = latency;
                    count = Math.addExact(count, frequency);
                }
            }
            counts.add(count);
        }
        return counts;
    }

    private static void mergeHistogram(Map<Double, Long> target, Object histogramValue, String tuple) {
        List<?> measurementHistograms = listValue(histogramValue, "rawDataHistogram fork partition", tuple);
        for (Object measurementHistogramValue : measurementHistograms) {
            List<?> bins = listValue(measurementHistogramValue, "rawDataHistogram measurement partition", tuple);
            for (Object binValue : bins) {
                List<?> bin = listValue(binValue, "rawDataHistogram bucket", tuple);
                double latency = finiteNumber(bin.get(0), "rawDataHistogram latency", tuple);
                long frequency = exactLong(bin.get(1), "rawDataHistogram frequency");
                target.merge(latency, frequency, Math::addExact);
            }
        }
    }

    private static Map<String, Object> confidenceInterval(TreeMap<Double, Long> histogram, long count) {
        double standardDeviation = Math.sqrt(P999 * (1d - P999) / count);
        double lowerProbability = Math.max(0d, P999 - Z_95 * standardDeviation);
        double upperProbability = Math.min(1d, P999 + Z_95 * standardDeviation);
        long lowerRank = Math.max(1L, (long) Math.floor(lowerProbability * count));
        long targetRank = Math.max(1L, (long) Math.ceil(P999 * count));
        long upperRank = Math.min(count, (long) Math.ceil(upperProbability * count));
        Map<String, Object> confidence = new LinkedHashMap<>();
        confidence.put("method", "approximate-binomial-order-statistic");
        confidence.put("confidenceLevelPercent", 95);
        confidence.put("targetPercentile", 99.9d);
        confidence.put("lowerRankPercentile", lowerRank * 100d / count);
        confidence.put("upperRankPercentile", upperRank * 100d / count);
        confidence.put("estimateMicros", quantile(histogram, targetRank));
        confidence.put("lowerLatencyMicros", quantile(histogram, lowerRank));
        confidence.put("upperLatencyMicros", quantile(histogram, upperRank));
        return confidence;
    }

    private static double quantile(TreeMap<Double, Long> histogram, long targetRank) {
        long cumulative = 0L;
        for (Map.Entry<Double, Long> bucket : histogram.entrySet()) {
            cumulative = Math.addExact(cumulative, bucket.getValue());
            if (cumulative >= targetRank) return bucket.getKey();
        }
        throw new IllegalArgumentException("Paired JMH histogram does not contain its declared sample count");
    }

    private static void validateCaptureStatus(Object value, String field) {
        Map<?, ?> capture = objectMap(value, field + " must disclose its capture status");
        String status = requiredString(capture.get("status"), field + ".status");
        if (!status.equals("CAPTURED") && !status.equals("NOT_CAPTURED")) {
            throw new IllegalArgumentException(field + ".status must be CAPTURED or NOT_CAPTURED");
        }
        if (status.equals("CAPTURED")) {
            requiredString(capture.get("value"), field + ".value");
        } else {
            requiredString(capture.get("reason"), field + ".reason");
        }
    }

    private static List<String> csvValues(String value) {
        List<String> values = new ArrayList<>();
        for (String item : value.split(",", -1)) values.add(item.trim());
        return values;
    }

    private static Map<?, ?> objectMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(message);
        return map;
    }

    private static List<?> listValue(Object value, String field, String tuple) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Paired JMH " + field + " is missing or malformed: " + tuple);
        }
        return list;
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Paired JMH " + field + " is missing or blank");
        }
        return text;
    }

    private static int exactInteger(Object value, String field) {
        long number = exactLong(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Paired JMH " + field + " is outside integer range");
        }
        return (int) number;
    }

    private static long exactLong(Object value, String field) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Paired JMH " + field + " is not numeric");
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                || decimal < Long.MIN_VALUE || decimal > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Paired JMH " + field + " must be a finite integer");
        }
        return number.longValue();
    }

    private static double finiteNumber(Object value, String field, String tuple) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Paired JMH " + field + " is not finite numeric data: " + tuple);
        }
        return number.doubleValue();
    }

    private static void requireFiniteNumber(Object value, String field, String tuple) {
        finiteNumber(value, field, tuple);
    }
}
