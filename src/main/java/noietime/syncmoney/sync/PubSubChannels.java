package noietime.syncmoney.sync;

import noietime.syncmoney.protocol.RedisChannels;

/**
 * Cross-server Redis Pub/Sub channel names.
 *
 * <p>These string values are part of the cross-server communication contract and MUST NOT change;
 * every server in the network publishes and subscribes using exactly these channel names. This
 * class only centralizes the literals so the publisher ({@code CrossServerSyncManager}) and the
 * subscriber ({@link PubsubSubscriber}) share one source of truth.
 */
public final class PubSubChannels {

    public static final String BALANCE_UPDATE = RedisChannels.BALANCE_UPDATE;

    public static final String CMI_BALANCE_UPDATE = RedisChannels.CMI_BALANCE_UPDATE;

    private PubSubChannels() {
    }
}
