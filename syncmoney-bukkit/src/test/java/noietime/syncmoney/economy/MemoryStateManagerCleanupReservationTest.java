package noietime.syncmoney.economy;

import noietime.syncmoney.core.state.AccountState;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryStateManagerCleanupReservationTest {

    @Test
    void cleanupAfterReservationKeepsTheSnapshotUsedByTheAcceptedWrite() throws Exception {
        UUID account = UUID.randomUUID();
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        MemoryStateManager states = new MemoryStateManager(plugin(), queue);
        AccountState expired = new AccountState(new BigDecimal("100.00"), 7L,
                System.currentTimeMillis() - (31L * 60L * 1000L));
        states.put(account, expired);

        EconomyWriteQueue.Reservation reservation = states.tryReserveWrite(account, expired);
        assertNotNull(reservation);
        try (reservation) {
            assertTrue(queue.hasAcceptedWrite(account), "the reservation owns the account before commit");
            assertEquals(0, states.cleanupExpiredEntries(),
                    "cleanup must not evict a snapshot while an accepted write reservation owns it");
            assertSame(expired, states.get(account));

            AccountState updated = states.compute(account, (uuid, current) -> new AccountState(
                    current.balance().add(new BigDecimal("10.00")), current.version() + 1,
                    System.currentTimeMillis()));
            EconomyEvent event = new EconomyEvent(account, new BigDecimal("10.00"), updated.balance(),
                    updated.version(), EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                    "cleanup-reservation-commit", System.currentTimeMillis());

            assertTrue(reservation.commit(event));
            assertEquals(new BigDecimal("110.00"), updated.balance());
            assertEquals(event, queue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void cleanupWinningBeforeReservationRejectsTheStaleSnapshotWithoutQueueing() throws Exception {
        UUID account = UUID.randomUUID();
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        MemoryStateManager states = new MemoryStateManager(plugin(), queue);
        AccountState expired = new AccountState(new BigDecimal("100.00"), 7L,
                System.currentTimeMillis() - (31L * 60L * 1000L));
        states.put(account, expired);

        assertEquals(1, states.cleanupExpiredEntries());
        assertNull(states.get(account));
        assertNull(states.tryReserveWrite(account, expired),
                "a transaction holding a stale pre-cleanup snapshot must reload instead of writing from zero");
        assertFalse(queue.hasAcceptedWrite(account));
        assertNull(queue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MemoryStateManagerCleanupReservationTest"));
        return plugin;
    }
}
