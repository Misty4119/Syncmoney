package noietime.syncmoney.util;

/**
 * Centralized constants for the Syncmoney plugin.
 * Contains all magic numbers and configuration constants to improve maintainability.
 */
public final class Constants {

    private Constants() {}

    // ==================== Lock Constants ====================
    
    /** Lock retry interval in milliseconds */
    public static final int LOCK_RETRY_MILLIS = 50;
    
    /** Maximum number of lock acquisition attempts */
    public static final int LOCK_MAX_RETRIES = 100;
    
    /** Lock timeout in seconds */
    public static final int LOCK_TIMEOUT_SECONDS = 30;

    // ==================== Player Transfer Constants ====================

    /** Maximum wait time for pending transactions when player quits (ms) */
    public static final int MAX_WAIT_MS = 500;

    /** Check interval for pending transactions (ms) */
    public static final int CHECK_INTERVAL_MS = 50;

    /** Confirm timeout in ticks (600 ticks = 30 seconds) */
    public static final long CONFIRM_TIMEOUT_TICKS = 30 * 20L;

    // ==================== Database Writer Constants ====================
    
    /** Batch size for database writes */
    public static final int BATCH_SIZE = 100;
    
    /** Batch timeout in milliseconds */
    public static final long BATCH_TIMEOUT_MS = 1000;

    // ==================== Economy Constants ====================
    
    /** Default cache expiration time in minutes */
    public static final int DEFAULT_EXPIRATION_MINUTES = 30;

    // ==================== Baltop Constants ====================
    
    /** Maximum number of cached top entries */
    public static final int MAX_TOP_CACHE_ENTRIES = 30;
    
    /** Number of entries per page in commands */
    public static final int ENTRIES_PER_PAGE = 10;

    // ==================== Redis Constants ====================
    
    /** Maximum failures before logging warning */
    public static final int MAX_FAILURES_BEFORE_WARNING = 5;

    // ==================== Cache Constants ====================
    
    /** Message component cache maximum size */
    public static final int MESSAGE_COMPONENT_CACHE_MAX = 256;
    
    /** Maximum entries in debounce cache */
    public static final int MAX_DEBOUNCE_ENTRIES = 10_000;
    
    /** Maximum entries in name resolver cache */
    public static final int MAX_NAME_RESOLVER_CACHE = 10_000;
    
    /** Maximum entries in message helper cache */
    public static final int MAX_MESSAGE_HELPER_CACHE = 500;

    // ==================== Retry Constants ====================
    
    /** Initial retry delay for pub/sub reconnection (ms) */
    public static final long PUBSUB_INITIAL_RETRY_DELAY_MS = 1000;
    
    /** Maximum retry delay for pub/sub reconnection (ms) */
    public static final long PUBSUB_MAX_RETRY_DELAY_MS = 30_000;
    
    /** Vault registration retry delay (ms) */
    public static final int VAULT_RETRY_DELAY_MS = 1000;
    
    /** Maximum vault registration attempts */
    public static final int VAULT_MAX_RETRIES = 5;

    // ==================== Queue Constants ====================
    
    /** Default queue capacity */
    public static final int DEFAULT_QUEUE_CAPACITY = 1000;
    
    /** High usage threshold (80% of capacity) */
    public static final double QUEUE_HIGH_USAGE_THRESHOLD = 0.8;

    // ==================== Event Consumer Constants ====================
    
    /** Maximum retry count for failed events */
    public static final int MAX_RETRY_COUNT = 3;

    // ==================== Sync Constants ====================
    
    /** Pub/Sub channel name */
    public static final String PUBSUB_CHANNEL = "syncmoney:balance:update";
    
    /** Lock prefix for Redis */
    public static final String LOCK_PREFIX = "syncmoney:lock:";
}
