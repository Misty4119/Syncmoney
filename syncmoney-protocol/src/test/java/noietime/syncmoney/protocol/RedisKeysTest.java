package noietime.syncmoney.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeysTest {

    @Test
    void keepsLegacyBalanceAndVersionKeyLiterals() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertEquals("syncmoney:balance:00000000-0000-0000-0000-000000000001",
                RedisKeys.balance(uuid));
        assertEquals("syncmoney:version:00000000-0000-0000-0000-000000000001",
                RedisKeys.version(uuid));
        assertEquals("syncmoney:balance:*", RedisKeys.balancePattern());
    }

    @Test
    void keepsLegacyChannelLiterals() {
        assertEquals("syncmoney:balance:update", RedisChannels.BALANCE_UPDATE);
        assertEquals("syncmoney:cmi:balance:update", RedisChannels.CMI_BALANCE_UPDATE);
    }
}
