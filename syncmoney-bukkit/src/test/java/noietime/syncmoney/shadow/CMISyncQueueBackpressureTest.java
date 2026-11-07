package noietime.syncmoney.shadow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CMISyncQueueBackpressureTest {

    @Test
    void retainsLatestSnapshotPerUuidAndAcceptsReplacementAtCapacity() {
        CMISyncQueue queue = new CMISyncQueue(10, 1_000L, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try {
            assertTrue(queue.offer(event(first, "1.00")));
            assertTrue(queue.offer(event(second, "2.00")));
            assertTrue(queue.offer(event(first, "3.00")), "existing UUID can be replaced at capacity");
            assertEquals(2, queue.size());

            List<CMISyncQueue.SyncEvent> drained = new ArrayList<>(queue.drainAll());
            assertEquals(2, drained.size());
            assertEquals(new BigDecimal("3.00"), findBalance(drained, first));
            assertEquals(new BigDecimal("2.00"), findBalance(drained, second));
            assertEquals(0L, queue.getRejectedOfferCount());
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void rejectsNewUuidAtCapacityAndCountsTheRejection() {
        CMISyncQueue queue = new CMISyncQueue(10, 1_000L, 1);
        UUID first = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();

        try {
            assertTrue(queue.offer(event(first, "1.00")));
            assertFalse(queue.offer(event(rejected, "2.00")));
            assertEquals(1, queue.size());
            assertEquals(1L, queue.getRejectedOfferCount());

            assertEquals(1, queue.drainAll().size());
            assertEquals(0, queue.size());
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void rejectsOlderAuthorityVersionAndKeepsNewestReceiptSnapshot() {
        CMISyncQueue queue = new CMISyncQueue(10, 1_000L, 1);
        UUID uuid = UUID.randomUUID();
        try {
            assertEquals(CMISyncQueue.OfferResult.ACCEPTED,
                    queue.offerWithStatus(event(uuid, "7.00", 7L)));
            assertEquals(CMISyncQueue.OfferResult.STALE,
                    queue.offerWithStatus(event(uuid, "5.00", 5L)));
            assertEquals(CMISyncQueue.OfferResult.STALE,
                    queue.offerWithStatus(event(uuid, "8.00", 7L)),
                    "a conflicting balance at one authority version must not replace the queued receipt");

            List<CMISyncQueue.SyncEvent> drained = new ArrayList<>(queue.drainAll());
            assertEquals(1, drained.size());
            assertEquals(7L, drained.get(0).authorityVersion());
            assertEquals(new BigDecimal("7.00"), drained.get(0).balance());
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void latestAcceptedSnapshotIsDrainedOrRemainsPendingWhenOfferRacesWithDrain() throws Exception {
        CMISyncQueue queue = new CMISyncQueue(10, 1_000L, 1);
        UUID uuid = UUID.randomUUID();
        int snapshots = 20_000;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ConcurrentLinkedQueue<CMISyncQueue.SyncEvent> drained = new ConcurrentLinkedQueue<>();
        Future<?> producer = workers.submit(() -> {
            for (int i = 0; i < snapshots; i++) {
                assertTrue(queue.offer(event(uuid, Integer.toString(i), i + 1L)));
            }
        });
        Future<?> drainer = workers.submit(() -> {
            while (!producer.isDone() || queue.size() > 0) {
                drained.addAll(queue.drainAll());
                Thread.yield();
            }
        });

        try {
            producer.get(10, TimeUnit.SECONDS);
            drainer.get(10, TimeUnit.SECONDS);
            drained.addAll(queue.drainAll());

            BigDecimal latest = new BigDecimal(Integer.toString(snapshots - 1));
            assertTrue(drained.stream().anyMatch(e -> e.uuid().equals(uuid) && e.balance().equals(latest)),
                    "the last accepted snapshot must be in a completed drain or the queue after the race");
            assertEquals(0L, queue.getRejectedOfferCount());
        } finally {
            workers.shutdownNow();
            queue.shutdown();
        }
    }

    private static CMISyncQueue.SyncEvent event(UUID uuid, String balance) {
        return event(uuid, balance, new BigDecimal(balance).longValueExact());
    }

    private static CMISyncQueue.SyncEvent event(UUID uuid, String balance, long authorityVersion) {
        return new CMISyncQueue.SyncEvent(
                uuid, "player", new BigDecimal(balance), authorityVersion, System.nanoTime());
    }

    private static BigDecimal findBalance(List<CMISyncQueue.SyncEvent> events, UUID uuid) {
        return events.stream()
                .filter(event -> event.uuid().equals(uuid))
                .map(CMISyncQueue.SyncEvent::balance)
                .findFirst()
                .orElseThrow();
    }
}
