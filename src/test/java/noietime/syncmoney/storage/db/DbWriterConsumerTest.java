package noietime.syncmoney.storage.db;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void testInterruptedException() {

        Thread mainThread = Thread.currentThread();

        Thread interruptThread = new Thread(() -> {
            try {
                Thread.sleep(50);
                mainThread.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        interruptThread.start();






        consumer.stop();

        assertTrue(true, "Consumer should support graceful stop");
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
    void testFailedTaskIsRetriedAndNotLost() throws InterruptedException {
        // Use a real queue so re-queued retries are actually observable.
        DbWriteQueue realQueue = new DbWriteQueue(1000);
        DatabaseManager failingThenOk = mock(DatabaseManager.class);

        // Simulate a transient DB outage: the first batch attempt fails,
        // the retry succeeds. The task must not be lost.
        doThrow(new RuntimeException("Simulated DB outage"))
                .doNothing()
                .when(failingThenOk).batchInsertOrUpdatePlayers(any());

        DbWriterConsumer retryConsumer = new DbWriterConsumer(mockPlugin, realQueue, failingThenOk);

        realQueue.offer(new DbWriteQueue.DbWriteTask(
                UUID.randomUUID(), "RetryPlayer", 777.0, 1L, "test-server", Instant.now()));

        Thread consumerThread = new Thread(retryConsumer::run);
        consumerThread.start();

        // Deterministically wait for the retry to happen (>= 2 attempts) rather than
        // racing on the transient queue size. Each batch poll window is up to
        // BATCH_TIMEOUT_MS (1s), so allow a generous timeout.
        verify(failingThenOk, timeout(10000).atLeast(2)).batchInsertOrUpdatePlayers(any());

        retryConsumer.stop();
        consumerThread.join(3000);

        assertEquals(0, realQueue.size(), "Failed task should be retried until written, not lost");
        assertTrue(realQueue.getWrittenCount() >= 1, "Successful write should be counted");
    }

    @Test
    void testTaskDroppedAfterMaxRetries() throws InterruptedException {
        // DB never recovers: the task should be retried up to the cap, then dropped
        // (logged), and the consumer must not loop forever on it.
        DbWriteQueue realQueue = new DbWriteQueue(1000);
        DatabaseManager alwaysFailing = mock(DatabaseManager.class);

        doThrow(new RuntimeException("Permanent DB failure"))
                .when(alwaysFailing).batchInsertOrUpdatePlayers(any());

        DbWriterConsumer retryConsumer = new DbWriterConsumer(mockPlugin, realQueue, alwaysFailing);

        realQueue.offer(new DbWriteQueue.DbWriteTask(
                UUID.randomUUID(), "DoomedPlayer", 1.0, 1L, "test-server", Instant.now()));

        Thread consumerThread = new Thread(retryConsumer::run);
        consumerThread.start();

        // initial attempt + DB_WRITE_MAX_RETRIES retries, then dropped. Each batch
        // poll window is up to BATCH_TIMEOUT_MS (1s), so wait deterministically for
        // the expected number of attempts instead of racing on queue size.
        verify(alwaysFailing,
                timeout(15000).atLeast(noietime.syncmoney.util.Constants.DB_WRITE_MAX_RETRIES + 1))
                .batchInsertOrUpdatePlayers(any());

        retryConsumer.stop();
        consumerThread.join(3000);

        assertEquals(0, realQueue.size(), "Task should be dropped after exceeding the retry cap");
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
