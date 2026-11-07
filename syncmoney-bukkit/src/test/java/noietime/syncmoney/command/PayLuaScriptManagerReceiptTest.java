package noietime.syncmoney.command;

import noietime.syncmoney.storage.RedisMutationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayLuaScriptManagerReceiptTest {
    @Test
    void preservesAValidTransferReceipt() {
        List<String> receipt = List.of("90.00", "10.00", "8", "3");

        assertSame(receipt, PayLuaScriptManager.validateTransferReceipt(receipt));
    }

    @Test
    void malformedReceiptIsClassifiedAsUnknownAfterPossibleMutation() {
        RedisMutationException failure = assertThrows(RedisMutationException.class,
                () -> PayLuaScriptManager.validateTransferReceipt(List.of("90.00", "10.00", "8")));

        assertEquals(RedisMutationException.ExecutionState.SENT_UNKNOWN, failure.executionState());
    }

    @Test
    void unparsableReceiptVersionIsClassifiedAsUnknownAfterPossibleMutation() {
        RedisMutationException failure = assertThrows(RedisMutationException.class,
                () -> PayLuaScriptManager.validateTransferReceipt(List.of("90.00", "10.00", "bad", "3")));

        assertEquals(RedisMutationException.ExecutionState.SENT_UNKNOWN, failure.executionState());
    }
}
