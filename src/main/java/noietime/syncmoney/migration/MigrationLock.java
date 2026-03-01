package noietime.syncmoney.migration;

/**
 * Migration lock controller.
 * Controls whether economy operations should be locked during migration.
 * When enabled, prevents data inconsistency that could occur if players
 * make transactions during the migration process.
 */
public final class MigrationLock {

    private static volatile boolean locked = false;
    private static volatile boolean enabled = false;

    /**
     * Enables the migration lock feature.
     * Must be called before lock() to take effect.
     */
    public static void enable() {
        enabled = true;
    }

    /**
     * Disables the migration lock feature.
     */
    public static void disable() {
        enabled = false;
        locked = false;
    }

    /**
     * Locks economy operations.
     * @return true if lock was applied, false if feature is disabled
     */
    public static boolean lock() {
        if (!enabled) {
            return false;
        }
        locked = true;
        return true;
    }

    /**
     * Unlocks economy operations.
     */
    public static void unlock() {
        locked = false;
    }

    /**
     * Checks if economy operations are currently locked.
     * @return true if locked, false otherwise
     */
    public static boolean isLocked() {
        return locked;
    }

    /**
     * Checks if the lock feature is enabled.
     * @return true if enabled, false otherwise
     */
    public static boolean isEnabled() {
        return enabled;
    }
}
