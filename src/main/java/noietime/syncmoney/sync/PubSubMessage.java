package noietime.syncmoney.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pub/Sub message data structure.
 * Used for cross-server balance synchronization message format.
 *
 * [AsyncScheduler] This class is a data structure, not involving threads.
 */
public final class PubSubMessage {

    private static final Gson GSON = new GsonBuilder().create();


    public enum EventType {
        UNKNOWN,
        DEPOSIT,
        WITHDRAW,
        SET_BALANCE,
        TRANSFER_IN,
        TRANSFER_OUT
    }

    private final String uuid;
    private final double balance;
    private final long version;
    private final String sourceServer;
    private final String messageId;
    private final long timestamp;
    private final String eventType;
    private final double amount;
    private final String sourcePlugin;
    private final String sourcePlayerName;

    public PubSubMessage(String uuid, double balance, long version,
                        String sourceServer, String messageId, long timestamp) {
        this(uuid, balance, version, sourceServer, messageId, timestamp, null, 0, null, null);
    }

    public PubSubMessage(String uuid, double balance, long version,
                        String sourceServer, String messageId, long timestamp,
                        String eventType, double amount) {
        this(uuid, balance, version, sourceServer, messageId, timestamp, eventType, amount, null, null);
    }

    public PubSubMessage(String uuid, double balance, long version,
                        String sourceServer, String messageId, long timestamp,
                        String eventType, double amount, String sourcePlugin) {
        this(uuid, balance, version, sourceServer, messageId, timestamp, eventType, amount, sourcePlugin, null);
    }

    public PubSubMessage(String uuid, double balance, long version,
                        String sourceServer, String messageId, long timestamp,
                        String eventType, double amount, String sourcePlugin, String sourcePlayerName) {
        this.uuid = uuid;
        this.balance = balance;
        this.version = version;
        this.sourceServer = sourceServer;
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.amount = amount;
        this.sourcePlugin = sourcePlugin;
        this.sourcePlayerName = sourcePlayerName;
    }

    /**
     * Parses JSON to PubSubMessage.
     */
    public static PubSubMessage fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return GSON.fromJson(json, PubSubMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts to JSON.
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    public String getUuid() {
        return uuid;
    }

    public UUID getUuidAsUUID() {
        return UUID.fromString(uuid);
    }

    public double getBalance() {
        return balance;
    }

    /**
     * Gets balance as BigDecimal (avoids floating-point errors).
     */
    public BigDecimal getBalanceAsBigDecimal() {
        return NumericUtil.normalize(balance);
    }

    public long getVersion() {
        return version;
    }

    public String getSourceServer() {
        return sourceServer;
    }

    public String getMessageId() {
        return messageId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public EventType getEventTypeEnum() {
        if (eventType == null) return EventType.UNKNOWN;
        try {
            return EventType.valueOf(eventType);
        } catch (IllegalArgumentException e) {
            return EventType.UNKNOWN;
        }
    }

    public double getAmount() {
        return amount;
    }

    public String getSourcePlugin() {
        return sourcePlugin;
    }

    public String getSourcePlayerName() {
        return sourcePlayerName;
    }

    /**
     * Builder pattern (optional).
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String uuid;
        private double balance;
        private long version;
        private String sourceServer;
        private String messageId;
        private long timestamp;
        private String eventType;
        private double amount;
        private String sourcePlugin;
        private String sourcePlayerName;

        public Builder uuid(String uuid) { this.uuid = uuid; return this; }
        public Builder balance(double balance) { this.balance = balance; return this; }
        public Builder version(long version) { this.version = version; return this; }
        public Builder sourceServer(String sourceServer) { this.sourceServer = sourceServer; return this; }
        public Builder messageId(String messageId) { this.messageId = messageId; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder amount(double amount) { this.amount = amount; return this; }
        public Builder sourcePlugin(String sourcePlugin) { this.sourcePlugin = sourcePlugin; return this; }
        public Builder sourcePlayerName(String sourcePlayerName) { this.sourcePlayerName = sourcePlayerName; return this; }

        public PubSubMessage build() {
            return new PubSubMessage(uuid, balance, version, sourceServer,
                messageId, timestamp, eventType, amount, sourcePlugin, sourcePlayerName);
        }
    }

    @Override
    public String toString() {
        return "PubSubMessage{uuid='" + uuid + "', balance=" + balance +
               ", version=" + version + ", sourceServer='" + sourceServer + "'}";
    }
}
