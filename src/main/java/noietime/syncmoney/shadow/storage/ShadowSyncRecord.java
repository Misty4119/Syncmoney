package noietime.syncmoney.shadow.storage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Represents a single shadow sync record.
 */
public class ShadowSyncRecord {

    private Long id;
    private String playerUuid;
    private String playerName;
    private BigDecimal balance;
    private String syncTarget;
    private String operation;
    private Instant timestamp;
    private boolean success;
    private String reason;

    public ShadowSyncRecord() {
    }

    public ShadowSyncRecord(String playerUuid, String playerName, BigDecimal balance,
                           String syncTarget, String operation, Instant timestamp,
                           boolean success, String reason) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.balance = balance;
        this.syncTarget = syncTarget;
        this.operation = operation;
        this.timestamp = timestamp;
        this.success = success;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getSyncTarget() {
        return syncTarget;
    }

    public void setSyncTarget(String syncTarget) {
        this.syncTarget = syncTarget;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDate getDate() {
        return timestamp != null ? timestamp.atZone(ZoneId.systemDefault()).toLocalDate() : null;
    }
}
