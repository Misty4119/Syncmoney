package noietime.syncmoney.sync;

import java.util.Map;

/**
 * Maps a cross-server economy {@code eventType} to the messages.yml key used to notify the
 * receiving player.
 *
 * <p>Previously this mapping was duplicated as inline {@code if/else} chains in
 * {@link PubsubSubscriber} (and partially in {@code CrossServerSyncManager}). Centralizing it here
 * keeps the event-type vocabulary in a single place so the two notification paths cannot drift.
 */
public final class MessageKeys {

    private static final Map<String, String> EVENT_TYPE_TO_KEY = Map.of(
            "VAULT_DEPOSIT", "cross-server.money-received",
            "VAULT_WITHDRAW", "cross-server.money-spent",
            "DEPOSIT", "admin.money-received",
            "ADMIN_GIVE", "admin.money-received",
            "WITHDRAW", "admin.money-taken",
            "ADMIN_TAKE", "admin.money-taken",
            "SET_BALANCE", "admin.balance-set-by-admin",
            "TRANSFER_IN", "pay.success-receiver"
    );

    private MessageKeys() {
    }

    /**
     * Resolve the messages.yml key for an event type.
     *
     * @param eventType the cross-server event type (may be {@code null})
     * @return the message key, or {@code null} if the event type has no associated notification
     */
    public static String forEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        return EVENT_TYPE_TO_KEY.get(eventType);
    }
}
