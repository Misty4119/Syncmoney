package noietime.syncmoney.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountStateTest {

    @Test
    void preservesSnapshotBalanceRepresentationWithoutChangingVersionOrAccessTime() {
        AccountState state = new AccountState(new BigDecimal("1.005"), 7L, 123L);

        assertEquals(new BigDecimal("1.005"), state.balance());
        assertEquals(7L, state.version());
        assertEquals(123L, state.lastAccessTime());
    }
}
