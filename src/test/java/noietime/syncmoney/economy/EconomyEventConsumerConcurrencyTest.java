package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Concurrency test for {@link EconomyEventConsumer}.
 *
 * <p>Scenario: 50 producer threads concurrently submit 1000 unique events into a real
 * {@link EconomyWriteQueue} while a single consumer drains it on its own thread.
 *
 * <p>Verifies two properties of the pipeline:
 * <ul>
 *   <li><b>Zero loss</b> – every submitted event is processed.</li>
 *   <li><b>Zero duplication</b> – no event is processed more than once.</li>
 * </ul>
 *
 * <p>Each event uses a unique player UUID, so the recorded call count per UUID must be
 * exactly 1: a missing UUID means a lost event, a count &gt; 1 means a duplicate.
 */
class EconomyEventConsumerConcurrencyTest {

    @Mock private Plugin mockPlugin;
    @Mock private SyncmoneyConfig mockConfig;
    @Mock private CacheManager mockCacheManager;
    @Mock private RedisManager mockRedisManager;
    @Mock private DbWriteQueue mockDbWriteQueue;
    @Mock private NameResolver mockNameResolver;
    @Mock private DatabaseManager mockDatabaseManager;

    private EconomyWriteQueue queue;
    private EconomyEventConsumer consumer;

    private final ConcurrentHashMap<UUID, AtomicInteger> processed = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("ConcurrencyTest"));

        // Keep processing on the in-memory success path; downstream channels are disabled.
        when(mockConfig.isPubsubEnabled()).thenReturn(false);
        when(mockConfig.isDbEnabled()).thenReturn(false);

        // Record each processed event by its (unique) UUID. A positive return value keeps
        // processEvent on the success path. Duplicates would bump the per-UUID counter above 1.
        when(mockCacheManager.atomicAddBalance(any(UUID.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    UUID uuid = invocation.getArgument(0);
                    processed.computeIfAbsent(uuid, k -> new AtomicInteger()).incrementAndGet();
                    return BigDecimal.TEN;
                });

        // Real queue with the production default capacity (50000). audit/baltop/shadow/overflow
        // collaborators are null so processEventSteps is a no-op for TEST-sourced events.
        queue = new EconomyWriteQueue(50000, java.util.logging.Logger.getLogger("ConcurrencyTestQueue"));
        consumer = new EconomyEventConsumer(
                mockPlugin, mockConfig, queue, mockCacheManager,
                mockRedisManager, mockDbWriteQueue,
                null, null, mockNameResolver, null, null,
                null, mockDatabaseManager);
    }

    @Test
    void testZeroLossZeroDuplicationUnderConcurrentSubmission() throws InterruptedException {
        final int producerThreads = 50;
        final int eventsPerThread = 20;
        final int totalEvents = producerThreads * eventsPerThread; // 1000

        // Pre-generate unique UUIDs so we can assert exact-once processing.
        List<UUID> uuids = new ArrayList<>(totalEvents);
        for (int i = 0; i < totalEvents; i++) {
            uuids.add(UUID.randomUUID());
        }

        consumer.start();

        ExecutorService producers = Executors.newFixedThreadPool(producerThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(producerThreads);

        for (int t = 0; t < producerThreads; t++) {
            final int threadIndex = t;
            producers.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < eventsPerThread; i++) {
                        UUID uuid = uuids.get(threadIndex * eventsPerThread + i);
                        EconomyEvent event = new EconomyEvent(
                                uuid,
                                BigDecimal.ONE,
                                BigDecimal.TEN,
                                1L,
                                EconomyEvent.EventType.DEPOSIT,
                                EconomyEvent.EventSource.TEST,
                                uuid.toString(),
                                System.currentTimeMillis());
                        // Block until accepted so a producer never silently drops an event.
                        while (!queue.offerWithTimeout(event)) {
                            if (Thread.currentThread().isInterrupted()) {
                                return;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(15, TimeUnit.SECONDS), "Producers should finish submitting all events");

        // Wait for the consumer to drain everything.
        long deadline = System.currentTimeMillis() + 15000;
        while (processed.size() < totalEvents && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        producers.shutdownNow();
        consumer.shutdown();

        // Zero loss: every unique event was processed.
        assertEquals(totalEvents, processed.size(),
                "Every submitted event must be processed (zero loss)");

        // Zero duplication: no UUID was processed more than once.
        for (UUID uuid : uuids) {
            AtomicInteger count = processed.get(uuid);
            assertNotNull(count, "Missing processed event for " + uuid + " (lost event)");
            assertEquals(1, count.get(),
                    "Event " + uuid + " processed " + count.get() + " times (expected exactly 1)");
        }
    }
}
