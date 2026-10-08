package noietime.syncmoney.protocol;

import java.math.BigDecimal;

/** Immutable v1 CMI Pub/Sub envelope, independent of platform and transport APIs. */
public record LegacyCmiMessage(
        String uuid,
        BigDecimal balance,
        long version,
        String sourceServer,
        String messageId,
        long timestamp,
        String eventType,
        double amount,
        String sourcePlugin,
        String sourcePlayerName) {
}
