package noietime.syncmoney.protocol;

import java.math.BigDecimal;

/** Immutable v1 balance Pub/Sub envelope, independent of platform and transport APIs. */
public record LegacyBalanceMessage(
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
