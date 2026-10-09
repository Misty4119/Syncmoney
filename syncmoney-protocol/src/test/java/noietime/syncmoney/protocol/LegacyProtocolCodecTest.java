package noietime.syncmoney.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import noietime.syncmoney.testkit.fixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyProtocolCodecTest {

    private static final String UUID = "00000000-0000-0000-0000-000000000001";

    @Test
    void decodesLegacyBalanceGoldenWithoutLosingLongOrUnknownEvent() throws IOException {
        String json = FixtureCatalog.legacyBalanceV1Json();

        LegacyBalanceMessage message = LegacyProtocolCodec.decodeBalance(json).orElseThrow();

        assertEquals(UUID, message.uuid());
        assertEquals(new BigDecimal("1.25E+2"), message.balance());
        assertEquals(9_007_199_254_740_993L, message.version());
        assertEquals("node-a", message.sourceServer());
        assertEquals("legacy-1", message.messageId());
        assertEquals(1_700_000_000_000L, message.timestamp());
        assertEquals("FUTURE_EVENT", message.eventType());
        assertEquals(0.0d, message.amount());
        assertEquals(1, LegacyProtocolCodec.LEGACY_PROTOCOL_VERSION,
                "A missing protocolVersion is interpreted as v1");
    }

    @Test
    void decodesLegacyCmiPayloadAndIgnoresUnknownFields() throws IOException {
        LegacyCmiMessage message = LegacyProtocolCodec.decodeCmi(
                FixtureCatalog.legacyCmiV1Json()).orElseThrow();

        assertEquals(UUID, message.uuid());
        assertEquals(new BigDecimal("1.0E-4"), message.balance());
        assertEquals(17_910_432_000_000_001L, message.version());
        assertEquals("CMI_BALANCE_UPDATE", message.eventType());
        assertEquals("legacy-player", message.sourcePlayerName());
    }

    @Test
    void missingOptionalFieldsKeepLegacyPrimitiveDefaults() {
        LegacyBalanceMessage message = LegacyProtocolCodec.decodeBalance(
                "{\"uuid\":\"" + UUID + "\",\"balance\":0.0}").orElseThrow();

        assertEquals(0L, message.version());
        assertEquals(0L, message.timestamp());
        assertEquals(0.0d, message.amount());
        assertNull(message.eventType());
        assertNull(message.sourceServer());
    }

    @Test
    void fieldOrderIsIgnoredAndTheLastDuplicateMatchesLegacyGsonBehavior() {
        String json = "{\"version\":7,\"amount\":0.0,\"balance\":1.0,\"uuid\":\""
                + UUID + "\",\"balance\":2.0,\"eventType\":\"DEPOSIT\"}";

        LegacyBalanceMessage message = LegacyProtocolCodec.decodeBalance(json).orElseThrow();

        assertEquals(new BigDecimal("2.0"), message.balance());
        assertEquals(7L, message.version());
        assertEquals("DEPOSIT", message.eventType());
    }

    @Test
    void encodesNumbersAsNumbersWithoutAddingARequiredProtocolVersion() {
        LegacyBalanceMessage message = new LegacyBalanceMessage(
                UUID, new BigDecimal("1E+3"), 9_007_199_254_740_993L,
                "node-b", "new-1", 1_700_000_000_001L, "DEPOSIT", 0.0d,
                null, null);

        String json = LegacyProtocolCodec.encodeBalance(message);
        JsonObject encoded = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(encoded.get("balance").isJsonPrimitive());
        assertTrue(encoded.get("balance").getAsJsonPrimitive().isNumber());
        assertTrue(encoded.get("amount").getAsJsonPrimitive().isNumber());
        assertEquals(9_007_199_254_740_993L, encoded.get("version").getAsLong());
        assertFalse(encoded.has("protocolVersion"));
        assertFalse(encoded.has("sourcePlugin"));
        assertFalse(encoded.has("sourcePlayerName"));
        assertEquals(new BigDecimal("1E+3"), LegacyProtocolCodec.decodeBalance(json)
                .orElseThrow().balance());
    }

    @Test
    void cmiCodecRoundTripsTheLegacyEnvelope() {
        LegacyCmiMessage original = new LegacyCmiMessage(
                UUID, new BigDecimal("12.50"), 7L, "node-c", "cmi-1",
                1_700_000_000_002L, "CMI_WITHDRAW", 2.5d, "CMI", "margo");

        String json = LegacyProtocolCodec.encodeCmi(original);

        assertEquals(original, LegacyProtocolCodec.decodeCmi(json).orElseThrow());
    }

    @Test
    void rejectsMalformedIdentityBalanceVersionAndNonFiniteNumbers() {
        assertFalse(LegacyProtocolCodec.decodeBalance("not-json").isPresent());
        assertFalse(LegacyProtocolCodec.decodeBalance("[]").isPresent());
        assertFalse(LegacyProtocolCodec.decodeBalance("{\"balance\":1.0}").isPresent());
        assertFalse(LegacyProtocolCodec.decodeBalance(
                "{\"uuid\":\"not-a-uuid\",\"balance\":1.0}").isPresent());
        assertFalse(LegacyProtocolCodec.decodeBalance(
                "{\"uuid\":\"" + UUID + "\",\"balance\":1.0,\"version\":-1}").isPresent());
        assertFalse(LegacyProtocolCodec.decodeBalance(
                "{\"uuid\":\"" + UUID + "\",\"balance\":1e9999}").isPresent());
        assertFalse(LegacyProtocolCodec.decodeBalance(
                "{\"uuid\":\"" + UUID + "\",\"balance\":1.0,\"amount\":1e9999}").isPresent());
    }

    @Test
    void rejectsPayloadsAboveTheUtf8ByteLimit() {
        String largeName = "猫".repeat(22_000);
        String json = "{\"uuid\":\"" + UUID + "\",\"balance\":1.0,\"sourcePlayerName\":\""
                + largeName + "\"}";

        assertEquals(65_536, LegacyProtocolCodec.MAX_PAYLOAD_BYTES);
        assertFalse(LegacyProtocolCodec.decodeBalance(json).isPresent());
    }

    @Test
    void rejectsInvalidValuesWhenEncoding() {
        assertThrows(IllegalArgumentException.class, () -> LegacyProtocolCodec.encodeBalance(
                new LegacyBalanceMessage("bad", BigDecimal.ONE, 0, null, null, 0, null, 0, null, null)));
        assertThrows(IllegalArgumentException.class, () -> LegacyProtocolCodec.encodeBalance(
                new LegacyBalanceMessage(UUID, null, 0, null, null, 0, null, 0, null, null)));
        assertThrows(IllegalArgumentException.class, () -> LegacyProtocolCodec.encodeBalance(
                new LegacyBalanceMessage(UUID, BigDecimal.ONE, -1, null, null, 0, null, 0, null, null)));
        assertThrows(IllegalArgumentException.class, () -> LegacyProtocolCodec.encodeBalance(
                new LegacyBalanceMessage(UUID, BigDecimal.ONE, 0, null, null, 0, null,
                        Double.POSITIVE_INFINITY, null, null)));
    }

}
