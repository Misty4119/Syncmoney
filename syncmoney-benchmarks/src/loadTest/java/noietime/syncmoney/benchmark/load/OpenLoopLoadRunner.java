package noietime.syncmoney.benchmark.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.storage.persistence.PlayerSnapshot;
import noietime.syncmoney.storage.persistence.SqlDialect;
import noietime.syncmoney.storage.persistence.SqlSnapshotWriter;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.lang.management.GarbageCollectorMXBean;

/** Bounded open-loop Redis authority receipt and SQL durability load runner. */
public final class OpenLoopLoadRunner {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");
    private static final long MAX_HISTOGRAM_MICROS = TimeUnit.HOURS.toMicros(1);
    private static final int ACCOUNT_COUNT = 10_000;
    private static final long P999_MINIMUM_SAMPLES = 100_000L;

    private OpenLoopLoadRunner() { }

    public static void main(String[] args) throws Exception {
        String runtimeJavaVersion = System.getProperty("java.version");
        if (runtimeJavaVersion == null || !runtimeJavaVersion.matches("25(?:\\..*|-.*)?")) {
            throw new IllegalStateException("Open-loop macro measurements must run on the planned Java 25 runtime");
        }
        BenchmarkEnvironment.Snapshot launchEnvironment = BenchmarkEnvironment.capture();
        if (args.length < 1) throw new IllegalArgumentException("Usage: loadTest <profile.json> --baseline-pilot <verified-1.3.2-macro-pilot.json>");
        OpenLoopProfile profile = OpenLoopProfile.read(Path.of(args[0]));
        BaselineMacroPilot baselinePilot = readBaselinePilot(args, profile, launchEnvironment);
        double baselineAcceptedRate = baselinePilot.acceptedTransactionsPerSecond();
        OpenLoopProfile.BackendSettings backend;
        try {
            backend = profile.resolveBackendSettings(System.getenv());
            validateDisposableTarget(backend, System.getenv("SYNCMONEY_BENCHMARK_DISPOSABLE_ACK"));
        } catch (IllegalArgumentException missingSetting) {
            throw new IllegalStateException(missingSetting.getMessage()
                    + "; values are intentionally not included in benchmark output", missingSetting);
        }

        Path outputDirectory = Path.of(profile.outputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        String invocationId = UUID.randomUUID().toString();
        int reportNumber = 0;
        for (double fraction : profile.arrivalRateFractions) {
            double targetRate = baselineAcceptedRate * fraction;
            if (!Double.isFinite(targetRate) || targetRate <= 0d || targetRate > 1_000_000_000d) {
                throw new IllegalArgumentException("Derived arrival rate is outside the runner's supported finite range");
            }
            for (int batchSize : profile.batchSizes) {
                for (int batchWait : profile.maxBatchWaitMillis) {
                    if (profile.warmupSecondsPerRate > 0) {
                        Scenario warmup = runScenario(profile, backend, targetRate, batchSize,
                                batchWait, profile.warmupSecondsPerRate, baselinePilot, false);
                        if (!warmup.valid) throw new IllegalStateException("Warmup scenario failed its correctness or drain checks");
                    }
                    Scenario measurement = runScenario(profile, backend, targetRate, batchSize,
                            batchWait, profile.measurementSecondsPerRate, baselinePilot, true);
                    Map<String, Object> report = report(profile, measurement, targetRate, fraction,
                            baselineAcceptedRate, baselinePilot.environmentFingerprintSha256(), batchSize, batchWait,
                            invocationId, launchEnvironment);
                    Path reportFile = outputDirectory.resolve(String.format("%s-%s-%03d.json",
                            profile.profileId, measurement.runId, ++reportNumber));
                    Files.writeString(reportFile, GSON.toJson(report) + System.lineSeparator(), StandardCharsets.UTF_8);
                    System.out.println("Benchmark report: " + reportFile.toAbsolutePath());
                    if (!measurement.valid) {
                        throw new IllegalStateException("Open-loop scenario failed; report records failure and no retry was attempted");
                    }
                }
            }
        }
    }

    private static BaselineMacroPilot readBaselinePilot(String[] args, OpenLoopProfile profile,
            BenchmarkEnvironment.Snapshot measurementEnvironment) throws IOException {
        for (int index = 1; index + 1 < args.length; index++) {
            if (args[index].equals("--baseline-pilot")) {
                String expectedBaselineSha = requiredSystemProperty("syncmoney.benchmarks.baselineSha");
                String expectedBaselineJarSha = requiredSystemProperty("syncmoney.benchmarks.baselineJarSha256");
                String expectedPilotSha = requiredSystemProperty("syncmoney.benchmarks.baselineMacroPilotSha256");
                return BaselineMacroPilot.read(Path.of(args[index + 1]), expectedBaselineSha,
                        expectedBaselineJarSha, profile.profileId, profile.sha256,
                        profile.minimumMeasurementSamples, expectedPilotSha, measurementEnvironment);
            }
        }
        throw new IllegalArgumentException("Supply a hash-bound 1.3.2 Redis/SQL macro pilot with --baseline-pilot");
    }

    private static String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing verified baseline property " + name);
        return value;
    }

