package noietime.syncmoney.benchmark.load;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Secret-free runtime and host metadata used to establish benchmark environment parity. */
final class BenchmarkEnvironment {
    private static final Gson GSON = new Gson();
    private static final List<String> FIELD_ORDER = List.of(
            "javaVersion", "javaRuntimeVersion", "javaVendor", "vmName", "vmVersion", "vmVendor",
            "osName", "osVersion", "osArch", "availableProcessors", "maxMemoryBytes",
            "garbageCollectors", "jvmArguments");
    private static final Set<String> FIELD_SET = Set.copyOf(FIELD_ORDER);
    private static final Pattern SAFE_JVM_ARGUMENT = Pattern.compile(
            "-Xm[sx][0-9]+[kKmMgG]?|-XX:[+-](?:Use[A-Za-z0-9]+GC|UseCompressedOops|"
                    + "UseCompressedClassPointers|UseContainerSupport|UseNUMA|UseStringDeduplication|"
                    + "UseLargePages|UseTransparentHugePages|AlwaysPreTouch|TieredCompilation)|"
                    + "-XX:[+]Unlock(?:Experimental|Diagnostic)VMOptions|-DcompilerBlackholesEnabled=(?:true|false)|-ea|"
                    + "-XX:(MaxGCPauseMillis|ActiveProcessorCount|ConcGCThreads|ParallelGCThreads|"
                    + "CICompilerCount|InitiatingHeapOccupancyPercent|G1HeapRegionSize|MaxTenuringThreshold|"
                    + "NewRatio|SurvivorRatio|MaxRAMPercentage|InitialRAMPercentage)=[0-9]+(?:\\.[0-9]+)?|"
                    + "-XX:(MaxDirectMemorySize|ReservedCodeCacheSize|InitialCodeCacheSize)=[0-9]+[kKmMgG]?");
    private static final Pattern SAFE_RUNTIME_PROPERTY = Pattern.compile(
            "-D(?:file\\.encoding|user\\.(?:country|language|variant|timezone))(?:=[A-Za-z0-9._/+:-]+)?|"
                    + "-Dnet\\.bytebuddy\\.experimental=(?:true|false)");
    private static final Set<String> PROVENANCE_ONLY_PROPERTIES = Set.of(
            "syncmoney.benchmarks.baselineArtifact", "syncmoney.benchmarks.baselineArtifactSha256",
            "syncmoney.benchmarks.baselineSha", "syncmoney.benchmarks.baselineJarSha256",
            "syncmoney.benchmarks.baselineBuildJavaVersion", "syncmoney.benchmarks.baselineMacroPilotSha256",
            "syncmoney.benchmarks.sourceSha", "syncmoney.benchmarks.sourceTreeSha256",
            "syncmoney.benchmarks.loadTestRuntimeClasspathSha256", "org.gradle.internal.worker.tmpdir");

    private BenchmarkEnvironment() { }

