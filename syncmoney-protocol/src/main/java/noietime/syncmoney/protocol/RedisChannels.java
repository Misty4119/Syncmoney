package noietime.syncmoney.protocol;

/** Existing Redis Pub/Sub channels; protocol extraction must not change these literals. */
public final class RedisChannels {

    public static final String BALANCE_UPDATE = "syncmoney:balance:update";
    public static final String CMI_BALANCE_UPDATE = "syncmoney:cmi:balance:update";

    private RedisChannels() {
    }
}
