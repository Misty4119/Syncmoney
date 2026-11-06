package noietime.syncmoney.economy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OverflowLogInterfaceCompatibilityTest {

    @Test
    void legacyImplementationFailsClosedWhenNonDestructiveReadIsUnavailable() {
        OverflowLogInterface legacy = new LegacyOverflowLog();

        IOException failure = assertThrows(IOException.class, legacy::readWithoutClear);

        assertEquals("Overflow log does not support non-destructive legacy reads", failure.getMessage());
    }

    /** Models an implementation compiled before readWithoutClear was introduced. */
    private static final class LegacyOverflowLog implements OverflowLogInterface {
        @Override
        public void log(EconomyEvent event) {
        }

        @Override
        public List<String> readAndClear() {
            return List.of();
        }

        @Override
        public long getSize() {
            return 0;
        }

        @Override
        public boolean hasOverflowRecords() {
            return false;
        }
    }
}
