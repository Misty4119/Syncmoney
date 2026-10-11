package noietime.syncmoney.benchmark.load;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineMacroPilotTest {
    private static final String BASELINE_SHA = "71cc55dfeefe9a8f40a3835b051ddd324d353767";
    private static final String ARTIFACT_SHA = "a".repeat(64);
    private static final String PROFILE_SHA = "b".repeat(64);
    private static final String HARNESS_SOURCE_SHA = "c".repeat(40);
    private static final String HARNESS_SOURCE_TREE_SHA = "d".repeat(64);
    private static final String RUNTIME_CLASSPATH_SHA = "e".repeat(64);
    private static final Gson GSON = new Gson();

    private String previousHarnessSourceSha;
    private String previousHarnessSourceTreeSha;
    private String previousRuntimeClasspathSha;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void installExpectedHarnessProvenance() {
        previousHarnessSourceSha = System.getProperty("syncmoney.benchmarks.sourceSha");
        previousHarnessSourceTreeSha = System.getProperty("syncmoney.benchmarks.sourceTreeSha256");
        previousRuntimeClasspathSha = System.getProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256");
        System.setProperty("syncmoney.benchmarks.sourceSha", HARNESS_SOURCE_SHA);
        System.setProperty("syncmoney.benchmarks.sourceTreeSha256", HARNESS_SOURCE_TREE_SHA);
        System.setProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256", RUNTIME_CLASSPATH_SHA);
    }

    @AfterEach
    void restoreHarnessProvenance() {
        restoreProperty("syncmoney.benchmarks.sourceSha", previousHarnessSourceSha);
        restoreProperty("syncmoney.benchmarks.sourceTreeSha256", previousHarnessSourceTreeSha);
        restoreProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256", previousRuntimeClasspathSha);
    }

    @Test
    void derivesRateOnlyFromCompleteArtifactAndProfileBoundMacroEvidence() throws Exception {
        Path manifest = write(validManifest());

        BaselineMacroPilot pilot = BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                "redis-sql-open-loop", PROFILE_SHA, 100_000);

        assertEquals(300_000d / 330d, pilot.acceptedTransactionsPerSecond(), 0.000_001d);
        assertEquals(64, pilot.manifestSha256().length());
        assertEquals(BenchmarkEnvironment.capture().fingerprint(), pilot.environmentFingerprintSha256());
        assertDoesNotThrow(() -> pilot.requireBackendMatch("8.0.0", "SQLITE", "SQLite", "3.50.0"));
    }

    @Test
    void rejectsBaselinePilotFromDifferentMachineOrJvmEnvironment() throws Exception {
        JsonObject manifest = JsonParser.parseString(validManifest()).getAsJsonObject();
        JsonObject environment = manifest.getAsJsonObject("environment");
        environment.addProperty("osVersion", environment.get("osVersion").getAsString() + "-other-host");
        BenchmarkEnvironment.Snapshot otherHost = BenchmarkEnvironment.parse(environment);
        manifest.addProperty("environmentFingerprintSHA256", otherHost.fingerprint());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(GSON.toJson(manifest)), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        assertTrue(failure.getMessage().contains("current open-loop measurement environment"));
    }

    @Test
    void validatesPilotAgainstTheLaunchSnapshotProvidedByTheOpenLoopRunner() throws Exception {
        BenchmarkEnvironment.Snapshot current = BenchmarkEnvironment.capture();
        Map<String, Object> differentLaunchFields = new LinkedHashMap<>(current.fields());
        differentLaunchFields.put("maxMemoryBytes", current.maxMemoryBytes() + 1_024L);
        BenchmarkEnvironment.Snapshot differentLaunch = BenchmarkEnvironment.fromFields(differentLaunchFields);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(validManifest()), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000, null, differentLaunch));

        assertTrue(failure.getMessage().contains("current open-loop measurement environment"));
    }

    @Test
    void rejectsPilotBoundToDifferentHarnessSourceProvenance() throws Exception {
        Map<String, String> mismatches = Map.of(
                "harnessSourceSHA", "f".repeat(40),
                "harnessSourceTreeSHA256", "f".repeat(64),
                "loadTestRuntimeClasspathSHA256", "f".repeat(64));
        for (Map.Entry<String, String> mismatch : mismatches.entrySet()) {
            JsonObject manifest = JsonParser.parseString(validManifest()).getAsJsonObject();
            manifest.addProperty(mismatch.getKey(), mismatch.getValue());

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BaselineMacroPilot.read(write(GSON.toJson(manifest)), BASELINE_SHA, ARTIFACT_SHA,
                            "redis-sql-open-loop", PROFILE_SHA, 100_000), mismatch.getKey());

            assertTrue(failure.getMessage().contains("harness provenance"), mismatch.getKey());
        }
    }

    @Test
    void acceptsSha1AndSha256HarnessSourceIdsButRejectsIntermediateLengths() throws Exception {
        String sha256HarnessSource = "f".repeat(64);
        System.setProperty("syncmoney.benchmarks.sourceSha", sha256HarnessSource);
        assertDoesNotThrow(() -> BaselineMacroPilot.read(
                write(validManifest().replace(HARNESS_SOURCE_SHA, sha256HarnessSource)), BASELINE_SHA, ARTIFACT_SHA,
                "redis-sql-open-loop", PROFILE_SHA, 100_000));

        for (int length : new int[] {41, 63}) {
            String nonCanonicalHarnessSource = "a".repeat(length);
            System.setProperty("syncmoney.benchmarks.sourceSha", nonCanonicalHarnessSource);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BaselineMacroPilot.read(
                            write(validManifest().replace(HARNESS_SOURCE_SHA, nonCanonicalHarnessSource)),
                            BASELINE_SHA, ARTIFACT_SHA, "redis-sql-open-loop", PROFILE_SHA, 100_000),
                    "non-canonical Git object ID length " + length);
            assertTrue(failure.getMessage().contains("harness provenance"));
        }
    }

    @Test
    void rejectsPilotWithMissingHarnessProvenanceFields() throws Exception {
        for (String field : new String[] {"harnessSourceSHA", "harnessSourceTreeSHA256",
                "loadTestRuntimeClasspathSHA256"}) {
            JsonObject manifest = JsonParser.parseString(validManifest()).getAsJsonObject();
            manifest.remove(field);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BaselineMacroPilot.read(write(GSON.toJson(manifest)), BASELINE_SHA, ARTIFACT_SHA,
                            "redis-sql-open-loop", PROFILE_SHA, 100_000), field);

            assertTrue(failure.getMessage().contains(field), field);
        }
    }

    @Test
    void rejectsOldPilotSchemaWithoutEnvironmentAndLatencySemantics() throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(validManifest().replace("\"schemaVersion\": 2",
                                "\"schemaVersion\": 1")), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("real 1.3.2 Redis/SQL macro pilot"));
    }

    @Test
    void rejectsEnvironmentFingerprintThatDoesNotBindRecordedMetadata() throws Exception {
        String invalid = validManifest().replace(BenchmarkEnvironment.capture().fingerprint(), "0".repeat(64));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(invalid), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("fingerprint"));
    }

    @Test
    void rejectsPollingLatencyHistogramsThatClaimIndependentSamplesOrCorrection() throws Exception {
        JsonObject manifest = JsonParser.parseString(validManifest()).getAsJsonObject();
        manifest.getAsJsonObject("receiptHistogram").addProperty("correctedCount", 300_100);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(GSON.toJson(manifest)), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("polling observations"));
    }

    @Test
    void runnerRejectsGradlePilotHashWhenTheManifestChangesBeforeItIsRead() throws Exception {
        String profileJson;
        try (InputStream stream = getClass().getResourceAsStream("/redis-sql-open-loop.json")) {
            if (stream == null) throw new AssertionError("Missing checked-in redis-sql-open-loop profile");
            profileJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        OpenLoopProfile profile = OpenLoopProfile.readJson(profileJson);
        Path manifest = write(validManifest().replace(PROFILE_SHA, profile.sha256));
        Method readPilot = OpenLoopLoadRunner.class.getDeclaredMethod("readBaselinePilot", String[].class,
                OpenLoopProfile.class, BenchmarkEnvironment.Snapshot.class);
        readPilot.setAccessible(true);

        String oldBaselineSha = System.getProperty("syncmoney.benchmarks.baselineSha");
        String oldArtifactSha = System.getProperty("syncmoney.benchmarks.baselineJarSha256");
        String oldPilotSha = System.getProperty("syncmoney.benchmarks.baselineMacroPilotSha256");
        try {
            System.setProperty("syncmoney.benchmarks.baselineSha", BASELINE_SHA);
            System.setProperty("syncmoney.benchmarks.baselineJarSha256", ARTIFACT_SHA);
            System.setProperty("syncmoney.benchmarks.baselineMacroPilotSha256", "f".repeat(64));

            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> readPilot.invoke(null, (Object) new String[] {"--baseline-pilot", manifest.toString()},
                            profile, BenchmarkEnvironment.capture()));

            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("hash"));
        } finally {
            restoreProperty("syncmoney.benchmarks.baselineSha", oldBaselineSha);
            restoreProperty("syncmoney.benchmarks.baselineJarSha256", oldArtifactSha);
            restoreProperty("syncmoney.benchmarks.baselineMacroPilotSha256", oldPilotSha);
        }
    }

    @Test
    void refusesToTreatUnobservableLegacyFailureTaxonomyAsZero() throws Exception {
        String missingTaxonomy = validManifest()
                .replace("  \"legacyFailureTaxonomy\": \"UNAVAILABLE_IN_1_3_2\",\n", "")
                .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                .replace("\"quarantine\": null", "\"quarantine\": 0");
        Path manifest = write(missingTaxonomy);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsCategorizedZerosWhenLegacyTaxonomyIsUnavailable() throws Exception {
        String categorizedAsZero = validManifest()
                .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                .replace("\"quarantine\": null", "\"quarantine\": 0");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(categorizedAsZero), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("taxonomy"));
    }

    @Test
    void rejectsNonzeroLegacyFailedEventCount() throws Exception {
        String failedManifest = validManifest()
                .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                .replace("\"quarantine\": null", "\"quarantine\": 0")
                .replace("\"legacyFailedEvents\": 0", "\"legacyFailedEvents\": 1");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(failedManifest), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("failed legacy events"));
    }

    @Test
    void requiresTheObservedMeasurementDurationToMeetTheMinimum() throws Exception {
        String shortRun = validManifest()
                .replace("\"measuredDurationMillis\": 330000", "\"measuredDurationMillis\": 299999")
                .replace("\"measuredDurationNanos\": 330000000000", "\"measuredDurationNanos\": 299999000000")
                .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                .replace("\"quarantine\": null", "\"quarantine\": 0");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(shortRun), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("300 measured seconds"));
    }

    @Test
    void rejectsRejectedOperationsWithoutMatchingAuthorityRejections() throws Exception {
        String inconsistent = manifest(240_000, 60_000, 0)
                .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                .replace("\"quarantine\": null", "\"quarantine\": 0");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(inconsistent), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
        assertTrue(failure.getMessage().contains("rejection counts"));
    }

    @Test
    void requiresProducerDrainPersistenceAndCleanupEvidence() throws Exception {
        for (String field : new String[] {"economyQueueDrained", "databaseQueueDrained",
                "exactRedisAndSqlRowsVerified", "cleanupComplete"}) {
            String incomplete = validManifest()
                    .replace("\"" + field + "\": true", "\"" + field + "\": false")
                    .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                    .replace("\"quarantine\": null", "\"quarantine\": 0");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BaselineMacroPilot.read(write(incomplete), BASELINE_SHA, ARTIFACT_SHA,
                            "redis-sql-open-loop", PROFILE_SHA, 100_000), field);
            assertTrue(failure.getMessage().contains(field), failure.getMessage());
        }
    }

    @Test
    void rejectsDroppedEventsAndOverflowRecords() throws Exception {
        for (String field : new String[] {"droppedEvents", "overflowRecords"}) {
            String incomplete = validManifest().replace("\"" + field + "\": 0", "\"" + field + "\": 1")
                    .replace("\"ambiguous\": null", "\"ambiguous\": 0")
                    .replace("\"quarantine\": null", "\"quarantine\": 0");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BaselineMacroPilot.read(write(incomplete), BASELINE_SHA, ARTIFACT_SHA,
                            "redis-sql-open-loop", PROFILE_SHA, 100_000), field);
            assertTrue(failure.getMessage().contains(field), failure.getMessage());
        }
    }

    @Test
    void rejectsPilotMeasuredAgainstDifferentRedisOrSqlVersion() throws Exception {
        BaselineMacroPilot pilot = BaselineMacroPilot.read(write(validManifest()), BASELINE_SHA, ARTIFACT_SHA,
                "redis-sql-open-loop", PROFILE_SHA, 100_000);

        assertThrows(IllegalArgumentException.class,
                () -> pilot.requireBackendMatch("7.4.2", "SQLITE", "SQLite", "3.50.0"));
        assertThrows(IllegalArgumentException.class,
                () -> pilot.requireBackendMatch("8.0.0", "SQLITE", "SQLite", "3.49.1"));
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

        Path tooFewSamples = write(validManifest().replace("\"durable\": 300000", "\"durable\": 99999"));
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

        Path receiptMismatch = write(valid.replace("\"receipt\": 300000", "\"receipt\": 299999"));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(receiptMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        Path durableMismatch = write(valid.replace("\"durable\": 300000", "\"durable\": 299999"));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(durableMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        String responseHistogram = histogram(300000, true);
        Path histogramMismatch = write(valid.replace("\"responseHistogram\": " + responseHistogram,
                "\"responseHistogram\": " + responseHistogram.replace("\"rawCount\":300000", "\"rawCount\":299999")));
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(histogramMismatch, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsImpossibleNegativeRejectionCountEvenWhenOtherCountsConserve() throws Exception {
        String impossible = validManifest()
                .replace("\"receipt\": 300000", "\"receipt\": 900000")
                .replace("\"durable\": 300000", "\"durable\": 900000")
                .replace("\"accepted\": 300000", "\"accepted\": 900000")
                .replace("\"rejected\": 0", "\"rejected\": -600000")
                .replace("\"persisted\": 300000", "\"persisted\": 900000")
                .replace("\"rawCount\":300000", "\"rawCount\":900000")
                .replace("\"correctedCount\":300100", "\"correctedCount\":900100");
        Path manifest = write(impossible);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));

        assertTrue(failure.getMessage().contains("outcome counts"));
    }

    @Test
    void rejectsHistogramWithoutRawPercentileSummary() throws Exception {
        String complete = histogram(300000, true);
        String missingRawP999 = validManifest().replace("\"responseHistogram\": " + complete,
                "\"responseHistogram\": " + complete.replace("\"p99.9\":40", "\"p999\":40"));
        Path manifest = write(missingRawP999);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsHistogramWithoutExpectedIntervalCorrectedP999() throws Exception {
        String complete = histogram(300000, true);
        String missingCorrectedP999 = validManifest().replace("\"responseHistogram\": " + complete,
                "\"responseHistogram\": " + complete.replace("\"p99.9\":38", "\"p999\":38"));
        Path manifest = write(missingCorrectedP999);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsExpectedIntervalCorrectedP999AboveObservedMaximum() throws Exception {
        String complete = histogram(300000, true);
        String impossibleCorrectedP999 = validManifest().replace("\"responseHistogram\": " + complete,
                "\"responseHistogram\": " + complete.replace("\"p99.9\":38", "\"p99.9\":101"));
        Path manifest = write(impossibleCorrectedP999);

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(manifest, BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsPilotHistogramWithoutAQuantileConfidenceInterval() throws Exception {
        String incomplete = validManifest().replaceAll(",\"p99\\.9Confidence95\":\\{[^}]*}", "");

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(incomplete), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsConfidenceRanksThatDoNotMatchObservedSampleCount() throws Exception {
        String broadButUnsubstantiated = validManifest().replaceAll(
                "\"lowerRankPercentile\":[^,]+,\"upperRankPercentile\":[^,]+",
                "\"lowerRankPercentile\":0,\"upperRankPercentile\":100");

        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilot.read(write(broadButUnsubstantiated), BASELINE_SHA, ARTIFACT_SHA,
                        "redis-sql-open-loop", PROFILE_SHA, 100_000));
    }

    @Test
    void rejectsPilotMeasuredOnDifferentRuntimeJdk() throws Exception {
        Path wrongRuntime = write(validManifest().replace("\"runtimeJavaVersion\": \""
                        + BenchmarkEnvironment.capture().javaVersion() + "\"",
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
        return manifest(300_000, 0, 0);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private String manifest(int accepted, int rejected, int authorityRejected) {
        BenchmarkEnvironment.Snapshot environment = BenchmarkEnvironment.capture();
        String responseHistogram = histogram(300_000, true);
        String pollingHistogram = histogram(accepted, false);
        return """
                {
                  "schemaVersion": 2,
                  "measurementKind": "legacy132-redis-sql-macro",
                  "sourceSHA": "%s",
                  "baselineSHA": "%s",
                  "baselineArtifactSHA256": "%s",
                  "harnessSourceSHA": "%s",
                  "harnessSourceTreeSHA256": "%s",
                  "loadTestRuntimeClasspathSHA256": "%s",
                  "baselineBuildJavaVersion": "21.0.8",
                  "runtimeJavaVersion": "%s",
                  "environment": %s,
                  "environmentFingerprintSHA256": "%s",
                  "profileId": "redis-sql-open-loop",
                  "profileSHA256": "%s",
                  "durationSeconds": 300,
                  "measuredDurationMillis": 330000,
                  "measuredDurationNanos": 330000000000,
                  "sampleCount": {"response": 300000, "receipt": %d, "durable": %d},
                  "latencySemantics": {
                    "response": "synchronous-deposit-call-response",
                    "receipt": "accepted-to-redis-version-poll-observation-upper-bound",
                    "receiptMayCoalesce": true,
                    "durable": "accepted-to-sql-written-count-poll-observation-upper-bound",
                    "durableMayCoalesce": true,
                    "matchedLatencyComparisonEligible": false
                  },
                  "responseHistogram": %s,
                  "receiptHistogram": %s,
                  "durableHistogram": %s,
                  "offered": 300000,
                  "accepted": %d,
                  "rejected": %d,
                  "persisted": %d,
                  "moneyInvariantFailures": 0,
                  "versionConflicts": 0,
                  "authorityRejected": %d,
                  "legacyFailureTaxonomy": "UNAVAILABLE_IN_1_3_2",
                  "legacyFailedEvents": 0,
                  "ambiguous": null,
                  "quarantine": null,
                  "droppedEvents": 0,
                  "overflowRecords": 0,
                  "economyQueueDrained": true,
                  "databaseQueueDrained": true,
                  "exactRedisAndSqlRowsVerified": true,
                  "expectedSqlRows": 10000,
                  "observedSqlRows": 10000,
                  "cleanupComplete": true,
                  "valid": true,
                  "dependencies": {
                    "redisVersion": "8.0.0",
                    "sqlDialect": "SQLITE",
                    "sqlProduct": "SQLite",
                    "sqlVersion": "3.50.0"
                  }
                }
                """.formatted(BASELINE_SHA, BASELINE_SHA, ARTIFACT_SHA,
                HARNESS_SOURCE_SHA, HARNESS_SOURCE_TREE_SHA, RUNTIME_CLASSPATH_SHA, environment.javaVersion(),
                GSON.toJson(environment.fields()), environment.fingerprint(), PROFILE_SHA,
                accepted, accepted, responseHistogram, pollingHistogram, pollingHistogram,
                accepted, rejected, accepted, authorityRejected);
    }

    private String histogram(long count, boolean perOperationTiming) {
        String raw = "{\"unit\":\"microseconds\",\"rawCount\":" + count
                + ",\"raw\":{\"p50\":10,\"p95\":20,\"p99\":30,\"p99.9\":40,\"max\":100}";
        if (!perOperationTiming) {
            return raw + ",\"latencySemantics\":\"polling-observation-upper-bound-may-coalesce\"}";
        }
        HistogramQuantileConfidence.RankBounds ranks = HistogramQuantileConfidence.p999Ranks(count);
        return raw
                + ",\"p99.9Confidence95\":{\"method\":\"approximate-binomial-order-statistic\",\"confidenceLevelPercent\":95,\"targetPercentile\":99.9,\"lowerRankPercentile\":"
                + ranks.lowerPercentile() + ",\"upperRankPercentile\":" + ranks.upperPercentile()
                + ",\"lowerLatencyMicros\":30,\"upperLatencyMicros\":60}"
                + ",\"correctedCount\":" + (count + 100)
                + ",\"expectedIntervalCorrected\":{\"p99.9\":38}}";
    }
}
