package noietime.syncmoney.benchmark.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import noietime.syncmoney.storage.persistence.SqlDialect;
import org.HdrHistogram.Histogram;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Produces observed 1.3.2 Redis-to-SQL macro evidence through the real plugin path. */
public final class BaselineMacroPilotProducer {
    static final String BASELINE_SHA = "71cc55dfeefe9a8f40a3835b051ddd324d353767";
    static final String BASELINE_ARTIFACT_SHA256 = "442a404276b538ce807b0f063fb37920be85e60961a52a475b3f3524d08f4f10";
    static final String BASELINE_ARTIFACT_NAME = "Syncmoney-1.3.2.jar";
    static final String MEASUREMENT_KIND = "legacy132-redis-sql-macro";
    static final int ACCOUNT_COUNT = 10_000;
    // Sample values are retained in boxed lists until report generation; keep the measured profile heap-bounded.
    static final int MAX_HISTOGRAM_SAMPLES = 1_000_000;
    private static final int ECONOMY_QUEUE_CAPACITY = 50_000;
    private static final int DB_QUEUE_CAPACITY = 10_000;
    private static final int MAX_ECONOMY_BACKLOG = 256;
    private static final int REDIS_MGET_BATCH = 2_000;
    private static final int SQL_QUERY_BATCH = 500;
    private static final long MAX_LATENCY_MICROS = TimeUnit.HOURS.toMicros(1);
    private static final String SCRATCH_ACK_ENV = "SYNCMONEY_BENCHMARK_DISPOSABLE_ACK";
    private static final String SCRATCH_ACK_VALUE = "syncmoney-t19-disposable";
    private static final String SCRATCH_REDIS_HOST = "127.0.0.1";
    private static final int SCRATCH_REDIS_PORT = 16_379;
    private static final int SCRATCH_REDIS_DATABASE = 0;
    private static final String SCRATCH_SQL_HOST = "127.0.0.1";
    private static final int SCRATCH_SQL_PORT = 13_306;
    private static final String SCRATCH_SQL_DATABASE = "syncmoney_t19";
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");
    private static final String SERVER_NAME = "baseline-macro-pilot";
    private static volatile String currentPhase = "startup";

    private BaselineMacroPilotProducer() { }

    public static void main(String[] args) throws Exception {
        try {
            currentPhase = "parse arguments";
            Arguments parsed = parseArguments(args);
            currentPhase = "verify Java runtime";
            if (System.getProperty("java.version") == null
                    || !System.getProperty("java.version").matches("25(?:\\..*|-.*)?")) {
                throw new IllegalStateException("The macro pilot must run on the planned Java 25 runtime.");
            }
            run(parsed, BenchmarkEnvironment.capture());
        } catch (Throwable failure) {
            // Backend exceptions can contain JDBC URLs, user names, or other configured data.
            // Preserve a useful phase and failure category without forwarding untrusted exception text.
            throw new IllegalStateException("The baseline macro pilot failed during " + currentPhase + " ("
                    + safeFailureSummary(failure)
                    + "); backend details were not logged to protect configured credentials.");
        }
    }

    private static String safeFailureSummary(Throwable failure) {
        List<String> types = new ArrayList<>();
        Throwable root = failure;
        for (Throwable cause = failure; cause != null && types.size() < 5; cause = cause.getCause()) {
            types.add(cause.getClass().getSimpleName());
            root = cause;
        }
        String trace = Arrays.stream(failure.getStackTrace()).limit(8)
                .map(BaselineMacroPilotProducer::safeFrame).collect(java.util.stream.Collectors.joining(" <- "));
        String rootTrace = Arrays.stream(root.getStackTrace()).limit(4)
                .map(BaselineMacroPilotProducer::safeFrame).collect(java.util.stream.Collectors.joining(" <- "));
        return String.join(" -> ", types) + " trace=" + trace + " root=" + rootTrace;
    }

    private static String safeFrame(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName() + "("
                + (frame.getFileName() == null ? "unknown" : frame.getFileName()) + ":" + frame.getLineNumber() + ")";
    }

    static Arguments parseArguments(String[] args) {
        if (args == null) throw new IllegalArgumentException("Arguments are required.");
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> allowed = Set.of("--baseline-checkout", "--baseline-artifact", "--baseline-build-attestation",
                "--profile", "--output");
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (!allowed.contains(option)) throw new IllegalArgumentException("Unknown option " + option);
            if (values.containsKey(option)) throw new IllegalArgumentException("Option may only be supplied once: " + option);
            if (index + 1 >= args.length || args[index + 1] == null || args[index + 1].isBlank()
                    || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            values.put(option, args[++index]);
        }
        for (String required : List.of("--baseline-checkout", "--baseline-artifact", "--baseline-build-attestation",
                "--profile", "--output")) {
            if (!values.containsKey(required)) throw new IllegalArgumentException("Required option is missing: " + required);
        }
        return new Arguments(Path.of(values.get("--baseline-checkout")).toAbsolutePath().normalize(),
                Path.of(values.get("--baseline-artifact")).toAbsolutePath().normalize(),
                Path.of(values.get("--baseline-build-attestation")).toAbsolutePath().normalize(),
                Path.of(values.get("--profile")).toAbsolutePath().normalize(),
                Path.of(values.get("--output")).toAbsolutePath().normalize());
    }

