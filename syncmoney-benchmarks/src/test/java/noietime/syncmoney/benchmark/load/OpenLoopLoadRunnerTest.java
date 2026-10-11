package noietime.syncmoney.benchmark.load;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import noietime.syncmoney.storage.persistence.SqlDialect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLoopLoadRunnerTest {
    @Test
    void failedAsyncWorkerStillRunsPostDrainValidationAndCleanup() {
        AtomicReference<Throwable> failure = new AtomicReference<>(new IllegalStateException("worker failed"));
        AtomicBoolean postDrainValidationCalled = new AtomicBoolean();

        boolean valid = OpenLoopLoadRunner.validateAfterDrain(failure, () -> {
            postDrainValidationCalled.set(true);
            return false;
        });

        assertTrue(postDrainValidationCalled.get());
        assertFalse(valid);
    }

    @Test
    void openLoopAcceptsOnlyAcknowledgedDisposableScratchBackend() {
        OpenLoopProfile.BackendSettings accepted = backend("127.0.0.1", 16_379,
                "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19", 0);

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OpenLoopLoadRunner.validateDisposableTarget(accepted, null))
                .getMessage().contains("SYNCMONEY_BENCHMARK_DISPOSABLE_ACK"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OpenLoopLoadRunner.validateDisposableTarget(
                        backend("redis.example.net", 6_379,
                                "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19", 0),
                        "syncmoney-t19-disposable"))
                .getMessage().contains("isolated T19 loopback"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OpenLoopLoadRunner.validateDisposableTarget(
                        backend("127.0.0.1", 16_379,
                                "jdbc:mariadb://db.example.net:3306/syncmoney_t19", 0),
                        "syncmoney-t19-disposable"))
                .getMessage().contains("isolated T19 MariaDB"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OpenLoopLoadRunner.validateDisposableTarget(
                        backend("127.0.0.1", 16_379,
                                "jdbc:mariadb://127.0.0.1:13306/production", 0),
                        "syncmoney-t19-disposable"))
                .getMessage().contains("isolated T19 MariaDB"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OpenLoopLoadRunner.validateDisposableTarget(
                        backend("127.0.0.1", 16_379,
                                "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19", 0, null),
                        "syncmoney-t19-disposable"))
                .getMessage().contains("separate SQL user"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                OpenLoopLoadRunner.validateDisposableTarget(accepted, "syncmoney-t19-disposable"));
    }

    @Test
    void duplicateStaleReceiptsCannotDefineTheExpectedFinalBalance() throws Exception {
        Method finalStateOracle = Arrays.stream(OpenLoopLoadRunner.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("matchesExpectedMutationCount"))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(finalStateOracle,
                "final verification needs an oracle independent from the Redis receipts");
        finalStateOracle.setAccessible(true);

        assertFalse((boolean) finalStateOracle.invoke(null, 3L, true,
                BigDecimal.ONE, 1L, true, BigDecimal.ONE, 1L),
                "three admitted deposits cannot be verified by a stale version-1 receipt and persistence row");
        assertTrue((boolean) finalStateOracle.invoke(null, 3L, true,
                BigDecimal.valueOf(3), 3L, true, BigDecimal.valueOf(3), 3L));
    }

    @Test
    void smokeProfileCannotMarkP999ReliableBelowTheB00SampleFloor() {
        assertFalse(OpenLoopLoadRunner.isP999Reliable(1_000, 1_000, 1_000, 1_000));
        assertFalse(OpenLoopLoadRunner.isP999Reliable(99_999, 100_000, 100_000, 1_000));
    }

    @Test
    void p999RequiresTheB00SampleFloorForEveryMeasuredStage() {
        assertTrue(OpenLoopLoadRunner.isP999Reliable(100_000, 100_000, 100_000, 1_000));
        assertFalse(OpenLoopLoadRunner.isP999Reliable(100_000, 99_999, 100_000, 1_000));
    }

    @Test
    void measurementValidityRequiresAllHistogramsToMeetTheSampleFloor() {
        assertFalse(OpenLoopLoadRunner.validMeasurementSamples(true, 99_999, 100_000, 100_000, 1_000));
        assertFalse(OpenLoopLoadRunner.validMeasurementSamples(true, 100_000, 100_000, 99_999, 1_000));
        assertFalse(OpenLoopLoadRunner.validMeasurementSamples(false, 100_000, 100_000, 100_000, 1_000));
        assertTrue(OpenLoopLoadRunner.validMeasurementSamples(true, 100_000, 100_000, 100_000, 1_000));
    }

    @Test
    void histogramOmitsP999ConfidenceIntervalBelowTheB00SampleFloor() {
        Histogram raw = new Histogram(3_600_000_000L, 3);
        Histogram corrected = new Histogram(3_600_000_000L, 3);
        for (int index = 1; index < 100_000; index++) {
            raw.recordValue(index);
            corrected.recordValue(index);
        }

        Map<String, Object> summary = OpenLoopLoadRunner.histogramSummary(raw, corrected, 100_000);

        assertFalse(summary.containsKey("p99.9Confidence95"));
    }

    @Test
    void histogramIncludesP999ConfidenceIntervalAtTheB00SampleFloor() {
        Histogram raw = new Histogram(3_600_000_000L, 3);
        Histogram corrected = new Histogram(3_600_000_000L, 3);
        for (int index = 1; index <= 100_000; index++) {
            raw.recordValue(index);
            corrected.recordValue(index);
        }

        Map<String, Object> summary = OpenLoopLoadRunner.histogramSummary(raw, corrected, 100_000);

        assertNotNull(summary.get("p99.9Confidence95"));
    }

    private static OpenLoopProfile.BackendSettings backend(String redisHost, int redisPort,
            String sqlUrl, int redisDatabase) {
        return backend(redisHost, redisPort, sqlUrl, redisDatabase, "benchmark");
    }

    private static OpenLoopProfile.BackendSettings backend(String redisHost, int redisPort,
            String sqlUrl, int redisDatabase, String sqlUser) {
        return new OpenLoopProfile.BackendSettings(redisHost, redisPort, null, redisDatabase,
                sqlUrl, sqlUser, null, SqlDialect.MYSQL);
    }
}
