package noietime.syncmoney.economy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Economic change event record.
 * Produced by economy facade, consumed by single consumer.
 *
 * [AsyncScheduler] This class is a data structure, no threading involved.
 */
public final class EconomyEvent {


    public enum EventType {

        DEPOSIT,

        WITHDRAW,

        SET_BALANCE,

        TRANSFER_IN,

        TRANSFER_OUT
    }


    public enum EventSource {

        VAULT_DEPOSIT,

        VAULT_WITHDRAW,

        COMMAND_PAY,

        COMMAND_ADMIN,

        ADMIN_SET,

        ADMIN_GIVE,

        ADMIN_TAKE,

        PLAYER_TRANSFER,

        MIGRATION,

        SHADOW_SYNC,

        TEST
    }

    private final UUID playerUuid;
    private final BigDecimal delta;
    private final BigDecimal balanceAfter;
    private final long version;
    private final EventType type;
    private final EventSource source;
    private final String requestId;
    private final long timestamp;

    public EconomyEvent(UUID playerUuid, BigDecimal delta, BigDecimal balanceAfter, long version,
                       EventType type, EventSource source, String requestId, long timestamp) {
        this.playerUuid = playerUuid;
        this.delta = delta;
        this.balanceAfter = balanceAfter;
        this.version = version;
        this.type = type;
        this.source = source;
        this.requestId = requestId;
        this.timestamp = timestamp;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    /**
     * Get player UUID (alias method).
     */
    public UUID uuid() {
        return playerUuid;
    }

    public BigDecimal delta() {
        return delta;
    }

    public BigDecimal balanceAfter() {
        return balanceAfter;
    }

    public long version() {
        return version;
    }

    public EventType type() {
        return type;
    }

    public EventSource source() {
        return source;
    }

    public String requestId() {
        return requestId;
    }

    public long timestamp() {
        return timestamp;
    }
}
