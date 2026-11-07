package noietime.syncmoney.sync;

/** Compatibility wrapper for the protocol module's legacy notification key mapping. */
public final class MessageKeys {

    private MessageKeys() {
    }

    /**
     * Resolve the messages.yml key for an event type.
     *
     * @param eventType the cross-server event type (may be {@code null})
     * @return the message key, or {@code null} if the event type has no associated notification
     */
    public static String forEventType(String eventType) {
        return noietime.syncmoney.protocol.MessageKeys.forEventType(eventType);
    }
}
