package noietime.syncmoney.benchmark.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineMacroPilotTest {
    private static final String BASELINE_SHA = "71cc55dfeefe9a8f40a3835b051ddd324d353767";
    private static final String ARTIFACT_SHA = "a".repeat(64);
    private static final String PROFILE_SHA = "b".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesRateOnlyFromCompleteArtifactAndProfileBoundMacroEvidence() throws Exception {
        Path manifest = write(validManifest());

        BaselineMacroPilot pilot = BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                "redis-sql-open-loop", PROFILE_SHA, 100_000);

        assertEquals(800.0, pilot.acceptedTransactionsPerSecond());
        assertEquals(64, pilot.manifestSha256().length());
    }

    @Test
    void rejectsSyntheticFacadePilot() throws Exception {
        Path manifest = write(validManifest().replace("legacy132-redis-sql-macro", "transaction-paired-jmh"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        assertTrue(failure.getMessage().contains("real 1.3.2 Redis/SQL macro pilot"));
    }

    @Test
    void rejectsMismatchedArtifactAndInsufficientTailSamples() throws Exception {
        Path wrongArtifact = write(validManifest().replace(ARTIFACT_SHA, "c".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(wrongArtifact, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        Path tooFewSamples = write(validManifest().replace("\"durable\": 240000", "\"durable\": 99999"));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(tooFewSamples, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsHistogramCountsThatDoNotMatchOutcomeCounts() throws Exception {
        String valid = validManifest();

        Path responseMismatch = write(valid.replace("\"response\": 300000", "\"response\": 299999"));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(responseMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        Path receiptMismatch = write(valid.replace("\"receipt\": 240000", "\"receipt\": 239999"));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(receiptMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        Path durableMismatch = write(valid.replace("\"durable\": 240000", "\"durable\": 239999"));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(durableMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        String responseHistogram = histogram(300000);
        Path histogramMismatch = write(valid.replace("\"responseHistogram\": " + responseHistogram,
                "\"responseHistogram\": " + responseHistogram.replace("\"rawCount\":300000", "\"rawCount\":299999")));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(histogramMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsImpossibleNegativeRejectionCountEvenWhenOtherCountsConserve() throws Exception {
        String impossible = validManifest()
                .replace("\"receipt\": 240000", "\"receipt\": 900000")
                .replace("\"durable\": 240000", "\"durable\": 900000")
                .replace("\"accepted\": 240000", "\"accepted\": 900000")
                .replace("\"rejected\": 60000", "\"rejected\": -600000")
                .replace("\"persisted\": 240000", "\"persisted\": 900000")
                .replace("\"rawCount\": 240000", "\"rawCount\": 900000")
                .replace("\"correctedCount\": 240100", "\"correctedCount\": 900100");
        Path manifest = write(impossible);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        assertTrue(failure.getMessage().contains("outcome counts"));
    }

    @Test
    void rejectsHistogramWithoutRawPercentileSummary() throws Exception {
        String complete = histogram(300000);
        String missingRawP999 = validManifest().replace("\"responseHistogram\": " + complete,
                "\"responseHistogram\": " + complete.replace("\"p99.9\":40", "\"p999\":40"));
        Path manifest = write(missingRawP999);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsHistogramWithoutExpectedIntervalCorrectedP999() throws Exception {
        String complete = histogram(240000);
        String missingCorrectedP999 = validManifest().replace("\"receiptHistogram\": " + complete,
                "\"receiptHistogram\": " + complete.replace("\"p99.9\":38", "\"p999\":38"));
        Path manifest = write(missingCorrectedP999);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsExpectedIntervalCorrectedP999AboveObservedMaximum() throws Exception {
        String complete = histogram(240000);
        String impossibleCorrectedP999 = validManifest().replace("\"receiptHistogram\": " + complete,
                "\"receiptHistogram\": " + complete.replace("\"p99.9\":38", "\"p99.9\":101"));
        Path manifest = write(impossibleCorrectedP999);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsPilotMeasuredOnDifferentRuntimeJdk() throws Exception {
        Path wrongRuntime = write(validManifest().replace("\"runtimeJavaVersion\": \"25.0.4\"",
                "\"runtimeJavaVersion\": \"21.0.8\""));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(wrongRuntime, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        assertTrue(failure.getMessage().contains("Java 25 runtime"));
    }

    private Path write(String json) throws Exception {
        Path manifest = temporaryDirectory.resolve("pilot.json");
        Files.writeString(manifest, json);
        return manifest;
    }

    private String validManifest() {
        return """
                {
                  "schemaVersion": 1,
                  "measurementKind": "legacy132-redis-sql-macro",
                  "sourceSHA": "%s",
                  "baselineSHA": "%s",
                  "baselineArtifactSHA256": "%s",
                  "baselineBuildJavaVersion": "21.0.8",
                  "runtimeJavaVersion": "25.0.4",
                  "profileId": "redis-sql-open-loop",
                  "profileSHA256": "%s",
                  "durationSeconds": 300,
                  "sampleCount": {"response": 300000, "receipt": 240000, "durable": 240000},
                  "responseHistogram": %s,
                  "receiptHistogram": %s,
                  "durableHistogram": %s,
                  "offered": 300000,
                  "accepted": 240000,
                  "rejected": 60000,
                  "persisted": 240000,
                  "moneyInvariantFailures": 0,
                  "versionConflicts": 0,
                  "authorityRejected": 0,
                  "ambiguous": 0,
                  "quarantine": 0,
                  "valid": true,
                  "dependencies": {
                    "redisVersion": "8.0.0",
                    "sqlDialect": "SQLITE",
                    "sqlProduct": "SQLite",
                    "sqlVersion": "3.50.0"
                  }
                }
                """.formatted(BASELINE_SHA, BASELINE_SHA, ARTIFACT_SHA, PROFILE_SHA,
                histogram(300000), histogram(240000), histogram(240000));
    }

    private String histogram(long count) {
        return "{\"unit\":\"microseconds\",\"rawCount\":" + count
                + ",\"raw\":{\"p50\":10,\"p95\":20,\"p99\":30,\"p99.9\":40,\"max\":100}"
                + ",\"correctedCount\":" + (count + 100)
                + ",\"expectedIntervalCorrected\":{\"p99.9\":38}}";
    }
}
