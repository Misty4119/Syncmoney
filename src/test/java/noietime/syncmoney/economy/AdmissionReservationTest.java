package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdmissionReservationTest {

    @Test
    void reservationsAreBoundedAndClosingTwiceReleasesOneSlot() {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID firstAccount = UUID.randomUUID();
        UUID secondAccount = UUID.randomUUID();
        UUID thirdAccount = UUID.randomUUID();

        EconomyWriteQueue.Reservation first = queue.tryReserve(firstAccount);
        EconomyWriteQueue.Reservation second = queue.tryReserve(secondAccount);
        EconomyWriteQueue.Reservation rejected = queue.tryReserve(thirdAccount);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(rejected, "all configured slots must be reserved before another producer can mutate");

        first.close();
        first.close();
        EconomyWriteQueue.Reservation afterRelease = queue.tryReserve(thirdAccount);
        assertNotNull(afterRelease, "closing an unused reservation releases exactly one slot");

        second.close();
        afterRelease.close();
        EconomyWriteQueue.Reservation finalSlot = queue.tryReserve(UUID.randomUUID());
        assertNotNull(finalSlot);
        finalSlot.close();
    }

    @Test
    void committedReservationRemainsOwnedThroughInflightWorkUntilCompletion() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        UUID account = UUID.randomUUID();
        EconomyWriteQueue.Reservation reservation = queue.tryReserve(account);
        EconomyEvent event = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "reservation-test", System.currentTimeMillis());

        assertNotNull(reservation);
        assertTrue(reservation.commit(event));
        reservation.close();
        assertTrue(queue.hasAcceptedWrite(account));
        assertNull(queue.tryReserve(UUID.randomUUID()), "commit must keep the slot until worker completion");

        assertSame(event, queue.poll(0, TimeUnit.MILLISECONDS));
        assertFalse(queue.hasPending(account), "legacy transfer guard tracking covers queued work only");
        assertTrue(queue.hasAcceptedWrite(account), "polling moves work in flight but does not complete it");
        assertTrue(queue.oldestAcceptedAgeMillis() >= 0,
                "the oldest accepted age must include work held by the worker or retry path");
        assertNull(queue.tryReserve(UUID.randomUUID()), "in-flight work still consumes bounded admission capacity");

        queue.complete(event);
        assertFalse(queue.hasAcceptedWrite(account));
        assertEquals(-1, queue.oldestAcceptedAgeMillis(), "completed work is removed from accepted-age tracking");
        assertNotNull(queue.tryReserve(UUID.randomUUID()), "worker completion releases the committed slot");
    }

    @Test
    void workerCannotPollAnEnvelopeBeforeReservationCommit() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        UUID account = UUID.randomUUID();
        EconomyWriteQueue.Reservation reservation = queue.tryReserve(account);
        EconomyEvent event = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "commit-barrier", System.currentTimeMillis());

        assertNotNull(reservation);
        assertNull(queue.poll(0, TimeUnit.MILLISECONDS),
                "a reserved slot has no visible envelope for the worker until commit");
        assertTrue(reservation.commit(event));
        assertSame(event, queue.poll(0, TimeUnit.MILLISECONDS));
        queue.complete(event);
    }

    @Test
    void completingOneEqualEventDoesNotRemoveAnotherReservation() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID account = UUID.randomUUID();
        EconomyEvent first = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "duplicate-value", 10L);
        EconomyEvent second = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "duplicate-value", 10L);
        assertEquals(first, second, "the fixture must exercise record value equality");

        EconomyWriteQueue.Reservation firstReservation = queue.tryReserve(account);
        EconomyWriteQueue.Reservation secondReservation = queue.tryReserve(account);
        assertNotNull(firstReservation);
        assertNotNull(secondReservation);
        assertTrue(firstReservation.commit(first));
        assertTrue(secondReservation.commit(second));

        assertSame(first, queue.poll(0, TimeUnit.MILLISECONDS));
        queue.complete(first);
        assertEquals(1, queue.size(), "completing the first identity must leave the equal second envelope queued");
        assertSame(second, queue.poll(0, TimeUnit.MILLISECONDS));
        queue.complete(second);
        assertFalse(queue.hasAcceptedWrite(account));
    }

    @Test
    void policyExceptionReleasesReservationWithoutMutatingMemory() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AdmissionReservationTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        PlayerTransactionGuard guard = mock(PlayerTransactionGuard.class);
        when(facade.getPlayerTransactionGuard()).thenReturn(guard);
        doThrow(new IllegalStateException("policy failed")).when(guard)
                .checkTransaction(any(), any(), any(), any(), any());

        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new EconomyFacade.EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        TransactionWriter writer = new TransactionWriter(facade, plugin, mock(SyncmoneyConfig.class), null,
                queue, new FallbackEconomyWrapper(plugin, mock(RedisManager.class)),
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);

        assertThrows(IllegalStateException.class,
                () -> writer.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST));
        assertEquals(new BigDecimal("100.00"), state.get(account).balance());
        assertEquals(7L, state.get(account).version());
        assertNotNull(queue.tryReserve(UUID.randomUUID()), "an exception after reservation must release its slot");
    }
}
