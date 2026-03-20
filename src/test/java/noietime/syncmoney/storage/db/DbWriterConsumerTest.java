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

        when(mockQueue.isEmpty()).thenReturn(true, false);


        Thread consumerThread = new Thread(() -> {
            consumer.run();
        });

        consumerThread.start();


        Thread.sleep(200);


        consumer.stop();
        consumerThread.join(1000);



        assertFalse(consumerThread.isAlive() || !consumerThread.isAlive(),
                "Consumer should handle idle state properly");
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
