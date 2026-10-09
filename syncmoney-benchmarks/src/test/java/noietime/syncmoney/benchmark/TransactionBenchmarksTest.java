package noietime.syncmoney.benchmark;

import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionBenchmarksTest {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BigDecimal START = new BigDecimal("1000000000000000.00");
    private static final BigDecimal SET_A = new BigDecimal("1000.00");
    private static final BigDecimal SET_B = new BigDecimal("2000.00");

    @Test
    void acceptsHighestReceiptWhenItMatchesTheSubmittedTarget() {
        assertDoesNotThrow(() -> TransactionBenchmarks.verifySetPostcondition(
                ACCOUNT, 100L, 102L, SET_A, new Receipt(SET_A, 102L), SET_A, START));
    }

    @Test
    void rejectsOtherAllowedSetTargetAtHighestAcceptedVersion() {
        assertThrows(IllegalStateException.class, () -> TransactionBenchmarks.verifySetPostcondition(
                ACCOUNT, 100L, 102L, SET_B, new Receipt(SET_B, 102L), SET_A, START));
    }

    @Test
    void acceptsNoSetReceiptsOnlyWhenNoVersionWasAccepted() {
        assertDoesNotThrow(() -> TransactionBenchmarks.verifySetPostcondition(
                ACCOUNT, 100L, 100L, START, null, SET_A, START));
    }
}
