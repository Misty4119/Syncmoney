package noietime.syncmoney.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Transaction event fired after a balance change occurs.
 * This event is fired regardless of whether the transaction was successful.
 */
public class PostTransactionEvent extends SyncmoneyEvent {

    private final UUID playerUuid;
    private final String playerName;
    private final AsyncPreTransactionEvent.TransactionType type;
    private final BigDecimal amount;
    private final BigDecimal balanceBefore;
    private final BigDecimal balanceAfter;
    private final String source;
    private final UUID targetUuid;
    private final String targetName;
    private final String reason;
    private final boolean success;
    private final String errorMessage;

    public PostTransactionEvent(UUID playerUuid, String playerName,
                            AsyncPreTransactionEvent.TransactionType type,
                            BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, String source,
                            UUID targetUuid, String targetName, String reason,
                            boolean success, String errorMessage) {
        super("PostTransactionEvent");
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.type = type;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.source = source;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.reason = reason;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public AsyncPreTransactionEvent.TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceBefore() {
        return balanceBefore;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
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

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Get the net balance change (can be negative for withdrawals).
     */
    public BigDecimal getBalanceChange() {
        return balanceAfter.subtract(balanceBefore);
    }
}
