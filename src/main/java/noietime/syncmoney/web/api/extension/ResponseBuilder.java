package noietime.syncmoney.web.api.extension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder for constructing consistent API responses.
 *
 * Provides a fluent API for building success and error responses
 * with proper metadata.
 *
 * Example:
 * ```java
 * ResponseBuilder.success()
 *     .put("player", playerName)
 *     .put("balance", balance)
 *     .put("currency", "coins")
 *     .build()
 * ```
 */
public class ResponseBuilder {

    private static final String VERSION = "1.1.0";
    private final Map<String, Object> data = new LinkedHashMap<>();
    private boolean success = true;
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> pagination;
    private Map<String, Object> meta;

    private ResponseBuilder() {
    }

    /**
     * Create a new builder for a success response.
     *
     * @return a new ResponseBuilder instance
     */
    public static ResponseBuilder success() {
        return new ResponseBuilder();
    }

    /**
     * Create a new builder for an error response.
     *
     * @param code error code
     * @param message error message
     * @return a new ResponseBuilder instance
     */
    public static ResponseBuilder error(String code, String message) {
        ResponseBuilder builder = new ResponseBuilder();
        builder.success = false;
        builder.errorCode = code;
        builder.errorMessage = message;
        return builder;
    }

    /**
     * Add a field to the response data.
     *
     * @param key field key
     * @param value field value
     * @return this builder for chaining
     */
    public ResponseBuilder put(String key, Object value) {
        this.data.put(key, value);
        return this;
    }

    /**
     * Add multiple fields to the response data.
     *
     * @param fields map of fields to add
     * @return this builder for chaining
     */
    public ResponseBuilder putAll(Map<String, Object> fields) {
        this.data.putAll(fields);
        return this;
    }

    /**
     * Set pagination information.
     *
     * @param page current page
     * @param pageSize items per page
     * @param totalItems total number of items
     * @return this builder for chaining
     */
    public ResponseBuilder paginate(int page, int pageSize, long totalItems) {
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        this.pagination = Map.of(
                "page", page,
                "pageSize", pageSize,
                "totalItems", totalItems,
                "totalPages", totalPages
        );
        return this;
    }

    /**
     * Set cursor-based pagination information.
     *
     * @param nextCursor the cursor for the next page
     * @param hasMore whether there are more results
     * @param pageSize items per page
     * @return this builder for chaining
     */
    public ResponseBuilder cursorPaginate(String nextCursor, boolean hasMore, int pageSize) {
        this.pagination = Map.of(
                "nextCursor", nextCursor,
                "hasMore", hasMore,
                "pageSize", pageSize
        );
        return this;
    }

    /**
     * Set custom metadata.
     *
     * @param key metadata key
     * @param value metadata value
     * @return this builder for chaining
     */
    public ResponseBuilder meta(String key, Object value) {
        if (this.meta == null) {
            this.meta = new LinkedHashMap<>();
        }
        this.meta.put(key, value);
        return this;
    }

    /**
     * Build the JSON response string.
     *
     * @return JSON string
     */
    public String build() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (success) {
            response.put("success", true);
            response.put("data", data.isEmpty() ? Map.of() : data);

            if (pagination != null) {
                response.put("pagination", pagination);
            }
        } else {
            response.put("success", false);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", errorCode != null ? errorCode : "UNKNOWN_ERROR");
            error.put("message", errorMessage != null ? errorMessage : "An unknown error occurred");
            response.put("error", error);
        }

        Map<String, Object> metaData = new LinkedHashMap<>();
        metaData.put("timestamp", System.currentTimeMillis());
        metaData.put("version", VERSION);

        if (this.meta != null) {
            metaData.putAll(this.meta);
        }

        response.put("meta", metaData);

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":{\"code\":\"SERIALIZATION_ERROR\",\"message\":\"Failed to serialize response\"}}";
        }
    }

    /**
     * Build a simple success response with data.
     *
     * @param data the data to include
     * @return JSON string
     */
    public static String buildSuccess(Object data) {
        return success().putAll(data instanceof Map ? (Map<String, Object>) data : Map.of("data", data)).build();
    }

    /**
     * Build a simple error response.
     *
     * @param code error code
     * @param message error message
     * @return JSON string
     */
    public static String buildError(String code, String message) {
        return error(code, message).build();
    }

    /**
     * Build a paginated response.
     *
     * @param data the data array
     * @param page current page
     * @param pageSize items per page
     * @param totalItems total number of items
     * @return JSON string
     */
    public static String buildPaginated(Object data, int page, int pageSize, long totalItems) {
        return success()
                .put("items", data)
                .paginate(page, pageSize, totalItems)
                .build();
    }

    /**
     * Build a cursor-based paginated response.
     *
     * @param data the data array
     * @param nextCursor cursor for next page
     * @param hasMore whether there are more results
     * @param pageSize items per page
     * @return JSON string
     */
    public static String buildCursorPaginated(Object data, String nextCursor, boolean hasMore, int pageSize) {
        return success()
                .put("items", data)
                .cursorPaginate(nextCursor, hasMore, pageSize)
                .build();
    }
}
