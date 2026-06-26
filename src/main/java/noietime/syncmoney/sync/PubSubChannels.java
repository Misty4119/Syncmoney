package noietime.syncmoney.sync;

/**
 * Cross-server Redis Pub/Sub channel names.
 *
 * <p>These string values are part of the cross-server communication contract and MUST NOT change;
 * every server in the network publishes and subscribes using exactly these channel names. This
 * class only centralizes the literals so the publisher ({@code CrossServerSyncManager}) and the
 * subscriber ({@link PubsubSubscriber}) share one source of truth.
 */
public final class PubSubChannels {

    public static final String BALANCE_UPDATE = "syncmoney:balance:update";

    public static final String CMI_BALANCE_UPDATE = "syncmoney:cmi:balance:update";

    private PubSubChannels() {
    }
}
