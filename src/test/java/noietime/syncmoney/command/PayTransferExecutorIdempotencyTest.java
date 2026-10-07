package noietime.syncmoney.command;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayTransferExecutorIdempotencyTest {
    @Test
    void inFlightTransferCannotBeReplacedByAnotherOperationAfterTtl() {
        UUID sender = UUID.randomUUID();
        String operation = sender + ":target:10.00";
        String differentOperation = sender + ":other:15.00";
        Map<UUID, String> keys = new ConcurrentHashMap<>();
        keys.put(sender, operation + ":IN_FLIGHT");

        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, differentOperation, 62_000);

        assertEquals(operation + ":IN_FLIGHT", keys.get(sender),
                "an unresolved transfer must not lose its idempotency slot to a different /pay operation");
        assertNotNull(keys.putIfAbsent(sender, differentOperation + ":IN_FLIGHT"),
                "another /pay operation must wait while the first authority outcome is unresolved");
    }

    @Test
    void confirmedReceiptCannotExpireWhilePostReceiptProcessingIsActive() {
        UUID sender = UUID.randomUUID();
        String operation = sender + ":target:10.00";
        Map<UUID, String> keys = new ConcurrentHashMap<>();
        keys.put(sender, operation + ":IN_FLIGHT");

        PayTransferExecutor.markReceiptConfirmed(keys, sender, operation);
        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, operation, 1_000_000);

        assertEquals(operation + ":RECEIPT_CONFIRMED", keys.get(sender),
                "post-receipt processing may not expire the idempotency slot while it is still running");
    }

    @Test
    void confirmedReceiptFailureRemainsQuarantinedPastOrdinaryIdempotencyTtl() {
        UUID sender = UUID.randomUUID();
        String operation = sender + ":target:10.00";
        Map<UUID, String> keys = new ConcurrentHashMap<>();
        keys.put(sender, operation + ":RECEIPT_CONFIRMED");

        PayTransferExecutor.quarantineConfirmedReceipt(keys, sender, operation);
        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, "different-operation", 62_000);

        String quarantine = keys.get(sender);
        assertTrue(PayTransferExecutor.isConfirmedQuarantineKey(quarantine));
        assertEquals(operation + ":CONFIRMED_QUARANTINE", quarantine);
        assertNotNull(keys.putIfAbsent(sender, "different-operation:62000"),
                "a post-receipt failure must keep the sender quarantined until reconciliation or restart");
    }

    @Test
    void successfulConfirmedReceiptStartsOrdinaryDuplicateWindowAtCompletion() {
        UUID sender = UUID.randomUUID();
        String operation = sender + ":target:10.00";
        Map<UUID, String> keys = new ConcurrentHashMap<>();
        keys.put(sender, operation + ":RECEIPT_CONFIRMED");

        PayTransferExecutor.completeConfirmedTransfer(keys, sender, operation, 62_000);
        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, operation, 121_999);
        assertEquals(operation + ":62000", keys.get(sender));

        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, operation, 122_001);
        assertNull(keys.get(sender));
    }

    @Test
    void sentUnknownTransferRemainsQuarantinedPastOrdinaryIdempotencyTtl() {
        UUID sender = UUID.randomUUID();
        String operation = sender + ":target:10.00";
        Map<UUID, String> keys = new ConcurrentHashMap<>();
        keys.put(sender, operation + ":IN_FLIGHT");

        PayTransferExecutor.quarantineSentUnknown(keys, sender, operation);
        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, "different-operation", 62_000);

        String quarantine = keys.get(sender);
        assertTrue(PayTransferExecutor.isSentUnknownKey(quarantine));
        assertEquals(operation + ":SENT_UNKNOWN", quarantine);
        assertNotNull(keys.putIfAbsent(sender, "different-operation:62000"),
                "another /pay operation must not replace an unresolved transfer quarantine");
    }

    @Test
    void knownFailureStillExpiresOrdinaryIdempotencyEntry() {
        UUID sender = UUID.randomUUID();
        String operation = sender + ":target:10.00";
        Map<UUID, String> keys = new ConcurrentHashMap<>();
        keys.put(sender, operation + ":1000");

        PayTransferExecutor.cleanupIdempotencyKeys(keys, sender, "different-operation", 62_000);

        assertNull(keys.get(sender));
    }
}
