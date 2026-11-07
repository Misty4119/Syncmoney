package noietime.syncmoney.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class QueueBenchmarkAdapterTest {
    @Test
    void concurrentProducersDoNotLoseTheirAcceptedQueueEntry() throws Exception {
        try (QueueBenchmarkAdapter adapter = new QueueBenchmarkAdapter(50_000);
             ExecutorService producers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> first = producers.submit(() -> produce(adapter, 1, ready, start));
            Future<?> second = producers.submit(() -> produce(adapter, 2, ready, start));

            ready.await();
            start.countDown();

            first.get();
            second.get();
        }
    }

    private static void produce(QueueBenchmarkAdapter adapter, int producer,
                                CountDownLatch ready, CountDownLatch start) {
        UUID account = UUID.nameUUIDFromBytes(("queue-regression-" + producer)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BigDecimal one = new BigDecimal("1.00");
        ready.countDown();
        try {
            start.await();
            for (int i = 0; i < 100_000; i++) {
                EconomyEvent event = new EconomyEvent(account, one, one, i + 1L,
                        EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST,
                        "queue-regression-" + producer + "-" + i, i + 1L, "benchmark-test");
                adapter.offerPollComplete(event);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
