package noietime.syncmoney.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationLockSourceSnapshotTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void postChangeCmiEventPreventsMigrationFromCommittingItsSnapshot() throws Exception {
        MigrationLock.MigrationLease lease = MigrationLock.tryAcquireMigrationLease();
        assertNotNull(lease);

        Method beginSnapshot = MigrationLock.MigrationLease.class.getDeclaredMethod("beginSourceSnapshot");
        beginSnapshot.setAccessible(true);
        assertTrue((Boolean) beginSnapshot.invoke(lease));

        Method recordMutation = MigrationLock.class.getDeclaredMethod("recordCmiAuthorityMutation");
        recordMutation.setAccessible(true);
        assertTrue((Boolean) recordMutation.invoke(null));

        Method completeSnapshot = MigrationLock.MigrationLease.class.getDeclaredMethod("completeSourceSnapshotIfStable");
        completeSnapshot.setAccessible(true);
        assertFalse((Boolean) completeSnapshot.invoke(lease));
        assertTrue(MigrationLock.isLocked(), "a dirty source snapshot must not release its write fence as complete");
    }

    @Test
    void postChangeCmiEventRevokesAnUnconsumedMigrationWritePermit() {
        MigrationLock.MigrationLease lease = MigrationLock.tryAcquireMigrationLease();
        assertNotNull(lease);
        assertTrue(lease.beginSourceSnapshot());

        try (MigrationLock.MigrationWritePermit ignored = lease.beginWrite()) {
            assertNotNull(ignored);
            assertTrue(MigrationLock.recordCmiAuthorityMutation());
            assertNull(MigrationLock.tryAcquireWrite(),
                    "a source mutation must revoke an unconsumed migration write permit");
        }

        assertFalse(lease.completeSourceSnapshotIfStable());
    }

    @Test
    void cmiMutationBetweenPermitConsumptionAndMemoryCommitRejectsTheMutation() throws Exception {
        MigrationLock.MigrationLease migrationLease = MigrationLock.tryAcquireMigrationLease();
        assertNotNull(migrationLease);
        assertTrue(migrationLease.beginSourceSnapshot());

        CountDownLatch writeAdmitted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicBoolean targetMutationRan = new AtomicBoolean();
        AtomicBoolean targetMutationAccepted = new AtomicBoolean();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (MigrationLock.MigrationWritePermit ignored = migrationLease.beginWrite()) {
                if (ignored == null) {
                    throw new AssertionError("the migration permit should be available before the source mutation");
                }
                try (MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite()) {
                    if (writeLease == null) {
                        throw new AssertionError("the one-shot migration permit should admit its write");
                    }
                    writeAdmitted.countDown();
                    if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the migration write");
                    }
                    Method commit = MigrationLock.WriteLease.class.getMethod(
                            "commitMutationIfSourceStable", BooleanSupplier.class);
                    targetMutationAccepted.set((Boolean) commit.invoke(writeLease,
                            (BooleanSupplier) () -> {
                                targetMutationRan.set(true);
                                return true;
                            }));
                }
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "migration-write-fence-test");
        writer.start();

        assertTrue(writeAdmitted.await(5, TimeUnit.SECONDS), "migration write should reach its commit boundary");
        try {
            assertTrue(MigrationLock.recordCmiAuthorityMutation());
        } finally {
            allowCommit.countDown();
        }
        writer.join(5_000);

        assertFalse(writer.isAlive(), "writer should finish after the source fence is released");
        assertNull(workerFailure.get(), "migration write should report the source fence rejection without throwing");
        assertFalse(targetMutationAccepted.get());
        assertFalse(targetMutationRan.get(), "no target mutation may run after the source change was recorded");
        assertFalse(migrationLease.completeSourceSnapshotIfStable());
    }
}
