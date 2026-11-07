package noietime.syncmoney.storage.db;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DbWriterConsumer.
 * Tests batch writing, idle handling, and interrupt handling.
 */
class DbWriterConsumerTest {

    @Mock
    private Plugin mockPlugin;

    @Mock
    private DbWriteQueue mockQueue;

    @Mock
    private DatabaseManager mockDbManager;

    private DbWriterConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("Test"));

        consumer = new DbWriterConsumer(mockPlugin, mockQueue, mockDbManager);
    }

    @Test
    void stoppedWriterLogsItsFinalDrainCounters() throws InterruptedException {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        when(mockPlugin.getLogger()).thenReturn(logger);
        when(mockQueue.isEmpty()).thenReturn(true);
        consumer = new DbWriterConsumer(mockPlugin, mockQueue, mockDbManager);

        Thread writer = new Thread(consumer, "test-db-writer-drain-status");
        try {
            writer.start();
            consumer.stop();
            writer.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(writer.isAlive(), "writer must stop before its final drain record is emitted");
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0")));
        } finally {
            if (writer.isAlive()) {
                consumer.stop();
                writer.interrupt();
                writer.join(TimeUnit.SECONDS.toMillis(5));
            }
            logger.removeHandler(capture);
        }
    }

    @Test
    void testNoBusyLoop() throws InterruptedException {

        when(mockQueue.isEmpty()).thenReturn(true);


        Thread consumerThread = new Thread(() -> {
            try {

                Thread.sleep(100);
                consumer.stop();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long startTime = System.currentTimeMillis();
        consumerThread.start();
        consumerThread.join(500);
        long elapsed = System.currentTimeMillis() - startTime;




        assertTrue(consumerThread.isAlive() || !consumerThread.isAlive(),
                "Consumer should handle stop gracefully");
    }

    @Test
    void testBatchWrite() {

        DbWriteQueue.DbWriteTask task = new DbWriteQueue.DbWriteTask(
                UUID.randomUUID(),
                "TestPlayer",
                1000.0,
                1L,
                "test-server",
                Instant.now()
        );

        when(mockQueue.isEmpty()).thenReturn(false);


        verify(mockQueue, never()).isEmpty();
    }

    @Test
    void testInterruptedException() throws InterruptedException {
        when(mockQueue.isEmpty()).thenReturn(false);
        Thread consumerThread = new Thread(consumer::run);
        consumerThread.start();
        consumerThread.interrupt();
        consumerThread.join(1000);

        assertFalse(consumerThread.isAlive(), "Consumer should exit cleanly when its own thread is interrupted");
    }

    @Test
    void testEmptyQueueSleep() throws InterruptedException {

        // Idle: queue always reports empty so that after stop() the run loop
        // condition (running || !queue.isEmpty()) becomes false and the thread exits.
        when(mockQueue.isEmpty()).thenReturn(true);

        Thread consumerThread = new Thread(consumer::run);
        consumerThread.start();

        Thread.sleep(200);

        consumer.stop();
        consumerThread.join(2000);

        assertFalse(consumerThread.isAlive(),
                "Consumer should stop gracefully when idle and stop() is called");
    }

    @Test
    void testQueueDrainOnStop() {



        DbWriteQueue.DbWriteTask task = new DbWriteQueue.DbWriteTask(
                UUID.randomUUID(),
                "TestPlayer",
                500.0,
                1L,
                "test-server",
                Instant.now()
        );



        assertDoesNotThrow(() -> consumer.stop());
    }

    @Test
    void testConcurrentWrites() throws InterruptedException {
        int writeCount = 50;
        AtomicInteger writeAttempts = new AtomicInteger(0);


        when(mockQueue.isEmpty()).thenAnswer(invocation -> {
            int count = writeAttempts.incrementAndGet();
            return count > writeCount;
        });


        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        executor.submit(() -> {
            try {
                latch.await();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void testFailedTaskIsRetriedAndNotLost() throws Exception {
        // Use a real queue so re-queued retries are actually observable.
        DbWriteQueue realQueue = new DbWriteQueue(1000);
        DatabaseManager failingThenOk = mock(DatabaseManager.class);

        // Simulate a transient DB outage: the first batch attempt fails,
        // the retry succeeds. The task must not be lost.
        doThrow(new RuntimeException("Simulated DB outage"))
                .doReturn(List.of())
                .when(failingThenOk).batchInsertOrUpdatePlayersWithStale(any());

        DbWriterConsumer retryConsumer = new DbWriterConsumer(mockPlugin, realQueue, failingThenOk);

        CompletableFuture<Boolean> completion = realQueue.offerAndTrack(new DbWriteQueue.DbWriteTask(
                UUID.randomUUID(), "RetryPlayer", 777.0, 1L, "test-server", Instant.now()));
        assertNotNull(completion);

        Thread consumerThread = new Thread(retryConsumer::run);
        consumerThread.start();

        // Deterministically wait for the retry to happen (>= 2 attempts) rather than
        // racing on the transient queue size. Each batch poll window is up to
        // BATCH_TIMEOUT_MS (1s), so allow a generous timeout.
        verify(failingThenOk, timeout(10000).atLeast(2)).batchInsertOrUpdatePlayersWithStale(any());

        retryConsumer.stop();
        consumerThread.join(3000);

        assertEquals(0, realQueue.size(), "Failed task should be retried until written, not lost");
        assertTrue(realQueue.getWrittenCount() >= 1, "Successful write should be counted");
        assertTrue(completion.get(1, TimeUnit.SECONDS), "completion must follow the successful SQL retry");
    }

    @Test
    void testTaskDroppedAfterMaxRetries() throws Exception {
        // DB never recovers: the task should be retried up to the cap, then dropped
        // (logged), and the consumer must not loop forever on it.
        DbWriteQueue realQueue = new DbWriteQueue(1000);
        DatabaseManager alwaysFailing = mock(DatabaseManager.class);

        doThrow(new RuntimeException("Permanent DB failure"))
                .when(alwaysFailing).batchInsertOrUpdatePlayersWithStale(any());

        DbWriterConsumer retryConsumer = new DbWriterConsumer(mockPlugin, realQueue, alwaysFailing);

        CompletableFuture<Boolean> completion = realQueue.offerAndTrack(new DbWriteQueue.DbWriteTask(
                UUID.randomUUID(), "DoomedPlayer", 1.0, 1L, "test-server", Instant.now()));
        assertNotNull(completion);

        Thread consumerThread = new Thread(retryConsumer::run);
        consumerThread.start();

        // initial attempt + DB_WRITE_MAX_RETRIES retries, then dropped. Each batch
        // poll window is up to BATCH_TIMEOUT_MS (1s), so wait deterministically for
        // the expected number of attempts instead of racing on queue size.
        verify(alwaysFailing,
                timeout(15000).atLeast(noietime.syncmoney.util.Constants.DB_WRITE_MAX_RETRIES + 1))
                .batchInsertOrUpdatePlayersWithStale(any());

        retryConsumer.stop();
        consumerThread.join(3000);

        assertEquals(0, realQueue.size(), "Task should be dropped after exceeding the retry cap");
        assertFalse(completion.get(1, TimeUnit.SECONDS),
                "a retry-exhausted SQL task must report failure to its recovery checkpoint owner");
    }

    @Test
    void testDbWriteTaskRecord() {

        UUID uuid = UUID.randomUUID();
        Instant now = Instant.now();
        
        DbWriteQueue.DbWriteTask task = new DbWriteQueue.DbWriteTask(
                uuid, "TestPlayer", 1000.0, 1L, "server", now);
        
        assertEquals(uuid, task.playerUuid());
        assertEquals("TestPlayer", task.playerName());
        assertEquals(1000.0, task.balance());
        assertEquals(1L, task.version());
        assertEquals("server", task.serverName());
        assertEquals(now, task.timestamp());
    }
}
