package noietime.syncmoney.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class MemoryStateManagerApiVisibilityTest {

    @Test
    void apiSnapshotTreatsSyntheticFallbackAsUnavailable() {
        MemoryStateManager state = newStateManager();
        UUID uuid = UUID.randomUUID();
        state.computeIfAbsentUnverified(uuid,
                key -> new EconomyFacade.EconomyState(BigDecimal.ZERO, 0L, System.currentTimeMillis()));

        assertNull(state.getConfirmedStateForApi(uuid));
    }

    @Test
    void apiSnapshotReturnsCacheMissWithoutWaitingForAccountLease() throws Exception {
        MemoryStateManager state = newStateManager();
        UUID uuid = UUID.randomUUID();
        state.computeIfAbsentUnverified(uuid,
                key -> new EconomyFacade.EconomyState(BigDecimal.ZERO, 0L, System.currentTimeMillis()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<EconomyFacade.EconomyState> snapshot;
            try (MemoryStateManager.AccountMutationLease ignored = state.lockAccounts(uuid)) {
                CountDownLatch readerStarted = new CountDownLatch(1);
                snapshot = executor.submit(() -> {
                    readerStarted.countDown();
                    return state.getConfirmedStateForApi(uuid);
                });

                assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
                assertNull(snapshot.get(1, TimeUnit.SECONDS),
                        "a cache lookup contending with an account mutation must immediately report a miss");
                state.cacheConfirmedBalance(uuid, new BigDecimal("9.00"), 1L, System.currentTimeMillis());
            }

            assertNull(snapshot.get(2, TimeUnit.SECONDS), "the contended lookup must not wait and return a later value");
            EconomyFacade.EconomyState confirmed = state.getConfirmedStateForApi(uuid);
            assertNotNull(confirmed);
            assertEquals(new BigDecimal("9.00"), confirmed.balance());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void remoteConfirmedReplacementReleasesSyntheticProvenanceMarker() throws Exception {
        MemoryStateManager state = newStateManager();
        UUID uuid = UUID.randomUUID();
        state.computeIfAbsentUnverified(uuid,
                key -> new EconomyFacade.EconomyState(BigDecimal.ZERO, 0L, System.currentTimeMillis()));

        assertEquals(MemoryStateManager.RemoteUpdateResult.APPLIED,
                state.applyRemoteUpdateResult(uuid, new BigDecimal("9.00"), 1L));
        assertNull(provenanceMarkers(state).get(uuid),
                "replacing a synthetic state with confirmed remote state must release its provenance marker");

        state.clearEntry(uuid);
        assertNull(provenanceMarkers(state).get(uuid), "eviction must not leave a stale provenance marker");
    }

    @Test
    void directTransferReceiptReleasesSyntheticProvenanceMarker() throws Exception {
        MemoryStateManager state = newStateManager();
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        state.put(sender, new EconomyFacade.EconomyState(new BigDecimal("100.00"), 1L,
                System.currentTimeMillis()));
        state.computeIfAbsentUnverified(receiver,
                key -> new EconomyFacade.EconomyState(BigDecimal.ZERO, 0L, System.currentTimeMillis()));

        assertTrue(state.beginDirectTransferMutation("api-transfer", sender, receiver));
        state.completeDirectTransferMutation("api-transfer", sender, new BigDecimal("90.00"), 2L,
                receiver, new BigDecimal("10.00"), 1L);

        assertEquals(new BigDecimal("10.00"), state.get(receiver).balance());
        assertNull(provenanceMarkers(state).get(receiver),
                "a confirmed direct-transfer receipt must release the replaced synthetic marker");
    }

    @Test
    void expirationEvictionReleasesObsoleteProvenanceMarkerByAccount() throws Exception {
        MemoryStateManager state = newStateManager();
        UUID uuid = UUID.randomUUID();
        long expiredAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31);
        state.put(uuid, new EconomyFacade.EconomyState(new BigDecimal("9.00"), 1L, expiredAt));
        provenanceMarkers(state).put(uuid,
                new EconomyFacade.EconomyState(BigDecimal.ZERO, 0L, expiredAt - 1L));

        assertEquals(1, state.cleanupExpiredEntries());
        assertNull(state.get(uuid));
        assertNull(provenanceMarkers(state).get(uuid),
                "evicting a confirmed replacement must clear any obsolete marker for the same UUID");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, EconomyFacade.EconomyState> provenanceMarkers(MemoryStateManager state)
            throws ReflectiveOperationException {
        var field = MemoryStateManager.class.getDeclaredField("unverifiedBalanceStates");
        field.setAccessible(true);
        return (java.util.Map<UUID, EconomyFacade.EconomyState>) field.get(state);
    }

    @Test
    void clearEntryWaitsForAccountSnapshotLeaseAndRemovesFallback() throws Exception {
        MemoryStateManager state = newStateManager();
        UUID uuid = UUID.randomUUID();
        state.computeIfAbsentUnverified(uuid,
                key -> new EconomyFacade.EconomyState(BigDecimal.ZERO, 0L, System.currentTimeMillis()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> clear;
            try (MemoryStateManager.AccountMutationLease ignored = state.lockAccounts(uuid)) {
                CountDownLatch clearStarted = new CountDownLatch(1);
                clear = executor.submit(() -> {
                    clearStarted.countDown();
                    state.clearEntry(uuid);
                });

                assertTrue(clearStarted.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> clear.get(100, TimeUnit.MILLISECONDS),
                        "eviction must serialize with the account snapshot lease");
            }

            clear.get(2, TimeUnit.SECONDS);
            assertNull(state.get(uuid));
            assertNull(state.getConfirmedStateForApi(uuid));
        } finally {
            executor.shutdownNow();
        }
    }

    private static MemoryStateManager newStateManager() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryStateManagerApiVisibilityTest"));
        return new MemoryStateManager(plugin);
    }
}
