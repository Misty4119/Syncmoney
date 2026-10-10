package noietime.syncmoney.economy;
import noietime.syncmoney.core.state.AccountState;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdmissionReservationTest {

    private static final class CountingLinkedBlockingQueue extends LinkedBlockingQueue<EconomyEvent> {
        private static final long serialVersionUID = 1L;
        private final AtomicInteger inspectedEvents = new AtomicInteger();
        private final AtomicReference<PollPause> pollPause = new AtomicReference<>();

        private CountingLinkedBlockingQueue(int capacity) {
            super(capacity);
        }

        @Override
        public boolean removeIf(Predicate<? super EconomyEvent> filter) {
            return super.removeIf(event -> {
                inspectedEvents.incrementAndGet();
                return filter.test(event);
            });
        }

        void pauseAfterNextDequeue() {
            pollPause.set(new PollPause());
        }

        @Override
        public EconomyEvent poll() {
            try {
                return afterDequeue(super.poll());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pausing test dequeue", interrupted);
            }
        }

        @Override
        public EconomyEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
            return afterDequeue(super.poll(timeout, unit));
        }

        private EconomyEvent afterDequeue(EconomyEvent event) throws InterruptedException {
            PollPause pause = pollPause.get();
            if (event != null && pause != null && pollPause.compareAndSet(pause, null)) {
                pause.dequeued.countDown();
                pause.resume.await();
            }
            return event;
        }
    }

    private static final class PollPause {
        private final CountDownLatch dequeued = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
    }

    private static CountingLinkedBlockingQueue observeQueue(EconomyWriteQueue queue, int capacity)
            throws ReflectiveOperationException {
        CountingLinkedBlockingQueue observed = new CountingLinkedBlockingQueue(capacity);
        Field queueField = EconomyWriteQueue.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        queueField.set(queue, observed);
        return observed;
    }

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
    void interruptedPollDoesNotDequeueAnAvailableEnvelope() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        UUID account = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "interrupted-poll", System.currentTimeMillis());
        assertTrue(queue.tryReserve(account).commit(event));
        AtomicBoolean interruptionObserved = new AtomicBoolean();
        Thread poller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException expected) {
                interruptionObserved.set(true);
            }
        }, "admission-reservation-interrupted-poll-test");

        poller.start();
        poller.join(1_000L);

        assertFalse(poller.isAlive(), "the interrupted poll must return promptly");
        assertTrue(interruptionObserved.get(), "poll must preserve its interruptible blocking contract");
        assertEquals(1, queue.size(), "an interrupted poll must not consume an available envelope");
        assertTrue(queue.hasPending(account), "an unconsumed envelope retains queued pending state");
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
    void completingDequeuedWriteDoesNotScanQueuedBacklog() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(3);
        CountingLinkedBlockingQueue observed = observeQueue(queue, 3);
        UUID account = UUID.randomUUID();
        EconomyEvent first = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "dequeued-completion", 1L);
        EconomyEvent second = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("102.00"), 9L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "queued-backlog", 2L);
        EconomyEvent third = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("103.00"), 10L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "queued-backlog", 3L);

        assertTrue(queue.tryReserve(account).commit(first));
        assertTrue(queue.tryReserve(account).commit(second));
        assertTrue(queue.tryReserve(account).commit(third));
        assertSame(first, queue.poll(0, TimeUnit.MILLISECONDS));

        queue.complete(first);

        assertEquals(0, observed.inspectedEvents.get(),
                "completing an event already removed by poll must not scan the queued backlog");
        assertEquals(2, queue.size(), "completion must leave unrelated accepted envelopes queued");

        assertSame(second, queue.poll(0, TimeUnit.MILLISECONDS));
        queue.complete(second);
        assertSame(third, queue.poll(0, TimeUnit.MILLISECONDS));
        queue.complete(third);
    }

    @Test
    void completionCannotObserveHalfFinishedDequeueTransition() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(3);
        CountingLinkedBlockingQueue observed = observeQueue(queue, 3);
        UUID account = UUID.randomUUID();
        EconomyEvent first = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "dequeue-transition", 1L);
        EconomyEvent second = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("102.00"), 9L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "dequeue-transition-backlog", 2L);
        EconomyEvent third = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("103.00"), 10L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "dequeue-transition-backlog", 3L);

        assertTrue(queue.tryReserve(account).commit(first));
        assertTrue(queue.tryReserve(account).commit(second));
        assertTrue(queue.tryReserve(account).commit(third));
        observed.pauseAfterNextDequeue();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        PollPause pause = observed.pollPause.get();
        AtomicReference<Thread> completionThread = new AtomicReference<>();
        CountDownLatch completionStarted = new CountDownLatch(1);
        try {
            Future<EconomyEvent> poll = executor.submit(() -> queue.poll(1, TimeUnit.SECONDS));
            assertTrue(pause.dequeued.await(1, TimeUnit.SECONDS),
                    "the test queue must pause after the underlying queue has removed the event");
            Future<?> completion = executor.submit(() -> {
                completionThread.set(Thread.currentThread());
                completionStarted.countDown();
                queue.complete(first);
            });
            assertTrue(completionStarted.await(1, TimeUnit.SECONDS));

            boolean waitedForTransition = awaitTransitionWait(completionThread.get());
            int scansDuringTransition = observed.inspectedEvents.get();
            pause.resume.countDown();

            assertSame(first, poll.get(1, TimeUnit.SECONDS));
            completion.get(1, TimeUnit.SECONDS);
            assertEquals(0, scansDuringTransition,
                    "completion must not inspect backlog while poll has physically dequeued the event");
            assertTrue(waitedForTransition,
                    "completion must wait until poll updates queued identity membership");
            assertEquals(0, observed.inspectedEvents.get(),
                    "completion during dequeue must not scan the remaining queue backlog");
            assertEquals(2, queue.size(), "the transition must preserve unrelated queued envelopes");
        } finally {
            pause.resume.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void completingQueuedWriteRemovesOnlyItsIdentityAndReleasesOwnership() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        CountingLinkedBlockingQueue observed = observeQueue(queue, 2);
        UUID account = UUID.randomUUID();
        EconomyEvent first = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "queued-completion", 1L);
        EconomyEvent second = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                "queued-completion", 1L);
        assertEquals(first, second, "the queued fixtures must have value equality but distinct identity");
        EconomyWriteQueue.Reservation firstReservation = queue.tryReserve(account);
        EconomyWriteQueue.Reservation secondReservation = queue.tryReserve(account);

        assertNotNull(firstReservation);
        assertNotNull(secondReservation);
        assertTrue(firstReservation.commit(first));
        assertTrue(secondReservation.commit(second));
        assertTrue(queue.hasPending(account));
        assertTrue(queue.hasAcceptedWrite(account));

        queue.complete(first);

        assertTrue(observed.inspectedEvents.get() > 0, "a still-queued event must be located by identity");
        assertEquals(1, queue.size(), "completing one equal event must preserve the other envelope");
        assertTrue(queue.hasPending(account), "the other queued event retains its pending count");
        assertSame(second, queue.poll(0, TimeUnit.MILLISECONDS));
        queue.complete(second);
        assertFalse(queue.hasPending(account), "queued and in-flight completion each release pending once");
        assertFalse(queue.hasAcceptedWrite(account), "completion releases accepted-write ownership");
        EconomyWriteQueue.Reservation next = queue.tryReserve(UUID.randomUUID());
        assertNotNull(next, "completion releases the bounded admission slot");
        next.close();
    }

    private static boolean awaitTransitionWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING) {
                return true;
            }
            if (!thread.isAlive()) {
                return false;
            }
            Thread.sleep(1L);
        }
        Thread.State state = thread.getState();
        return state == Thread.State.BLOCKED || state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING;
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
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
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