    private static Scenario runScenario(OpenLoopProfile profile, OpenLoopProfile.BackendSettings backend,
            double targetRate, int batchSize, int batchWaitMillis, int durationSeconds,
            BaselineMacroPilot baselinePilot, boolean requireMinimumSamples) throws Exception {
        RunSession session = new RunSession(profile, backend, targetRate, batchSize, batchWaitMillis);
        try {
            baselinePilot.requireBackendMatch(session.redisVersion.get(), backend.sqlDialect().name(),
                    session.sqlProduct.get(), session.sqlVersion.get());
            session.allocation.beginMeasurement();
            long issuedStart = System.nanoTime();
            long intervalNanos = Math.max(1L, Math.round(1_000_000_000d / targetRate));
            long end = issuedStart + TimeUnit.SECONDS.toNanos(durationSeconds);
            long scheduled = issuedStart;
            long sequence = 0L;
            while (scheduled < end && session.failure.get() == null) {
                long now = System.nanoTime();
                if (now < scheduled) {
                    LockSupport.parkNanos(scheduled - now);
                    if (Thread.interrupted()) throw new InterruptedException("Interrupted during open-loop scheduling");
                    now = System.nanoTime();
                }
                session.offer(sequence++, scheduled, now);
                scheduled = Math.addExact(scheduled, intervalNanos);
            }
            long issueEnd = System.nanoTime();
            session.finishWorkers();
            session.finishBatchWriter();
            long finish = System.nanoTime();
            boolean valid = validateAfterDrain(session.failure, session::validateAndCleanup);
            return session.snapshot(issuedStart, issueEnd, finish, durationSeconds, valid, requireMinimumSamples);
        } catch (InterruptedException | RuntimeException | Error runFailure) {
            session.abortAndCleanup(runFailure);
            throw runFailure;
        }
    }

    static boolean validateAfterDrain(AtomicReference<Throwable> failure, BooleanSupplier validateAndCleanup) {
        boolean postconditionsValid = validateAndCleanup.getAsBoolean();
        return failure.get() == null && postconditionsValid;
    }

