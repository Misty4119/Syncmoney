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

/** Validates an observed, artifact-bound 1.3.2 Redis/SQL pilot before calibrating open-loop rates. */
record BaselineMacroPilot(double acceptedTransactionsPerSecond, String manifestSha256) {
    private static final String REQUIRED_KIND = "legacy132-redis-sql-macro";

    static BaselineMacroPilot read(Path path, String expectedBaselineSha, String expectedBaselineJarSha256,
            String expectedProfileId, String expectedProfileSha256, int minimumSamples) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException invalidJson) {
            throw new IllegalArgumentException("Baseline macro pilot is not valid JSON", invalidJson);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Baseline macro pilot must be a JSON object");
        JsonObject root = parsed.getAsJsonObject();

        if (integer(root, "schemaVersion") != 1 || !string(root, "measurementKind").equals(REQUIRED_KIND)) {
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
        if (!string(root, "profileId").equals(expectedProfileId)
                || !string(root, "profileSHA256").equals(expectedProfileSha256)) {
            throw new IllegalArgumentException("Baseline macro pilot profile does not match the requested open-loop profile");
        }
        if (integer(root, "durationSeconds") < 300) {
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
        JsonObject sampleCount = object(root, "sampleCount");
        long responseSamples = integer(sampleCount, "response");
        long receiptSamples = integer(sampleCount, "receipt");
        long durableSamples = integer(sampleCount, "durable");
        if (responseSamples != offered || receiptSamples != accepted || durableSamples != persisted) {
            throw new IllegalArgumentException("Baseline macro pilot histogram samples do not match offered, accepted, and persisted counts");
        }
        validateHistogram(object(root, "responseHistogram"), "response", responseSamples);
        validateHistogram(object(root, "receiptHistogram"), "receipt", receiptSamples);
        validateHistogram(object(root, "durableHistogram"), "durable", durableSamples);
        if (responseSamples < minimumSamples || receiptSamples < minimumSamples || durableSamples < minimumSamples) {
            throw new IllegalArgumentException("Baseline macro pilot does not meet the required response, receipt, and durable sample minima");
        }
        for (String name : new String[] {"moneyInvariantFailures", "versionConflicts", "authorityRejected", "ambiguous", "quarantine"}) {
            if (integer(root, name) != 0L) {
                throw new IllegalArgumentException("Baseline macro pilot contains correctness failures");
            }
        }
        if (!bool(root, "valid")) throw new IllegalArgumentException("Baseline macro pilot did not pass its correctness checks");

        JsonObject dependencies = object(root, "dependencies");
        for (String name : new String[] {"redisVersion", "sqlDialect", "sqlProduct", "sqlVersion"}) {
            String value = string(dependencies, name);
            if (value.isBlank() || value.equalsIgnoreCase("UNKNOWN")) {
                throw new IllegalArgumentException("Baseline macro pilot is missing an observed " + name);
            }
        }

        double acceptedRate = (double) accepted / integer(root, "durationSeconds");
        if (!Double.isFinite(acceptedRate) || acceptedRate <= 0d) {
            throw new IllegalArgumentException("Baseline macro pilot accepted rate must be positive and finite");
        }
        return new BaselineMacroPilot(acceptedRate, sha256(bytes));
    }

    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Missing baseline pilot object " + name);
        return value.getAsJsonObject();
    }

    private static void validateHistogram(JsonObject histogram, String name, long expectedRawCount) {
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

        if (integer(histogram, "correctedCount") < expectedRawCount) {
            throw new IllegalArgumentException("Baseline macro pilot " + name + " corrected histogram omits raw samples");
        }
        JsonObject corrected = object(histogram, "expectedIntervalCorrected");
        long correctedP999 = integer(corrected, "p99.9");
        if (correctedP999 < 0 || correctedP999 > max) {
            throw new IllegalArgumentException("Baseline macro pilot " + name + " expected-interval-corrected p99.9 is invalid");
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
