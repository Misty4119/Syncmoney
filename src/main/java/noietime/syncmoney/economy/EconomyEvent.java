package noietime.syncmoney.economy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Economic change event record.
 * Converted to Java Record for immutability and cleaner code (Java 21).
 *
 * [AsyncScheduler] This class is a data structure, no threading involved.
 */
public record EconomyEvent(
    UUID playerUuid,
    BigDecimal delta,
    BigDecimal balanceAfter,
    long version,
    EventType type,
    EventSource source,
    String requestId,
    long timestamp
) {

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

        TEST,

        // Third-party plugin source - bypasses Vault pairing logic, uses atomic_transfer.lua
        PLUGIN_DEPOSIT,

        PLUGIN_WITHDRAW
    }

    /**
     * Get player UUID (alias for playerUuid()).
     * [DEPRECATED] Use playerUuid() instead for consistency.
     */
    @Deprecated
    public UUID uuid() {
        return playerUuid;
    }
}
