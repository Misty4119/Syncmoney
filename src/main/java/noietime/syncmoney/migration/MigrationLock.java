package noietime.syncmoney.migration;

import java.util.function.BooleanSupplier;

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
    private static Object migrationOwner;
    private static boolean sourceSnapshotStarted;
    private static boolean cmiAuthorityChangedDuringSnapshot;
    private static final ThreadLocal<MigrationWritePermit> currentMigrationWritePermit = new ThreadLocal<>();

    /** Internal lease tying privileged migration writes and lock release to one owner. */
    static final class MigrationLease implements AutoCloseable {
        private final Object owner;
        private boolean closed;

        private MigrationLease(Object owner) {
            this.owner = owner;
        }

        boolean isActive() {
            synchronized (WRITE_GATE) {
                return !closed && locked && migrationOwner == owner;
            }
        }

        boolean beginSourceSnapshot() {
            synchronized (WRITE_GATE) {
                if (closed || !locked || migrationOwner != owner) {
                    return false;
                }
                sourceSnapshotStarted = true;
                return true;
            }
        }

        boolean sourceChangedDuringSnapshot() {
            synchronized (WRITE_GATE) {
                return !closed && locked && migrationOwner == owner
                        && sourceSnapshotStarted && cmiAuthorityChangedDuringSnapshot;
            }
        }

        /** Atomically checks the CMI source fence and releases this migration's lock. */
        boolean completeSourceSnapshotIfStable() {
            synchronized (WRITE_GATE) {
                if (closed || !locked || migrationOwner != owner
                        || !sourceSnapshotStarted || cmiAuthorityChangedDuringSnapshot) {
                    return false;
                }
                closeOwnedLock();
                return true;
            }
        }

        MigrationWritePermit beginWrite() {
            synchronized (WRITE_GATE) {
                if (closed || !locked || migrationOwner != owner
                        || (sourceSnapshotStarted && cmiAuthorityChangedDuringSnapshot)) {
                    return null;
                }
                MigrationWritePermit permit = new MigrationWritePermit(owner, currentMigrationWritePermit.get());
                currentMigrationWritePermit.set(permit);
                return permit;
            }
        }

        @Override
        public void close() {
            synchronized (WRITE_GATE) {
                if (closed) {
                    return;
                }
                if (migrationOwner == owner) {
                    closeOwnedLock();
                } else {
                    closed = true;
                }
            }
        }

        private void closeOwnedLock() {
            locked = false;
            enabled = false;
            migrationOwner = null;
            sourceSnapshotStarted = false;
            cmiAuthorityChangedDuringSnapshot = false;
            closed = true;
        }
    }

    /**
     * Internal cross-package coordination lease held for the full lifetime of an economy write.
     * This is a public JVM member for callers in the plugin's existing packages, not a supported
     * third-party API contract.
     */
    public static final class WriteLease implements AutoCloseable {
        private final boolean counted;
        private final Object migrationOwner;
        private boolean closed;

        private WriteLease(boolean counted, Object migrationOwner) {
            this.counted = counted;
            this.migrationOwner = migrationOwner;
        }

        /**
         * Commits the short in-memory mutation only while its migration source is still stable.
         * The source event recorder uses the same gate, so whichever operation wins this gate
         * determines whether the migration write is accepted. Backend I/O must happen afterward.
         */
        public boolean commitMutationIfSourceStable(BooleanSupplier mutation) {
            if (counted) {
                synchronized (WRITE_GATE) {
                    if (closed) {
                        return false;
                    }
                }
                return mutation.getAsBoolean();
            }

            synchronized (WRITE_GATE) {
                if (closed || migrationOwner == null || !locked || MigrationLock.migrationOwner != migrationOwner
                        || (sourceSnapshotStarted && cmiAuthorityChangedDuringSnapshot)) {
                    return false;
                }
                return mutation.getAsBoolean();
            }
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
            migrationOwner = null;
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
            migrationOwner = new Object();
            sourceSnapshotStarted = false;
            cmiAuthorityChangedDuringSnapshot = false;
            return true;
        }
    }

    /** Atomically enables and takes the economy lock for one migration owner. */
    static MigrationLease tryAcquireMigrationLease() {
        synchronized (WRITE_GATE) {
            enabled = true;
            if (locked || activeWriters != 0) {
                return null;
            }
            Object owner = new Object();
            locked = true;
            migrationOwner = owner;
            sourceSnapshotStarted = false;
            cmiAuthorityChangedDuringSnapshot = false;
            return new MigrationLease(owner);
        }
    }

    /**
     * Unlocks economy operations.
     */
    public static void unlock() {
        synchronized (WRITE_GATE) {
            locked = false;
            migrationOwner = null;
            sourceSnapshotStarted = false;
            cmiAuthorityChangedDuringSnapshot = false;
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

    /** Records a post-change CMI event while a migration source snapshot is active. */
    public static boolean recordCmiAuthorityMutation() {
        synchronized (WRITE_GATE) {
            if (!locked || migrationOwner == null || !sourceSnapshotStarted) {
                return false;
            }
            cmiAuthorityChangedDuringSnapshot = true;
            return true;
        }
    }


    /**
     * Internal cross-package entry point. A locked migration may only admit the migration worker's
     * single authorized set-balance mutation. This public JVM member is not a supported
     * third-party API contract.
     */
    public static WriteLease tryAcquireWrite() {
        synchronized (WRITE_GATE) {
            if (locked) {
                Object owner = consumeMigrationWriteAuthorization();
                return owner == null ? null : new WriteLease(false, owner);
            }
            activeWriters++;
            return new WriteLease(true, null);
        }
    }

    /**
     * Checks whether the current thread is inside the migration worker's write scope.
     * The scope itself is package-private so callers outside the migration implementation
     * cannot bypass an active economy lock by choosing an event source.
     */
    private static Object consumeMigrationWriteAuthorization() {
        MigrationWritePermit permit = currentMigrationWritePermit.get();
        if (permit == null || permit.consumed || permit.owner != migrationOwner
                || (sourceSnapshotStarted && cmiAuthorityChangedDuringSnapshot)) {
            return null;
        }
        permit.consumed = true;
        return permit.owner;
    }

    static final class MigrationWritePermit implements AutoCloseable {
        private final Object owner;
        private final MigrationWritePermit previous;
        private boolean consumed;
        private boolean closed;

        private MigrationWritePermit(Object owner, MigrationWritePermit previous) {
            this.owner = owner;
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
