package noietime.syncmoney.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * Utility class for standardized JSON parsing.
 * Provides safe parsing methods with proper error handling.
 */
public final class JsonParseUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonParseUtil() {
    }

    /**
     * Parse JSON string to Map.
     * @param json JSON string
     * @return Optional containing Map if parsing succeeds, empty otherwise
     */
    public static Optional<Map<String, Object>> parseToMap(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> result = MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return Optional.of(result);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * Extract value from JSON string using key.
     * @param json JSON string
     * @param key JSON key
     * @return Optional containing value as String if found, empty otherwise
     */
    public static Optional<String> getString(String json, String key) {
        return parseToMap(json)
                .map(map -> {
                    Object value = map.get(key);
                    return value != null ? value.toString() : null;
                })
                .filter(v -> v != null);
    }

    /**
     * Extract value from JSON string using key, with default value.
     * @param json JSON string
     * @param key JSON key
     * @param defaultValue default value if key not found or parsing fails
     * @return value or default
     */
    public static String getStringOrDefault(String json, String key, String defaultValue) {
        return getString(json, key).orElse(defaultValue);
    }

    /**
     * Extract integer value from JSON string.
     * @param json JSON string
     * @param key JSON key
     * @return Optional containing Integer if found and valid, empty otherwise
     */
    public static Optional<Integer> getInt(String json, String key) {
        return parseToMap(json)
                .map(map -> {
                    Object value = map.get(key);
                    if (value instanceof Number) {
                        return ((Number) value).intValue();
                    }
                    if (value instanceof String) {
                        try {
                            return Integer.parseInt((String) value);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }
                    return null;
                })
                .filter(v -> v != null);
    }

    /**
     * Extract boolean value from JSON string.
     * @param json JSON string
     * @param key JSON key
     * @return Optional containing Boolean if found, empty otherwise
     */
    public static Optional<Boolean> getBoolean(String json, String key) {
        return parseToMap(json)
                .map(map -> {
                    Object value = map.get(key);
                    if (value instanceof Boolean) {
                        return (Boolean) value;
                    }
                    if (value instanceof String) {
                        String strValue = ((String) value).toLowerCase();
                        if ("true".equals(strValue) || "false".equals(strValue)) {
                            return Boolean.parseBoolean(strValue);
                        }
                    }
                    return null;
                })
                .filter(v -> v != null);
    }

    /**
     * Validate if string is valid JSON.
     * @param json JSON string
     * @return true if valid JSON, false otherwise
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Get ObjectMapper instance for custom parsing.
     * @return shared ObjectMapper instance
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
