package noietime.syncmoney.audit;

import noietime.syncmoney.economy.EconomyEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit log record.
 * Records detailed information for all economic changes.
 *
 * [ThreadSafe] This class is immutable and thread-safe.
 */
public record AuditRecord(

        String id,

        long timestamp,

        int sequence,

        AuditType type,

        UUID playerUuid,

        String playerName,

        BigDecimal amount,

        BigDecimal balanceAfter,

        EconomyEvent.EventSource source,

        String server,

        UUID targetUuid,

        String targetName,

        String reason,

        int mergedCount
) {
    /**
     * Creates a copy of this record with a new sequence number.
     * @param newSequence the new sequence number
     * @return a new AuditRecord with the updated sequence
     */
    public AuditRecord withSequence(int newSequence) {
        return new AuditRecord(
                this.id,
                this.timestamp,
                newSequence,
                this.type,
                this.playerUuid,
                this.playerName,
                this.amount,
                this.balanceAfter,
                this.source,
                this.server,
                this.targetUuid,
                this.targetName,
                this.reason,
                this.mergedCount
        );
    }

    /**
     * Audit type enumeration.
     */
    public enum AuditType {

        DEPOSIT,

        WITHDRAW,

        SET_BALANCE,

        TRANSFER,

        CRITICAL_FAILURE
    }

    /**
     * Creates audit record from EconomyEvent.
     */
    public static AuditRecord fromEconomyEvent(EconomyEvent event, String playerName, String server) {
        return fromEconomyEvent(event, playerName, server, event.balanceAfter(), 1, 0);
    }

    /**
     * Creates audit record from EconomyEvent with explicit balanceAfter.
     * @param balanceAfter the actual balance after the transaction (not from event, but from actual write result)
     */
    public static AuditRecord fromEconomyEvent(EconomyEvent event, String playerName, String server, BigDecimal balanceAfter) {
        return fromEconomyEvent(event, playerName, server, balanceAfter, 1, 0);
    }

    /**
     * Creates audit record from EconomyEvent (merged version).
     * @param mergedCount number of merged transactions
     */
    public static AuditRecord fromEconomyEvent(EconomyEvent event, String playerName, String server, int mergedCount) {
        return fromEconomyEvent(event, playerName, server, event.balanceAfter(), mergedCount, 0);
    }

    /**
     * Creates audit record from EconomyEvent with explicit sequence for ordering.
     * @param sequence the sequence number for ordering concurrent transactions at the same millisecond
     */
    public static AuditRecord fromEconomyEvent(EconomyEvent event, String playerName, String server, BigDecimal balanceAfter, int mergedCount, int sequence) {
        return new AuditRecord(
                event.requestId(),
                event.timestamp(),
                sequence,
                convertEventType(event.type()),
                event.playerUuid(),
                playerName,
                event.delta(),
                balanceAfter,
                event.source(),
                server,
                null,
                null,
                null,
                mergedCount
        );
    }

    /**
     * Creates transfer audit record from EconomyEvent.
     */
    public static AuditRecord fromTransferEvent(
            EconomyEvent event,
            String playerName,
            String server,
            UUID targetUuid,
            String targetName
    ) {
        return fromTransferEvent(event, playerName, server, targetUuid, targetName, 1, 0);
    }

    /**
     * Creates transfer audit record from EconomyEvent (merged version).
     * @param mergedCount number of merged transactions
     */
    public static AuditRecord fromTransferEvent(
            EconomyEvent event,
            String playerName,
            String server,
            UUID targetUuid,
            String targetName,
            int mergedCount
    ) {
        return fromTransferEvent(event, playerName, server, targetUuid, targetName, mergedCount, 0);
    }

    /**
     * Creates transfer audit record with explicit sequence for ordering.
     * @param sequence the sequence number for ordering concurrent transactions at the same millisecond
     */
    public static AuditRecord fromTransferEvent(
            EconomyEvent event,
            String playerName,
            String server,
            UUID targetUuid,
            String targetName,
            int mergedCount,
            int sequence
    ) {
        return new AuditRecord(
                event.requestId(),
                event.timestamp(),
                sequence,
                AuditType.TRANSFER,
                event.playerUuid(),
                playerName,
                event.delta(),
                event.balanceAfter(),
                event.source(),
                server,
                targetUuid,
                targetName,
                null,
                mergedCount
        );
    }

    /**
     * Converts EconomyEvent.EventType to AuditType.
     */
    private static AuditType convertEventType(EconomyEvent.EventType type) {
        return switch (type) {
            case DEPOSIT -> AuditType.DEPOSIT;
            case WITHDRAW -> AuditType.WITHDRAW;
            case SET_BALANCE -> AuditType.SET_BALANCE;
            case TRANSFER_IN, TRANSFER_OUT -> AuditType.TRANSFER;
        };
    }

    /**
     * Gets formatted time string.
     */
    public String getFormattedTime() {
        Instant instant = Instant.ofEpochMilli(timestamp);
        return instant.toString().replace("T", " ").substring(0, 19);
    }

    /**
     * Gets formatted amount string (with sign).
     */
    public String getFormattedAmount() {
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            return "+" + amount.toPlainString();
        }
        return amount.toPlainString();
    }

    /**
     * Gets display amount string (includes merge information).
     */
    public String getDisplayAmount() {
        String baseAmount = getFormattedAmount();
        if (mergedCount > 1) {
            return baseAmount + " (x" + mergedCount + ")";
        }
        return baseAmount;
    }

    /**
     * Checks if this is a merged record.
     */
    public boolean isMerged() {
        return mergedCount > 1;
    }
}
