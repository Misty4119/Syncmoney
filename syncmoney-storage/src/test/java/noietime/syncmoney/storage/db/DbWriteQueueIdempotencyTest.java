package noietime.syncmoney.storage.db;

import noietime.syncmoney.core.port.ConfirmedAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbWriteQueueIdempotencyTest {

    @Test
    void retriesWhileSqlWriteIsPendingShareTheSameCompletionAndQueueEntry() throws Exception {
        DbWriteQueue queue = new DbWriteQueue(10);
        UUID account = UUID.randomUUID();
        String operationId = "receipt-1";
        CompletableFuture<Boolean> first = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("12.50"), 4L, Instant.parse("2026-01-01T00:00:00Z")));

        CompletableFuture<Boolean> retry = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("12.500"), 4L, Instant.parse("2026-01-01T00:00:08Z")));

        assertNotNull(first);
        assertSame(first, retry);
        assertEquals(1, queue.size());
        DbWriteQueue.DbWriteTask admitted = queue.poll();
        assertEquals(operationId, admitted.operationId());
        admitted.complete(true);
        assertTrue(retry.join());

        CompletableFuture<Boolean> lateRetry = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("12.50"), 4L, Instant.parse("2026-01-01T00:00:10Z")));
        assertTrue(lateRetry.join());
        assertEquals(0, queue.size());
    }

    @Test
    void operationIdCollisionWithDifferentAccountSnapshotFailsClosed() {
        DbWriteQueue queue = new DbWriteQueue(10);
        UUID account = UUID.randomUUID();
        String operationId = "receipt-collision";
        CompletableFuture<Boolean> first = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("12.50"), 4L, Instant.now()));

        CompletableFuture<Boolean> collision = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("13.50"), 5L, Instant.now()));

        assertNotNull(first);
        assertNotNull(collision);
        assertTrue(collision.isCompletedExceptionally());
        assertEquals(1, queue.size());
    }

    @Test
    void failedSqlReceiptCanBeRetriedWithTheSameOperationId() throws Exception {
        DbWriteQueue queue = new DbWriteQueue(10);
        UUID account = UUID.randomUUID();
        String operationId = "receipt-retry";
        CompletableFuture<Boolean> first = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("12.50"), 4L, Instant.now()));
        DbWriteQueue.DbWriteTask failed = queue.poll();
        failed.complete(false);
        assertFalse(first.join());

        CompletableFuture<Boolean> retry = queue.offerAndTrack(task(operationId, account,
                new BigDecimal("12.50"), 4L, Instant.now()));

        assertNotNull(retry);
        assertNotSame(first, retry);
        assertEquals(1, queue.size());
    }

    private static DbWriteQueue.DbWriteTask task(String operationId, UUID uuid, BigDecimal balance,
            long version, Instant timestamp) {
        ConfirmedAccount account = new ConfirmedAccount(uuid, balance, version);
        DbWriteQueue.AccountWrite write = new DbWriteQueue.AccountWrite(
                account, "player", "server", timestamp);
        return new DbWriteQueue.DbWriteTask(operationId, List.of(write));
    }
}
