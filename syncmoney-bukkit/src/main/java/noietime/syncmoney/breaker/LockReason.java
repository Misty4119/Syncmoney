package noietime.syncmoney.breaker;

/**
 * Identifies the cause that drove the {@link EconomicCircuitBreaker} into a LOCKED state.
 *
 * <p>Used instead of fragile substring matching on the human-readable lock reason string,
 * so that recovery logic (e.g. auto-reset after Redis reconnection) can reliably detect
 * whether a lockdown originated from a Redis disconnection.
 */
public enum LockReason {

    /** Lockdown triggered because Redis was disconnected for too long. */
    REDIS_DISCONNECT,

    /** Lockdown triggered by the periodic total-supply inflation check. */
    INFLATION,

    /** Lockdown triggered manually by an administrator. */
    MANUAL,

    /** Lockdown triggered by an unspecified/other cause. */
    OTHER
}
