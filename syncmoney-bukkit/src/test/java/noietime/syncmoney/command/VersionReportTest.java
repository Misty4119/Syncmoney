package noietime.syncmoney.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionReportTest {

    @Test
    void rendersStableEnglishFieldsAndLatencySummary() {
        VersionReport report = new VersionReport("FULL")
                .add("plugin.version", "1.3.2")
                .add("host.os", "Windows\nsecret-like-value")
                .addProbe("redis", new VersionReport.ProbeResult(
                        "PASS", "7.2.0", List.of(1.0, 2.0, 3.0), null));

        String rendered = report.render();
        assertTrue(rendered.contains("plugin.version: 1.3.2"));
        assertTrue(rendered.contains("host.os: Windows secret-like-value"));
        assertTrue(rendered.contains("redis.status: PASS"));
        assertTrue(rendered.contains("redis.version: 7.2.0"));
        assertTrue(rendered.contains("redis.latency_ms: min=1.00 avg=2.00 max=3.00"));
        assertFalse(rendered.toLowerCase().contains("adventure"));
    }
}