    private static void run(Arguments arguments, BenchmarkEnvironment.Snapshot launchEnvironment) throws Exception {
        currentPhase = "verify baseline checkout and artifact";
        VerifiedBaseline baseline = verifyBaseline(arguments);
        BaselineBuildEvidence buildEvidence = readBuildAttestation(arguments.baselineBuildAttestation(),
                baseline.commitSha(), baseline.artifactSha256());
        currentPhase = "load and validate open-loop profile";
        OpenLoopProfile profile = OpenLoopProfile.read(arguments.profile());
        if (profile.measurementSecondsPerRate < 300) {
            throw new IllegalArgumentException("The real baseline macro measurement must run for at least 300 seconds.");
        }
        validateSampleLimit(profile.minimumMeasurementSamples);
        currentPhase = "verify benchmark harness provenance";
        HarnessProvenance harness = requireHarnessProvenance();

        OpenLoopProfile.BackendSettings backend;
        SqlEndpoint sqlEndpoint;
        currentPhase = "resolve isolated backend configuration";
        try {
            backend = profile.resolveBackendSettings(System.getenv());
            sqlEndpoint = parseSqlEndpoint(backend.sqlUrl(), backend.sqlDialect());
            validateDisposableBackend(backend, sqlEndpoint, System.getenv(SCRATCH_ACK_ENV));
        } catch (IllegalArgumentException invalidBackendSettings) {
            throw new IllegalArgumentException(invalidBackendSettings.getMessage()
                    + "; configured values are intentionally omitted from output");
        }
        if (backend.sqlUser() == null || backend.sqlUser().isBlank()) {
            throw new IllegalArgumentException("A separate SQL user setting is required by the profile.");
        }

        currentPhase = "prepare run paths and fixture state";
        String runId = UUID.randomUUID().toString();
        if (Files.exists(arguments.output())) {
            throw new IllegalArgumentException("--output must name a new manifest path; existing output was preserved.");
        }
        Path outputParent = arguments.output().getParent();
        if (outputParent != null) Files.createDirectories(outputParent);
        Path dataFolder = arguments.output().resolveSibling(arguments.output().getFileName() + ".data-" + runId);
        Files.createDirectory(dataFolder);
        Logger logger = quietLogger(runId);
        ClassLoader previousContextLoader = null;
        Server previousServer = Bukkit.getServer();
        RuntimeState state = new RuntimeState();
        boolean drained = false;
        List<Account> accounts = accounts(runId);
        Throwable primaryFailure = null;
        try {
            BaselineClassLoader loader = new BaselineClassLoader(arguments.baselineArtifact().toUri().toURL(),
                    BaselineMacroPilotProducer.class.getClassLoader());
            state.baselineLoader = loader;
            previousContextLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            currentPhase = "create baseline plugin fixture";
            TestPlugin fixture = testPlugin(loader, dataFolder, backend, sqlEndpoint, logger);
            currentPhase = "load baseline configuration";
            state.config = config(loader, fixture.plugin());

            currentPhase = "initialize isolated Redis";
            state.redisManager = construct(loader, "noietime.syncmoney.storage.RedisManager",
                    List.of(fixture.plugin(), state.config, true));
            if (Boolean.TRUE.equals(invoke(state.redisManager, "isDegraded"))) {
                throw new IllegalStateException("Redis authority was not available at startup.");
            }
            requireEmptyScratchRedis(state.redisManager);
            currentPhase = "initialize baseline cache manager";
            state.cacheManager = construct(loader, "noietime.syncmoney.storage.CacheManager",
                    List.of(fixture.plugin(), state.redisManager, true));
            currentPhase = "initialize baseline SQL manager";
            state.databaseManager = construct(loader, "noietime.syncmoney.storage.db.DatabaseManager",
                    List.of(fixture.plugin(), state.config));
            currentPhase = "read isolated backend metadata";
            BackendMetadata metadata = backendMetadata(state.redisManager, state.databaseManager,
                    backend.sqlDialect().name());
            currentPhase = "verify empty scratch SQL players table";
            requireScratchDatabaseReady(state.databaseManager);
            if (!keysAndRowsAreAbsent(state.redisManager, state.databaseManager, accounts)) {
                throw new IllegalArgumentException("A generated run UUID already exists in the configured scratch backends; no data was changed.");
            }
            state.redisKeysOwned = true;
            state.sqlRowsOwned = true;

            currentPhase = "initialize baseline write path";
            Object economyQueue = construct(loader, "noietime.syncmoney.economy.EconomyWriteQueue",
                    List.of(ECONOMY_QUEUE_CAPACITY));
            Object dbQueue = construct(loader, "noietime.syncmoney.storage.db.DbWriteQueue", List.of(DB_QUEUE_CAPACITY));
            Object overflowLog = construct(loader, "noietime.syncmoney.economy.OverflowLog",
                    List.of(fixture.plugin(), dataFolder));
            Object fallback = construct(loader, "noietime.syncmoney.economy.FallbackEconomyWrapper",
                    List.of(fixture.plugin(), state.redisManager));
            Object nameResolver = construct(loader, "noietime.syncmoney.uuid.NameResolver",
                    List.of(fixture.plugin(), state.cacheManager, state.databaseManager));
            for (Account account : accounts) invoke(nameResolver, "cacheName", account.playerName(), account.uuid());
            state.economy = construct(loader, "noietime.syncmoney.economy.EconomyFacade",
                    List.of(fixture.plugin(), state.config, state.cacheManager,
                            state.redisManager, state.databaseManager, economyQueue, fallback, overflowLog));
            invoke(state.economy, "setNameResolver", nameResolver);
            state.eventConsumer = construct(loader, "noietime.syncmoney.economy.EconomyEventConsumer",
                    List.of(fixture.plugin(), state.config, economyQueue, state.cacheManager,
                            state.redisManager, dbQueue, nameResolver, overflowLog, state.databaseManager));
            state.dbConsumer = construct(loader, "noietime.syncmoney.storage.db.DbWriterConsumer",
                    List.of(fixture.plugin(), dbQueue, state.databaseManager, state.cacheManager, state.redisManager));
            state.economyQueue = economyQueue;
            state.dbQueue = dbQueue;
            state.overflowLog = overflowLog;
            state.nameResolver = nameResolver;
            state.accounts = accounts;
            state.runtimeLogger = logger;

            Thread dbThread = new Thread((Runnable) state.dbConsumer, "Syncmoney-BaselineMacroPilot-DbWriter");
            dbThread.setDaemon(false);
            state.dbThread = dbThread;
            dbThread.start();
            invoke(state.eventConsumer, "start");

            currentPhase = "verify zero-start account balances";
            long[] accountVersions = new long[accounts.size()];
            for (Account account : accounts) {
                Object balance = invoke(state.economy, "getBalance", account.uuid());
                if (!(balance instanceof BigDecimal value) || value.signum() != 0) {
                    throw new IllegalStateException("Run account preflight found a nonzero starting balance.");
                }
            }

            currentPhase = "run baseline warmup";
            RunCounts warmup = runLoad(state, accounts, accountVersions, profile.warmupSecondsPerRate, false,
                    profile.minimumMeasurementSamples);
            if (warmup.rejected() != 0 || warmup.moneyInvariantFailures() != 0) {
                throw new IllegalStateException("Warmup did not preserve the expected accepted-write invariants.");
            }
            if (!awaitDrained(state, warmup.accepted(), 120_000L)) {
                throw new IllegalStateException("Warmup queues did not drain within the bounded wait.");
            }

            long durableAtMeasurementStart = number(invoke(state.dbQueue, "getWrittenCount"));
            currentPhase = "run baseline measurement profile";
            Measurement measurement = measure(state, accounts, accountVersions, profile);
            shutdownLegacyEconomyFacade(state.economy);
            invoke(state.eventConsumer, "shutdown");
            measurement.stopReceiptMonitor();
            invoke(state.dbConsumer, "stop");
            dbThread.join(30_000L);
            boolean economyQueueDrained = Boolean.TRUE.equals(invoke(state.economyQueue, "isEmpty"));
            boolean databaseQueueDrained = Boolean.TRUE.equals(invoke(state.dbQueue, "isEmpty"));
            long totalPersisted = number(invoke(state.dbQueue, "getWrittenCount"));
            drained = !dbThread.isAlive() && consumerTerminated(state.eventConsumer)
                    && economyQueueDrained && databaseQueueDrained
                    && totalPersisted == warmup.accepted() + measurement.accepted();

            long persisted = Math.max(0L, totalPersisted - durableAtMeasurementStart);
            measurement.collectDurableSamples(persisted, System.nanoTime());
            currentPhase = "verify measured balances, versions, and persistence";
            ObservationCheck check = verifyFinalState(loader, state, accounts, accountVersions,
                    measurement, warmup.accepted() + measurement.accepted(), totalPersisted);
            long dropped = number(invoke(state.economy, "getDroppedEventCount"));
            boolean overflow = Boolean.TRUE.equals(invoke(state.overflowLog, "hasOverflowRecords"));
            FailureCounts failures = consumerFailures(state.eventConsumer);
            boolean correctness = check.moneyInvariantFailures() == 0 && check.versionConflicts() == 0
                    && measurement.counts().moneyInvariantFailures() == 0
                    && measurement.authorityRejected() == 0 && failures.legacyFailedEvents() == 0;
            boolean valid = drained && correctness && dropped == 0 && !overflow
                    && measurement.offered() >= Math.max(100_000, profile.minimumMeasurementSamples)
                    && measurement.accepted() >= Math.max(100_000, profile.minimumMeasurementSamples)
                    && measurement.receiptLatencies().size() == measurement.accepted()
                    && measurement.durableLatencies().size() == persisted
                    && persisted == measurement.accepted() && check.sqlRowsMatch();

            boolean cleanupComplete = writersStopped(state) && cleanupExactRunData(state, accounts);
            if (cleanupComplete) {
                state.redisKeysOwned = false;
                state.sqlRowsOwned = false;
            }
            valid &= cleanupComplete;
            currentPhase = "write baseline macro-pilot manifest";
            Map<String, Object> manifest = manifest(arguments, baseline, buildEvidence, harness, launchEnvironment,
                    profile, backend, metadata, runId,
                    measurement, persisted, durableAtMeasurementStart, warmup, accountVersions,
                    check, dropped, overflow, failures, economyQueueDrained, databaseQueueDrained,
                    cleanupComplete, valid);
            Files.writeString(arguments.output(), serializeManifest(manifest) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("Observed baseline macro-pilot manifest: " + arguments.output());
            if (!valid) throw new IllegalStateException("Observed macro-pilot run failed one or more correctness, sample, drain, or cleanup checks.");
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (primaryFailure == null) currentPhase = "shutdown baseline runtime";
            ShutdownOutcome shutdown = shutdownRuntime(state, accounts, dataFolder, previousServer,
                    previousContextLoader);
            if (!shutdown.complete()) {
                IllegalStateException failure = new IllegalStateException(shutdown.failureMessage());
                if (primaryFailure != null) primaryFailure.addSuppressed(failure);
                else throw failure;
            }
        }
    }

    static void validateSampleLimit(int minimumSamples) {
        if (Math.max(100_000, minimumSamples) > MAX_HISTOGRAM_SAMPLES) {
            throw new IllegalArgumentException("The requested sample minimum exceeds the bounded macro-pilot histogram limit.");
        }
    }

    private static Object config(ClassLoader loader, JavaPlugin plugin) throws Exception {
        Class<?> configType = Class.forName("noietime.syncmoney.config.SyncmoneyConfig", true, loader);
        Constructor<?> constructor = configType.getConstructor(JavaPlugin.class);
        try {
            return constructor.newInstance(plugin);
        } catch (InvocationTargetException failure) {
            throw propagate(failure.getCause());
        }
    }

    private static TestPlugin testPlugin(BaselineClassLoader baselineLoader, Path dataFolder,
            OpenLoopProfile.BackendSettings backend, SqlEndpoint sql, Logger logger) {
        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(BaselineMacroPilotProducer.class.getClassLoader());
            return createTestPlugin(baselineLoader, dataFolder, backend, sql, logger);
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
        }
    }

