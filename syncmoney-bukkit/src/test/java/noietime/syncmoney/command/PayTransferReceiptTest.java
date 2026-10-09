package noietime.syncmoney.command;

import noietime.syncmoney.storage.db.DbWriteQueue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayTransferReceiptTest {

    @Test
    void sqlReceiptPreservesBalancesBeyondDoubleIntegerPrecision() {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BigDecimal senderBalance = new BigDecimal("9007199254740993.01");
        BigDecimal targetBalance = new BigDecimal("9007199254740993.02");

        DbWriteQueue.DbWriteTask task = PayTransferExecutor.createTransferDbTask(
                sender, "sender", senderBalance, 41,
                target, "target", targetBalance, 42,
                "server-a", Instant.parse("2026-10-07T00:00:00Z"));

        assertEquals(2, task.accountCount());
        assertEquals(senderBalance, task.accounts().get(0).account().balance());
        assertEquals(targetBalance, task.accounts().get(1).account().balance());
        assertEquals(sender, task.accounts().get(0).account().playerUuid());
        assertEquals(target, task.accounts().get(1).account().playerUuid());
    }
}
