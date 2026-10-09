package noietime.syncmoney.benchmark;

import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import noietime.syncmoney.economy.CurrentFacadeBenchmarkAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CurrentFacadeBenchmarkAdapterTest {
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