    private static TestPlugin createTestPlugin(BaselineClassLoader baselineLoader, Path dataFolder,
            OpenLoopProfile.BackendSettings backend, SqlEndpoint sql, Logger logger) {
        currentPhase = "create Paper scheduler fixture";
        GlobalRegionScheduler globalScheduler = (GlobalRegionScheduler) Proxy.newProxyInstance(
                GlobalRegionScheduler.class.getClassLoader(), new Class<?>[] {GlobalRegionScheduler.class},
                BaselineMacroPilotProducer::paperFixtureInvocation);
        currentPhase = "create Paper server fixture";
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "BaselineMacroPilotServer";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    if (method.getName().equals("getGlobalRegionScheduler")
                            && method.getParameterCount() == 0) return globalScheduler;
                    return primitiveDefault(method.getReturnType());
                });
        currentPhase = "prepare baseline Bukkit configuration";
        FileConfiguration configuration = new YamlConfiguration();
        configuration.setDefaults(new YamlConfiguration());
        configuration.set("server-name", SERVER_NAME);
        configuration.set("economy.mode", "SYNC");
        configuration.set("redis.enabled", true);
        configuration.set("redis.host", backend.redisHost());
        configuration.set("redis.port", backend.redisPort());
        configuration.set("redis.password", backend.redisPassword() == null ? "" : backend.redisPassword());
        configuration.set("redis.database", backend.redisDatabase());
        configuration.set("redis.pool-size", 32);
        configuration.set("db-enabled", true);
        configuration.set("database.enabled", true);
        configuration.set("database.type", backend.sqlDialect().name().equals("POSTGRESQL") ? "postgresql" : "mysql");
        configuration.set("database.host", sql.hostForConfig());
        configuration.set("database.port", sql.port());
        configuration.set("database.database", sql.database());
        configuration.set("database.username", backend.sqlUser());
        configuration.set("database.password", backend.sqlPassword() == null ? "" : backend.sqlPassword());
        configuration.set("database.pool-size", 16);
        configuration.set("database.minimum-idle", 1);
        configuration.set("database.connection-timeout", 10_000);
        configuration.set("database.max-lifetime", 600_000L);
        configuration.set("database.idle-timeout", 300_000L);
        configuration.set("pubsub-enabled", false);
        configuration.set("queue-capacity", ECONOMY_QUEUE_CAPACITY);
        configuration.set("debug", false);
        currentPhase = "create baseline Bukkit plugin fixture";
        installServerFixture(server);
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class, Mockito.CALLS_REAL_METHODS);
        setPaperPluginField(plugin, "server", server);
        setPaperPluginField(plugin, "dataFolder", dataFolder.toFile());
        setPaperPluginField(plugin, "isEnabled", true);
        Mockito.doReturn(configuration).when(plugin).getConfig();
        Mockito.doNothing().when(plugin).saveDefaultConfig();
        Mockito.doReturn(logger).when(plugin).getLogger();
        Mockito.doAnswer(invocation -> {
            String resourceName = invocation.getArgument(0);
            InputStream resource = baselineLoader.getBaselineResourceAsStream(resourceName);
            if (resource == null) throw new IllegalStateException("Baseline JAR is missing a required Lua resource.");
            return resource;
        }).when(plugin).getResource(Mockito.anyString());
        return new TestPlugin(plugin, server);
    }

    private static Object paperFixtureInvocation(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "BaselineMacroPilotPaperFixture";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            };
        }
        return primitiveDefault(method.getReturnType());
    }

    private static void setPaperPluginField(JavaPlugin plugin, String name, Object value) {
        try {
            java.lang.reflect.Field field = JavaPlugin.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(plugin, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to initialize the baseline Paper plugin fixture.", failure);
        }
    }

    private static RunCounts runLoad(RuntimeState state, List<Account> accounts, long[] accountVersions,
            int durationSeconds, boolean measured, int sampleMinimum) throws Exception {
        return load(state, accounts, accountVersions, durationSeconds, measured, sampleMinimum, null);
    }

    private static Measurement measure(RuntimeState state, List<Account> accounts, long[] accountVersions,
            OpenLoopProfile profile) throws Exception {
        Measurement measurement = new Measurement(profile.measurementSecondsPerRate);
        state.measurement = measurement;
        measurement.startReceiptMonitor(state.redisManager, accounts);
        RunCounts counts = load(state, accounts, accountVersions, profile.measurementSecondsPerRate,
                true, profile.minimumMeasurementSamples, measurement);
        measurement.setCounts(counts);
        return measurement;
    }

    private static RunCounts load(RuntimeState state, List<Account> accounts, long[] accountVersions,
            int durationSeconds, boolean measured, int sampleMinimum, Measurement measurement) throws Exception {
        long offered = 0L;
        long accepted = 0L;
        long rejected = 0L;
        long moneyInvariantFailures = 0L;
        long authorityRejected = 0L;
        long start = System.nanoTime();
        long end = start + TimeUnit.SECONDS.toNanos(durationSeconds);
        long lastDurableCount = number(invoke(state.dbQueue, "getWrittenCount"));
        long durableAtMeasurementStart = measured ? lastDurableCount : 0L;
        List<Long> acceptedTimes = measured ? measurement.acceptedTimes() : new ArrayList<>();
        java.util.SplittableRandom random = new java.util.SplittableRandom(start ^ accounts.get(0).uuid().getLeastSignificantBits());
        Class<?> sourceType = state.economy.getClass().getClassLoader().loadClass("noietime.syncmoney.economy.EconomyEvent$EventSource");
        Object testSource = enumValue(sourceType, "TEST");
        Method deposit = state.economy.getClass().getMethod("deposit", UUID.class, BigDecimal.class, sourceType);
        while (System.nanoTime() < end) {
            if (measured && offered >= MAX_HISTOGRAM_SAMPLES) {
                throw new IllegalStateException("The measured sample count exceeded the bounded macro-pilot limit.");
            }
            checkReceiptMonitor(measurement);
            int queueSize = (int) number(invoke(state.economyQueue, "size"));
            if (queueSize >= MAX_ECONOMY_BACKLOG) {
                updateDurableSamples(state, measurement, durableAtMeasurementStart);
                Thread.sleep(1L);
                continue;
            }

            int accountIndex = random.nextInt(accounts.size());
            Account account = accounts.get(accountIndex);
            long expectedVersion = accountVersions[accountIndex] + 1L;
            long issued = System.nanoTime();
            PendingReceipt pending = measured ? measurement.addPending(account.uuid(), expectedVersion) : null;
            Object result;
            try {
                result = deposit.invoke(state.economy, account.uuid(), ONE_DOLLAR, testSource);
            } catch (InvocationTargetException transactionFailure) {
                throw propagate(transactionFailure.getCause());
            }
            long completed = System.nanoTime();
            offered++;
            if (measured) measurement.addResponseLatency(completed - issued);
            if (!(result instanceof BigDecimal balance) || balance.signum() < 0) {
                rejected++;
                authorityRejected++;
                if (pending != null) measurement.rejectPending(account.uuid(), expectedVersion, pending);
            } else {
                accountVersions[accountIndex] = expectedVersion;
                accepted++;
                acceptedTimes.add(completed);
                BigDecimal expectedBalance = BigDecimal.valueOf(expectedVersion).setScale(2);
                if (balance.compareTo(expectedBalance) != 0) moneyInvariantFailures++;
                if (pending != null) measurement.acceptPending(pending, completed);
            }
            long writtenNow = number(invoke(state.dbQueue, "getWrittenCount"));
            if (writtenNow > lastDurableCount) lastDurableCount = updateDurableSamples(state,
                    measurement, durableAtMeasurementStart);
        }
        long measuredNanos = Math.max(1L, System.nanoTime() - start);
        return new RunCounts(durationSeconds, measuredNanos, offered, accepted, rejected,
                authorityRejected, moneyInvariantFailures, acceptedTimes, lastDurableCount, sampleMinimum);
    }

    private static long updateDurableSamples(RuntimeState state, Measurement measurement,
            long durableAtMeasurementStart) throws Exception {
        long written = number(invoke(state.dbQueue, "getWrittenCount"));
        if (measurement != null) {
            long measurementWritten = Math.max(0L, written - durableAtMeasurementStart);
            measurement.collectDurableSamples(measurementWritten, System.nanoTime());
        }
        return written;
    }

    private static boolean awaitDrained(RuntimeState state, long expectedWritten, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (queuesDrained(state) && number(invoke(state.dbQueue, "getWrittenCount")) == expectedWritten) return true;
            Thread.sleep(10L);
        }
        return queuesDrained(state) && number(invoke(state.dbQueue, "getWrittenCount")) == expectedWritten;
    }

    private static boolean queuesDrained(RuntimeState state) throws Exception {
        return Boolean.TRUE.equals(invoke(state.economyQueue, "isEmpty"))
                && Boolean.TRUE.equals(invoke(state.dbQueue, "isEmpty"));
    }

    private static boolean consumerTerminated(Object consumer) {
        try {
            Object executor = field(consumer, "executor");
            return executor == null || ((ExecutorService) executor).isTerminated();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static ObservationCheck verifyFinalState(ClassLoader loader, RuntimeState state,
            List<Account> accounts, long[] accountVersions, Measurement measurement,
            long totalAccepted, long persisted) throws Exception {
        long moneyFailures = measurement.moneyInvariantFailures();
        long versionConflicts = 0L;
        Map<UUID, Integer> accountIndexes = new HashMap<>();
        for (int index = 0; index < accounts.size(); index++) accountIndexes.put(accounts.get(index).uuid(), index);
        Object jedis = invoke(state.redisManager, "getResource");
        try {
            for (int index = 0; index < accounts.size(); index++) {
                Account account = accounts.get(index);
                List<?> values = redisMget(jedis, List.of("syncmoney:balance:" + account.uuid(),
                        "syncmoney:version:" + account.uuid()));
                BigDecimal actualBalance = values.get(0) == null ? BigDecimal.ZERO : new BigDecimal(values.get(0).toString());
                long actualVersion = values.get(1) == null ? 0L : Long.parseLong(values.get(1).toString());
                BigDecimal expectedBalance = BigDecimal.valueOf(accountVersions[index]).setScale(2);
                if (actualVersion != accountVersions[index]) versionConflicts++;
                if (actualBalance.compareTo(expectedBalance) != 0) moneyFailures++;
            }
        } finally {
            invoke(jedis, "close");
        }

        Map<UUID, SqlRow> sqlRows = readRunRows(state.databaseManager, accounts);
        long expectedRows = 0L;
        boolean sqlMatch = true;
        for (int index = 0; index < accounts.size(); index++) {
            Account account = accounts.get(index);
            long version = accountVersions[index];
            SqlRow row = sqlRows.get(account.uuid());
            if (version == 0L) {
                if (row != null) sqlMatch = false;
                continue;
            }
            expectedRows++;
            BigDecimal balance = BigDecimal.valueOf(version).setScale(2);
            if (row == null || row.version() != version || row.balance().compareTo(balance) != 0
                    || !account.playerName().equals(row.playerName()) || !SERVER_NAME.equals(row.lastServer())) {
                sqlMatch = false;
                if (row != null && row.version() != version) versionConflicts++;
                if (row != null && row.balance().compareTo(balance) != 0) moneyFailures++;
            }
        }
        if (sqlRows.size() != expectedRows || persisted != totalAccepted) sqlMatch = false;
        return new ObservationCheck(moneyFailures, versionConflicts, sqlMatch, expectedRows, sqlRows.size());
    }

    private static Map<UUID, SqlRow> readRunRows(Object databaseManager, List<Account> accounts) throws Exception {
        Map<UUID, SqlRow> rows = new HashMap<>();
        try (Connection connection = (Connection) invoke(databaseManager, "getConnection")) {
            for (int offset = 0; offset < accounts.size(); offset += SQL_QUERY_BATCH) {
                int limit = Math.min(accounts.size(), offset + SQL_QUERY_BATCH);
                String placeholders = String.join(",", Collections.nCopies(limit - offset, "?"));
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_uuid, player_name, balance, version, last_server FROM players WHERE player_uuid IN ("
                                + placeholders + ")")) {
                    for (int index = offset; index < limit; index++) statement.setString(index - offset + 1,
                            accounts.get(index).uuid().toString());
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            UUID uuid = UUID.fromString(result.getString("player_uuid"));
                            rows.put(uuid, new SqlRow(result.getString("player_name"), result.getBigDecimal("balance"),
                                    result.getLong("version"), result.getString("last_server")));
                        }
                    }
                }
            }
        }
        return rows;
    }

    private static FailureCounts consumerFailures(Object consumer) throws Exception {
        Object failedEvents = field(consumer, "failedEvents");
        if (!(failedEvents instanceof Map<?, ?> failed)) {
            throw new IllegalStateException("The verified 1.3.2 event consumer does not expose its failed-event map.");
        }
        return new FailureCounts("UNAVAILABLE_IN_1_3_2", null, null, failed.size());
    }

    private static boolean keysAndRowsAreAbsent(Object redisManager,
            Object databaseManager, List<Account> accounts) throws Exception {
        Object jedis = invoke(redisManager, "getResource");
        try {
            for (int offset = 0; offset < accounts.size(); offset += REDIS_MGET_BATCH / 2) {
                int limit = Math.min(accounts.size(), offset + REDIS_MGET_BATCH / 2);
                List<String> keys = new ArrayList<>((limit - offset) * 2);
                for (int index = offset; index < limit; index++) {
                    UUID uuid = accounts.get(index).uuid();
                    keys.add("syncmoney:balance:" + uuid);
                    keys.add("syncmoney:version:" + uuid);
                }
                if (redisMget(jedis, keys).stream().anyMatch(Objects::nonNull)) return false;
            }
        } finally {
            invoke(jedis, "close");
        }
        return readRunRows(databaseManager, accounts).isEmpty();
    }

    private static boolean cleanupExactRunData(RuntimeState state, List<Account> accounts) {
        if (!writersStopped(state)) return false;
        boolean complete = true;
        if (state.redisKeysOwned && state.redisManager != null) {
            Object jedis = null;
            try {
                jedis = invoke(state.redisManager, "getResource");
                Method delete = findMethod(jedis.getClass(), "del", String[].class);
                for (int offset = 0; offset < accounts.size(); offset += 500) {
                    int limit = Math.min(accounts.size(), offset + 500);
                    String[] keys = new String[(limit - offset) * 2];
                    int target = 0;
                    for (int index = offset; index < limit; index++) {
                        UUID uuid = accounts.get(index).uuid();
                        keys[target++] = "syncmoney:balance:" + uuid;
                        keys[target++] = "syncmoney:version:" + uuid;
                    }
                    delete.invoke(jedis, (Object) keys);
                }
            } catch (Throwable failure) {
                complete = false;
            } finally {
                closeQuietly(jedis, "close");
            }
        }
        if (state.sqlRowsOwned && state.databaseManager != null) {
            try (Connection connection = (Connection) invoke(state.databaseManager, "getConnection")) {
                for (int offset = 0; offset < accounts.size(); offset += SQL_QUERY_BATCH) {
                    int limit = Math.min(accounts.size(), offset + SQL_QUERY_BATCH);
                    String placeholders = String.join(",", Collections.nCopies(limit - offset, "?"));
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM players WHERE player_uuid IN (" + placeholders + ")")) {
                        for (int index = offset; index < limit; index++) statement.setString(index - offset + 1,
                                accounts.get(index).uuid().toString());
                        statement.executeUpdate();
                    }
                }
            } catch (Throwable failure) {
                complete = false;
            }
        }
        if (complete) {
            try {
                complete = keysAndRowsAreAbsent(state.redisManager, state.databaseManager, accounts);
            } catch (Throwable failure) {
                complete = false;
            }
        }
        return complete;
    }

    private static BackendMetadata backendMetadata(Object redisManager,
            Object databaseManager, String dialect) throws Exception {
        Object jedis = invoke(redisManager, "getResource");
        String redisVersion;
        try {
            String info = (String) invoke(jedis, "info", "server");
            redisVersion = Arrays.stream(info.split("\\R"))
                    .filter(line -> line.startsWith("redis_version:"))
                    .map(line -> line.substring("redis_version:".length()).trim())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Redis server version was not observed."));
        } finally {
            invoke(jedis, "close");
        }
        try (Connection connection = (Connection) invoke(databaseManager, "getConnection")) {
            return new BackendMetadata(redisVersion, dialect, connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getDatabaseProductVersion());
        }
    }

    private static void requireScratchDatabaseReady(Object databaseManager) throws Exception {
        try (Connection connection = (Connection) invoke(databaseManager, "getConnection");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT player_uuid FROM players WHERE 1 = 0")) {
            // The scratch database and schema must have been provisioned before the producer starts.
        }
        try (Connection connection = (Connection) invoke(databaseManager, "getConnection");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM players")) {
            if (!result.next() || result.getLong(1) != 0L) {
                throw new IllegalArgumentException("The isolated T19 scratch players table must be empty before the run.");
            }
        }
    }

    private static void requireEmptyScratchRedis(Object redisManager) throws Exception {
        Object jedis = invoke(redisManager, "getResource");
        try {
            if (number(invoke(jedis, "dbSize")) != 0L) {
                throw new IllegalArgumentException("The isolated T19 scratch Redis database must be empty before the run.");
            }
        } finally {
            invoke(jedis, "close");
        }
    }

    private static Map<String, Object> manifest(Arguments arguments, VerifiedBaseline baseline,
            BaselineBuildEvidence buildEvidence,
            HarnessProvenance harness,
            BenchmarkEnvironment.Snapshot launchEnvironment,
            OpenLoopProfile profile, OpenLoopProfile.BackendSettings backend, BackendMetadata metadata,
            String runId, Measurement measurement, long persisted, long durableAtMeasurementStart,
            RunCounts warmup, long[] accountVersions, ObservationCheck check, long dropped,
            boolean overflow, FailureCounts failures, boolean economyQueueDrained,
            boolean databaseQueueDrained, boolean cleanupComplete, boolean valid) {
        RunCounts counts = measurement.counts();
        int minimumSamples = Math.max(100_000, profile.minimumMeasurementSamples);
        long actualDurationMillis = TimeUnit.NANOSECONDS.toMillis(counts.measuredNanos());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", 2);
        output.put("measurementKind", MEASUREMENT_KIND);
        output.put("runId", runId);
        output.put("createdAt", Instant.now().toString());
        output.put("sourceSHA", baseline.commitSha());
        output.put("baselineSHA", baseline.commitSha());
        output.put("baselineArtifactSHA256", baseline.artifactSha256());
        output.put("baselineBuildJavaVersion", buildEvidence.javaVersion());
        output.put("baselineBuildAttestationSHA256", buildEvidence.attestationSha256());
        output.put("harnessSourceSHA", harness.sourceSha());
        output.put("harnessSourceTreeSHA256", harness.sourceTreeSha256());
        output.put("loadTestRuntimeClasspathSHA256", harness.runtimeClasspathSha256());
        output.put("runtimeJavaVersion", launchEnvironment.javaVersion());
        addEnvironmentMetadata(output, launchEnvironment);
        output.put("profileId", profile.profileId);
        output.put("profileSHA256", profile.sha256);
        output.put("durationSeconds", counts.durationSeconds());
        output.put("measuredDurationMillis", actualDurationMillis);
        output.put("measuredDurationNanos", counts.measuredNanos());
        output.put("warmupSeconds", warmup.durationSeconds());
        output.put("warmupAccepted", warmup.accepted());
        output.put("warmupPersisted", warmup.accepted());
        output.put("persistedBeforeMeasurement", durableAtMeasurementStart);
        output.put("accountCount", ACCOUNT_COUNT);
        output.put("accountDistribution", "uniform");
        output.put("producerThreads", 1);
        output.put("offeredRateModel", "bounded-queue-saturation");
        output.put("queueBacklogLimit", MAX_ECONOMY_BACKLOG);
        output.put("sampleCount", Map.of("response", counts.offered(), "receipt", counts.accepted(), "durable", persisted));
        output.put("responseHistogram", histogram(measurement.responseLatencies(), counts.offered(), counts.measuredNanos(), minimumSamples, true));
        output.put("receiptHistogram", histogram(measurement.receiptLatencies(), counts.offered(), counts.measuredNanos(), minimumSamples, false));
        output.put("durableHistogram", histogram(measurement.durableLatencies(), counts.offered(), counts.measuredNanos(), minimumSamples, false));
        output.put("offered", counts.offered());
        output.put("accepted", counts.accepted());
        output.put("rejected", counts.rejected());
        output.put("persisted", persisted);
        output.put("moneyInvariantFailures", counts.moneyInvariantFailures() + check.moneyInvariantFailures());
        output.put("versionConflicts", check.versionConflicts());
        output.put("authorityRejected", counts.authorityRejected());
        output.put("legacyFailureTaxonomy", failures.taxonomyStatus());
        output.put("legacyFailedEvents", failures.legacyFailedEvents());
        output.put("ambiguous", failures.ambiguous() == null ? JsonNull.INSTANCE : failures.ambiguous());
        output.put("quarantine", failures.quarantine() == null ? JsonNull.INSTANCE : failures.quarantine());
        output.put("droppedEvents", dropped);
        output.put("overflowRecords", overflow ? 1 : 0);
        output.put("economyQueueDrained", economyQueueDrained);
        output.put("databaseQueueDrained", databaseQueueDrained);
        output.put("exactRedisAndSqlRowsVerified", check.sqlRowsMatch());
        output.put("expectedSqlRows", check.expectedSqlRows());
        output.put("observedSqlRows", check.observedSqlRows());
        output.put("cleanupComplete", cleanupComplete);
        output.put("valid", valid);
        output.put("latencySemantics", Map.of(
                "response", "synchronous-deposit-call-response",
                "receipt", "accepted-to-redis-version-poll-observation-upper-bound",
                "receiptMayCoalesce", true,
                "durable", "accepted-to-sql-written-count-poll-observation-upper-bound",
                "durableMayCoalesce", true,
                "matchedLatencyComparisonEligible", false));
        output.put("dependencies", Map.of("redisVersion", metadata.redisVersion(), "sqlDialect", metadata.sqlDialect(),
                "sqlProduct", metadata.sqlProduct(), "sqlVersion", metadata.sqlVersion()));
        output.put("notes", List.of(
                "Real 1.3.2 shaded plugin classes were loaded child-first; each measured TEST deposit used EconomyFacade, EconomyWriteQueue, EconomyEventConsumer, the Redis Lua receipt, DbWriteQueue, DbWriterConsumer, and SQL persistence.",
                "SYNC authority used Redis and SQL; Pub/Sub and optional collaborators were disabled. A mocked Paper server supplied only the global scheduler required by CacheManager.",
                "One producer thread selected uniformly from 10,000 named accounts. Admission paused at the observed queue backlog limit to avoid dropped events; this measures bounded queue saturation, not open-loop latency.",
                "Receipt samples are upper bounds observed from per-account Redis version keys; one observation can coalesce several accepted versions. Durable samples are upper bounds observed from the SQL writer's committed-write count; one observation can coalesce several commits. Their histograms are descriptive only and are excluded from matched latency comparisons.",
                "Only the accepted transactions-per-second rate calibrates the open-loop arrival schedule; baseline receipt and durable latencies are not used for latency comparison.",
                "The 1.3.2 failed-event map has no ambiguity/quarantine classification. Those categories are reported as null; validity requires the observable legacy failed-event count to be zero.",
                "The run cleaned only its generated Redis balance/version keys and SQL player rows; it did not flush either backend."));
        return output;
    }

    static String serializeManifest(Map<String, Object> manifest) {
        return GSON.toJson(manifest);
    }

    static void addEnvironmentMetadata(Map<String, Object> manifest, BenchmarkEnvironment.Snapshot environment) {
        manifest.put("environment", environment.fields());
        manifest.put("environmentFingerprintSHA256", environment.fingerprint());
    }

    private static Map<String, Object> histogram(List<Long> samples, long offered, long measuredNanos,
            int minimumSamples, boolean perOperationTiming) {
        Histogram raw = new Histogram(MAX_LATENCY_MICROS, 3);
        long intervalMicros = Math.max(1L, TimeUnit.NANOSECONDS.toMicros(measuredNanos) / Math.max(1L, offered));
        Histogram corrected = new Histogram(MAX_LATENCY_MICROS, 3);
        for (long sample : samples) {
            long micros = Math.max(1L, TimeUnit.NANOSECONDS.toMicros(sample));
            if (micros > MAX_LATENCY_MICROS) throw new IllegalStateException("Observed latency exceeded the bounded histogram range.");
            raw.recordValue(micros);
            if (perOperationTiming) corrected.recordValueWithExpectedInterval(micros, intervalMicros);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("unit", "microseconds");
        summary.put("rawCount", raw.getTotalCount());
        summary.put("raw", Map.of("p50", raw.getValueAtPercentile(50), "p95", raw.getValueAtPercentile(95),
                "p99", raw.getValueAtPercentile(99), "p99.9", raw.getValueAtPercentile(99.9), "max", raw.getMaxValue()));
        if (perOperationTiming) {
            if (raw.getTotalCount() >= minimumSamples) summary.put("p99.9Confidence95", HistogramQuantileConfidence.p999(raw).asMap());
            summary.put("correctedCount", corrected.getTotalCount());
            summary.put("expectedIntervalCorrected", Map.of("p99.9", corrected.getValueAtPercentile(99.9)));
            summary.put("expectedIntervalMicros", intervalMicros);
        } else {
            summary.put("latencySemantics", "polling-observation-upper-bound-may-coalesce");
        }
        return summary;
    }

    static SqlEndpoint parseSqlEndpoint(String jdbcUrl, Object dialectValue) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) throw new IllegalArgumentException("SQL URL is required.");
        String dialect = String.valueOf(dialectValue).toUpperCase(java.util.Locale.ROOT);
        String location = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : "";
        URI uri;
        try {
            uri = URI.create(location);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("SQL URL must be a plain JDBC host/database URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean mysql = scheme.equals("mysql") || scheme.equals("mariadb");
        boolean postgresql = scheme.equals("postgresql") || scheme.equals("postgres");
        if (!(mysql && dialect.equals("MYSQL")) && !(postgresql && dialect.equals("POSTGRESQL"))) {
            throw new IllegalArgumentException("SQL URL scheme must match the configured MYSQL or POSTGRESQL dialect.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("SQL URL must omit embedded credentials and query parameters.");
        }
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!database.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("SQL URL must name one pre-provisioned database with a portable identifier.");
        }
        int port = uri.getPort() < 0 ? (postgresql ? 5432 : 3306) : uri.getPort();
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("SQL URL port is outside the supported range.");
        return new SqlEndpoint(uri.getHost(), port, database);
    }

    static void validateDisposableBackend(OpenLoopProfile.BackendSettings backend, SqlEndpoint sql,
            String acknowledgement) {
        if (!SCRATCH_ACK_VALUE.equals(acknowledgement)) {
            throw new IllegalArgumentException("Set " + SCRATCH_ACK_ENV
                    + " to its documented value to acknowledge the isolated T19 scratch backends.");
        }
        if (!SCRATCH_REDIS_HOST.equals(backend.redisHost()) || backend.redisPort() != SCRATCH_REDIS_PORT
                || backend.redisDatabase() != SCRATCH_REDIS_DATABASE) {
            throw new IllegalArgumentException("Redis target must be the isolated T19 loopback scratch instance.");
        }
        if (!SCRATCH_SQL_HOST.equals(sql.host()) || sql.port() != SCRATCH_SQL_PORT
                || !SCRATCH_SQL_DATABASE.equals(sql.database()) || backend.sqlDialect() != SqlDialect.MYSQL) {
            throw new IllegalArgumentException("SQL target must be the isolated T19 MariaDB scratch database.");
        }
    }

    static BaselineBuildEvidence readBuildAttestation(Path path, String expectedBaselineSha,
            String expectedArtifactSha256) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("--baseline-build-attestation must name the receipt produced by runBaselineMacroPilot.");
        }
        JsonObject receipt;
        try {
            receipt = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Baseline build attestation is malformed.");
        }
        String baselineSha = jsonString(receipt, "baselineSHA");
        String artifactSha = jsonString(receipt, "baselineArtifactSHA256");
        String javaVersion = jsonString(receipt, "baselineBuildJavaVersion");
        if (!expectedBaselineSha.equals(baselineSha) || !expectedArtifactSha256.equals(artifactSha)) {
            throw new IllegalArgumentException("Baseline build attestation does not match the verified baseline source and artifact.");
        }
        if (!javaVersion.matches("21(?:\\.\\d+(?:\\.\\d+)?(?:[-+][A-Za-z0-9._-]+)?)")) {
            throw new IllegalArgumentException("Baseline build attestation must report the observed Java 21 builder version.");
        }
        return new BaselineBuildEvidence(javaVersion, sha256(path));
    }

    private static String jsonString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("Baseline build attestation is missing a required text field.");
        }
        return object.get(name).getAsString();
    }

    static boolean canCleanupOwnedData(boolean eventConsumerTerminated, boolean databaseWriterTerminated) {
        return eventConsumerTerminated && databaseWriterTerminated;
    }

    private static HarnessProvenance requireHarnessProvenance() {
        String sourceSha = System.getProperty("syncmoney.benchmarks.sourceSha");
        String sourceTreeSha256 = System.getProperty("syncmoney.benchmarks.sourceTreeSha256");
        String runtimeClasspathSha256 = System.getProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256");
        if (sourceSha == null || !sourceSha.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")
                || sourceTreeSha256 == null || !sourceTreeSha256.matches("[a-f0-9]{64}")
                || runtimeClasspathSha256 == null || !runtimeClasspathSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("The baseline macro pilot must be launched by the provenance-bound Gradle task.");
        }
        return new HarnessProvenance(sourceSha, sourceTreeSha256, runtimeClasspathSha256);
    }

    private static boolean writersStopped(RuntimeState state) {
        boolean eventConsumerTerminated = state.eventConsumer == null || consumerTerminated(state.eventConsumer);
        boolean databaseWriterTerminated = state.dbThread == null || !state.dbThread.isAlive();
        boolean receiptMonitorTerminated = state.measurement == null || state.measurement.receiptMonitorTerminated();
        return canDisposeRuntime(eventConsumerTerminated, databaseWriterTerminated, receiptMonitorTerminated);
    }

    static boolean canDisposeRuntime(boolean eventConsumerStopped, boolean databaseWriterStopped,
            boolean receiptMonitorStopped) {
        return eventConsumerStopped && databaseWriterStopped && receiptMonitorStopped;
    }

    static boolean shouldDeleteSupportFiles(boolean backgroundWorkersStopped, boolean backendCleanupComplete) {
        return backgroundWorkersStopped && backendCleanupComplete;
    }

    static void installServerFixture(Server server) {
        try {
            java.lang.reflect.Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to install the isolated Paper server fixture.", failure);
        }
    }

    static List<String> runShutdownSteps(List<ShutdownStep> steps) {
        List<String> failures = new ArrayList<>();
        for (ShutdownStep step : steps) {
            try {
                step.action().run();
            } catch (Throwable ignored) {
                failures.add(step.name());
            }
        }
        return List.copyOf(failures);
    }

    private static ShutdownOutcome shutdownRuntime(RuntimeState state, List<Account> accounts, Path dataFolder,
            Server previousServer, ClassLoader previousContextLoader) {
        List<ShutdownStep> stopSteps = new ArrayList<>();
        if (state.economy != null) stopSteps.add(new ShutdownStep("economy stop",
                () -> shutdownLegacyEconomyFacade(state.economy)));
        if (state.eventConsumer != null) stopSteps.add(new ShutdownStep("event consumer stop", () -> invoke(state.eventConsumer, "shutdown")));
        if (state.measurement != null) stopSteps.add(new ShutdownStep("receipt monitor stop", state.measurement::stopReceiptMonitor));
        if (state.dbConsumer != null) stopSteps.add(new ShutdownStep("database consumer stop", () -> invoke(state.dbConsumer, "stop")));
        if (state.dbThread != null) stopSteps.add(new ShutdownStep("database writer join", () -> {
            if (state.dbThread.isAlive()) state.dbThread.join(30_000L);
        }));

        List<String> failures = new ArrayList<>(runShutdownSteps(stopSteps));
        boolean backgroundWorkersStopped = writersStopped(state);
        if (!backgroundWorkersStopped) {
            failures.add("background worker termination");
            return new ShutdownOutcome(failures);
        }

        boolean backendCleanupComplete = !state.redisKeysOwned && !state.sqlRowsOwned;
        if (!backendCleanupComplete && state.redisManager != null && state.databaseManager != null) {
            try {
                backendCleanupComplete = cleanupExactRunData(state, accounts);
            } catch (Throwable ignored) {
                backendCleanupComplete = false;
            }
        }
        if (backendCleanupComplete) {
            state.redisKeysOwned = false;
            state.sqlRowsOwned = false;
        } else {
            failures.add("owned backend cleanup");
        }

        failures.addAll(runShutdownSteps(List.of(
                new ShutdownStep("economy close", () -> close(state.economy, "shutdown")),
                new ShutdownStep("database manager close", () -> close(state.databaseManager, "close")),
                new ShutdownStep("Redis manager close", () -> close(state.redisManager, "close")),
                new ShutdownStep("server restore", () -> installServerFixture(previousServer)),
                new ShutdownStep("context classloader restore", () -> {
                    if (state.baselineLoader != null && previousContextLoader != null) {
                        Thread.currentThread().setContextClassLoader(previousContextLoader);
                    }
                }),
                new ShutdownStep("baseline classloader close", () -> close(state.baselineLoader, "close"))
        )));

        if (shouldDeleteSupportFiles(backgroundWorkersStopped, backendCleanupComplete)
                && !deleteRunSupportFiles(dataFolder)) {
            failures.add("support file cleanup");
        }
        return new ShutdownOutcome(failures);
    }

    private static void close(Object receiver, String method) throws Exception {
        if (receiver != null) invoke(receiver, method);
    }

    private static void shutdownLegacyEconomyFacade(Object economy) throws Exception {
        invoke(economy, "shutdown");
    }

    private static VerifiedBaseline verifyBaseline(Arguments arguments) throws Exception {
        Path checkout = arguments.baselineCheckout().toRealPath();
        if (!Files.isDirectory(checkout)) throw new IllegalArgumentException("--baseline-checkout must name an existing checkout.");
        String root = runCommand(List.of("git", "-C", checkout.toString(), "rev-parse", "--show-toplevel"), checkout);
        String sha = runCommand(List.of("git", "-C", checkout.toString(), "rev-parse", "HEAD"), checkout);
        String status = runCommand(List.of("git", "-C", checkout.toString(), "status", "--porcelain", "--untracked-files=all"), checkout);
        if (!Path.of(root).toRealPath().equals(checkout) || !sha.equals(BASELINE_SHA) || !status.isEmpty()) {
            throw new IllegalArgumentException("--baseline-checkout must be a clean checkout at the verified 1.3.2 commit " + BASELINE_SHA + ".");
        }
        Path artifact = arguments.baselineArtifact().toRealPath();
        Path expected = checkout.resolve("build/libs").resolve(BASELINE_ARTIFACT_NAME).toRealPath();
        if (!Files.isRegularFile(artifact) || !Files.isSameFile(artifact, expected)) {
            throw new IllegalArgumentException("--baseline-artifact must be the freshly built Syncmoney-1.3.2.jar inside the verified checkout.");
        }
        String artifactSha = sha256(artifact);
        if (!artifactSha.equals(BASELINE_ARTIFACT_SHA256)) {
            throw new IllegalArgumentException("--baseline-artifact does not match the verified 1.3.2 shaded artifact SHA-256.");
        }
        return new VerifiedBaseline(sha, artifactSha, checkout, artifact);
    }

    private static String runCommand(List<String> command, Path workingDirectory) throws Exception {
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalArgumentException("Unable to verify the baseline checkout with Git.");
        }
        return new String(output, StandardCharsets.UTF_8).trim();
    }

    private static String sha256(Path path) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private static Object construct(ClassLoader loader, String className, List<Object> preferredArguments) throws Exception {
        Class<?> type = Class.forName(className, true, loader);
        List<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(type.getDeclaredConstructors()));
        constructors.sort(Comparator.comparingInt((Constructor<?> ctor) -> ctor.getParameterCount()).reversed());
        for (Constructor<?> constructor : constructors) {
            Object[] adapted = adaptArguments(constructor.getParameterTypes(), preferredArguments);
            if (adapted == null) continue;
            constructor.setAccessible(true);
            try {
                return constructor.newInstance(adapted);
            } catch (InvocationTargetException invocationFailure) {
                throw propagate(invocationFailure.getCause());
            }
        }
        throw new IllegalStateException("Baseline runtime constructor contract was not found for " + className + ".");
    }

    private static Object[] adaptArguments(Class<?>[] parameterTypes, List<Object> preferredArguments) {
        Object[] result = new Object[parameterTypes.length];
        boolean[] used = new boolean[preferredArguments.size()];
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameter = parameterTypes[index];
            boolean assigned = false;
            for (int candidate = 0; candidate < preferredArguments.size(); candidate++) {
                Object value = preferredArguments.get(candidate);
                if (!used[candidate] && value != null && wrap(parameter).isInstance(value)) {
                    result[index] = value;
                    used[candidate] = true;
                    assigned = true;
                    break;
                }
            }
            if (parameter.isPrimitive() && !assigned) result[index] = primitiveDefault(parameter);
            if (!assigned && parameter.getName().equals("org.bukkit.plugin.Plugin")) return null;
        }
        return result;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static Object invoke(Object receiver, String name, Object... arguments) throws Exception {
        if (receiver == null) throw new IllegalStateException("Required baseline runtime object is unavailable.");
        Method method = findCompatibleMethod(receiver.getClass(), name, arguments);
        method.setAccessible(true);
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException invocationFailure) {
            throw propagate(invocationFailure.getCause());
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] arguments) throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean match = true;
                for (int index = 0; index < parameters.length; index++) {
                    if (arguments[index] != null && !wrap(parameters[index]).isInstance(arguments[index])) {
                        match = false;
                        break;
                    }
                }
                if (match) return method;
            }
        }
        throw new NoSuchMethodException("Baseline runtime method is unavailable: " + name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try { return cursor.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("Baseline runtime method is unavailable: " + name);
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private static Object field(Object receiver, String name) throws Exception {
        for (Class<?> cursor = receiver.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            try {
                var field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException("Baseline runtime state is unavailable.");
    }

    private static List<?> redisMget(Object jedis, List<String> keys) throws Exception {
        Method method = findMethod(jedis.getClass(), "mget", String[].class);
        Object response = method.invoke(jedis, (Object) keys.toArray(String[]::new));
        if (!(response instanceof List<?> values) || values.size() != keys.size()) {
            throw new IllegalStateException("Redis returned an incomplete exact-key read.");
        }
        return values;
    }

    private static Object enumValue(Class<?> enumClass, String value) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) throw new IllegalArgumentException("Baseline event source is not an enum.");
        return Arrays.stream(constants).filter(constant -> ((Enum<?>) constant).name().equals(value))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Baseline TEST event source is unavailable."));
    }

    private static void checkReceiptMonitor(Measurement measurement) {
        if (measurement == null) return;
        Throwable failure = measurement.monitorFailure();
        if (failure != null) throw new IllegalStateException("Redis receipt observation failed.");
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }

    private static List<Account> accounts(String runId) {
        List<Account> accounts = new ArrayList<>(ACCOUNT_COUNT);
        for (int index = 0; index < ACCOUNT_COUNT; index++) {
            UUID uuid = UUID.nameUUIDFromBytes((runId + ":" + index).getBytes(StandardCharsets.UTF_8));
            accounts.add(new Account(uuid, "Pilot" + String.format(java.util.Locale.ROOT, "%05d", index)));
        }
        return List.copyOf(accounts);
    }

    private static Logger quietLogger(String runId) {
        Logger logger = Logger.getLogger("syncmoney.baseline-macro-pilot." + runId);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        for (String name : List.of("com.zaxxer.hikari", "redis.clients.jedis", "com.mysql", "org.postgresql")) {
            Logger.getLogger(name).setLevel(Level.OFF);
        }
        return logger;
    }

    private static void closeQuietly(Object receiver, String method) {
        if (receiver == null) return;
        try { invoke(receiver, method); } catch (Throwable ignored) { }
    }

    private static boolean deleteRunSupportFiles(Path dataFolder) {
        try {
            if (Files.exists(dataFolder)) {
                try (var paths = Files.walk(dataFolder)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            return !Files.exists(dataFolder);
        } catch (IOException ignored) {
            return false;
        }
    }

    record Arguments(Path baselineCheckout, Path baselineArtifact, Path baselineBuildAttestation,
            Path profile, Path output) { }
    record SqlEndpoint(String host, int port, String database) {
        String hostForConfig() { return host.contains(":") ? "[" + host + "]" : host; }
    }
    private record VerifiedBaseline(String commitSha, String artifactSha256, Path checkout, Path artifact) { }
    record BaselineBuildEvidence(String javaVersion, String attestationSha256) { }
    private record HarnessProvenance(String sourceSha, String sourceTreeSha256, String runtimeClasspathSha256) { }
    private record TestPlugin(JavaPlugin plugin, Server server) { }
    private record Account(UUID uuid, String playerName) { }
    private record BackendMetadata(String redisVersion, String sqlDialect, String sqlProduct, String sqlVersion) { }
    private record SqlRow(String playerName, BigDecimal balance, long version, String lastServer) { }
    private record ObservationCheck(long moneyInvariantFailures, long versionConflicts,
            boolean sqlRowsMatch, long expectedSqlRows, long observedSqlRows) { }
    private record FailureCounts(String taxonomyStatus, Long ambiguous, Long quarantine, long legacyFailedEvents) { }
    @FunctionalInterface
    interface ShutdownAction { void run() throws Throwable; }
    record ShutdownStep(String name, ShutdownAction action) { }
    private record ShutdownOutcome(List<String> failures) {
        boolean complete() { return failures.isEmpty(); }
        String failureMessage() {
            return "Baseline macro-pilot shutdown did not complete: " + String.join(", ", failures) + ".";
        }
    }
    private record RunCounts(int durationSeconds, long measuredNanos, long offered, long accepted,
            long rejected, long authorityRejected, long moneyInvariantFailures, List<Long> acceptedTimes,
            long writtenCount, int sampleMinimum) { }

    private static final class RuntimeState {
        private ClassLoader baselineLoader;
        private Object config;
        private Measurement measurement;
        private Object redisManager;
        private Object cacheManager;
        private Object databaseManager;
        private Object economy;
        private Object eventConsumer;
        private Object dbConsumer;
        private Object economyQueue;
        private Object dbQueue;
        private Object overflowLog;
        private Object nameResolver;
        private List<Account> accounts = List.of();
        private Thread dbThread;
        private Logger runtimeLogger;
        private boolean redisKeysOwned;
        private boolean sqlRowsOwned;
    }

    private static final class Measurement {
        private final int durationSeconds;
        private final List<Long> response = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> receipts = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> durable = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> acceptedTimes = new ArrayList<>();
        private final ConcurrentMap<UUID, ConcurrentMap<Long, PendingReceipt>> pending = new ConcurrentHashMap<>();
        private final AtomicBoolean monitoring = new AtomicBoolean(true);
        private final AtomicReference<Throwable> monitorFailure = new AtomicReference<>();
        private volatile Thread monitorThread;
        private volatile RunCounts counts;
        private volatile long moneyInvariantFailures;
        private long durableSamplesCollected;

        private Measurement(int durationSeconds) { this.durationSeconds = durationSeconds; }

        private void startReceiptMonitor(Object redisManager, List<Account> accounts) {
            monitorThread = new Thread(() -> pollRedisReceipts(redisManager), "Syncmoney-BaselineMacroPilot-Receipts");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        private void pollRedisReceipts(Object redisManager) {
            Object jedis = null;
            try {
                jedis = invoke(redisManager, "getResource");
                while (monitoring.get() || !pending.isEmpty()) {
                    List<Map.Entry<UUID, ConcurrentMap<Long, PendingReceipt>>> buckets = new ArrayList<>(pending.entrySet());
                    if (buckets.isEmpty()) {
                        Thread.sleep(5L);
                        continue;
                    }
                    List<UUID> uuids = new ArrayList<>(buckets.size());
                    List<String> keys = new ArrayList<>(buckets.size());
                    for (Map.Entry<UUID, ConcurrentMap<Long, PendingReceipt>> bucket : buckets) {
                        if (bucket.getValue().isEmpty()) continue;
                        uuids.add(bucket.getKey());
                        keys.add("syncmoney:version:" + bucket.getKey());
                    }
                    for (int offset = 0; offset < uuids.size(); offset += REDIS_MGET_BATCH) {
                        int limit = Math.min(uuids.size(), offset + REDIS_MGET_BATCH);
                        List<?> versions = redisMget(jedis, keys.subList(offset, limit));
                        long observedAt = System.nanoTime();
                        for (int index = offset; index < limit; index++) {
                            Object versionText = versions.get(index - offset);
                            if (versionText == null) continue;
                            long version = Long.parseLong(versionText.toString());
                            ConcurrentMap<Long, PendingReceipt> receiptsForAccount = pending.get(uuids.get(index));
                            if (receiptsForAccount == null) continue;
                            for (Map.Entry<Long, PendingReceipt> receipt : new TreeMap<>(receiptsForAccount).entrySet()) {
                                if (receipt.getKey() > version) break;
                                if (receipt.getValue().accepted() && receiptsForAccount.remove(receipt.getKey(), receipt.getValue())) {
                                    receipts.add(observedAt - receipt.getValue().acceptedAtNanos());
                                }
                            }
                            if (receiptsForAccount.isEmpty()) pending.remove(uuids.get(index), receiptsForAccount);
                        }
                    }
                    Thread.sleep(5L);
                }
            } catch (Throwable failure) {
                monitorFailure.compareAndSet(null, failure);
                monitoring.set(false);
            } finally {
                closeQuietly(jedis, "close");
            }
        }

        private PendingReceipt addPending(UUID uuid, long version) {
            PendingReceipt result = new PendingReceipt();
            pending.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(version, result);
            return result;
        }

        private void acceptPending(PendingReceipt receipt, long acceptedAtNanos) {
            receipt.accept(acceptedAtNanos);
        }
        private void rejectPending(UUID uuid, long version, PendingReceipt receipt) {
            ConcurrentMap<Long, PendingReceipt> accountReceipts = pending.get(uuid);
            if (accountReceipts != null) {
                accountReceipts.remove(version, receipt);
                if (accountReceipts.isEmpty()) pending.remove(uuid, accountReceipts);
            }
        }

        private void addResponseLatency(long nanos) { response.add(nanos); }
        private void addDurableLatency(long nanos) { durable.add(nanos); }
        private void setCounts(RunCounts counts) { this.counts = counts; }
        private RunCounts counts() { return counts; }
        private List<Long> acceptedTimes() { return acceptedTimes; }
        private List<Long> responseLatencies() { return List.copyOf(response); }
        private List<Long> receiptLatencies() { return List.copyOf(receipts); }
        private List<Long> durableLatencies() { return List.copyOf(durable); }
        private Throwable monitorFailure() { return monitorFailure.get(); }
        private long moneyInvariantFailures() { return moneyInvariantFailures; }

        private boolean receiptMonitorTerminated() {
            return monitorThread == null || !monitorThread.isAlive();
        }

        private void collectDurableSamples(long written, long observedAtNanos) {
            long durableCount = Math.min(written, acceptedTimes.size());
            while (durableSamplesCollected < durableCount) {
                durable.add(Math.max(1L, observedAtNanos - acceptedTimes.get((int) durableSamplesCollected)));
                durableSamplesCollected++;
            }
        }

        private long offered() { return counts().offered(); }
        private long accepted() { return counts().accepted(); }
        private long authorityRejected() { return counts().authorityRejected(); }

        private void stopReceiptMonitor() throws InterruptedException {
            monitoring.set(false);
            if (monitorThread != null) {
                monitorThread.join(30_000L);
                if (monitorThread.isAlive() || !pending.isEmpty()) {
                    throw new IllegalStateException("Redis receipt observation did not drain.");
                }
            }
        }
    }

    static final class PendingReceipt {
        private volatile long acceptedAtNanos;
        private final AtomicBoolean accepted = new AtomicBoolean();
        void accept(long acceptedAtNanos) {
            this.acceptedAtNanos = acceptedAtNanos;
            accepted.set(true);
        }
        boolean accepted() { return accepted.get(); }
        long acceptedAtNanos() { return acceptedAtNanos; }
    }

    static final class BaselineClassLoader extends URLClassLoader {
        private static final List<String> PARENT_FIRST = List.of("java.", "javax.", "jdk.", "sun.", "org.bukkit.",
                "io.papermc.paper.", "net.kyori.", "com.destroystokyo.", "org.jetbrains.");
        BaselineClassLoader(URL artifact, ClassLoader parent) { super(new URL[] {artifact}, parent); }

        InputStream getBaselineResourceAsStream(String name) throws IOException {
            URL resource = findResource(name);
            return resource == null ? null : resource.openStream();
        }

        @Override
        public URL getResource(String name) {
            URL resource = findResource(name);
            return resource != null ? resource : super.getResource(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    boolean parentFirst = PARENT_FIRST.stream().anyMatch(name::startsWith);
                    if (parentFirst) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try { loaded = findClass(name); }
                        catch (ClassNotFoundException absentFromBaseline) { loaded = super.loadClass(name, false); }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
