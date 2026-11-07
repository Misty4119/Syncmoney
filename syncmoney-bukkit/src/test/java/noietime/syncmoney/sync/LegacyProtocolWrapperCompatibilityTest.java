package noietime.syncmoney.sync;

import com.google.gson.Gson;
import noietime.syncmoney.protocol.LegacyBalanceMessage;
import noietime.syncmoney.protocol.LegacyCmiMessage;
import noietime.syncmoney.protocol.LegacyProtocolCodec;
import noietime.syncmoney.protocol.RedisChannels;
import noietime.syncmoney.protocol.RedisKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyProtocolWrapperCompatibilityTest {

    private static final String PLAYER_UUID = "00000000-0000-0000-0000-000000000001";

    @Test
    void oldDoubleFieldCodecOutputIsReadableByTheNewCodec() {
        LegacyV132Message oldMessage = new LegacyV132Message();
        oldMessage.uuid = PLAYER_UUID;
        oldMessage.balance = 123.45d;
        oldMessage.version = 9_007_199_254_740_993L;
        oldMessage.sourceServer = "legacy-node";
        oldMessage.messageId = "legacy-message";
        oldMessage.timestamp = 1_700_000_000_000L;
        oldMessage.eventType = "DEPOSIT";
        oldMessage.amount = 0.0d;
        oldMessage.sourcePlugin = "LegacyPlugin";
        oldMessage.sourcePlayerName = "Legacy Player";

        LegacyBalanceMessage decoded = LegacyProtocolCodec.decodeBalance(new Gson().toJson(oldMessage))
                .orElseThrow();

        assertEquals(PLAYER_UUID, decoded.uuid());
        assertEquals(9_007_199_254_740_993L, decoded.version());
        assertEquals("legacy-node", decoded.sourceServer());
        assertEquals("legacy-message", decoded.messageId());
        assertEquals(1_700_000_000_000L, decoded.timestamp());
        assertEquals("DEPOSIT", decoded.eventType());
        assertEquals(123.45d, decoded.balance().doubleValue());
        assertEquals("LegacyPlugin", decoded.sourcePlugin());
        assertEquals("Legacy Player", decoded.sourcePlayerName());
    }

    @Test
    void newCodecOutputIsReadableByTheLegacyDoubleFieldCodec() {
        LegacyBalanceMessage message = new LegacyBalanceMessage(
                PLAYER_UUID, new BigDecimal("42.75"), 9_007_199_254_740_993L,
                "new-node", "new-message", 1_700_000_000_001L,
                "FUTURE_EVENT", 1.25d, "Plugin", "Player");

        String json = LegacyProtocolCodec.encodeBalance(message);
        LegacyV132Message oldReader = new Gson().fromJson(json, LegacyV132Message.class);

        assertEquals(PLAYER_UUID, oldReader.uuid);
        assertEquals(42.75d, oldReader.balance);
        assertEquals(9_007_199_254_740_993L, oldReader.version);
        assertEquals(1.25d, oldReader.amount);
        assertEquals("FUTURE_EVENT", oldReader.eventType);
        assertEquals("new-node", oldReader.sourceServer);
        assertEquals("new-message", oldReader.messageId);
        assertEquals(1_700_000_000_001L, oldReader.timestamp);
        assertEquals("Plugin", oldReader.sourcePlugin);
        assertEquals("Player", oldReader.sourcePlayerName);
    }

    @Test
    void legacyPubSubMessageKeepsTheReleasedDoubleSignatures() throws Exception {
        assertNotNull(PubSubMessage.class.getDeclaredConstructor(
                String.class, double.class, long.class, String.class, String.class, long.class));
        assertNotNull(PubSubMessage.class.getDeclaredConstructor(
                String.class, double.class, long.class, String.class, String.class, long.class,
                String.class, double.class));
        assertNotNull(PubSubMessage.class.getDeclaredConstructor(
                String.class, double.class, long.class, String.class, String.class, long.class,
                String.class, double.class, String.class));
        assertNotNull(PubSubMessage.class.getDeclaredConstructor(
                String.class, double.class, long.class, String.class, String.class, long.class,
                String.class, double.class, String.class, String.class));

        Method fromJson = PubSubMessage.class.getDeclaredMethod("fromJson", String.class);
        Method getBalance = PubSubMessage.class.getDeclaredMethod("getBalance");
        Constructor<?> builder = PubSubMessage.Builder.class.getDeclaredConstructor();
        assertEquals(PubSubMessage.class, fromJson.getReturnType());
        assertEquals(double.class, getBalance.getReturnType());
        assertNotNull(builder);
        assertNotNull(PubSubMessage.Builder.class.getDeclaredMethod("balance", double.class));
        assertNotNull(PubSubMessage.Builder.class.getDeclaredMethod("build"));
    }

    @Test
    void legacyMessageMethodsDelegateToTheNewCodecAndPreserveAddedBigDecimalOverloads() throws Exception {
        PubSubMessage message = new PubSubMessage(PLAYER_UUID, new BigDecimal("10.25"), 5,
                "node-a", "message-a", 1_700_000_000_000L, "DEPOSIT", 0.0d);

        PubSubMessage decoded = PubSubMessage.fromJson(message.toJson());

        assertNotNull(decoded);
        assertEquals(new BigDecimal("10.25"), decoded.getBalanceAsBigDecimal());
        assertEquals(5L, decoded.getVersion());
        assertEquals(PubSubMessage.EventType.DEPOSIT, decoded.getEventTypeEnum());
        assertNotNull(PubSubMessage.class.getDeclaredConstructor(
                String.class, BigDecimal.class, long.class, String.class, String.class, long.class)
        );
    }

    @Test
    void legacyWrapperRejectsMalformedIdentityInsteadOfReturningAnUnsafeDto() {
        assertNull(PubSubMessage.fromJson(
                "{\"uuid\":\"not-a-uuid\",\"balance\":10.0,\"version\":1}"));
    }

    @Test
    void cmiSubscriberWrapperUsesTheDedicatedLegacyCmiCodec() {
        String json = LegacyProtocolCodec.encodeCmi(new LegacyCmiMessage(
                PLAYER_UUID, new BigDecimal("20.50"), 7, "cmi-node", "cmi-message",
                1_700_000_000_003L, "CMI_DEPOSIT", 2.5d, "CMI", "Player"));

        PubSubMessage decoded = PubSubMessage.fromCmiJson(json);

        assertNotNull(decoded);
        assertEquals("CMI_DEPOSIT", decoded.getEventType());
        assertEquals(7L, decoded.getVersion());
        assertEquals("Player", decoded.getSourcePlayerName());
    }

    @Test
    void legacyRedisKeyAndChannelWrappersDelegateWithoutChangingLiterals() {
        UUID uuid = UUID.fromString(PLAYER_UUID);

        assertEquals(RedisKeys.balance(uuid), "syncmoney:balance:" + PLAYER_UUID);
        assertEquals(RedisKeys.version(uuid), "syncmoney:version:" + PLAYER_UUID);
        assertEquals(PubSubChannels.BALANCE_UPDATE, RedisChannels.BALANCE_UPDATE);
        assertEquals(PubSubChannels.CMI_BALANCE_UPDATE, RedisChannels.CMI_BALANCE_UPDATE);
        assertEquals("admin.money-received", MessageKeys.forEventType("ADMIN_GIVE"));
    }

    private static final class LegacyV132Message {
        private String uuid;
        private double balance;
        private long version;
        private String sourceServer;
        private String messageId;
        private long timestamp;
        private String eventType;
        private double amount;
        private String sourcePlugin;
        private String sourcePlayerName;
    }
}
