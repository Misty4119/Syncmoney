package noietime.syncmoney.protocol;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Existing Redis key names and builders; values remain compatible with the 1.3.2 inventory. */
public final class RedisKeys {

    public static final String BALANCE_PREFIX = "syncmoney:balance:";
    public static final String VERSION_PREFIX = "syncmoney:version:";
    public static final String BALTOP = "syncmoney:baltop";
    public static final String BALTOP_TOTAL = "syncmoney:baltop:total";
    public static final String ONLINE_PLAYERS = "syncmoney:online:players";
    public static final String ONLINE_PLAYER_PREFIX = "syncmoney:online:player:";
    public static final String AUDIT_RECENT = "syncmoney:audit:recent";
    public static final String AUDIT_INDEX = "syncmoney:audit:index";
    public static final String AUDIT_DEDUP_PREFIX = "syncmoney:audit:dedup:";
    public static final String BANK_BALANCE_PREFIX = "syncmoney:bank:";
    public static final String BANK_OWNER_PREFIX = "syncmoney:bank:owner:";
    public static final String BANK_VERSION_PREFIX = "syncmoney:bank:version:";
    public static final String OVERFLOW_EVENTS = "syncmoney:overflow:events";
    public static final String LOCK_PREFIX = "syncmoney:lock:";
    public static final String CMI_DEFAULT_PREFIX = "syncmoney:cmi";

    private RedisKeys() {
    }

    public static String balance(UUID uuid) {
        return BALANCE_PREFIX + Objects.requireNonNull(uuid, "uuid");
    }

    public static String version(UUID uuid) {
        return VERSION_PREFIX + Objects.requireNonNull(uuid, "uuid");
    }

    public static String balancePattern() {
        return BALANCE_PREFIX + "*";
    }

    public static String onlinePlayer(UUID uuid) {
        return ONLINE_PLAYER_PREFIX + Objects.requireNonNull(uuid, "uuid");
    }

    public static String onlinePlayer(String uuid) {
        return ONLINE_PLAYER_PREFIX + uuid;
    }

    public static String auditDedup(long timestamp, String type, UUID uuid, BigDecimal amount) {
        return AUDIT_DEDUP_PREFIX + timestamp + ":" + type + ":" + uuid + ":" + amount;
    }

    public static String bankBalance(String normalizedName) {
        return BANK_BALANCE_PREFIX + normalizedName;
    }

    public static String bankOwner(String normalizedName) {
        return BANK_OWNER_PREFIX + normalizedName;
    }

    public static String bankVersion(String normalizedName) {
        return BANK_VERSION_PREFIX + normalizedName;
    }

    public static String bankBalancePattern() {
        return BANK_BALANCE_PREFIX + "*";
    }

    public static String lock(UUID uuid) {
        return LOCK_PREFIX + Objects.requireNonNull(uuid, "uuid");
    }

    public static String cmiBalance(String configuredPrefix, UUID uuid) {
        return configuredPrefix + ":" + Objects.requireNonNull(uuid, "uuid");
    }

    public static String cmiVersion(String configuredPrefix, UUID uuid) {
        return cmiBalance(configuredPrefix, uuid) + ":version";
    }
}
