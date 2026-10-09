package noietime.syncmoney.benchmark.load;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkEnvironmentTest {
    @Test
    void fingerprintIsStableAndRoundTripsThroughJson() {
        BenchmarkEnvironment.Snapshot first = BenchmarkEnvironment.capture();
        BenchmarkEnvironment.Snapshot second = BenchmarkEnvironment.capture();

        assertEquals(first.fingerprint(), second.fingerprint());
        BenchmarkEnvironment.Snapshot parsed = BenchmarkEnvironment.parse(JsonParser.parseString(
                new Gson().toJson(first.fields())).getAsJsonObject());
        assertEquals(first.fingerprint(), parsed.fingerprint());
    }

    @Test
    void fingerprintRejectsNonAllowlistedJvmArguments() {
        BenchmarkEnvironment.Snapshot current = BenchmarkEnvironment.capture();
        Map<String, Object> fields = new LinkedHashMap<>(current.fields());
        ArrayList<String> arguments = new ArrayList<>(current.jvmArguments());
        arguments.add("-Dsyncmoney.benchmark.password=must-not-be-recorded");
        fields.put("jvmArguments", arguments);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkEnvironment.fromFields(fields));

        assertTrue(failure.getMessage().contains("non-allowlisted"));
        assertTrue(!failure.getMessage().contains("must-not-be-recorded"));
    }

    @Test
    void runtimeArgumentClassificationRejectsUnknownFlagsWithoutLeakingValues() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkEnvironment.classifyJvmArguments(List.of(
                        "-XX:+UnlockExperimentalVMOptions", "-Dbenchmark.secret=must-not-be-recorded")));

        assertTrue(failure.getMessage().contains("unclassified"));
        assertTrue(!failure.getMessage().contains("must-not-be-recorded"));
    }

    @Test
    void runtimeArgumentClassificationKeepsPerformanceFlagsAndSkipsKnownProvenanceProperties() {
        List<String> selected = BenchmarkEnvironment.classifyJvmArguments(List.of(
                "-Xmx2g",
                "-XX:ActiveProcessorCount=4",
                "-Dfile.encoding=UTF-8",
                "-Duser.country=TW",
                "-Duser.language=zh",
                "-Duser.variant",
                "-Dnet.bytebuddy.experimental=true",
                "-ea",
                "-Dorg.gradle.internal.worker.tmpdir=C:/Users/example/AppData/Local/Temp/worker-1",
                "-javaagent:C:/Users/example/build/tmp/jacocoagent.jar=destfile=C:/Temp/test.exec",
                "-Dsyncmoney.benchmarks.sourceSha=0123456789012345678901234567890123456789",
                "-Dsyncmoney.benchmarks.sourceTreeSha256=" + "a".repeat(64)));

        assertEquals(List.of("-Xmx2g", "-XX:ActiveProcessorCount=4", "-Dfile.encoding=UTF-8",
                "-Duser.country=TW", "-Duser.language=zh", "-Duser.variant",
                "-Dnet.bytebuddy.experimental=true", "-ea"), selected);
    }

    @Test
    void runtimeArgumentClassificationRejectsUnrecognizedAgentsWithoutLeakingOptions() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkEnvironment.classifyJvmArguments(List.of(
                        "-javaagent:C:/private/agent.jar=token=must-not-be-recorded")));

        assertTrue(failure.getMessage().contains("-javaagent"));
        assertTrue(!failure.getMessage().contains("must-not-be-recorded"));
    }
}
