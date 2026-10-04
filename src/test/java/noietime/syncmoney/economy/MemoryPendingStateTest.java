package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
    void pendingStateSurvivesEvictionAndRejectsStaleRemoteSnapshot() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryPendingStateTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("MemoryPendingStateTest"));
        TransactionWriter writer = new TransactionWriter(
                facade, plugin, mock(SyncmoneyConfig.class), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)),
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);

        BigDecimal afterDeposit = writer.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);
        assertEquals(0, afterDeposit.compareTo(new BigDecimal("110.00")));
        assertTrue(queue.hasPending(account), "the accepted deposit must have pending persistence work");

        state.put(account, new EconomyFacade.EconomyState(
                new BigDecimal("110.00"), 8L, System.currentTimeMillis() - TimeUnitMinutes.THIRTY));
        state.cleanupExpiredEntries();

        boolean staleSnapshotApplied = state.applyRemoteUpdate(account, new BigDecimal("100.00"), 7L);
        EconomyFacade.EconomyState afterStaleSnapshot = state.get(account);
        assertAll(
                () -> assertFalse(staleSnapshotApplied,
                        "an older remote version must not replace an accepted local pending write"),
                () -> assertEquals(new BigDecimal("110.00"), afterStaleSnapshot.balance(),
                        "the local pending balance must remain visible after stale remote delivery"),
                () -> assertEquals(8L, afterStaleSnapshot.version(),
                        "eviction must not permit the account version to move backwards"),
                () -> assertTrue(queue.hasPending(account),
                        "eviction must not discard the accepted queue entry")
        );
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

    private static final class TimeUnitMinutes {
        private static final long THIRTY = 31L * 60L * 1000L;
    }
}
