package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.LogRecord;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerShutdownTest {
    @Test
    void shutdownClosesAdmissionAndDrainsAProducerReservationAcceptedBeforeShutdown() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerStopped = new CountDownLatch(1);
        Handler lifecycleHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().equals("EconomyEventConsumer started.")) {
                    workerStarted.countDown();
                } else if (record.getMessage().equals("EconomyEventConsumer stopped.")) {
                    workerStopped.countDown();
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        lifecycleHandler.setLevel(Level.ALL);
        logger.addHandler(lifecycleHandler);
        when(plugin.getLogger()).thenReturn(logger);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        CacheManager cache = mock(CacheManager.class);
        UUID player = UUID.randomUUID();
        EconomyWriteQueue queue = new EconomyWriteQueue(10);
        EconomyWriteQueue.Reservation acceptedBeforeShutdown = queue.tryReserve(player);
        assertNotNull(acceptedBeforeShutdown);
        when(cache.atomicSetBalance(player, BigDecimal.TEN)).thenReturn(1L);
        when(cache.getVersion(player)).thenReturn(1L);

        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cache,
                null, null, null, null, null, null, null, null, null);
        Thread shutdown = new Thread(consumer::shutdown, "test-consumer-shutdown");
        try {
            consumer.start();
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "consumer worker must start before shutdown");

            shutdown.start();
            long stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (consumer.isRunning() && System.nanoTime() < stopDeadline) {
                Thread.onSpinWait();
            }
            assertFalse(consumer.isRunning(), "shutdown must signal the consumer to stop");

            EconomyWriteQueue.Reservation rejectedDuringShutdown = queue.tryReserve(UUID.randomUUID());
            if (rejectedDuringShutdown != null) {
                rejectedDuringShutdown.close();
            }
            assertNull(rejectedDuringShutdown, "shutdown must close shared write admission before draining");

            assertFalse(workerStopped.await(500, TimeUnit.MILLISECONDS),
                    "consumer must keep draining while a pre-shutdown producer reservation is outstanding");

            EconomyEvent event = new EconomyEvent(player, BigDecimal.TEN, BigDecimal.TEN, 1L,
                    EconomyEvent.EventType.SET_BALANCE, EconomyEvent.EventSource.TEST,
                    "shutdown-reservation", System.currentTimeMillis());
            assertTrue(acceptedBeforeShutdown.commit(event),
                    "a reservation accepted before shutdown must remain committable during drain");

            shutdown.join(5_000);
            assertFalse(shutdown.isAlive(), "shutdown must return after the accepted write is completed");
            assertFalse(queue.hasOutstandingWrites(), "completed accepted write ownership must be released");
            verify(cache).atomicSetBalance(player, BigDecimal.TEN);
        } finally {
            queue.closeAdmission();
            acceptedBeforeShutdown.close();
            if (shutdown.isAlive()) {
                shutdown.join(5_000);
            }
            logger.removeHandler(lifecycleHandler);
        }
    }

    @Test
    void shutdownDrainsAcceptedWritesBeforeReturning() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        CacheManager cache = mock(CacheManager.class);
        UUID player = UUID.randomUUID();
        when(cache.atomicSetBalance(player, BigDecimal.TEN)).thenReturn(1L);
        EconomyWriteQueue queue = new EconomyWriteQueue(100);
        for (int i = 0; i < 20; i++) assertTrue(queue.offer(new EconomyEvent(player,
                BigDecimal.TEN, BigDecimal.TEN, i, EconomyEvent.EventType.SET_BALANCE,
                EconomyEvent.EventSource.TEST, "shutdown-" + i, System.currentTimeMillis())));
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cache,
                null, null, null, null, null, null, null, null, null);
        consumer.start();
        consumer.shutdown();
        assertTrue(queue.isEmpty());
        verify(cache, times(20)).atomicSetBalance(player, BigDecimal.TEN);
    }
}
