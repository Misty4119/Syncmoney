package noietime.syncmoney.benchmark;

import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import noietime.syncmoney.economy.CurrentFacadeBenchmarkAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
}
