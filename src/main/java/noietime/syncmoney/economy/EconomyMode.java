package noietime.syncmoney.economy;

/**
 * Economy mode enum.
 * Determines plugin's operation mode based on configuration.
 */
public enum EconomyMode {
    /**
     * Local mode - SQLite.
     * Data stored in local SQLite, no Redis required.
     * Suitable for single server or testing environment.
     */
    LOCAL,

    /**
     * Local mode - Redis.
     * Data stored in Redis, no MySQL.
     * Supports cross-server sync (Pub/Sub).
     * Suitable for small multi-server clusters.
     */
    LOCAL_REDIS,

    /**
     * Sync mode.
     * Data stored in Redis + MySQL.
     * Achieves cross-server sync through Vault interception.
     * Suitable for multi-server clusters.
     */
    SYNC,

    /**
     * CMI mode.
     * Data stored in Redis (no MySQL).
     * Asynchronously detects CMI economic changes.
     * Suitable for servers using CMI economy.
     */
    CMI
}
