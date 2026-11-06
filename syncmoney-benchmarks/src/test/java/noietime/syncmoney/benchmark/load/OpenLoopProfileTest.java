package noietime.syncmoney.benchmark.load;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLoopProfileTest {
    private static final String VALID_PROFILE = """
            {
              "schemaVersion": 1,
              "profileId": "test-profile",
              "mode": "open-loop",
              "arrivalRateFractions": [0.25, 1.0],
              "warmupSecondsPerRate": 0,
              "measurementSecondsPerRate": 1,
              "minimumMeasurementSamples": 100,
              "workerThreads": 2,
              "maxInFlight": 8,
              "batchSizes": [1, 4],
              "maxBatchWaitMillis": [0, 5],
              "redis": {
                "hostEnvironmentVariable": "REDIS_HOST",
                "portEnvironmentVariable": "REDIS_PORT",
                "passwordEnvironmentVariable": "REDIS_PASSWORD",
                "databaseEnvironmentVariable": "REDIS_DATABASE"
              },
              "sql": {
                "urlEnvironmentVariable": "SQL_URL",
                "userEnvironmentVariable": "SQL_USER",
                "passwordEnvironmentVariable": "SQL_PASSWORD",
                "dialectEnvironmentVariable": "SQL_DIALECT"
              },
              "outputDirectory": "build/reports/benchmarks"
            }
            """;

    @Test
    void parsesAndResolvesOnlyEnvironmentVariableReferences() {
        OpenLoopProfile profile = OpenLoopProfile.readJson(VALID_PROFILE);
        OpenLoopProfile.BackendSettings backend = profile.resolveBackendSettings(Map.of(
                "REDIS_HOST", "127.0.0.1",
                "SQL_URL", "jdbc:sqlite:file:benchmark.db",
                "SQL_DIALECT", "sqlite"));

        assertEquals("test-profile", profile.profileId);
        assertEquals(2, profile.arrivalRateFractions.size());
        assertEquals(2, profile.batchSizes.size());
        assertEquals(6379, backend.redisPort());
        assertEquals(0, backend.redisDatabase());
        assertEquals("sqlite", backend.sqlDialect().name().toLowerCase());
    }

    @Test
    void checkedInProfilesStayParseableAndBounded() throws Exception {
        for (String profileName : new String[] {"local-smoke.json", "redis-sql-open-loop.json"}) {
            try (var stream = getClass().getResourceAsStream("/" + profileName)) {
                if (stream == null) throw new AssertionError("Missing checked-in benchmark profile " + profileName);
                OpenLoopProfile profile = OpenLoopProfile.readJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                assertTrue(profile.maxInFlight >= profile.workerThreads);
                assertTrue(profile.arrivalRateFractions.stream().allMatch(rate -> rate > 0d && Double.isFinite(rate)));
            }
        }
    }

    @Test
    void rejectsInvalidBoundsAndAbsoluteOutputPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenLoopProfile.readJson(VALID_PROFILE.replace("\"maxInFlight\": 8", "\"maxInFlight\": 1")));
        String absoluteOutputDirectory = Path.of(System.getProperty("java.io.tmpdir"), "syncmoney-outside")
                .toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        assertThrows(IllegalArgumentException.class,
                () -> OpenLoopProfile.readJson(VALID_PROFILE.replace(
                        "\"outputDirectory\": \"build/reports/benchmarks\"",
                        "\"outputDirectory\": \"" + absoluteOutputDirectory + "\"")));
    }

    @Test
    void missingBackendSettingsNameOnlyTheMissingVariable() {
        OpenLoopProfile profile = OpenLoopProfile.readJson(VALID_PROFILE);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> profile.resolveBackendSettings(Map.of("REDIS_HOST", "private-host")));
        assertTrue(missing.getMessage().contains("SQL_URL"));
        assertTrue(!missing.getMessage().contains("private-host"));
    }
}
