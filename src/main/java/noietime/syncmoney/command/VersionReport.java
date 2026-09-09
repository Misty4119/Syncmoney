package noietime.syncmoney.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain-text support report. It intentionally has no Adventure dependency so
 * that the saved artifact is safe to paste into an issue tracker.
 */
public final class VersionReport {

    private final Map<String, String> values = new LinkedHashMap<>();

    public VersionReport(String scope) {
        add("report.scope", scope);
        add("report.sanitized", true);
    }

    public VersionReport add(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }
        String normalized = value == null ? "UNKNOWN" : String.valueOf(value);
        normalized = normalized.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        values.put(key, normalized.isEmpty() ? "UNKNOWN" : normalized);
        return this;
    }

    public VersionReport addProbe(String prefix, ProbeResult result) {
        add(prefix + ".status", result.status());
        if (result.version() != null && !result.version().isBlank()) {
            add(prefix + ".version", result.version());
        }
        if (!result.latencyMs().isEmpty()) {
            double min = result.latencyMs().stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double average = result.latencyMs().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double max = result.latencyMs().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            add(prefix + ".latency_ms", String.format(Locale.ROOT,
                    "min=%.2f avg=%.2f max=%.2f", min, average, max));
        }
        if (result.detail() != null && !result.detail().isBlank()) {
            add(prefix + ".detail", result.detail());
        }
        return this;
    }

    public String render() {
        StringBuilder output = new StringBuilder("=== Syncmoney Support Report ===");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            output.append(System.lineSeparator())
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue());
        }
        return output.toString();
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("=== Syncmoney Support Report ===");
        values.forEach((key, value) -> lines.add(key + ": " + value));
        return List.copyOf(lines);
    }

    public record ProbeResult(String status, String version, List<Double> latencyMs, String detail) {
        public ProbeResult {
            status = status == null || status.isBlank() ? "ERROR" : status;
            latencyMs = latencyMs == null ? List.of() : List.copyOf(latencyMs);
        }
    }
}
