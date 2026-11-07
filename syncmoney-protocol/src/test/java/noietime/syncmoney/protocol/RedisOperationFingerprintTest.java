package noietime.syncmoney.protocol;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RedisOperationFingerprintTest {

    private static final UUID FROM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void normalizesMoneyToExistingTwoDecimalMoneyContract() {
        assertEquals(RedisOperationFingerprint.add(FROM, new BigDecimal("1.0")),
                RedisOperationFingerprint.add(FROM, new BigDecimal("1.00")));
        assertEquals(RedisOperationFingerprint.set(FROM, new BigDecimal("1.004")),
                RedisOperationFingerprint.set(FROM, new BigDecimal("1.00")));
    }

    @Test
    void bindsOperationTypeAccountOrderAndMoney() {
        String forward = RedisOperationFingerprint.transfer(FROM, TO, new BigDecimal("2.00"));

        assertNotEquals(forward, RedisOperationFingerprint.transfer(TO, FROM, new BigDecimal("2.00")));
        assertNotEquals(forward, RedisOperationFingerprint.transfer(FROM, TO, new BigDecimal("3.00")));
        assertNotEquals(forward, RedisOperationFingerprint.add(FROM, new BigDecimal("2.00")));
        assertNotEquals(RedisOperationFingerprint.add(FROM, new BigDecimal("2.00")),
                RedisOperationFingerprint.set(FROM, new BigDecimal("2.00")));
    }
}