    private static Map<String, Object> report(OpenLoopProfile profile, Scenario scenario, double targetRate,
            double rateFraction, double baselineRate, String baselineEnvironmentFingerprint,
            int batchSize, int batchWaitMillis, String invocationId,
            BenchmarkEnvironment.Snapshot environment) {
        String baselineSha = System.getProperty("syncmoney.benchmarks.baselineSha");
        if (baselineSha != null && baselineSha.equals("UNSPECIFIED")) baselineSha = null;
        return Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("invocationId", invocationId),
                Map.entry("runId", scenario.runId),
                Map.entry("sourceSHA", System.getProperty("syncmoney.benchmarks.sourceSha", "UNKNOWN")),
                Map.entry("sourceTreeSHA256", System.getProperty("syncmoney.benchmarks.sourceTreeSha256", "UNKNOWN")),
                Map.entry("baselineSHA", baselineSha == null ? "" : baselineSha),
                Map.entry("baselineBuildJavaVersion", System.getProperty("syncmoney.benchmarks.baselineBuildJavaVersion", "UNKNOWN")),
                Map.entry("baselineMacroPilotSHA256", System.getProperty("syncmoney.benchmarks.baselineMacroPilotSha256", "UNKNOWN")),
                Map.entry("profileSHA256", profile.sha256),
                Map.entry("runnerMainClass", "noietime.syncmoney.benchmark.load.OpenLoopLoadRunner"),
                Map.entry("artifactSHA256", Map.of(
                        "loadTestRuntimeClasspath", System.getProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256", "UNKNOWN"),
                        "baselineJar", System.getProperty("syncmoney.benchmarks.baselineJarSha256", ""))),
                Map.entry("profileId", profile.profileId),
                Map.entry("jdk", environment.javaVersion()),
                Map.entry("runtimeJavaVersion", environment.javaVersion()),
                Map.entry("environment", environment.fields()),
                Map.entry("environmentFingerprintSHA256", environment.fingerprint()),
                Map.entry("baselineEnvironmentFingerprintSHA256", baselineEnvironmentFingerprint),
                Map.entry("baselineLatencyComparisonEligible", false),
                Map.entry("os", environment.osName() + " " + environment.osVersion() + " " + environment.osArch()),
                Map.entry("hardware", Map.of("availableProcessors", environment.availableProcessors(),
                        "maxMemoryBytes", environment.maxMemoryBytes())),
                Map.entry("dependencies", Map.of("jmh", "1.37", "hdrHistogram", "2.2.2",
                        "sqlDialect", scenario.sqlDialect.name(), "sqlProduct", scenario.sqlProduct,
                        "sqlVersion", scenario.sqlVersion, "redisVersion", scenario.redisVersion)),
                Map.entry("flags", environment.jvmArguments()),
                Map.entry("dataset", Map.of("accounts", ACCOUNT_COUNT, "operation", "atomic add + version-guarded SQL snapshot",
                        "amount", "1.00", "batchSize", batchSize, "maxBatchWaitMillis", batchWaitMillis,
                        "sqlTable", "connection-local TEMP players")),
                Map.entry("targetArrivalRate", Map.of("transactionsPerSecond", targetRate,
                        "baselineAcceptedRate", baselineRate, "fraction", rateFraction)),
                Map.entry("durationSeconds", scenario.durationSeconds),
                Map.entry("sampleCount", Map.of("response", scenario.response.rawTotalCount(),
                        "receipt", scenario.receipt.rawTotalCount(), "durable", scenario.durable.rawTotalCount())),
                Map.entry("offered", scenario.offered),
                Map.entry("accepted", scenario.accepted),
                Map.entry("rejected", scenario.rejected),
                Map.entry("authorityRejected", scenario.authorityRejected),
                Map.entry("persisted", scenario.persisted),
                Map.entry("responseHistogram", scenario.response.asMap()),
                Map.entry("scheduleLagHistogram", scenario.scheduleLag.asMap()),
                Map.entry("receiptHistogram", scenario.receipt.asMap()),
                Map.entry("durableHistogram", scenario.durable.asMap()),
                Map.entry("allocation", scenario.allocation.asMap()),
                Map.entry("gc", scenario.gc),
                Map.entry("queueMax", scenario.queueMax),
                Map.entry("oldestBacklogAgeMicros", scenario.oldestBacklogAgeMicros),
                Map.entry("backlogRecoveryMillis", scenario.backlogRecoveryMillis),
                Map.entry("ambiguous", scenario.ambiguous),
                Map.entry("quarantine", scenario.quarantine),
                Map.entry("moneyInvariantFailures", scenario.moneyInvariantFailures),
                Map.entry("versionConflicts", scenario.versionConflicts),
                Map.entry("failureCategory", scenario.failureCategory),
                Map.entry("valid", scenario.valid),
                Map.entry("p999Reliable", isP999Reliable(scenario.response.rawTotalCount(),
                        scenario.receipt.rawTotalCount(), scenario.durable.rawTotalCount(),
                        profile.minimumMeasurementSamples)),
                Map.entry("p999ConfidenceMethod", "approximate 95% binomial order-statistic interval; emitted only when each histogram meets its sample minimum; assumes independent samples"),
                Map.entry("minimumMeasurementSamples", Math.max(P999_MINIMUM_SAMPLES,
                        profile.minimumMeasurementSamples)),
                Map.entry("notes", List.of(
                        "Response is scheduled-arrival to Redis result for admitted work, or immediate bounded-runner rejection; it includes scheduler lag and rejection samples.",
                        "Receipt is accepted-admission to the actual protocol-v1 atomic_add_balance.lua Redis receipt.",
                        "Durable is accepted-admission to commit through the production SqlSnapshotWriter in one isolated temporary players table.",
                        "The baseline macro pilot's receipt and durable latencies are polling-observation upper bounds, may coalesce writes, and are excluded from matched latency comparisons; only its accepted rate calibrates arrivals.",
                        "Raw and expected-interval-corrected HDR distributions are separate; a 95% approximate order-statistic interval is omitted for any histogram below the p99.9 sample minimum; no blind Redis retry is performed.",
                        "SQL and Redis endpoint values, credentials, player names, UUIDs and per-account balances are excluded from this report.")));
    }

    static void validateDisposableTarget(OpenLoopProfile.BackendSettings backend, String acknowledgement) {
        requireDedicatedSqlUser(backend);
        BaselineMacroPilotProducer.SqlEndpoint sql = BaselineMacroPilotProducer.parseSqlEndpoint(
                backend.sqlUrl(), backend.sqlDialect());
        BaselineMacroPilotProducer.validateDisposableBackend(backend, sql, acknowledgement);
    }

    private static void requireDedicatedSqlUser(OpenLoopProfile.BackendSettings backend) {
        if (backend.sqlUser() == null || backend.sqlUser().isBlank()) {
            throw new IllegalArgumentException("A separate SQL user setting is required for the isolated benchmark.");
        }
    }

    static boolean validMeasurementSamples(boolean postconditionsValid, long responseSamples,
            long receiptSamples, long durableSamples, int profileMinimumSamples) {
        return postconditionsValid && isP999Reliable(responseSamples, receiptSamples,
                durableSamples, profileMinimumSamples);
    }

    static boolean isP999Reliable(long responseSamples, long receiptSamples, long durableSamples,
            int profileMinimumSamples) {
        long requiredSamples = Math.max(P999_MINIMUM_SAMPLES, profileMinimumSamples);
        return responseSamples >= requiredSamples && receiptSamples >= requiredSamples
                && durableSamples >= requiredSamples;
    }

    static boolean matchesExpectedMutationCount(long expectedMutationCount, boolean redisPairComplete,
            BigDecimal redisBalance, Long redisVersion, boolean sqlRowPresent,
            BigDecimal sqlBalance, Long sqlVersion) {
        if (expectedMutationCount < 0L || !redisPairComplete) return false;
        if (expectedMutationCount == 0L) {
            return redisBalance == null && redisVersion == null && !sqlRowPresent
                    && sqlBalance == null && sqlVersion == null;
        }
        if (redisBalance == null || redisVersion == null || !sqlRowPresent
                || sqlBalance == null || sqlVersion == null) return false;
        BigDecimal expectedBalance = BigDecimal.valueOf(expectedMutationCount, 0);
        return redisBalance.compareTo(expectedBalance) == 0 && redisVersion == expectedMutationCount
                && sqlBalance.compareTo(expectedBalance) == 0 && sqlVersion == expectedMutationCount;
    }

    private static final class RunSession {
        private final OpenLoopProfile profile;
        private final OpenLoopProfile.BackendSettings backend;
        private final double targetRate;
        private final int batchSize;
        private final int batchWaitMillis;
        private final ThreadAllocationTracker allocation = new ThreadAllocationTracker();
        private final String runId = UUID.randomUUID().toString();
        private final String keyPrefix = "syncmoney:benchmark:" + runId + ":";
        private final List<UUID> accounts = new ArrayList<>(ACCOUNT_COUNT);
        private final AtomicLongArray expectedMutationCounts = new AtomicLongArray(ACCOUNT_COUNT);
        private final Semaphore inFlight;
        private final ArrayBlockingQueue<Receipt> receipts;
        private final ThreadPoolExecutor workers;
        private final Thread batchWriter;
        private final ThreadLocal<Jedis> redisClient;
        private final ConcurrentLinkedQueue<Jedis> redisClients = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean workersFinished = new AtomicBoolean();
        private final AtomicBoolean batchFinished = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger queueMax = new AtomicInteger();
        private final AtomicLong offered = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong persisted = new AtomicLong();
        private final AtomicLong authorityRejected = new AtomicLong();
        private final AtomicLong ambiguous = new AtomicLong();
        private final AtomicLong quarantine = new AtomicLong();
        private final AtomicLong moneyInvariantFailures = new AtomicLong();
        private final AtomicLong versionConflicts = new AtomicLong();
        private final AtomicLong oldestBacklogAgeMicros = new AtomicLong();
        private final AtomicLong durableEndNanos = new AtomicLong();
        private final AtomicReference<Connection> sqlConnection = new AtomicReference<>();
        private final AtomicReference<String> sqlProduct = new AtomicReference<>("UNKNOWN");
        private final AtomicReference<String> sqlVersion = new AtomicReference<>("UNKNOWN");
        private final AtomicReference<String> redisVersion = new AtomicReference<>("UNKNOWN");
        private final AtomicReference<Throwable> sqlSetupFailure = new AtomicReference<>();
        private final LatencyDistribution response = new LatencyDistribution();
        private final LatencyDistribution scheduleLag = new LatencyDistribution();
        private final LatencyDistribution receipt = new LatencyDistribution();
        private final LatencyDistribution durable = new LatencyDistribution();
        private final long expectedIntervalMicros;
        private final Map<String, Map<String, Long>> gcBefore = gcSnapshot();
        private volatile String addScriptSha;

        private RunSession(OpenLoopProfile profile, OpenLoopProfile.BackendSettings backend,
                double targetRate, int batchSize, int batchWaitMillis) throws Exception {
            this.profile = profile;
            this.backend = backend;
            this.targetRate = targetRate;
            this.batchSize = batchSize;
            this.batchWaitMillis = batchWaitMillis;
            expectedIntervalMicros = Math.max(1L, Math.round(1_000_000d / targetRate));
            inFlight = new Semaphore(profile.maxInFlight);
            receipts = new ArrayBlockingQueue<>(profile.maxInFlight);
            for (int index = 0; index < ACCOUNT_COUNT; index++) accounts.add(UUID.randomUUID());

            try (Jedis initializer = newRedisClient()) {
                initializer.ping();
                redisVersion.set(serverVersion(initializer.info("server")));
                addScriptSha = initializer.scriptLoad(loadAddScript());
            }
            redisClient = ThreadLocal.withInitial(() -> {
                try {
                    Jedis client = newRedisClient();
                    client.ping();
                    redisClients.add(client);
                    return client;
                } catch (RuntimeException failedConnection) {
                    throw new IllegalStateException("Could not create benchmark Redis worker connection", failedConnection);
                }
            });
            AtomicInteger workerSequence = new AtomicInteger();
            workers = new ThreadPoolExecutor(profile.workerThreads, profile.workerThreads,
                    0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(profile.maxInFlight),
                    task -> new Thread(allocation.track(task), "syncmoney-benchmark-redis-worker-"
                            + workerSequence.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
            batchWriter = new Thread(allocation.track(this::writeBatches), "syncmoney-benchmark-sql-batcher");
            batchWriter.setDaemon(false);
            try {
                CountDownLatch workerReady = new CountDownLatch(profile.workerThreads);
                for (int index = 0; index < profile.workerThreads; index++) {
                    workers.submit(() -> {
                        redisClient.get();
                        workerReady.countDown();
                    });
                }
                if (!workerReady.await(30L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Redis worker setup timed out");
                }
                batchWriter.start();
                long sqlReadyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
                while (sqlConnection.get() == null && sqlSetupFailure.get() == null
                        && System.nanoTime() < sqlReadyDeadline) {
                    Thread.sleep(5L);
                }
                if (sqlConnection.get() == null) {
                    throw new IllegalStateException("SQL benchmark connection setup failed", sqlSetupFailure.get());
                }
            } catch (Exception | Error setupFailure) {
                cleanupFailedSetup(setupFailure);
                throw setupFailure;
            }
        }

        private void cleanupFailedSetup(Throwable setupFailure) {
            boolean interrupted = setupFailure instanceof InterruptedException;
            workersFinished.set(true);
            workers.shutdownNow();
            if (batchWriter.getState() != Thread.State.NEW) {
                batchWriter.interrupt();
            }
            try {
                if (!workers.awaitTermination(10L, TimeUnit.SECONDS)) {
                    setupFailure.addSuppressed(new IllegalStateException("Redis benchmark workers did not stop after setup failure"));
                }
            } catch (InterruptedException cleanupInterrupted) {
                setupFailure.addSuppressed(cleanupInterrupted);
                interrupted = true;
            }
            if (batchWriter.getState() != Thread.State.NEW) {
                try {
                    batchWriter.join(TimeUnit.SECONDS.toMillis(10L));
                    if (batchWriter.isAlive()) {
                        setupFailure.addSuppressed(new IllegalStateException("SQL benchmark writer did not stop after setup failure"));
                    }
                } catch (InterruptedException cleanupInterrupted) {
                    setupFailure.addSuppressed(cleanupInterrupted);
                    interrupted = true;
                }
            }
            closeSqlConnection();
            closeRedisClients();
            if (interrupted) Thread.currentThread().interrupt();
        }

        private void abortAndCleanup(Throwable runFailure) {
            failure.compareAndSet(null, runFailure);
            boolean interrupted = Thread.interrupted();
            if (runFailure instanceof InterruptedException) interrupted = true;
            boolean workersStopped = false;
            try {
                workers.shutdown();
                try {
                    workersStopped = workers.awaitTermination(30L, TimeUnit.SECONDS);
                } catch (InterruptedException cleanupInterrupted) {
                    runFailure.addSuppressed(cleanupInterrupted);
                    interrupted = true;
                }
                if (!workersStopped) {
                    workers.shutdownNow();
                    closeRedisClients();
                    try {
                        workersStopped = workers.awaitTermination(10L, TimeUnit.SECONDS);
                    } catch (InterruptedException cleanupInterrupted) {
                        runFailure.addSuppressed(cleanupInterrupted);
                        interrupted = true;
                    }
                }
                workersFinished.set(true);
                if (batchWriter.getState() != Thread.State.NEW) {
                    try {
                        batchWriter.join(TimeUnit.SECONDS.toMillis(30L));
                    } catch (InterruptedException cleanupInterrupted) {
                        runFailure.addSuppressed(cleanupInterrupted);
                        interrupted = true;
                    }
                    if (batchWriter.isAlive()) {
                        batchWriter.interrupt();
                        closeSqlConnection();
                        try {
                            batchWriter.join(TimeUnit.SECONDS.toMillis(10L));
                        } catch (InterruptedException cleanupInterrupted) {
                            runFailure.addSuppressed(cleanupInterrupted);
                            interrupted = true;
                        }
                    }
                }
            } finally {
                workersFinished.set(true);
                closeSqlConnection();
                closeRedisClients();
                if (workersStopped) cleanupRedisKeys();
                else runFailure.addSuppressed(new IllegalStateException(
                        "Redis benchmark workers did not stop; run-specific keys were not deleted to avoid racing a late mutation"));
                if (batchWriter.isAlive()) {
                    runFailure.addSuppressed(new IllegalStateException("SQL benchmark writer did not stop after interruption"));
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void offer(long sequence, long scheduledNanos, long nowNanos) {
            offered.incrementAndGet();
            long lag = Math.max(0L, nowNanos - scheduledNanos);
            record(scheduleLag, lag);
            if (!inFlight.tryAcquire()) {
                rejected.incrementAndGet();
                record(response, System.nanoTime() - scheduledNanos);
                return;
            }
            int accountIndex = Math.floorMod(sequence, ACCOUNT_COUNT);
            long acceptedAt = System.nanoTime();
            ReceiptRequest request = new ReceiptRequest(accountIndex, scheduledNanos, acceptedAt);
            try {
                workers.execute(() -> executeRedisMutation(request));
                accepted.incrementAndGet();
                expectedMutationCounts.incrementAndGet(accountIndex);
                queueMax.accumulateAndGet(workers.getQueue().size() + workers.getActiveCount() + receipts.size(), Math::max);
            } catch (RuntimeException submissionFailure) {
                inFlight.release();
                rejected.incrementAndGet();
                record(response, System.nanoTime() - scheduledNanos);
                failure.compareAndSet(null, submissionFailure);
            }
        }

        private void executeRedisMutation(ReceiptRequest request) {
            boolean requestMayHaveReachedRedis = false;
            long started = System.nanoTime();
            try {
                UUID uuid = accounts.get(request.accountIndex());
                String balanceKey = keyPrefix + "balance:" + uuid;
                String versionKey = keyPrefix + "version:" + uuid;
                Jedis client = redisClient.get();
                requestMayHaveReachedRedis = true;
                Object raw;
                try {
                    raw = client.evalsha(addScriptSha, List.of(balanceKey, versionKey), List.of(ONE_DOLLAR.toPlainString()));
                } catch (JedisNoScriptException missingScript) {
                    // NOSCRIPT is an explicit non-execution response; reload and retry only this known-safe case.
                    addScriptSha = client.scriptLoad(loadAddScript());
                    raw = client.evalsha(addScriptSha, List.of(balanceKey, versionKey), List.of(ONE_DOLLAR.toPlainString()));
                }
                if (!(raw instanceof List<?> values) || values.size() != 2) {
                    moneyInvariantFailures.incrementAndGet();
                    throw new IllegalStateException("Redis returned an incomplete atomic receipt");
                }
                BigDecimal balance = new BigDecimal(values.get(0).toString());
                long version = Long.parseLong(values.get(1).toString());
                long balanceCents = balance.movePointRight(2).longValueExact();
                if (version < 1L || balance.compareTo(BigDecimal.valueOf(version, 0)) != 0
                        || balanceCents != Math.multiplyExact(version, 100L)) {
                    moneyInvariantFailures.incrementAndGet();
                    throw new IllegalStateException("Redis atomic receipt violated the benchmark add invariant");
                }
                long receivedAt = System.nanoTime();
                record(receipt, receivedAt - request.acceptedAtNanos());
                record(response, receivedAt - request.scheduledNanos());
                PlayerSnapshot snapshot = new PlayerSnapshot(new ConfirmedAccount(uuid, balance, version),
                        "benchmark", "benchmark", Instant.now());
                Receipt confirmed = new Receipt(request, snapshot, receivedAt, balance, version);
                receipts.put(confirmed);
                queueMax.accumulateAndGet(receipts.size(), Math::max);
            } catch (Throwable problem) {
                record(response, System.nanoTime() - request.scheduledNanos());
                if (requestMayHaveReachedRedis && isExplicitRedisRejection(problem)) {
                    authorityRejected.incrementAndGet();
                } else if (requestMayHaveReachedRedis) {
                    ambiguous.incrementAndGet();
                }
                failure.compareAndSet(null, problem);
                inFlight.release();
            }
        }

        private void writeBatches() {
            Connection connection = null;
            try {
                connection = openSqlConnection();
                createTemporaryPlayersTable(connection, backend.sqlDialect());
                sqlProduct.set(connection.getMetaData().getDatabaseProductName());
                sqlVersion.set(connection.getMetaData().getDatabaseProductVersion());
                connection.setAutoCommit(false);
                sqlConnection.set(connection);
            } catch (Throwable setupFailure) {
                sqlSetupFailure.set(setupFailure);
                failure.compareAndSet(null, setupFailure);
                closeQuietly(connection);
                return;
            }

            try {
                while (!workersFinished.get() || !receipts.isEmpty()) {
                    Receipt first = receipts.poll(100L, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                    List<Receipt> batch = collectBatch(first);
                    if (failure.get() != null) {
                        quarantineBatch(batch);
                        continue;
                    }
                    try {
                        SqlSnapshotWriter.persistPlayers(connection,
                                batch.stream().map(Receipt::snapshot).toList(), backend.sqlDialect());
                        connection.commit();
                        long committedAt = System.nanoTime();
                        durableEndNanos.set(committedAt);
                        for (Receipt item : batch) {
                            persisted.incrementAndGet();
                            record(durable, committedAt - item.request().acceptedAtNanos());
                            oldestBacklogAgeMicros.accumulateAndGet(
                                    TimeUnit.NANOSECONDS.toMicros(Math.max(0L, committedAt - item.receivedAtNanos())), Math::max);
                            inFlight.release();
                        }
                    } catch (Throwable persistenceFailure) {
                        try { connection.rollback(); } catch (SQLException ignored) { }
                        if (persistenceFailure.getClass().getSimpleName().contains("Conflict")) {
                            versionConflicts.addAndGet(batch.size());
                        } else {
                            ambiguous.addAndGet(batch.size());
                        }
                        failure.compareAndSet(null, persistenceFailure);
                        quarantineBatch(batch);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, interrupted);
            } finally {
                batchFinished.set(true);
            }
        }

        private List<Receipt> collectBatch(Receipt first) throws InterruptedException {
            List<Receipt> batch = new ArrayList<>(batchSize);
            batch.add(first);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchWaitMillis);
            while (batch.size() < batchSize) {
                long remaining = deadline - System.nanoTime();
                if (batchWaitMillis == 0 || remaining <= 0L) break;
                Receipt next = receipts.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) break;
                batch.add(next);
            }
            return batch;
        }

        private void quarantineBatch(List<Receipt> batch) {
            quarantine.addAndGet(batch.size());
            for (int ignored = 0; ignored < batch.size(); ignored++) inFlight.release();
        }

        private boolean validateAndCleanup() {
            if (failure.get() != null || sqlConnection.get() == null || redisClients.isEmpty()) {
                cleanupRedisKeys();
                closeSqlConnection();
                closeRedisClients();
                return false;
            }
            try {
                Jedis client = redisClients.peek();
                Connection connection = sqlConnection.get();
                try (var query = connection.prepareStatement("SELECT balance, version FROM players WHERE player_uuid = ?")) {
                    for (int index = 0; index < ACCOUNT_COUNT; index++) {
                        long expectedCount = expectedMutationCounts.get(index);
                        UUID uuid = accounts.get(index);
                        List<String> redisValues = client.mget(keyPrefix + "balance:" + uuid,
                                keyPrefix + "version:" + uuid);
                        boolean redisPairComplete = redisValues.size() == 2;
                        BigDecimal redisBalance = redisPairComplete && redisValues.get(0) != null
                                ? new BigDecimal(redisValues.get(0)) : null;
                        Long redisVersion = redisPairComplete && redisValues.get(1) != null
                                ? Long.parseLong(redisValues.get(1)) : null;
                        query.setString(1, uuid.toString());
                        try (ResultSet row = query.executeQuery()) {
                            boolean sqlRowPresent = row.next();
                            BigDecimal sqlBalance = sqlRowPresent ? row.getBigDecimal(1) : null;
                            Long sqlVersion = sqlRowPresent ? row.getLong(2) : null;
                            if (!matchesExpectedMutationCount(expectedCount, redisPairComplete, redisBalance,
                                    redisVersion, sqlRowPresent, sqlBalance, sqlVersion)) {
                                moneyInvariantFailures.incrementAndGet();
                            }
                        }
                    }
                }
            } catch (Throwable verificationFailure) {
                failure.compareAndSet(null, verificationFailure);
                moneyInvariantFailures.incrementAndGet();
            } finally {
                cleanupRedisKeys();
                closeSqlConnection();
                closeRedisClients();
            }
            return failure.get() == null && moneyInvariantFailures.get() == 0L
                    && versionConflicts.get() == 0L && authorityRejected.get() == 0L && quarantine.get() == 0L
                    && persisted.get() == accepted.get();
        }

        private void cleanupRedisKeys() {
            List<String> keys = new ArrayList<>(ACCOUNT_COUNT * 2);
            for (UUID account : accounts) {
                keys.add(keyPrefix + "balance:" + account);
                keys.add(keyPrefix + "version:" + account);
            }
            try (Jedis client = newRedisClient()) {
                for (int offset = 0; offset < keys.size(); offset += 500) {
                    String[] chunk = keys.subList(offset, Math.min(offset + 500, keys.size())).toArray(String[]::new);
                    client.del(chunk);
                }
            } catch (RuntimeException cleanupFailure) {
                failure.compareAndSet(null, cleanupFailure);
            }
        }

        private void finishWorkers() throws InterruptedException {
            workers.shutdown();
            if (!workers.awaitTermination(120L, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, new IllegalStateException("Redis workers did not drain within the bounded shutdown deadline"));
                workers.shutdownNow();
                if (!workers.awaitTermination(10L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Redis workers did not stop after forced shutdown");
                }
            }
            workersFinished.set(true);
        }

        private void finishBatchWriter() throws InterruptedException {
            batchWriter.join(TimeUnit.MINUTES.toMillis(2L));
            if (batchWriter.isAlive()) {
                failure.compareAndSet(null, new IllegalStateException("SQL batcher did not drain within the bounded shutdown deadline"));
                batchWriter.interrupt();
                batchWriter.join(TimeUnit.SECONDS.toMillis(10L));
                if (batchWriter.isAlive()) throw new IllegalStateException("SQL batcher did not stop after forced shutdown");
            }
        }

        private Scenario snapshot(long started, long issueEnd, long finished, int durationSeconds,
                boolean valid, boolean requireMinimumSamples) {
            long recoveryStart = issueEnd;
            long durableEnd = durableEndNanos.get();
            long recoveryMillis = durableEnd == 0L ? 0L
                    : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, durableEnd - recoveryStart));
            HistogramSummary responseSummary = histogram(response, minimumP999Samples(profile));
            HistogramSummary scheduleLagSummary = histogram(scheduleLag, minimumP999Samples(profile));
            HistogramSummary receiptSummary = histogram(receipt, minimumP999Samples(profile));
            HistogramSummary durableSummary = histogram(durable, minimumP999Samples(profile));
            boolean measurementValid = valid && inFlight.availablePermits() == profile.maxInFlight;
            if (requireMinimumSamples) {
                measurementValid = validMeasurementSamples(measurementValid,
                        responseSummary.rawTotalCount(), receiptSummary.rawTotalCount(),
                        durableSummary.rawTotalCount(), profile.minimumMeasurementSamples);
            }
            return new Scenario(runId, backend.sqlDialect(), durationSeconds,
                    offered.get(), accepted.get(), rejected.get(), persisted.get(),
                    responseSummary, scheduleLagSummary, receiptSummary, durableSummary,
                    gcDelta(gcBefore, gcSnapshot()), queueMax.get(), oldestBacklogAgeMicros.get(), recoveryMillis,
                    ambiguous.get(), quarantine.get(), moneyInvariantFailures.get(), versionConflicts.get(),
                    authorityRejected.get(),
                    measurementValid,
                    redisVersion.get(), sqlProduct.get(), sqlVersion.get(),
                    allocation.snapshot(offered.get(), accepted.get(), profile.workerThreads + 1),
                    failure.get() == null ? "NONE" : failure.get().getClass().getSimpleName(),
                    started, issueEnd, finished);
        }

        private Jedis newRedisClient() {
            Jedis client = new Jedis(backend.redisHost(), backend.redisPort(), 5_000);
            try {
                if (backend.redisPassword() != null && !backend.redisPassword().isBlank()) client.auth(backend.redisPassword());
                if (backend.redisDatabase() != 0) client.select(backend.redisDatabase());
                return client;
            } catch (RuntimeException failure) {
                client.close();
                throw failure;
            }
        }

        private Connection openSqlConnection() throws SQLException {
            Connection connection = backend.sqlUser() == null || backend.sqlUser().isBlank()
                    ? DriverManager.getConnection(backend.sqlUrl())
                    : DriverManager.getConnection(backend.sqlUrl(), backend.sqlUser(),
                            backend.sqlPassword() == null ? "" : backend.sqlPassword());
            connection.setNetworkTimeout(Runnable::run, 5_000);
            return connection;
        }

        private void createTemporaryPlayersTable(Connection connection, SqlDialect dialect) throws SQLException {
            String create = switch (dialect) {
                case SQLITE -> "CREATE TEMP TABLE players (player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64), "
                        + "balance DECIMAL(20,2) NOT NULL, version BIGINT NOT NULL, last_server VARCHAR(128), updated_at TIMESTAMP)";
                case MYSQL -> "CREATE TEMPORARY TABLE players (player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64), "
                        + "balance DECIMAL(20,2) NOT NULL, version BIGINT NOT NULL, last_server VARCHAR(128), updated_at TIMESTAMP)";
                case POSTGRESQL -> "CREATE TEMPORARY TABLE players (player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64), "
                        + "balance DECIMAL(20,2) NOT NULL, version BIGINT NOT NULL, last_server VARCHAR(128), updated_at TIMESTAMP) ON COMMIT PRESERVE ROWS";
            };
            try (Statement statement = connection.createStatement()) {
                statement.execute(create);
            }
            connection.commit();
        }

        private String loadAddScript() {
            try (InputStream stream = OpenLoopLoadRunner.class.getClassLoader().getResourceAsStream("lua/atomic_add_balance.lua")) {
                if (stream == null) throw new IllegalStateException("Packaged atomic_add_balance.lua resource is missing");
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException("Could not read benchmark Lua resource", error);
            }
        }

        private static String serverVersion(String info) {
            return Arrays.stream(info.split("\\R"))
                    .filter(line -> line.startsWith("redis_version:"))
                    .map(line -> line.substring("redis_version:".length()).trim())
                    .findFirst().orElse("UNKNOWN");
        }

        private void closeSqlConnection() {
            closeQuietly(sqlConnection.getAndSet(null));
        }

        private void closeRedisClients() {
            Jedis client;
            while ((client = redisClients.poll()) != null) client.close();
        }

        private static boolean isExplicitRedisRejection(Throwable failure) {
            if (failure instanceof redis.clients.jedis.exceptions.JedisDataException data) {
                String message = data.getMessage();
                return message != null && (message.contains("INSUFFICIENT_FUNDS")
                        || message.contains("INVALID_KEY_TYPE") || message.contains("INVALID_BALANCE")
                        || message.contains("INVALID_AMOUNT") || message.contains("INVALID_VERSION")
                        || message.contains("VERSION_OVERFLOW"));
            }
            return false;
        }

        private static void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) return;
            try { closeable.close(); } catch (Exception ignored) { }
        }

        private void record(LatencyDistribution distribution, long nanos) {
            long micros = Math.max(1L, TimeUnit.NANOSECONDS.toMicros(Math.max(0L, nanos)));
            distribution.raw.recordValue(micros);
            distribution.corrected.recordValueWithExpectedInterval(micros, expectedIntervalMicros);
        }
    }

    private static Map<String, Map<String, Long>> gcSnapshot() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(GarbageCollectorMXBean::getName,
                        bean -> Map.of("count", Math.max(0L, bean.getCollectionCount()),
                                "timeMillis", Math.max(0L, bean.getCollectionTime()))));
    }

    private static Map<String, Map<String, Long>> gcDelta(Map<String, Map<String, Long>> before,
            Map<String, Map<String, Long>> after) {
        return after.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> Map.of("count", Math.max(0L, entry.getValue().get("count") - before.getOrDefault(entry.getKey(), Map.of()).getOrDefault("count", 0L)),
                        "timeMillis", Math.max(0L, entry.getValue().get("timeMillis") - before.getOrDefault(entry.getKey(), Map.of()).getOrDefault("timeMillis", 0L)))));
    }

    private static HistogramSummary histogram(LatencyDistribution distribution, long minimumP999Samples) {
        Histogram raw = distribution.raw.getIntervalHistogram();
        Histogram corrected = distribution.corrected.getIntervalHistogram();
        return summarize(raw, corrected, minimumP999Samples);
    }

    private static long minimumP999Samples(OpenLoopProfile profile) {
        return Math.max(P999_MINIMUM_SAMPLES, profile.minimumMeasurementSamples);
    }

    static Map<String, Object> histogramSummary(Histogram raw, Histogram corrected, long minimumP999Samples) {
        return summarize(raw, corrected, minimumP999Samples).asMap();
    }

    private static HistogramSummary summarize(Histogram raw, Histogram corrected, long minimumP999Samples) {
        HistogramQuantileConfidence confidence = raw.getTotalCount() < minimumP999Samples
                ? null : HistogramQuantileConfidence.p999(raw);
        return new HistogramSummary(raw.getTotalCount(), raw.getValueAtPercentile(50.0d),
                raw.getValueAtPercentile(95.0d), raw.getValueAtPercentile(99.0d),
                raw.getValueAtPercentile(99.9d), raw.getMaxValue(), corrected.getTotalCount(),
                corrected.getValueAtPercentile(99.9d), confidence);
    }

    private record ReceiptRequest(int accountIndex, long scheduledNanos, long acceptedAtNanos) { }
    private record Receipt(ReceiptRequest request, PlayerSnapshot snapshot, long receivedAtNanos,
            BigDecimal balance, long version) { }
    record HistogramSummary(long rawTotalCount, long p50Micros, long p95Micros, long p99Micros,
            long p999Micros, long maxMicros, long correctedTotalCount, long correctedP999Micros,
            HistogramQuantileConfidence p999Confidence) {
        Map<String, Object> asMap() {
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("unit", "microseconds");
            summary.put("rawCount", rawTotalCount);
            summary.put("raw", Map.of("p50", p50Micros, "p95", p95Micros, "p99", p99Micros,
                    "p99.9", p999Micros, "max", maxMicros));
            if (p999Confidence != null) summary.put("p99.9Confidence95", p999Confidence.asMap());
            summary.put("correctedCount", correctedTotalCount);
            summary.put("expectedIntervalCorrected", Map.of("p99.9", correctedP999Micros));
            return Map.copyOf(summary);
        }
    }
    private static final class LatencyDistribution {
        private final Recorder raw = new Recorder(MAX_HISTOGRAM_MICROS, 3);
        private final Recorder corrected = new Recorder(MAX_HISTOGRAM_MICROS, 3);
    }
    private record Scenario(String runId, SqlDialect sqlDialect, int durationSeconds,
            long offered, long accepted, long rejected, long persisted,
            HistogramSummary response, HistogramSummary scheduleLag, HistogramSummary receipt, HistogramSummary durable,
            Map<String, Map<String, Long>> gc, int queueMax, long oldestBacklogAgeMicros,
            long backlogRecoveryMillis, long ambiguous, long quarantine, long moneyInvariantFailures,
            long versionConflicts, long authorityRejected, boolean valid, String redisVersion, String sqlProduct, String sqlVersion,
            ThreadAllocationTracker.AllocationSnapshot allocation,
            String failureCategory, long startedNanos, long issueEndNanos, long finishedNanos) { }
}
