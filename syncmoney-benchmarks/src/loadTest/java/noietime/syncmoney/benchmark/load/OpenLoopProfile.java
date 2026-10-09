package noietime.syncmoney.benchmark.load;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import noietime.syncmoney.storage.persistence.SqlDialect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated, secret-free workload settings loaded from a checked-in profile. */
public final class OpenLoopProfile {
    final String profileId;
    final String sha256;
    final List<Double> arrivalRateFractions;
    final int warmupSecondsPerRate;
    final int measurementSecondsPerRate;
    final int minimumMeasurementSamples;
    final int workerThreads;
    final int maxInFlight;
    final List<Integer> batchSizes;
    final List<Integer> maxBatchWaitMillis;
    final String outputDirectory;
    final EnvironmentNames redis;
    final EnvironmentNames sql;

    private OpenLoopProfile(JsonObject root, String sha256) {
        if (integer(root, "schemaVersion") != 1 || !string(root, "mode").equals("open-loop")) {
            throw new IllegalArgumentException("Unsupported benchmark profile schema or mode");
        }
        profileId = string(root, "profileId");
        this.sha256 = sha256;
        if (!profileId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("profileId must be a short portable identifier");
        }
        arrivalRateFractions = doubles(root, "arrivalRateFractions");
        warmupSecondsPerRate = integer(root, "warmupSecondsPerRate");
        measurementSecondsPerRate = integer(root, "measurementSecondsPerRate");
        minimumMeasurementSamples = integer(root, "minimumMeasurementSamples");
        workerThreads = integer(root, "workerThreads");
        maxInFlight = integer(root, "maxInFlight");
        batchSizes = integers(root, "batchSizes");
        maxBatchWaitMillis = integers(root, "maxBatchWaitMillis");
        outputDirectory = string(root, "outputDirectory");
        redis = EnvironmentNames.read(object(root, "redis"), true);
        sql = EnvironmentNames.read(object(root, "sql"), false);

        if (arrivalRateFractions.isEmpty() || arrivalRateFractions.stream().anyMatch(rate -> !Double.isFinite(rate) || rate <= 0d)) {
            throw new IllegalArgumentException("arrivalRateFractions must contain positive finite values");
        }
        if (warmupSecondsPerRate < 0 || measurementSecondsPerRate < 1 || minimumMeasurementSamples < 1
                || workerThreads < 1 || maxInFlight < workerThreads) {
            throw new IllegalArgumentException("Benchmark durations, sample count, or bounded worker settings are invalid");
        }
        if (batchSizes.isEmpty() || batchSizes.stream().anyMatch(size -> size < 1 || size > maxInFlight)
                || maxBatchWaitMillis.isEmpty() || maxBatchWaitMillis.stream().anyMatch(wait -> wait < 0 || wait > 60_000)) {
            throw new IllegalArgumentException("Batch sizes and waits must fit the bounded benchmark profile");
        }
        Path output = Path.of(outputDirectory).normalize();
        if (output.isAbsolute() || output.startsWith("..")) {
            throw new IllegalArgumentException("outputDirectory must stay below the project working directory");
        }
    }

    public static OpenLoopProfile read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        byte[] bytes = Files.readAllBytes(path);
        String json = new String(bytes, StandardCharsets.UTF_8);
        return readJson(json, sha256(bytes));
    }

    static OpenLoopProfile readJson(String json) {
        return readJson(json, sha256(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static OpenLoopProfile readJson(String json, String sha256) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("Benchmark profile must be a JSON object");
        return new OpenLoopProfile(root.getAsJsonObject(), sha256);
    }

    public BackendSettings resolveBackendSettings(Map<String, String> environment) {
        String host = requiredEnvironment(environment, redis.host);
        int port = optionalIntegerEnvironment(environment, redis.port, 6379, 1, 65_535);
        String redisPassword = optionalEnvironment(environment, redis.password);
        int database = optionalIntegerEnvironment(environment, redis.database, 0, 0, 15);
        String sqlUrl = requiredEnvironment(environment, sql.url);
        String sqlUser = optionalEnvironment(environment, sql.user);
        String sqlPassword = optionalEnvironment(environment, sql.password);
        String dialectName = requiredEnvironment(environment, sql.dialect);
        SqlDialect dialect = SqlDialect.fromDatabaseType(dialectName);
        return new BackendSettings(host, port, redisPassword, database,
                sqlUrl, sqlUser, sqlPassword, dialect);
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required environment variable: " + name);
        return value;
    }

    private static String optionalEnvironment(Map<String, String> environment, String name) {
        return name == null ? null : environment.get(name);
    }

    private static int optionalIntegerEnvironment(Map<String, String> environment, String name,
            int fallback, int minimum, int maximum) {
        if (name == null || environment.get(name) == null || environment.get(name).isBlank()) return fallback;
        try {
            int value = Integer.parseInt(environment.get(name));
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid value in environment variable " + name);
        }
    }

    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Missing profile object " + name);
        return value.getAsJsonObject();
    }

    private static String string(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Profile field " + name + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Profile field " + name + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Profile field " + name + " must be an integer");
        }
    }

    private static List<Integer> integers(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Profile field " + name + " must be an array");
        List<Integer> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            try {
                result.add(element.getAsBigDecimal().intValueExact());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Profile field " + name + " must contain integers");
            }
        }
        return List.copyOf(result);
    }

    private static List<Double> doubles(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Profile field " + name + " must be an array");
        List<Double> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            try {
                result.add(element.getAsDouble());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Profile field " + name + " must contain numbers");
            }
        }
        return List.copyOf(result);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    record BackendSettings(String redisHost, int redisPort, String redisPassword, int redisDatabase,
            String sqlUrl, String sqlUser, String sqlPassword, SqlDialect sqlDialect) { }

    private record EnvironmentNames(String host, String port, String password, String database,
            String url, String user, String dialect) {
        static EnvironmentNames read(JsonObject object, boolean redis) {
            if (redis) {
                return new EnvironmentNames(string(object, "hostEnvironmentVariable"),
                        string(object, "portEnvironmentVariable"), string(object, "passwordEnvironmentVariable"),
                        string(object, "databaseEnvironmentVariable"), null, null, null);
            }
            return new EnvironmentNames(null, null, string(object, "passwordEnvironmentVariable"), null,
                    string(object, "urlEnvironmentVariable"), string(object, "userEnvironmentVariable"),
                    string(object, "dialectEnvironmentVariable"));
        }
    }
}
