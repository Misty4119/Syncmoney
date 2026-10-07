package noietime.syncmoney.economy;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryPendingStateTest {

    @Test
    void remoteUpdatesRequireStrictlyNewerVersion() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));

        boolean equalVersionApplied = state.applyRemoteUpdate(account, new BigDecimal("1.00"), 7L);
        EconomyFacade.EconomyState afterEqual = state.get(account);
        boolean olderVersionApplied = state.applyRemoteUpdate(account, new BigDecimal("2.00"), 6L);
        EconomyFacade.EconomyState afterOlder = state.get(account);
        boolean newerVersionApplied = state.applyRemoteUpdate(account, new BigDecimal("110.00"), 8L);
        EconomyFacade.EconomyState afterNewer = state.get(account);

        assertAll(
                () -> assertFalse(equalVersionApplied,
                        "an equal version is not a newly applied remote update even if its value differs"),
                () -> assertEquals(new BigDecimal("100.00"), afterEqual.balance()),
                () -> assertFalse(olderVersionApplied),
                () -> assertEquals(new BigDecimal("100.00"), afterOlder.balance()),
                () -> assertTrue(newerVersionApplied),
                () -> assertEquals(new BigDecimal("110.00"), afterNewer.balance()),
                () -> assertEquals(8L, afterNewer.version())
        );
    }

    @Test
    void pendingStateSurvivesEvictionAndRejectsStaleRemoteSnapshot() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("MemoryPendingStateTest"));
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        TransactionWriter writer = new TransactionWriter(
                facade, plugin, mock(SyncmoneyConfig.class), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)),
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);

        BigDecimal afterDeposit = writer.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);
        assertEquals(0, afterDeposit.compareTo(new BigDecimal("110.00")));
        assertTrue(queue.hasPending(account), "the accepted deposit must have pending persistence work");

        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("110.00"), 8L, System.currentTimeMillis() - TimeUnitMinutes.THIRTY));
        EconomyEvent inFlight = queue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        int removedWhileInFlight = state.cleanupExpiredEntries();

        boolean staleSnapshotApplied = state.applyRemoteUpdate(account, new BigDecimal("100.00"), 7L);
        EconomyFacade.EconomyState afterStaleSnapshot = state.get(account);
        assertAll(
                () -> assertNotNull(inFlight, "the accepted event must reach the worker queue"),
                () -> assertEquals(0, removedWhileInFlight,
                        "cache expiry must preserve an account with work already in flight"),
                () -> assertFalse(staleSnapshotApplied,
                        "an older remote version must not replace an accepted local pending write"),
                () -> assertEquals(new BigDecimal("110.00"), afterStaleSnapshot.balance(),
                        "the local pending balance must remain visible after stale remote delivery"),
                () -> assertEquals(8L, afterStaleSnapshot.version(),
                        "eviction must not permit the account version to move backwards"),
                () -> assertFalse(queue.hasPending(account),
                        "polling removes work from the queue even while the worker still owns it"),
                () -> assertTrue(queue.hasAcceptedWrite(account),
                        "the worker's in-flight write remains pending until completion")
        );

        queue.complete(inFlight);
        assertFalse(queue.hasAcceptedWrite(account), "worker completion releases pending ownership");
    }

    @Test
    void newerRemoteAuthorityConvergesAfterPendingWriteCompletes() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        TransactionWriter writer = new TransactionWriter(
                facade, plugin, mock(SyncmoneyConfig.class), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)),
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);

        assertEquals(new BigDecimal("110.00"), writer.deposit(
                account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST));
        assertFalse(state.applyRemoteUpdate(account, new BigDecimal("120.00"), 9L),
                "new remote authority is deferred while the local accepted write is pending");
        assertEquals(new BigDecimal("110.00"), state.get(account).balance(),
                "the accepted local projection remains visible until its worker finishes");

        CacheManager cache = mock(CacheManager.class);
        when(cache.atomicAddBalanceReceipt(account, new BigDecimal("10.00")))
                .thenReturn(new ConfirmedAccount(account, new BigDecimal("130.00"), 10L));
        SyncmoneyConfig consumerConfig = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(consumerConfig.isPubsubEnabled()).thenReturn(false);
        when(consumerConfig.isDbEnabled()).thenReturn(false);
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, consumerConfig, queue, cache,
                null, null, null, null, null, null, null, null, null);
        consumer.start();
        consumer.shutdown();

        assertAll(
                () -> assertFalse(queue.hasAcceptedWrite(account),
                        "successful worker completion releases accepted write ownership"),
                () -> assertEquals(new BigDecimal("130.00"), state.get(account).balance(),
                        "memory converges to the successful Redis result after applying the accepted local write"),
                () -> assertEquals(10L, state.get(account).version(),
                        "memory version must match the authority version observed by the worker")
        );
    }

    @Test
    void deferredRemoteBalanceWinsWhenCompletionReceiptCarriesItsVersion() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));

        EconomyWriteQueue.Reservation reservation = queue.tryReserve(account);
        EconomyEvent event = new EconomyEvent(account, new BigDecimal("10.00"), new BigDecimal("110.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "mismatched-completion-receipt", System.currentTimeMillis());
        assertNotNull(reservation);
        assertTrue(reservation.commit(event));
        assertEquals(MemoryStateManager.RemoteUpdateResult.DEFERRED,
                state.applyRemoteUpdateResult(account, new BigDecimal("120.00"), 9L));

        // The consumer's balance result may belong to v8 while a later cache version read observes v9.
        queue.complete(event, new BigDecimal("110.00"), 9L);

        assertAll(
                () -> assertEquals(new BigDecimal("120.00"), state.get(account).balance(),
                        "the deferred balance associated with v9 must not be replaced by an earlier write's balance"),
                () -> assertEquals(9L, state.get(account).version(),
                        "the visible balance and version must continue to describe the same authority snapshot")
        );
    }

    @Test
    void uncommittedAdmissionReservationDoesNotDiscardNewerRemoteState() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis() - TimeUnitMinutes.THIRTY));

        EconomyWriteQueue.Reservation reservation = queue.tryReserve(account);
        assertNotNull(reservation);
        try {
            assertEquals(0, state.cleanupExpiredEntries(),
                    "an active reservation must keep its source account state available for the mutation");
            assertTrue(state.applyRemoteUpdate(account, new BigDecimal("120.00"), 8L),
                    "a capacity reservation is not an accepted local write and must not hide a newer remote version");
            assertEquals(new BigDecimal("120.00"), state.get(account).balance());
            assertEquals(8L, state.get(account).version());
        } finally {
            reservation.close();
        }
    }

    @Test
    void readOnlyExpiredStateCanBeEvicted() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis() - TimeUnitMinutes.THIRTY));

        assertNotNull(state.get(account), "a read sees the current cached entry");
        int removed = state.cleanupExpiredEntries();

        assertAll(
                () -> assertEquals(1, removed, "an expired read-only account is eligible for cache eviction"),
                () -> assertNull(state.get(account))
        );
    }

    @Test
    void forceUpdateIsRestrictedToMaintenanceLock() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));

        MigrationLock.disable();
        try {
            state.forceUpdate(account, new BigDecimal("90.00"), 6L);
            assertEquals(new BigDecimal("100.00"), state.get(account).balance(),
                    "an ordinary producer must not force an unversioned state replacement");

            MigrationLock.enable();
            assertTrue(MigrationLock.lock());
            state.forceUpdate(account, new BigDecimal("90.00"), 6L);
            assertEquals(new BigDecimal("90.00"), state.get(account).balance(),
                    "the explicit maintenance path may replace state while producers are locked");
        } finally {
            MigrationLock.unlock();
            MigrationLock.disable();
        }
    }

    @Test
    void forceUpdateCannotReplaceStateWhileAcceptedWorkIsPending() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        EconomyWriteQueue.Reservation reservation = queue.tryReserve(account);
        EconomyEvent event = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "pending-force-update", System.currentTimeMillis());
        assertNotNull(reservation);
        assertTrue(reservation.commit(event));

        MigrationLock.disable();
        MigrationLock.enable();
        assertTrue(MigrationLock.lock());
        try {
            state.forceUpdate(account, new BigDecimal("90.00"), 6L);
            assertEquals(new BigDecimal("100.00"), state.get(account).balance(),
                    "maintenance must not replace state owned by an accepted write");

            queue.complete(event);
            state.forceUpdate(account, new BigDecimal("90.00"), 6L);
            assertEquals(new BigDecimal("90.00"), state.get(account).balance(),
                    "force replacement is available after pending ownership is released");
        } finally {
            MigrationLock.unlock();
            MigrationLock.disable();
        }
    }

    @Test
    void capacityEvictionSkipsTheOldestAccountWithPendingWork() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        long now = System.currentTimeMillis();
        UUID pendingAccount = UUID.randomUUID();
        state.put(pendingAccount, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, now - 1));
        EconomyWriteQueue.Reservation reservation = queue.tryReserve(pendingAccount);
        EconomyEvent event = new EconomyEvent(pendingAccount, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "capacity-pending", now);
        assertNotNull(reservation);
        assertTrue(reservation.commit(event));

        for (int i = 0; i < 10_000; i++) {
            UUID account = UUID.nameUUIDFromBytes(("capacity-account-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            state.put(account, new EconomyFacade.EconomyState(
                    BigDecimal.ZERO, 1L, now + i));
        }

        assertEquals(10_001, state.size());
        state.cleanupExpiredEntries();

        assertNotNull(state.get(pendingAccount), "capacity cleanup must not evict an account with accepted pending work");
        assertTrue(queue.hasAcceptedWrite(pendingAccount));
        assertEquals(10_000, state.size(), "cleanup should evict an unowned entry to enforce the configured cache limit");
        queue.complete(event);
    }

    @Test
    void capacityCleanupDoesNotRemoveEntryRefreshedAfterCandidateSnapshot() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        UUID account = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EconomyFacade.EconomyState observed = new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, now - 1);
        state.put(account, observed);
        for (int i = 0; i < 10_000; i++) {
            UUID filler = UUID.nameUUIDFromBytes(("cleanup-race-" + i)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            state.put(filler, new EconomyFacade.EconomyState(BigDecimal.ZERO, 1L, now + i));
        }

        CountDownLatch updaterOwnsEntry = new CountDownLatch(1);
        CountDownLatch resumeUpdater = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread updater = new Thread(() -> {
            try {
                state.compute(account, (key, current) -> {
                    updaterOwnsEntry.countDown();
                    try {
                        if (!resumeUpdater.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("capacity cleanup barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return new EconomyFacade.EconomyState(
                            new BigDecimal("120.00"), 9L, System.currentTimeMillis() + 60_000);
                });
            } catch (Throwable failure) {
                threadFailure.set(failure);
            }
        }, "memory-capacity-entry-updater");
        Thread cleanup = new Thread(state::cleanupExpiredEntries, "memory-capacity-cleanup");

        try {
            updater.start();
            assertTrue(updaterOwnsEntry.await(5, TimeUnit.SECONDS), "updater must hold the snapshotted entry");
            cleanup.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cleanup.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.BLOCKED, cleanup.getState(),
                    "cleanup should reach its candidate removal while the newer state is held by the updater");
        } finally {
            resumeUpdater.countDown();
            updater.join(TimeUnit.SECONDS.toMillis(5));
            cleanup.join(TimeUnit.SECONDS.toMillis(5));
        }

        if (threadFailure.get() != null) {
            throw new AssertionError("capacity interleaving failed unexpectedly", threadFailure.get());
        }
        EconomyFacade.EconomyState after = state.get(account);
        assertAll(
                () -> assertNotNull(after, "capacity cleanup must preserve an entry changed after its snapshot"),
                () -> assertEquals(new BigDecimal("120.00"), after.balance()),
                () -> assertEquals(9L, after.version())
        );
    }

    private static final class TimeUnitMinutes {
        private static final long THIRTY = 31L * 60L * 1000L;
    }
}
