package noietime.syncmoney.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairedJmhReportValidatorTest {
    private static final int PRODUCERS = 2;
    private static final int FULL_FORKS = 3;
    private static final int FULL_MEASUREMENTS = 10;
    private static final long P999_MINIMUM_SAMPLES = 100_000L;
    private static final List<String> OPERATIONS = List.of("balance", "deposit", "withdraw", "set", "transfer",
            "reject-withdraw", "cancel-deposit", "cancel-withdraw");

    @Test
    void acceptsCompletePairedMatrixAndReportsSamplePartitionsAndP999Confidence() {
        Map<String, Object> summary = PairedJmhReportValidator.validateRows(
                fullRows(List.of("current", "legacy132"), null, FULL_FORKS, 100_000),
                List.of("current", "legacy132"), null, false, PRODUCERS, FULL_FORKS,
                FULL_MEASUREMENTS, P999_MINIMUM_SAMPLES);

        assertEquals(192, summary.get("rowCount"));
        assertEquals(96, summary.get("sampleTimeRows"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assessments = (List<Map<String, Object>>) summary.get("sampleTailAssessment");
        assertEquals(96, assessments.size());
        assertEquals(List.of(100_000L, 100_000L, 100_000L), assessments.getFirst().get("forkSampleCounts"));
        assertEquals(300_000L, assessments.getFirst().get("sampleCount"));
        assertEquals(Boolean.TRUE, assessments.getFirst().get("sampleMinimumMet"));
        assertNotNull(assessments.getFirst().get("p999Confidence95"));
    }

    @Test
    void rejectsMissingExpectedTuple() {
        List<Map<String, Object>> rows = fullRows(List.of("current", "legacy132"), null, FULL_FORKS, 100_000);
        rows.removeFirst();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateRows(rows, List.of("current", "legacy132"), null,
                        false, PRODUCERS, FULL_FORKS, FULL_MEASUREMENTS, P999_MINIMUM_SAMPLES));

        assertTrue(failure.getMessage().contains("missing"));
    }

    @Test
    void rejectsDuplicateTupleEvenWhenRowCountMatches() {
        List<Map<String, Object>> rows = fullRows(List.of("current", "legacy132"), null, FULL_FORKS, 100_000);
        rows.set(rows.size() - 1, rows.getFirst());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateRows(rows, List.of("current", "legacy132"), null,
                        false, PRODUCERS, FULL_FORKS, FULL_MEASUREMENTS, P999_MINIMUM_SAMPLES));

        assertTrue(failure.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsRawDataWithFewerForkPartitionsThanTheRequestedMatrix() {
        List<Map<String, Object>> rows = fullRows(List.of("current", "legacy132"), null, FULL_FORKS, 100_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> metric = (Map<String, Object>) rows.getFirst().get("primaryMetric");
        @SuppressWarnings("unchecked")
        List<List<Double>> rawData = (List<List<Double>>) metric.get("rawData");
        rawData.removeLast();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateRows(rows, List.of("current", "legacy132"), null,
                        false, PRODUCERS, FULL_FORKS, FULL_MEASUREMENTS, P999_MINIMUM_SAMPLES));

        assertTrue(failure.getMessage().contains("rawData"));
        assertTrue(failure.getMessage().contains("fork"));
    }

    @Test
    void rejectsSampleHistogramWithMissingForkPartition() {
        List<Map<String, Object>> rows = fullRows(List.of("current", "legacy132"), null, FULL_FORKS, 100_000);
        Map<String, Object> sampleRow = rows.stream().filter(row -> row.get("mode").equals("sample")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> metric = (Map<String, Object>) sampleRow.get("primaryMetric");
        @SuppressWarnings("unchecked")
        List<List<List<Number>>> histogram = (List<List<List<Number>>>) metric.get("rawDataHistogram");
        histogram.removeLast();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateRows(rows, List.of("current", "legacy132"), null,
                        false, PRODUCERS, FULL_FORKS, FULL_MEASUREMENTS, P999_MINIMUM_SAMPLES));

        assertTrue(failure.getMessage().contains("rawDataHistogram"));
        assertTrue(failure.getMessage().contains("fork"));
    }

    @Test
    void quickProfileMarksInsufficientP999SamplesWithoutManufacturingConfidence() {
        Map<String, Object> summary = PairedJmhReportValidator.validateRows(
                quickRows(List.of("current"), "set", 1, 100), List.of("current"), "set", true,
                PRODUCERS, 1, 1, P999_MINIMUM_SAMPLES);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assessments = (List<Map<String, Object>>) summary.get("sampleTailAssessment");
        assertEquals(1, assessments.size());
        assertEquals(Boolean.FALSE, assessments.getFirst().get("sampleMinimumMet"));
        assertNull(assessments.getFirst().get("p999Confidence95"));
    }

    @Test
    void quickProfileSuppressesP999ConfidenceEvenWhenSampleMinimumIsMet() {
        Map<String, Object> summary = PairedJmhReportValidator.validateRows(
                quickRows(List.of("current"), "set", 1, 100_000), List.of("current"), "set", true,
                PRODUCERS, 1, 1, P999_MINIMUM_SAMPLES);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assessments = (List<Map<String, Object>>) summary.get("sampleTailAssessment");
        assertEquals(Boolean.TRUE, assessments.getFirst().get("sampleMinimumMet"));
        assertNull(assessments.getFirst().get("p999Confidence95"));
    }

    @Test
    void acceptsJmhSampleTimeHistogramPartitionsWithoutSampleRawData() {
        List<Map<String, Object>> rows = quickRows(List.of("current"), "set", 1, 100);
        Map<String, Object> sampleRow = rows.stream().filter(row -> row.get("mode").equals("sample"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> metric = (Map<String, Object>) sampleRow.get("primaryMetric");
        assertNull(metric.get("rawData"));

        Map<String, Object> summary = PairedJmhReportValidator.validateRows(rows, List.of("current"), "set",
                true, PRODUCERS, 1, 1, P999_MINIMUM_SAMPLES);

        assertEquals(2, summary.get("rowCount"));
        assertEquals(1, summary.get("sampleTimeRows"));
    }

    @Test
    void resultManifestMustBindExactJsonDigestAndLength(@TempDir Path temporaryDirectory) throws Exception {
        Path result = temporaryDirectory.resolve("paired.json");
        byte[] content = "[]\n".getBytes(StandardCharsets.UTF_8);
        Files.write(result, content);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("resultSHA256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        manifest.put("resultBytes", (long) content.length);

        assertDoesNotThrow(() -> PairedJmhReportValidator.verifyResultBinding(manifest, result));

        manifest.put("resultSHA256", "0".repeat(64));
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.verifyResultBinding(manifest, result));
        assertTrue(mismatch.getMessage().contains("digest"));

        manifest.remove("resultSHA256");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.verifyResultBinding(manifest, result));
        assertTrue(missing.getMessage().contains("resultSHA256"));
    }

    @Test
    void hostProvenanceRequiresPlatformFactsAndExplicitAffinityAndPowerCaptureStatus() {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("osName", "Windows 11");
        host.put("osVersion", "10.0");
        host.put("osArch", "amd64");
        host.put("cpuModel", "Example CPU");
        host.put("availableProcessors", 20);
        host.put("totalPhysicalMemoryBytes", 32L * 1024L * 1024L * 1024L);
        host.put("forkAffinity", Map.of("status", "NOT_CAPTURED", "reason", "The fork mask was not observed."));
        host.put("powerPlan", Map.of("status", "CAPTURED", "value", "381b4222-f694-41f0-9685-ff5bb260df2e"));

        assertDoesNotThrow(() -> PairedJmhReportValidator.validateHostProvenance(host));

        host.remove("forkAffinity");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateHostProvenance(host));
        assertTrue(missing.getMessage().contains("forkAffinity"));
    }

    @Test
    void rejectsMultilinePowerShellErrorTextAsCapturedCpuModel() {
        Map<String, Object> host = validHostProvenance();
        host.put("cpuModel", "Stop : The term 'Stop' is not recognized.\r\nAt line:1 char:26");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateHostProvenance(host));

        assertTrue(failure.getMessage().contains("cpuModel"));
    }

    @Test
    void rejectsCapturedPowerPlanOutputWithoutAStableGuid() {
        Map<String, Object> host = validHostProvenance();
        host.put("powerPlan", Map.of("status", "CAPTURED", "value", "???? GUID: 381b4222-f694-41f0-9685-ff5bb260df2e (??)"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PairedJmhReportValidator.validateHostProvenance(host));

        assertTrue(failure.getMessage().contains("powerPlan"));
    }

    private static List<Map<String, Object>> fullRows(List<String> variants, String operation,
            int forks, int samplesPerFork) {
        return rows(variants, operation, false, forks, samplesPerFork);
    }

    private static Map<String, Object> validHostProvenance() {
        return new LinkedHashMap<>(Map.of(
                "osName", "Windows 11",
                "osVersion", "10.0",
                "osArch", "amd64",
                "cpuModel", "Example CPU",
                "availableProcessors", 20,
                "totalPhysicalMemoryBytes", 32L * 1024L * 1024L * 1024L,
                "forkAffinity", Map.of("status", "NOT_CAPTURED", "reason", "The fork mask was not observed."),
                "powerPlan", Map.of("status", "CAPTURED", "value", "381b4222-f694-41f0-9685-ff5bb260df2e")));
    }

    private static List<Map<String, Object>> quickRows(List<String> variants, String operation,
            int forks, int samplesPerFork) {
        return rows(variants, operation, true, forks, samplesPerFork);
    }

    private static List<Map<String, Object>> rows(List<String> variants, String operation, boolean quick,
            int forks, int samplesPerFork) {
        List<String> operations = operation == null ? OPERATIONS : List.of(operation);
        List<String> accountCounts = quick ? List.of("1000") : List.of("1", "1000", "10000");
        List<String> accessPatterns = quick ? List.of("uniform") : List.of("uniform", "hot");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String variant : variants) {
            for (String selectedOperation : operations) {
                for (String accountCount : accountCounts) {
                    for (String accessPattern : accessPatterns) {
                        for (String mode : List.of("thrpt", "sample")) {
                            rows.add(row(variant, selectedOperation, accountCount, accessPattern,
                                    mode, forks, samplesPerFork));
                        }
                    }
                }
            }
        }
        return rows;
    }

    private static Map<String, Object> row(String variant, String operation, String accountCount,
            String accessPattern, String mode, int forks, int samplesPerFork) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jmhVersion", "1.37");
        row.put("benchmark", "noietime.syncmoney.benchmark.TransactionBenchmarks.execute");
        row.put("mode", mode);
        row.put("threads", PRODUCERS);
        row.put("forks", forks);
        row.put("measurementIterations", forks == 1 ? 1 : FULL_MEASUREMENTS);
        row.put("jdkVersion", "25.0.4");
        row.put("jvmArgs", List.of("-XX:+UseSerialGC", "-Dnet.bytebuddy.experimental=true", "-Xmx1g"));
        row.put("params", Map.of("variant", variant, "operation", operation,
                "accountCount", accountCount, "accessPattern", accessPattern));

        List<List<Double>> rawData = new ArrayList<>();
        for (int fork = 0; fork < forks; fork++) {
            List<Double> measurements = new ArrayList<>();
            for (int iteration = 0; iteration < (forks == 1 ? 1 : FULL_MEASUREMENTS); iteration++) {
                measurements.add(1.0d + iteration);
            }
            rawData.add(measurements);
        }
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("score", 1.0d);
        metric.put("scoreUnit", mode.equals("sample") ? "us/op" : "ops/us");
        if (mode.equals("thrpt")) {
            metric.put("rawData", rawData);
        } else {
            List<List<List<List<Number>>>> histograms = new ArrayList<>();
            int measurementIterations = forks == 1 ? 1 : FULL_MEASUREMENTS;
            long samplesPerIteration = samplesPerFork / measurementIterations;
            for (int fork = 0; fork < forks; fork++) {
                List<List<List<Number>>> iterationHistograms = new ArrayList<>();
                for (int iteration = 0; iteration < measurementIterations; iteration++) {
                    if (samplesPerIteration <= 200) {
                        iterationHistograms.add(List.of(List.of(5.0d, samplesPerIteration)));
                    } else {
                        iterationHistograms.add(List.of(List.of(5.0d, samplesPerIteration - 200),
                                List.of(20.0d, 200L)));
                    }
                }
                histograms.add(iterationHistograms);
            }
            metric.put("rawDataHistogram", histograms);
            metric.put("scorePercentiles", Map.of("99.9", 20.0d));
        }
        row.put("primaryMetric", metric);
        return row;
    }
}
