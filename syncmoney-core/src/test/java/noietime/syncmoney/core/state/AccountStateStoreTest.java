package noietime.syncmoney.core.state;

import noietime.syncmoney.testkit.fixtures.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccountStateStoreTest {

    @Test
    void supportsAtomicSnapshotOperations() {
        AccountStateStore store = new AccountStateStore();
        UUID account = FixtureCatalog.PLAYER_A;
        AccountState initial = new AccountState(new BigDecimal("10.00"), 1L, 1L);

        assertNull(store.get(account));
        assertEquals(initial, store.computeIfAbsent(account, ignored -> initial));
        assertEquals(new AccountState(new BigDecimal("11.00"), 2L, 2L),
                store.compute(account, (ignored, current) ->
                        new AccountState(current.balance().add(BigDecimal.ONE), current.version() + 1, 2L)));
        assertTrue(store.containsKey(account));
        assertEquals(1, store.size());
        assertTrue(store.remove(account, store.get(account)));
        assertFalse(store.containsKey(account));
    }

    @Test
    void reversedPairLockAcquisitionUsesOneStableOrder() throws Exception {
        AccountStateStore store = new AccountStateStore();
        UUID first = FixtureCatalog.PLAYER_A;
        UUID second = FixtureCatalog.PLAYER_B;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inCriticalSection = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> forward = executor.submit(() -> lockRepeatedly(store, first, second, start, inCriticalSection));
            Future<?> reverse = executor.submit(() -> lockRepeatedly(store, second, first, start, inCriticalSection));
            start.countDown();
            forward.get(10, TimeUnit.SECONDS);
            reverse.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, inCriticalSection.get());
    }

    @Test
    void tryLockReportsContentionWithoutBlocking() throws Exception {
        AccountStateStore store = new AccountStateStore();
        UUID account = FixtureCatalog.PLAYER_A;
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> {
                try (AccountStateStore.MutationLease ignored = store.lockAccounts(account)) {
                    locked.countDown();
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            assertTrue(locked.await(2, TimeUnit.SECONDS));
            assertNull(store.tryLockAccount(account));
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
            try (AccountStateStore.MutationLease acquired = store.tryLockAccount(account)) {
                assertNotNull(acquired);
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void lockRepeatedly(AccountStateStore store, UUID first, UUID second,
            CountDownLatch start, AtomicInteger inCriticalSection) {
        try {
            start.await();
            for (int i = 0; i < 1_000; i++) {
                try (AccountStateStore.MutationLease ignored = store.lockAccounts(first, second)) {
                    if (inCriticalSection.incrementAndGet() != 1) {
                        throw new AssertionError("account pair lock allowed overlapping critical sections");
                    }
                    inCriticalSection.decrementAndGet();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
