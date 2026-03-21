package noietime.syncmoney.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified API response format.
 * Provides consistent JSON structure for all API responses.
 */
public class ApiResponse {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String VERSION = "1.1.2";

    /**
     * Create a successful response with data.
     */
    public static String success(Object data) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            response.put("meta", Map.of(
                "timestamp", System.currentTimeMillis(),
                "version", VERSION
            ));
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":{\"code\":\"SERIALIZATION_ERROR\",\"message\":\"Failed to serialize response\"}}";
        }
    }

    /**
     * Create an error response.
     */
    public static String error(String code, String message) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            Map<String, Object> error = new HashMap<>();
            error.put("code", code);
            error.put("message", message);
            response.put("error", error);
            response.put("meta", Map.of(
                "timestamp", System.currentTimeMillis(),
                "version", VERSION
            ));
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":{\"code\":\"SERIALIZATION_ERROR\",\"message\":\"Failed to serialize error\"}}";
        }
    }

    /**
     * Create a paginated response.
     */
    public static String paginated(Object data, int page, int pageSize, long totalItems) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);

            int totalPages = (int) Math.ceil((double) totalItems / pageSize);
            response.put("pagination", Map.of(
                "page", page,
                "pageSize", pageSize,
                "totalItems", totalItems,
                "totalPages", totalPages
            ));
            response.put("meta", Map.of(
                "timestamp", System.currentTimeMillis(),
                "version", VERSION
            ));
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":{\"code\":\"SERIALIZATION_ERROR\",\"message\":\"Failed to serialize response\"}}";
        }
    }

    /**
     * Create a simple success response without data.
     */
    public static String ok() {
        return success(Map.of("message", "OK"));
    }

    /**
     * Create a cursor-based paginated response.
     */
    public static String cursorPaginated(Object data, Object pagination) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("data", data);
            response.put("pagination", pagination);
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return error("SERIALIZATION_ERROR", "Failed to serialize response");
        }
    }
}
