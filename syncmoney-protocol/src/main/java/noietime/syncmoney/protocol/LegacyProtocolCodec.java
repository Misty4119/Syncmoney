package noietime.syncmoney.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Codec for the existing protocol-v1 balance and CMI envelopes.
 *
 * <p>Absent {@code protocolVersion} means v1. Encoded messages deliberately omit that field to
 * remain readable by 1.3.2 peers. The codec owns its Gson dependency and never relies on a server's
 * transitive JSON library.
 */
public final class LegacyProtocolCodec {

    public static final int LEGACY_PROTOCOL_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 65_536;

    private static final Gson GSON = new GsonBuilder().create();

    private LegacyProtocolCodec() {
    }

    public static Optional<LegacyBalanceMessage> decodeBalance(String json) {
        return decodeEnvelope(json).map(Envelope::toBalanceMessage);
    }

    public static String encodeBalance(LegacyBalanceMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Balance message is required");
        }
        validate(message.uuid(), message.balance(), message.version(), message.amount());
        return encode(new Envelope(message.uuid(), message.balance(), message.version(),
                message.sourceServer(), message.messageId(), message.timestamp(), message.eventType(),
                message.amount(), message.sourcePlugin(), message.sourcePlayerName()));
    }

    public static Optional<LegacyCmiMessage> decodeCmi(String json) {
        return decodeEnvelope(json).map(Envelope::toCmiMessage);
    }

    public static String encodeCmi(LegacyCmiMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("CMI message is required");
        }
        validate(message.uuid(), message.balance(), message.version(), message.amount());
        return encode(new Envelope(message.uuid(), message.balance(), message.version(),
                message.sourceServer(), message.messageId(), message.timestamp(), message.eventType(),
                message.amount(), message.sourcePlugin(), message.sourcePlayerName()));
    }

    private static Optional<Envelope> decodeEnvelope(String json) {
        if (json == null || json.isBlank() || !isWithinUtf8Limit(json)) {
            return Optional.empty();
        }

        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) {
                return Optional.empty();
            }

            JsonObject object = parsed.getAsJsonObject();
            String uuid = requiredString(object, "uuid");
            BigDecimal balance = requiredDecimal(object, "balance");
            long version = optionalLong(object, "version", 0L);
            long timestamp = optionalLong(object, "timestamp", 0L);
            double amount = optionalDouble(object, "amount", 0.0d);
            String sourceServer = optionalString(object, "sourceServer");
            String messageId = optionalString(object, "messageId");
            String eventType = optionalString(object, "eventType");
            String sourcePlugin = optionalString(object, "sourcePlugin");
            String sourcePlayerName = optionalString(object, "sourcePlayerName");

            validate(uuid, balance, version, amount);
            return Optional.of(new Envelope(uuid, balance, version, sourceServer, messageId,
                    timestamp, eventType, amount, sourcePlugin, sourcePlayerName));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String encode(Envelope envelope) {
        String json = GSON.toJson(envelope);
        if (!isWithinUtf8Limit(json)) {
            throw new IllegalArgumentException("Encoded protocol message exceeds the payload limit");
        }
        return json;
    }

    private static void validate(String uuid, BigDecimal balance, long version, double amount) {
        if (!isCanonicalUuid(uuid)) {
            throw new IllegalArgumentException("A canonical UUID is required");
        }
        if (balance == null || !Double.isFinite(balance.doubleValue())) {
            throw new IllegalArgumentException("Balance must be a finite number");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Version must not be negative");
        }
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("Amount must be finite");
        }
    }

    private static boolean isCanonicalUuid(String uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            return UUID.fromString(uuid).toString().equalsIgnoreCase(uuid);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field " + name);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field " + name + " must be a string");
        }
        return element.getAsString();
    }

    private static BigDecimal requiredDecimal(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing numeric field " + name);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException("Field " + name + " must be numeric");
        }
        return new BigDecimal(primitive.getAsString());
    }

    private static long optionalLong(JsonObject object, String name, long defaultValue) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Field " + name + " must be an integer");
        }
        return new BigDecimal(element.getAsString()).longValueExact();
    }

    private static double optionalDouble(JsonObject object, String name, double defaultValue) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Field " + name + " must be numeric");
        }
        double value = Double.parseDouble(element.getAsString());
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Field " + name + " must be finite");
        }
        return value;
    }

    private static boolean isWithinUtf8Limit(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                bytes += 4;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_PAYLOAD_BYTES) {
                return false;
            }
        }
        return true;
    }

    private record Envelope(
            String uuid,
            BigDecimal balance,
            long version,
            String sourceServer,
            String messageId,
            long timestamp,
            String eventType,
            double amount,
            String sourcePlugin,
            String sourcePlayerName) {

        private LegacyBalanceMessage toBalanceMessage() {
            return new LegacyBalanceMessage(uuid, balance, version, sourceServer, messageId,
                    timestamp, eventType, amount, sourcePlugin, sourcePlayerName);
        }

        private LegacyCmiMessage toCmiMessage() {
            return new LegacyCmiMessage(uuid, balance, version, sourceServer, messageId,
                    timestamp, eventType, amount, sourcePlugin, sourcePlayerName);
        }
    }
}
