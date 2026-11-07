package noietime.syncmoney.protocol;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisKeysContractTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void everyInventoryKeyKeepsItsExistingLiteralAndShape() {
        assertEquals("syncmoney:balance:00000000-0000-0000-0000-000000000001", RedisKeys.balance(PLAYER));
        assertEquals("syncmoney:version:00000000-0000-0000-0000-000000000001", RedisKeys.version(PLAYER));
        assertEquals("syncmoney:balance:*", RedisKeys.balancePattern());
        assertEquals("syncmoney:baltop", RedisKeys.BALTOP);
        assertEquals("syncmoney:baltop:total", RedisKeys.BALTOP_TOTAL);
        assertEquals("syncmoney:online:players", RedisKeys.ONLINE_PLAYERS);
        assertEquals("syncmoney:online:player:00000000-0000-0000-0000-000000000001",
                RedisKeys.onlinePlayer(PLAYER));
        assertEquals("syncmoney:audit:recent", RedisKeys.AUDIT_RECENT);
        assertEquals("syncmoney:audit:index", RedisKeys.AUDIT_INDEX);
        assertEquals("syncmoney:audit:dedup:1700000000000:DEPOSIT:00000000-0000-0000-0000-000000000001:1.20",
                RedisKeys.auditDedup(1_700_000_000_000L, "DEPOSIT", PLAYER, new BigDecimal("1.20")));
        assertEquals("syncmoney:bank:alice", RedisKeys.bankBalance("alice"));
        assertEquals("syncmoney:bank:owner:alice", RedisKeys.bankOwner("alice"));
        assertEquals("syncmoney:bank:version:alice", RedisKeys.bankVersion("alice"));
        assertEquals("syncmoney:bank:*", RedisKeys.bankBalancePattern());
        assertEquals("syncmoney:overflow:events", RedisKeys.OVERFLOW_EVENTS);
        assertEquals("syncmoney:lock:00000000-0000-0000-0000-000000000001", RedisKeys.lock(PLAYER));
    }

    @Test
    void cmiMirrorKeysKeepConfiguredPrefixAndLegacySuffixes() {
        assertEquals("syncmoney:cmi:00000000-0000-0000-0000-000000000001",
                RedisKeys.cmiBalance(RedisKeys.CMI_DEFAULT_PREFIX, PLAYER));
        assertEquals("custom:cmi:00000000-0000-0000-0000-000000000001:version",
                RedisKeys.cmiVersion("custom:cmi", PLAYER));
    }

    @Test
    void operationReceiptKeyIsStableBoundedAndDoesNotExposeCallerId() {
        String id = "transfer:00000000-0000-0000-0000-000000000001";
        String key = RedisKeys.operationReceipt(id);

        assertEquals("syncmoney:operation:receipt:" + RedisOperationFingerprint.sha256(id), key);
        assertEquals(key, RedisKeys.operationReceipt(id));
        org.junit.jupiter.api.Assertions.assertFalse(key.contains("transfer:"));
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.operationReceipt(" "));
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.operationReceipt("x".repeat(513)));
    }
}
