package noietime.syncmoney.benchmark.load;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import noietime.syncmoney.storage.persistence.SqlDialect;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaselineMacroPilotProducerTest {

    @Test
    void manifestSerializationPreservesRequiredNullFailureTaxonomyFields() {
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("ambiguous", null);
        manifest.put("quarantine", null);

        JsonObject serialized = JsonParser.parseString(BaselineMacroPilotProducer.serializeManifest(manifest))
                .getAsJsonObject();

        assertTrue(serialized.has("ambiguous"));
        assertTrue(serialized.get("ambiguous").isJsonNull());
        assertTrue(serialized.has("quarantine"));
        assertTrue(serialized.get("quarantine").isJsonNull());
    }

    @Test
    void manifestEnvironmentMetadataUsesTheSuppliedLaunchSnapshot() {
        BenchmarkEnvironment.Snapshot current = BenchmarkEnvironment.capture();
        Map<String, Object> launchFields = new LinkedHashMap<>(current.fields());
        launchFields.put("maxMemoryBytes", current.maxMemoryBytes() + 1_024L);
        BenchmarkEnvironment.Snapshot launchEnvironment = BenchmarkEnvironment.fromFields(launchFields);
        Map<String, Object> manifest = new LinkedHashMap<>();

        BaselineMacroPilotProducer.addEnvironmentMetadata(manifest, launchEnvironment);

        assertEquals(launchEnvironment.fields(), manifest.get("environment"));
        assertEquals(launchEnvironment.fingerprint(), manifest.get("environmentFingerprintSHA256"));
    }

    @Test
    void sampleMinimumCannotExceedTheHeapBoundedMacroPilotLimit() {
        assertDoesNotThrow(() -> BaselineMacroPilotProducer.validateSampleLimit(100_000));
        assertDoesNotThrow(() -> BaselineMacroPilotProducer.validateSampleLimit(
                BaselineMacroPilotProducer.MAX_HISTOGRAM_SAMPLES));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilotProducer.validateSampleLimit(
                        BaselineMacroPilotProducer.MAX_HISTOGRAM_SAMPLES + 1));
        assertTrue(failure.getMessage().contains("bounded macro-pilot histogram limit"));
    }

    @Test
    void receiptLatencyStartsAtSuccessfulDepositAcceptance() {
        BaselineMacroPilotProducer.PendingReceipt receipt = new BaselineMacroPilotProducer.PendingReceipt();
        assertFalse(receipt.accepted());

        receipt.accept(123_456L);

        assertTrue(receipt.accepted());
        assertEquals(123_456L, receipt.acceptedAtNanos());
    }

    @Test
    void pluginFixtureTemporarilyUsesPaperApiParentContextLoader(@TempDir Path temporaryDirectory) throws Exception {
        Path baselineJar = temporaryDirectory.resolve("baseline-fixture.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(baselineJar))) {
            // The fixture only needs the loader identity; resources are not requested during construction.
        }
        URLClassLoader baselineLoader = baselineClassLoader(baselineJar,
                BaselineMacroPilotProducer.class.getClassLoader());
        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();
        Server previousServer = Bukkit.getServer();
        Thread.currentThread().setContextClassLoader(baselineLoader);
        try {
            Method createFixture = producerMethod("testPlugin", BaselineMacroPilotProducer.BaselineClassLoader.class,
                    Path.class, OpenLoopProfile.BackendSettings.class, BaselineMacroPilotProducer.SqlEndpoint.class,
                    Logger.class);
            Object fixture = createFixture.invoke(null, baselineLoader, temporaryDirectory,
                    new OpenLoopProfile.BackendSettings("127.0.0.1", 16379, null, 0,
                            "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19", "root", null, SqlDialect.MYSQL),
                    new BaselineMacroPilotProducer.SqlEndpoint("127.0.0.1", 13306, "syncmoney_t19"),
                    Logger.getLogger("syncmoney-test-fixture"));
            Method pluginAccessor = fixture.getClass().getDeclaredMethod("plugin");
            pluginAccessor.setAccessible(true);
            JavaPlugin plugin = (JavaPlugin) pluginAccessor.invoke(fixture);

            assertNotNull(plugin);
            assertEquals(temporaryDirectory.toFile(), plugin.getDataFolder());
            assertEquals("baseline-macro-pilot", plugin.getConfig().getString("server-name"));
            assertNotNull(plugin.getConfig().getDefaults());
            assertTrue(plugin.isEnabled());
            assertSame(baselineLoader, Thread.currentThread().getContextClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
            BaselineMacroPilotProducer.installServerFixture(previousServer);
            baselineLoader.close();
        }
    }

    @Test
    void inputParserRequiresBuilderGeneratedBaselineAttestation() throws Exception {
        Method parseArguments = producerMethod("parseArguments", String[].class);
        String[] arguments = {
                "--baseline-checkout", "C:/scratch/baseline",
                "--baseline-artifact", "C:/scratch/baseline/build/libs/Syncmoney-1.3.2.jar",
                "--profile", "syncmoney-benchmarks/profiles/redis-sql-open-loop.json",
                "--output", "build/reports/benchmarks/pilot.json"
        };

        InvocationTargetException invocation = assertThrows(InvocationTargetException.class,
                () -> parseArguments.invoke(null, (Object) arguments));

        assertInstanceOf(IllegalArgumentException.class, invocation.getCause());
        assertTrue(invocation.getCause().getMessage().contains("--baseline-build-attestation"));
    }

    @Test
    void inputParserAcceptsAllRequiredOptionsAndAttestationPath() throws Exception {
        Method parseArguments = producerMethod("parseArguments", String[].class);
        String[] arguments = {
                "--baseline-checkout", "C:/scratch/baseline",
                "--baseline-artifact", "C:/scratch/baseline/build/libs/Syncmoney-1.3.2.jar",
                "--baseline-build-attestation", "build/reports/benchmarks/baseline-build-attestation.json",
                "--profile", "profiles/redis-sql-open-loop.json",
                "--output", "build/reports/benchmarks/pilot.json"
        };

        Object parsed = parseArguments.invoke(null, (Object) arguments);

        assertInstanceOf(BaselineMacroPilotProducer.Arguments.class, parsed);
        BaselineMacroPilotProducer.Arguments result = (BaselineMacroPilotProducer.Arguments) parsed;
        assertEquals(Path.of("C:/scratch/baseline").toAbsolutePath().normalize(), result.baselineCheckout());
        assertEquals(Path.of("C:/scratch/baseline/build/libs/Syncmoney-1.3.2.jar").toAbsolutePath().normalize(),
                result.baselineArtifact());
        assertEquals(Path.of("build/reports/benchmarks/baseline-build-attestation.json")
                .toAbsolutePath().normalize(), result.baselineBuildAttestation());
    }

    @Test
    void producerRequiresGradleBoundHarnessProvenance() throws Exception {
        Method requireProvenance = producerMethod("requireHarnessProvenance");
        String sourceSha = System.getProperty("syncmoney.benchmarks.sourceSha");
        String sourceTreeSha = System.getProperty("syncmoney.benchmarks.sourceTreeSha256");
        String classpathSha = System.getProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256");
        try {
            System.clearProperty("syncmoney.benchmarks.sourceSha");
            System.clearProperty("syncmoney.benchmarks.sourceTreeSha256");
            System.clearProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256");
            InvocationTargetException invocation = assertThrows(InvocationTargetException.class,
                    () -> requireProvenance.invoke(null));
            assertInstanceOf(IllegalStateException.class, invocation.getCause());
            assertTrue(invocation.getCause().getMessage().contains("provenance-bound Gradle task"));
        } finally {
            restoreProperty("syncmoney.benchmarks.sourceSha", sourceSha);
            restoreProperty("syncmoney.benchmarks.sourceTreeSha256", sourceTreeSha);
            restoreProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256", classpathSha);
        }
    }

    @Test
    void producerAcceptsOnlyCanonicalGitObjectIdWidthsForHarnessSource() throws Exception {
        Method requireProvenance = producerMethod("requireHarnessProvenance");
        String sourceSha = System.getProperty("syncmoney.benchmarks.sourceSha");
        String sourceTreeSha = System.getProperty("syncmoney.benchmarks.sourceTreeSha256");
        String classpathSha = System.getProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256");
        try {
            System.setProperty("syncmoney.benchmarks.sourceTreeSha256", "d".repeat(64));
            System.setProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256", "e".repeat(64));

            for (int length : new int[] {40, 64}) {
                System.setProperty("syncmoney.benchmarks.sourceSha", "a".repeat(length));
                assertDoesNotThrow(() -> requireProvenance.invoke(null), "Git object ID length " + length);
            }

            for (int length : new int[] {41, 63}) {
                System.setProperty("syncmoney.benchmarks.sourceSha", "a".repeat(length));
                InvocationTargetException invocation = assertThrows(InvocationTargetException.class,
                        () -> requireProvenance.invoke(null), "non-canonical Git object ID length " + length);
                assertInstanceOf(IllegalStateException.class, invocation.getCause());
            }
        } finally {
            restoreProperty("syncmoney.benchmarks.sourceSha", sourceSha);
            restoreProperty("syncmoney.benchmarks.sourceTreeSha256", sourceTreeSha);
            restoreProperty("syncmoney.benchmarks.loadTestRuntimeClasspathSha256", classpathSha);
        }
    }

    @Test
    void buildAttestationBindsBuilderVersionToVerifiedSourceAndArtifact(@TempDir Path temporaryDirectory) throws Exception {
        Path receipt = temporaryDirectory.resolve("baseline-build.json");
        String expectedBaseline = BaselineMacroPilotProducer.BASELINE_SHA;
        String expectedArtifact = BaselineMacroPilotProducer.BASELINE_ARTIFACT_SHA256;
        Files.writeString(receipt, "{\"baselineSHA\":\"" + expectedBaseline
                + "\",\"baselineArtifactSHA256\":\"" + expectedArtifact
                + "\",\"baselineBuildJavaVersion\":\"21.0.6\"}", StandardCharsets.UTF_8);

        BaselineMacroPilotProducer.BaselineBuildEvidence evidence =
                BaselineMacroPilotProducer.readBuildAttestation(receipt, expectedBaseline, expectedArtifact);
        assertEquals("21.0.6", evidence.javaVersion());
        assertEquals(64, evidence.attestationSha256().length());

        Files.writeString(receipt, "{\"baselineSHA\":\"" + expectedBaseline
                + "\",\"baselineArtifactSHA256\":\"" + expectedArtifact
                + "\",\"baselineBuildJavaVersion\":\"21.forged\"}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilotProducer.readBuildAttestation(receipt, expectedBaseline, expectedArtifact));

        Files.writeString(receipt, "{\"baselineSHA\":\"" + expectedBaseline
                + "\",\"baselineArtifactSHA256\":\"wrong\",\"baselineBuildJavaVersion\":\"21.0.6\"}",
                StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> BaselineMacroPilotProducer.readBuildAttestation(receipt, expectedBaseline, expectedArtifact));
    }

    @Test
    void baselineJarResourceWinsOverCurrentRuntimeCollision(@TempDir Path temporaryDirectory) throws Exception {
        String resourceName = "lua/atomic_add_balance.lua";
        Path currentResources = temporaryDirectory.resolve("current-resources");
        Path currentResource = currentResources.resolve(resourceName);
        Files.createDirectories(currentResource.getParent());
        Files.writeString(currentResource, "CURRENT_RESOURCE", StandardCharsets.UTF_8);
        Path baselineJar = temporaryDirectory.resolve("baseline.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(baselineJar))) {
            jar.putNextEntry(new JarEntry(resourceName));
            jar.write("BASELINE_RESOURCE".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }

        try (URLClassLoader currentParent = new URLClassLoader(
                        new URL[] {currentResources.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
                URLClassLoader baselineLoader = baselineClassLoader(baselineJar, currentParent);
                InputStream resource = baselineLoader.getResourceAsStream(resourceName)) {
            assertEquals("BASELINE_RESOURCE", new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void baselinePilotRejectsNonIsolatedRedisAndSqlTargets() throws Exception {
        Method parseSqlEndpoint = producerMethod("parseSqlEndpoint", String.class, Object.class);
        BaselineMacroPilotProducer.SqlEndpoint remoteSql = (BaselineMacroPilotProducer.SqlEndpoint) parseSqlEndpoint.invoke(null,
                "jdbc:mariadb://db.example.net:3306/syncmoney_t19", SqlDialect.MYSQL);
        BaselineMacroPilotProducer.SqlEndpoint wrongDatabase = (BaselineMacroPilotProducer.SqlEndpoint) parseSqlEndpoint.invoke(null,
                "jdbc:mariadb://127.0.0.1:13306/production", SqlDialect.MYSQL);
        BaselineMacroPilotProducer.SqlEndpoint wrongPort = (BaselineMacroPilotProducer.SqlEndpoint) parseSqlEndpoint.invoke(null,
                "jdbc:mariadb://127.0.0.1:3306/syncmoney_t19", SqlDialect.MYSQL);
        BaselineMacroPilotProducer.SqlEndpoint accepted = (BaselineMacroPilotProducer.SqlEndpoint) parseSqlEndpoint.invoke(null,
                "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19", SqlDialect.MYSQL);
        Method validate = producerMethod("validateDisposableBackend",
                OpenLoopProfile.BackendSettings.class, BaselineMacroPilotProducer.SqlEndpoint.class, String.class);
        String acknowledgement = "syncmoney-t19-disposable";
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "redis.example.net", 6379, null, 0, "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), accepted, acknowledgement);
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 16379, null, 0, "jdbc:mariadb://db.example.net:3306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), remoteSql, acknowledgement);
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 16379, null, 0, "jdbc:mariadb://127.0.0.1:13306/production",
                "bench", null, SqlDialect.MYSQL), wrongDatabase, acknowledgement);
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 16379, null, 0, "jdbc:mariadb://127.0.0.1:3306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), wrongPort, acknowledgement);
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 6379, null, 0, "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), accepted, acknowledgement);
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 16379, null, 1, "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), accepted, acknowledgement);
        assertInvalidBackend(validate, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 16379, null, 0, "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), accepted, "");
        validate.invoke(null, new OpenLoopProfile.BackendSettings(
                "127.0.0.1", 16379, null, 0, "jdbc:mariadb://127.0.0.1:13306/syncmoney_t19",
                "bench", null, SqlDialect.MYSQL), accepted, acknowledgement);
    }

    @Test
    void runDataCleanupRequiresBothWritersToBeStopped() throws Exception {
        Method canCleanup = producerMethod("canCleanupOwnedData", boolean.class, boolean.class);
        assertEquals(false, canCleanup.invoke(null, false, true));
        assertEquals(false, canCleanup.invoke(null, true, false));
        assertEquals(false, canCleanup.invoke(null, false, false));
        assertEquals(true, canCleanup.invoke(null, true, true));

        Class<?> producer = Class.forName("noietime.syncmoney.benchmark.load.BaselineMacroPilotProducer");
        Class<?> stateType = Class.forName(producer.getName() + "$RuntimeState");
        Constructor<?> stateConstructor = stateType.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();
        CountingRedisManager manager = new CountingRedisManager();
        setStateField(stateType, state, "redisManager", manager);
        setStateField(stateType, state, "redisKeysOwned", true);
        setStateField(stateType, state, "dbThread", Thread.currentThread());
        Method cleanup = producer.getDeclaredMethod("cleanupExactRunData", stateType, java.util.List.class);
        cleanup.setAccessible(true);

        InvocationTargetException invocation = null;
        try {
            cleanup.invoke(null, state, java.util.List.of());
        } catch (InvocationTargetException failure) {
            invocation = failure;
        }

        assertEquals(null, invocation);
        assertEquals(0, manager.resourceRequests.get(),
                "cleanup must not contact Redis while the database writer can still recreate rows");
    }

    @Test
    void shutdownAttemptsDatabaseStopAndJoinWhenReceiptMonitorStopFails() {
        List<String> attempted = new ArrayList<>();

        List<String> failures = BaselineMacroPilotProducer.runShutdownSteps(List.of(
                new BaselineMacroPilotProducer.ShutdownStep("receipt monitor stop", () -> {
                    attempted.add("receipt monitor stop");
                    throw new IllegalStateException("injected shutdown failure");
                }),
                new BaselineMacroPilotProducer.ShutdownStep("database consumer stop",
                        () -> attempted.add("database consumer stop")),
                new BaselineMacroPilotProducer.ShutdownStep("database writer join",
                        () -> attempted.add("database writer join"))));

        assertEquals(List.of("receipt monitor stop", "database consumer stop", "database writer join"), attempted);
        assertEquals(List.of("receipt monitor stop"), failures);
    }

    @Test
    void shutdownRuntimeUsesTheLegacyEconomyFacadeShutdownMethod(@TempDir Path temporaryDirectory) throws Exception {
        Class<?> producer = Class.forName("noietime.syncmoney.benchmark.load.BaselineMacroPilotProducer");
        Class<?> stateType = Class.forName(producer.getName() + "$RuntimeState");
        Constructor<?> stateConstructor = stateType.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();
        LegacyEconomyFacade economy = new LegacyEconomyFacade();
        setStateField(stateType, state, "economy", economy);
        Path dataFolder = Files.createDirectory(temporaryDirectory.resolve("baseline-data"));
        Server previousServer = Bukkit.getServer();
        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();

        Method shutdownRuntime = producer.getDeclaredMethod("shutdownRuntime", stateType, List.class,
                Path.class, Server.class, ClassLoader.class);
        shutdownRuntime.setAccessible(true);
        Object outcome = shutdownRuntime.invoke(null, state, List.of(), dataFolder, previousServer,
                previousContextLoader);
        Method failures = outcome.getClass().getDeclaredMethod("failures");
        failures.setAccessible(true);

        assertEquals(List.of(), failures.invoke(outcome));
        assertEquals(2, economy.shutdownCalls.get(), "stop and close should use the legacy shutdown method");
    }

    @Test
    void shutdownRuntimeDoesNotRequireCacheManagerToExposeALifecycleMethod(@TempDir Path temporaryDirectory) throws Exception {
        Class<?> producer = Class.forName("noietime.syncmoney.benchmark.load.BaselineMacroPilotProducer");
        Class<?> stateType = Class.forName(producer.getName() + "$RuntimeState");
        Constructor<?> stateConstructor = stateType.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();
        setStateField(stateType, state, "cacheManager", new Object());
        Path dataFolder = Files.createDirectory(temporaryDirectory.resolve("baseline-data"));
        Server previousServer = Bukkit.getServer();
        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();

        Method shutdownRuntime = producer.getDeclaredMethod("shutdownRuntime", stateType, List.class,
                Path.class, Server.class, ClassLoader.class);
        shutdownRuntime.setAccessible(true);
        Object outcome = shutdownRuntime.invoke(null, state, List.of(), dataFolder, previousServer,
                previousContextLoader);
        Method failures = outcome.getClass().getDeclaredMethod("failures");
        failures.setAccessible(true);

        assertEquals(List.of(), failures.invoke(outcome));
    }

    @Test
    void legacyFailureSummaryDoesNotAssumeRecoveryFieldsFromTheNewRuntime() throws Exception {
        Method summarize = producerMethod("consumerFailures", Object.class);

        Object emptySummary = summarize.invoke(null, new LegacyEventConsumer(Map.of()));
        assertNull(recordValue(emptySummary, "ambiguous"));
        assertNull(recordValue(emptySummary, "quarantine"));
        assertEquals("UNAVAILABLE_IN_1_3_2", recordValue(emptySummary, "taxonomyStatus"));
        assertEquals(0L, recordValue(emptySummary, "legacyFailedEvents"));

        Object failedSummary = summarize.invoke(null, new LegacyEventConsumer(Map.of("failed", new Object())));
        assertEquals(1L, recordValue(failedSummary, "legacyFailedEvents"));
    }

    @Test
    void backendCleanupAndEveryBackgroundWorkerGateRuntimeDisposal() {
        assertFalse(BaselineMacroPilotProducer.canDisposeRuntime(true, true, false));
        assertFalse(BaselineMacroPilotProducer.canDisposeRuntime(false, true, true));
        assertFalse(BaselineMacroPilotProducer.canDisposeRuntime(true, false, true));
        assertEquals(true, BaselineMacroPilotProducer.canDisposeRuntime(true, true, true));

        assertFalse(BaselineMacroPilotProducer.shouldDeleteSupportFiles(true, false));
        assertFalse(BaselineMacroPilotProducer.shouldDeleteSupportFiles(false, true));
        assertEquals(true, BaselineMacroPilotProducer.shouldDeleteSupportFiles(true, true));
    }

    private static URLClassLoader baselineClassLoader(Path baselineJar, ClassLoader parent) throws Exception {
        Class<?> type = Class.forName("noietime.syncmoney.benchmark.load.BaselineMacroPilotProducer$BaselineClassLoader");
        Constructor<?> constructor = type.getDeclaredConstructor(URL.class, ClassLoader.class);
        constructor.setAccessible(true);
        return (URLClassLoader) constructor.newInstance(baselineJar.toUri().toURL(), parent);
    }

    private static void assertInvalidBackend(Method validate, OpenLoopProfile.BackendSettings backend,
            BaselineMacroPilotProducer.SqlEndpoint sql, String acknowledgement) throws Exception {
        InvocationTargetException invocation = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(null, backend, sql, acknowledgement));
        assertInstanceOf(IllegalArgumentException.class, invocation.getCause());
    }

    private static void setStateField(Class<?> stateType, Object state, String name, Object value) throws Exception {
        var field = stateType.getDeclaredField(name);
        field.setAccessible(true);
        field.set(state, value);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static final class CountingRedisManager {
        private final AtomicInteger resourceRequests = new AtomicInteger();
        public Object getResource() {
            resourceRequests.incrementAndGet();
            return new Object();
        }
    }

    private static final class LegacyEconomyFacade {
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        public void shutdown() {
            shutdownCalls.incrementAndGet();
        }
    }

    private static final class LegacyEventConsumer {
        private final Map<String, Object> failedEvents;

        private LegacyEventConsumer(Map<String, Object> failedEvents) {
            this.failedEvents = failedEvents;
        }
    }

    private static Object recordValue(Object record, String component) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(component);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private static Method producerMethod(String name, Class<?>... parameterTypes) {
        try {
            Class<?> producer = Class.forName(
                    "noietime.syncmoney.benchmark.load.BaselineMacroPilotProducer");
            Method method = producer.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException missingProducerContract) {
            throw new AssertionError("Baseline macro producer input contract is missing", missingProducerContract);
        }
    }
}
