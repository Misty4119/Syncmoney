package noietime.syncmoney.benchmark;

import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import noietime.syncmoney.economy.CurrentFacadeBenchmarkAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentFacadeBenchmarkAdapterTest {
    @Test
    void cancelledDepositLeavesBalanceVersionAndQueueUnchanged() throws Exception {
        UUID account = UUID.randomUUID();
        CurrentFacadeBenchmarkAdapter adapter = new CurrentFacadeBenchmarkAdapter();
        try {
            adapter.seed(account, new BigDecimal("100.00"), 7L);
            adapter.enablePreTransactionCancellation("DEPOSIT");

            BigDecimal result = adapter.deposit(account, new BigDecimal("1.00"));
            adapter.awaitDrain(1_000L);

            assertTrue(result.signum() < 0);
            assertEquals(0, adapter.balance(account).compareTo(new BigDecimal("100.00")));
            assertEquals(7L, adapter.version(account));
            assertEquals(0, adapter.queuedEvents());
            assertNull(adapter.latestReceipt(account));
        } finally {
            adapter.close();
        }
    }

    @Test
    void cancelledWithdrawLeavesBalanceVersionAndQueueUnchanged() throws Exception {
        UUID account = UUID.randomUUID();
        CurrentFacadeBenchmarkAdapter adapter = new CurrentFacadeBenchmarkAdapter();
        try {
            adapter.seed(account, new BigDecimal("100.00"), 9L);
            adapter.enablePreTransactionCancellation("WITHDRAW");

            BigDecimal result = adapter.withdraw(account, new BigDecimal("1.00"));
            adapter.awaitDrain(1_000L);

            assertTrue(result.signum() < 0);
            assertEquals(0, adapter.balance(account).compareTo(new BigDecimal("100.00")));
            assertEquals(9L, adapter.version(account));
            assertEquals(0, adapter.queuedEvents());
            assertNull(adapter.latestReceipt(account));
        } finally {
            adapter.close();
        }
    }

    @Test
    void setReceiptCarriesTheBalanceAtItsAcceptedVersion() throws Exception {
        UUID account = UUID.randomUUID();
        CurrentFacadeBenchmarkAdapter adapter = new CurrentFacadeBenchmarkAdapter();
        try {
            adapter.seed(account, new BigDecimal("1000000000000000.00"), 100L);

            BigDecimal result = adapter.set(account, new BigDecimal("1000.00"));
            adapter.awaitDrain(5_000L);

            assertEquals(0, result.compareTo(new BigDecimal("1000.00")));
            assertEquals(101L, adapter.version(account));
            assertEquals(0, adapter.balance(account).compareTo(new BigDecimal("1000.00")));
            Receipt receipt = adapter.latestReceipt(account);
            assertNotNull(receipt);
            assertEquals(101L, receipt.version());
            assertEquals(0, receipt.balance().compareTo(new BigDecimal("1000.00")));
        } finally {
            adapter.close();
        }
    }

    @Test
    void manyDistinctTransferPairsDrainWithExactReceipts() throws Exception {
        int transfers = 1_000;
        List<UUID> senders = new ArrayList<>(transfers);
        List<UUID> receivers = new ArrayList<>(transfers);
        CurrentFacadeBenchmarkAdapter adapter = new CurrentFacadeBenchmarkAdapter();
        try {
            for (int index = 0; index < transfers; index++) {
                UUID sender = UUID.nameUUIDFromBytes(("transfer-sender-" + index).getBytes(StandardCharsets.UTF_8));
                UUID receiver = UUID.nameUUIDFromBytes(("transfer-receiver-" + index).getBytes(StandardCharsets.UTF_8));
                senders.add(sender);
                receivers.add(receiver);
                adapter.seed(sender, new BigDecimal("10000.00"), 7L);
                adapter.seed(receiver, new BigDecimal("10000.00"), 11L);
            }
            for (int index = 0; index < transfers; index++) {
                BigDecimal result = adapter.transfer(senders.get(index), receivers.get(index), new BigDecimal("1.00"));
                assertTrue(result.signum() >= 0,
                        "accepted transfer " + index + " should return a non-negative sender balance; result="
                                + result + ", queued=" + adapter.queuedEvents());
            }
            adapter.awaitDrain(5_000L);

            assertEquals(2L * transfers, adapter.drainedEvents());
            assertEquals(0, adapter.queuedEvents());
            for (int index = 0; index < transfers; index++) {
                UUID sender = senders.get(index);
                UUID receiver = receivers.get(index);
                assertEquals(8L, adapter.version(sender));
                assertEquals(12L, adapter.version(receiver));
                assertEquals(0, adapter.balance(sender).compareTo(new BigDecimal("9999.00")));
                assertEquals(0, adapter.balance(receiver).compareTo(new BigDecimal("10001.00")));
                Receipt senderReceipt = adapter.latestReceipt(sender);
                Receipt receiverReceipt = adapter.latestReceipt(receiver);
                assertNotNull(senderReceipt, "sender receipt should exist after drain");
                assertNotNull(receiverReceipt, "receiver receipt should exist after drain");
                assertEquals(8L, senderReceipt.version());
                assertEquals(12L, receiverReceipt.version());
                assertEquals(0, senderReceipt.balance().compareTo(new BigDecimal("9999.00")));
                assertEquals(0, receiverReceipt.balance().compareTo(new BigDecimal("10001.00")));
            }
        } finally {
            adapter.close();
        }
    }
}
