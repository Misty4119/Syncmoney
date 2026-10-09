package noietime.syncmoney.benchmark.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Validates an observed, artifact-bound 1.3.2 Redis/SQL pilot before calibrating open-loop rates. */
record BaselineMacroPilot(double acceptedTransactionsPerSecond, String manifestSha256,
        String environmentFingerprintSha256, String redisVersion, String sqlDialect, String sqlProduct, String sqlVersion) {
    private static final String REQUIRED_KIND = "legacy132-redis-sql-macro";
    private static final String CONFIDENCE_METHOD = "approximate-binomial-order-statistic";
    private static final int P999_MINIMUM_SAMPLES = 100_000;

    static BaselineMacroPilot read(Path path, String expectedBaselineSha, String expectedBaselineJarSha256,
            String expectedProfileId, String expectedProfileSha256, int minimumSamples) throws IOException {
        return read(path, expectedBaselineSha, expectedBaselineJarSha256, expectedProfileId,
                expectedProfileSha256, minimumSamples, null);
    }

    static BaselineMacroPilot read(Path path, String expectedBaselineSha, String expectedBaselineJarSha256,
            String expectedProfileId, String expectedProfileSha256, int minimumSamples,
            String expectedManifestSha256) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String actualManifestSha256 = sha256(bytes);
        if (expectedManifestSha256 != null && (!expectedManifestSha256.matches("[0-9a-f]{64}")
                || !actualManifestSha256.equals(expectedManifestSha256))) {
            throw new IllegalArgumentException("Baseline macro pilot manifest hash does not match the verified file");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException invalidJson) {
            throw new IllegalArgumentException("Baseline macro pilot is not valid JSON", invalidJson);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Baseline macro pilot must be a JSON object");
        JsonObject root = parsed.getAsJsonObject();

        if (integer(root, "schemaVersion") != 2 || !string(root, "measurementKind").equals(REQUIRED_KIND)) {
            throw new IllegalArgumentException("A real 1.3.2 Redis/SQL macro pilot is required; facade/JMH pilots are not accepted");
        }
        if (!string(root, "sourceSHA").equals(expectedBaselineSha)
                || !string(root, "baselineSHA").equals(expectedBaselineSha)
                || !string(root, "baselineArtifactSHA256").equals(expectedBaselineJarSha256)) {
            throw new IllegalArgumentException("Baseline pilot source or artifact does not match the verified 1.3.2 checkout and JAR");
        }
        if (!string(root, "baselineBuildJavaVersion").matches("21(?:\\..*|-.*)?")) {
            throw new IllegalArgumentException("Baseline macro pilot must use the verified Java 21 build");
        }
        if (!string(root, "runtimeJavaVersion").matches("25(?:\\..*|-.*)?")) {
            throw new IllegalArgumentException("Baseline macro pilot must be measured on the planned Java 25 runtime");
        }
        BenchmarkEnvironment.Snapshot environment = BenchmarkEnvironment.parse(object(root, "environment"));
        if (!environment.fingerprint().equals(string(root, "environmentFingerprintSHA256"))) {
            throw new IllegalArgumentException("Baseline macro pilot environment fingerprint does not match its metadata");
        }
        if (!environment.javaVersion().equals(string(root, "runtimeJavaVersion"))) {
            throw new IllegalArgumentException("Baseline macro pilot Java version does not match its environment metadata");
        }
        if (!environment.fingerprint().equals(BenchmarkEnvironment.capture().fingerprint())) {
            throw new IllegalArgumentException("Baseline macro pilot environment does not match the current open-loop measurement environment");
        }
        JsonObject latencySemantics = object(root, "latencySemantics");
        if (!string(latencySemantics, "response").equals("synchronous-deposit-call-response")
                || !string(latencySemantics, "receipt").equals("accepted-to-redis-version-poll-observation-upper-bound")
                || !bool(latencySemantics, "receiptMayCoalesce")
                || !string(latencySemantics, "durable").equals("accepted-to-sql-written-count-poll-observation-upper-bound")
                || !bool(latencySemantics, "durableMayCoalesce")
                || bool(latencySemantics, "matchedLatencyComparisonEligible")) {
            throw new IllegalArgumentException("Baseline macro pilot latency semantics must exclude polling observations from matched comparison");
        }
        if (!string(root, "profileId").equals(expectedProfileId)
                || !string(root, "profileSHA256").equals(expectedProfileSha256)) {
            throw new IllegalArgumentException("Baseline macro pilot profile does not match the requested open-loop profile");
        }
        long durationSeconds = integer(root, "durationSeconds");
        long measuredDurationMillis = integer(root, "measuredDurationMillis");
        long measuredDurationNanos = integer(root, "measuredDurationNanos");
        if (durationSeconds < 300 || measuredDurationNanos < TimeUnit.SECONDS.toNanos(300L)
                || measuredDurationMillis != TimeUnit.NANOSECONDS.toMillis(measuredDurationNanos)) {
            throw new IllegalArgumentException("Baseline macro pilot must include at least 300 measured seconds");
        }

        long offered = integer(root, "offered");
        long accepted = integer(root, "accepted");
        long rejected = integer(root, "rejected");
        long persisted = integer(root, "persisted");
        if (offered <= 0 || accepted <= 0 || rejected < 0
                || offered != Math.addExact(accepted, rejected) || persisted != accepted) {
            throw new IllegalArgumentException("Baseline macro pilot outcome counts are incomplete or do not conserve writes");
        }
        long authorityRejected = integer(root, "authorityRejected");
        if (rejected != authorityRejected) {
            throw new IllegalArgumentException("Baseline macro pilot rejection counts do not match authority rejections");
        }
        JsonObject sampleCount = object(root, "sampleCount");
        long responseSamples = integer(sampleCount, "response");
        long receiptSamples = integer(sampleCount, "receipt");
        long durableSamples = integer(sampleCount, "durable");
        if (responseSamples != offered || receiptSamples != accepted || durableSamples != persisted) {
            throw new IllegalArgumentException("Baseline macro pilot histogram samples do not match offered, accepted, and persisted counts");
        }
        validateHistogram(object(root, "responseHistogram"), "response", responseSamples, true);
        validateHistogram(object(root, "receiptHistogram"), "receipt", receiptSamples, false);
        validateHistogram(object(root, "durableHistogram"), "durable", durableSamples, false);
        int requiredSamples = Math.max(P999_MINIMUM_SAMPLES, minimumSamples);
        if (responseSamples < requiredSamples || receiptSamples < requiredSamples || durableSamples < requiredSamples) {
            throw new IllegalArgumentException("Baseline macro pilot does not meet the required response, receipt, and durable sample minima");
        }
        for (String name : new String[] {"moneyInvariantFailures", "versionConflicts", "authorityRejected"}) {
            if (integer(root, name) != 0L) {
                throw new IllegalArgumentException("Baseline macro pilot contains correctness failures");
            }
        }
        if (integer(root, "legacyFailedEvents") != 0L) {
            throw new IllegalArgumentException("Baseline macro pilot contains failed legacy events");
        }
        for (String name : new String[] {"droppedEvents", "overflowRecords"}) {
            if (integer(root, name) != 0L) {
                throw new IllegalArgumentException("Baseline macro pilot did not prove a clean producer state: " + name);
            }
        }
        for (String name : new String[] {"economyQueueDrained", "databaseQueueDrained",
                "exactRedisAndSqlRowsVerified", "cleanupComplete"}) {
            if (!bool(root, name)) {
                throw new IllegalArgumentException("Baseline macro pilot did not prove producer gate " + name);
            }
        }
        long expectedSqlRows = integer(root, "expectedSqlRows");
        long observedSqlRows = integer(root, "observedSqlRows");
        if (expectedSqlRows < 0L || expectedSqlRows != observedSqlRows) {
            throw new IllegalArgumentException("Baseline macro pilot SQL row verification counts do not match");
        }

        if (!string(root, "legacyFailureTaxonomy").equals("UNAVAILABLE_IN_1_3_2")) {
            throw new IllegalArgumentException("Baseline macro pilot must identify the unavailable legacy failure taxonomy");
        }
        requireJsonNull(root, "ambiguous");
        requireJsonNull(root, "quarantine");
        if (!bool(root, "valid")) throw new IllegalArgumentException("Baseline macro pilot did not pass its correctness checks");

        JsonObject dependencies = object(root, "dependencies");
        String redisVersion = string(dependencies, "redisVersion");
        String sqlDialect = string(dependencies, "sqlDialect");
        String sqlProduct = string(dependencies, "sqlProduct");
        String sqlVersion = string(dependencies, "sqlVersion");
        for (String value : new String[] {redisVersion, sqlDialect, sqlProduct, sqlVersion}) {
            if (value.isBlank() || value.equalsIgnoreCase("UNKNOWN")) {
                throw new IllegalArgumentException("Baseline macro pilot is missing observed backend metadata");
            }
        }

        double acceptedRate = (double) accepted * 1_000_000_000d / measuredDurationNanos;
        if (!Double.isFinite(acceptedRate) || acceptedRate <= 0d) {
            throw new IllegalArgumentException("Baseline macro pilot accepted rate must be positive and finite");
        }
        return new BaselineMacroPilot(acceptedRate, actualManifestSha256, environment.fingerprint(),
                redisVersion, sqlDialect, sqlProduct, sqlVersion);
    }

    void requireBackendMatch(String actualRedisVersion, String actualSqlDialect,
            String actualSqlProduct, String actualSqlVersion) {
        if (!redisVersion.equals(actualRedisVersion)
                || !sqlDialect.equalsIgnoreCase(actualSqlDialect)
                || !sqlProduct.equalsIgnoreCase(actualSqlProduct)
                || !sqlVersion.equals(actualSqlVersion)) {
            throw new IllegalArgumentException("Baseline pilot Redis/SQL versions and dialect must match the measured backend");
        }
    }

    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Missing baseline pilot object " + name);
        return value.getAsJsonObject();
    }

    private static void requireJsonNull(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonNull()) {
            throw new IllegalArgumentException("Baseline macro pilot legacy taxonomy is unavailable; " + name + " must be null");
        }
    }

    private static void validateHistogram(JsonObject histogram, String name, long expectedRawCount,
            boolean perOperationTiming) {
        if (!string(histogram, "unit").equals("microseconds")) {
            throw new IllegalArgumentException("Baseline macro pilot " + name + " histogram must use microseconds");
        }
        if (integer(histogram, "rawCount") != expectedRawCount) {
            throw new IllegalArgumentException("Baseline macro pilot " + name + " sample count does not match histogram raw count");
        }

        JsonObject raw = object(histogram, "raw");
        long p50 = integer(raw, "p50");
        long p95 = integer(raw, "p95");
        long p99 = integer(raw, "p99");
        long p999 = integer(raw, "p99.9");
        long max = integer(raw, "max");
        if (p50 < 0 || p50 > p95 || p95 > p99 || p99 > p999 || p999 > max) {
            throw new IllegalArgumentException("Baseline macro pilot " + name + " raw latency percentiles are invalid");
        }

        if (perOperationTiming) {
            JsonObject confidence = object(histogram, "p99.9Confidence95");
            double targetPercentile = decimal(confidence, "targetPercentile");
            double lowerRankPercentile = decimal(confidence, "lowerRankPercentile");
            double upperRankPercentile = decimal(confidence, "upperRankPercentile");
            long lowerLatencyMicros = integer(confidence, "lowerLatencyMicros");
            long upperLatencyMicros = integer(confidence, "upperLatencyMicros");
            if (!string(confidence, "method").equals(CONFIDENCE_METHOD)
                    || integer(confidence, "confidenceLevelPercent") != 95L
                    || targetPercentile != 99.9d
                    || lowerRankPercentile < 0d || lowerRankPercentile > targetPercentile
                    || upperRankPercentile < targetPercentile || upperRankPercentile > 100d
                    || lowerLatencyMicros < 0 || lowerLatencyMicros > p999
                    || upperLatencyMicros < p999 || upperLatencyMicros > max) {
                throw new IllegalArgumentException("Baseline macro pilot " + name + " p99.9 confidence interval is invalid");
            }

            HistogramQuantileConfidence.RankBounds expectedRanks = HistogramQuantileConfidence.p999Ranks(expectedRawCount);
            if (Double.compare(lowerRankPercentile, expectedRanks.lowerPercentile()) != 0
                    || Double.compare(upperRankPercentile, expectedRanks.upperPercentile()) != 0) {
                throw new IllegalArgumentException("Baseline macro pilot " + name
                        + " p99.9 confidence ranks do not match the observed sample count and declared method");
            }

            if (integer(histogram, "correctedCount") < expectedRawCount) {
                throw new IllegalArgumentException("Baseline macro pilot " + name + " corrected histogram omits raw samples");
            }
            JsonObject corrected = object(histogram, "expectedIntervalCorrected");
            long correctedP999 = integer(corrected, "p99.9");
            if (correctedP999 < 0 || correctedP999 > max) {
                throw new IllegalArgumentException("Baseline macro pilot " + name + " expected-interval-corrected p99.9 is invalid");
            }
        } else {
            if (!string(histogram, "latencySemantics").equals("polling-observation-upper-bound-may-coalesce")
                    || histogram.has("p99.9Confidence95") || histogram.has("correctedCount")
                    || histogram.has("expectedIntervalCorrected") || histogram.has("expectedIntervalMicros")) {
                throw new IllegalArgumentException("Baseline macro pilot " + name
                        + " polling observations must not claim independent-sample confidence or interval correction");
            }
        }
    }

    private static String string(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing baseline pilot string " + name);
        }
        return value.getAsString();
    }

    private static long integer(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing baseline pilot integer " + name);
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid baseline pilot integer " + name, invalid);
        }
    }

    private static double decimal(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing baseline pilot decimal " + name);
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Invalid baseline pilot decimal " + name);
        return number;
    }

    private static boolean bool(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Missing baseline pilot boolean " + name);
        }
        return value.getAsBoolean();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }
}
