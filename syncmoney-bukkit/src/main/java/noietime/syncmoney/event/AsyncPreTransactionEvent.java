package noietime.syncmoney.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Transaction event fired before a balance change occurs.
 * This event can be cancelled to prevent the transaction.
 */
public class AsyncPreTransactionEvent extends SyncmoneyEvent {

    private final UUID playerUuid;
    private final String playerName;
    private final TransactionType type;
    private final BigDecimal amount;
    private final BigDecimal currentBalance;
    private final String source;
    private final UUID targetUuid;
    private final String targetName;
    private final String reason;

    private boolean cancelled = false;
    private String cancelReason;

    public AsyncPreTransactionEvent(UUID playerUuid, String playerName,
                                  TransactionType type, BigDecimal amount,
                                  BigDecimal currentBalance, String source,
                                  UUID targetUuid, String targetName, String reason) {
        super("AsyncPreTransactionEvent");
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.type = type;
        this.amount = amount;
        this.currentBalance = currentBalance;
        this.source = source;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.reason = reason;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public void setCancelled(boolean cancelled, String reason) {
        this.cancelled = cancelled;
        this.cancelReason = reason;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public String getSource() {
        return source;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getReason() {
        return reason;
    }

    /**
     * Transaction types.
     */
    public enum TransactionType {
        DEPOSIT,
        WITHDRAW,
        SET_BALANCE,
        TRANSFER
    }
}
