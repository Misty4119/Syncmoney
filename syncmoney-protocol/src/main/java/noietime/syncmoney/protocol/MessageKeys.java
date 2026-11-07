package noietime.syncmoney.protocol;

import java.util.Map;

/** Legacy event-type to notification-key mapping retained at the protocol boundary. */
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

    public static String forEventType(String eventType) {
        return eventType == null ? null : EVENT_TYPE_TO_KEY.get(eventType);
    }
}
