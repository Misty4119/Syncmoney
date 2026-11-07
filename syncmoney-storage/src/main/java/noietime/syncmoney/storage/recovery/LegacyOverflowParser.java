package noietime.syncmoney.storage.recovery;

import java.math.BigDecimal;
import java.util.UUID;

/** Read-only parser for the pre-1.4 six-column overflow line. It never authorizes replay. */
public final class LegacyOverflowParser {
    public enum Classification { AMBIGUOUS, INVALID }

    public record Result(Classification classification, String originalLine, Long timestamp,
            String playerUuid, String eventType, String eventSource, String amount, String requestId,
            String reason) {
        public boolean replaySafe() { return false; }
    }

    private LegacyOverflowParser() { }

    public static Result parse(String line) {
        if (line == null) {
            return invalid(null, "legacy overflow line is null");
        }
        String[] parts = line.trim().split("\\|", -1);
        if (parts.length != 6) {
            return invalid(line, "expected exactly six legacy fields");
        }
        try {
            long timestamp = Long.parseLong(parts[0]);
            UUID.fromString(parts[1]);
            if (parts[2].isBlank() || parts[3].isBlank() || parts[5].isBlank()) {
                return invalid(line, "event type, source, and request id must be present");
            }
            new BigDecimal(parts[4]);
            String reason = "legacy record has no authority execution stage or confirmed receipt";
            if ("SET_BALANCE".equals(parts[2])) {
                reason += "; the amount field is not a proven absolute SET target";
            }
            return new Result(Classification.AMBIGUOUS, line, timestamp, parts[1], parts[2], parts[3],
                    parts[4], parts[5], reason);
        } catch (RuntimeException invalid) {
            return invalid(line, "legacy field validation failed: " + invalid.getMessage());
        }
    }

    private static Result invalid(String line, String reason) {
        return new Result(Classification.INVALID, line, null, null, null, null, null, null, reason);
    }
}