    static Snapshot capture() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("javaVersion", property("java.version"));
        fields.put("javaRuntimeVersion", property("java.runtime.version"));
        fields.put("javaVendor", property("java.vendor"));
        fields.put("vmName", property("java.vm.name"));
        fields.put("vmVersion", property("java.vm.version"));
        fields.put("vmVendor", property("java.vm.vendor"));
        fields.put("osName", property("os.name"));
        fields.put("osVersion", property("os.version"));
        fields.put("osArch", property("os.arch"));
        fields.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        fields.put("maxMemoryBytes", Runtime.getRuntime().maxMemory());
        fields.put("garbageCollectors", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).sorted().toList());
        fields.put("jvmArguments", classifyJvmArguments(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        return fromFields(fields);
    }

    /**
     * Selects the JVM arguments that affect a measurement while failing closed on unknown input.
     * Benchmark provenance properties are deliberately omitted because they identify the run rather
     * than its execution environment; their values may also contain local paths.
     */
    static List<String> classifyJvmArguments(List<String> arguments) {
        List<String> selected = new ArrayList<>(arguments.size());
        boolean gradleTestWorker = arguments.stream().anyMatch(argument ->
                argument.startsWith("-D") && propertyName(argument).equals("org.gradle.internal.worker.tmpdir"));
        for (String argument : arguments) {
            if (SAFE_JVM_ARGUMENT.matcher(argument).matches() || SAFE_RUNTIME_PROPERTY.matcher(argument).matches()) {
                selected.add(argument);
            } else if (!isProvenanceOnlyProperty(argument)
                    && !(gradleTestWorker && isGradleJacocoAgent(argument))) {
                if (argument.startsWith("-D")) {
                    throw new IllegalArgumentException("Benchmark environment contains an unclassified JVM property: "
                            + propertyName(argument));
                }
                throw new IllegalArgumentException("Benchmark environment contains an unclassified JVM option: "
                        + optionName(argument));
            }
        }
        return List.copyOf(selected);
    }

    private static boolean isProvenanceOnlyProperty(String argument) {
        return argument.startsWith("-D") && PROVENANCE_ONLY_PROPERTIES.contains(propertyName(argument));
    }

    private static boolean isGradleJacocoAgent(String argument) {
        if (!argument.startsWith("-javaagent:")) return false;
        String value = argument.substring("-javaagent:".length());
        int optionsSeparator = value.indexOf('=');
        String path = optionsSeparator < 0 ? value : value.substring(0, optionsSeparator);
        int fileSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String fileName = path.substring(fileSeparator + 1);
        return fileName.equals("jacocoagent.jar");
    }

    private static String propertyName(String argument) {
        int separator = argument.indexOf('=', 2);
        return separator < 0 ? argument.substring(2) : argument.substring(2, separator);
    }

    private static String optionName(String argument) {
        if (argument.startsWith("-javaagent:") || argument.startsWith("-agentlib:")
                || argument.startsWith("-agentpath:")) {
            return argument.substring(0, argument.indexOf(':'));
        }
        int separator = argument.indexOf('=');
        return separator < 0 ? argument : argument.substring(0, separator);
    }

    static Snapshot parse(JsonObject source) {
        if (source.size() != FIELD_ORDER.size() || !source.keySet().equals(FIELD_SET)) {
            throw new IllegalArgumentException("Baseline macro pilot environment fields are incomplete or unexpected");
        }
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (String name : FIELD_ORDER) {
            JsonElement value = source.get(name);
            if (value == null || value.isJsonNull()) {
                throw new IllegalArgumentException("Baseline macro pilot environment is missing " + name);
            }
            switch (name) {
                case "javaVersion", "javaRuntimeVersion", "javaVendor", "vmName", "vmVersion", "vmVendor",
                        "osName", "osVersion", "osArch" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                            || value.getAsString().isBlank()) {
                        throw new IllegalArgumentException("Baseline macro pilot environment field is invalid: " + name);
                    }
                    fields.put(name, value.getAsString());
                }
                case "availableProcessors", "maxMemoryBytes" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        throw new IllegalArgumentException("Baseline macro pilot environment field is invalid: " + name);
                    }
                    try {
                        long number = value.getAsBigDecimal().longValueExact();
                        if (number <= 0L || (name.equals("availableProcessors") && number > Integer.MAX_VALUE)) {
                            throw new IllegalArgumentException("Baseline macro pilot environment field is invalid: " + name);
                        }
                        if (name.equals("availableProcessors")) fields.put(name, Integer.valueOf((int) number));
                        else fields.put(name, Long.valueOf(number));
                    } catch (ArithmeticException invalid) {
                        throw new IllegalArgumentException("Baseline macro pilot environment field is invalid: " + name, invalid);
                    }
                }
                case "garbageCollectors", "jvmArguments" -> fields.put(name, parseStringArray(value, name));
                default -> throw new IllegalStateException("Unexpected benchmark environment field " + name);
            }
        }
        return fromFields(fields);
    }

    static Snapshot fromFields(Map<String, Object> source) {
        if (source.size() != FIELD_ORDER.size() || !source.keySet().equals(FIELD_SET)) {
            throw new IllegalArgumentException("Benchmark environment fields are incomplete or unexpected");
        }
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        for (String name : FIELD_ORDER) canonical.put(name, source.get(name));
        validateFields(canonical);
        Map<String, Object> immutable = Collections.unmodifiableMap(canonical);
        return new Snapshot(immutable, sha256(GSON.toJson(canonical)));
    }

    private static void validateFields(Map<String, Object> fields) {
        for (String name : FIELD_ORDER.subList(0, 9)) {
            if (!(fields.get(name) instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("Benchmark environment field is invalid: " + name);
            }
        }
        if (!(fields.get("availableProcessors") instanceof Integer processors) || processors <= 0
                || !(fields.get("maxMemoryBytes") instanceof Long maxMemory) || maxMemory <= 0L) {
            throw new IllegalArgumentException("Benchmark environment hardware fields are invalid");
        }
        validateStringList(fields.get("garbageCollectors"), "garbageCollectors");
        validateStringList(fields.get("jvmArguments"), "jvmArguments");
        @SuppressWarnings("unchecked")
        List<String> arguments = (List<String>) fields.get("jvmArguments");
        if (arguments.stream().anyMatch(argument -> !isFingerprintArgument(argument))) {
            throw new IllegalArgumentException("Benchmark environment contains a non-allowlisted JVM argument");
        }
    }

    private static boolean isFingerprintArgument(String argument) {
        return SAFE_JVM_ARGUMENT.matcher(argument).matches() || SAFE_RUNTIME_PROPERTY.matcher(argument).matches();
    }

    private static void validateStringList(Object value, String name) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(element -> !(element instanceof String))) {
            throw new IllegalArgumentException("Benchmark environment list is invalid: " + name);
        }
    }

    private static List<String> parseStringArray(JsonElement value, String name) {
        if (!value.isJsonArray()) throw new IllegalArgumentException("Baseline macro pilot environment field is invalid: " + name);
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Baseline macro pilot environment field is invalid: " + name);
            }
            result.add(element.getAsString());
        }
        return List.copyOf(result);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing JVM environment property " + name);
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    record Snapshot(Map<String, Object> fields, String fingerprint) {
        String javaVersion() { return (String) fields.get("javaVersion"); }
        String osName() { return (String) fields.get("osName"); }
        String osVersion() { return (String) fields.get("osVersion"); }
        String osArch() { return (String) fields.get("osArch"); }
        int availableProcessors() { return (Integer) fields.get("availableProcessors"); }
        long maxMemoryBytes() { return (Long) fields.get("maxMemoryBytes"); }
        @SuppressWarnings("unchecked")
        List<String> jvmArguments() { return (List<String>) fields.get("jvmArguments"); }
    }
}
