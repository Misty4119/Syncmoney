package noietime.syncmoney.migration;

/**
 * Migration lock controller.
 * Controls whether economy operations should be locked during migration.
 * When enabled, prevents data inconsistency that could occur if players
 * make transactions during the migration process.
 */
public final class MigrationLock {

    private static final Object WRITE_GATE = new Object();
    private static volatile boolean locked = false;
    private static volatile boolean enabled = false;
    private static int activeWriters;
    private static final ThreadLocal<MigrationWritePermit> currentMigrationWritePermit = new ThreadLocal<>();

    /**
     * Internal cross-package coordination lease held for the full lifetime of an economy write.
     * This is a public JVM member for callers in the plugin's existing packages, not a supported
     * third-party API contract.
     */
    public static final class WriteLease implements AutoCloseable {
        private final boolean counted;
        private boolean closed;

        private WriteLease(boolean counted) {
            this.counted = counted;
        }

        @Override
        public void close() {
            synchronized (WRITE_GATE) {
                if (closed) {
                    return;
                }
                closed = true;
                if (counted) {
                    activeWriters--;
                    if (activeWriters < 0) {
                        throw new IllegalStateException("Migration write lease count became negative");
                    }
                }
            }
        }
    }

    /**
     * Enables the migration lock feature.
     * Must be called before lock() to take effect.
     */
    public static void enable() {
        synchronized (WRITE_GATE) {
            enabled = true;
        }
    }

    /**
     * Disables the migration lock feature.
     */
    public static void disable() {
        synchronized (WRITE_GATE) {
            enabled = false;
            locked = false;
        }
    }

    /**
     * Locks economy operations.
     * @return true if lock was applied, false if disabled or a producer is still active
     */
    public static boolean lock() {
        synchronized (WRITE_GATE) {
            if (!enabled) {
                return false;
            }
            if (locked) {
                return false;
            }
            if (activeWriters != 0) {
                return false;
            }
            locked = true;
            return true;
        }
    }

    /**
     * Unlocks economy operations.
     */
    public static void unlock() {
        synchronized (WRITE_GATE) {
            locked = false;
        }
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

    /**
     * Internal cross-package entry point. A locked migration may only admit the migration worker's
     * single authorized set-balance mutation. This public JVM member is not a supported
     * third-party API contract.
     */
    public static WriteLease tryAcquireWrite() {
        synchronized (WRITE_GATE) {
            if (locked) {
                return consumeMigrationWriteAuthorization() ? new WriteLease(false) : null;
            }
            activeWriters++;
            return new WriteLease(true);
        }
    }

    /**
     * Checks whether the current thread is inside the migration worker's write scope.
     * The scope itself is package-private so callers outside the migration implementation
     * cannot bypass an active economy lock by choosing an event source.
     */
    private static boolean consumeMigrationWriteAuthorization() {
        MigrationWritePermit permit = currentMigrationWritePermit.get();
        if (permit == null || permit.consumed) {
            return false;
        }
        permit.consumed = true;
        return true;
    }

    static MigrationWritePermit beginMigrationWrite() {
        MigrationWritePermit permit = new MigrationWritePermit(currentMigrationWritePermit.get());
        currentMigrationWritePermit.set(permit);
        return permit;
    }

    static final class MigrationWritePermit implements AutoCloseable {
        private final MigrationWritePermit previous;
        private boolean consumed;
        private boolean closed;

        private MigrationWritePermit(MigrationWritePermit previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (currentMigrationWritePermit.get() != this) {
                throw new IllegalStateException("Migration write permits must close in nesting order");
            }
            closed = true;
            if (previous == null) {
                currentMigrationWritePermit.remove();
            } else {
                currentMigrationWritePermit.set(previous);
            }
        }
    }
}
