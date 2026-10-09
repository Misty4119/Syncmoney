package noietime.syncmoney.web.api.extension;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.web.api.ApiResponse;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;

/**
 * Request context for API extension handlers.
 *
 * Provides convenient access to common request data such as
 * path parameters, query parameters, request body, and
 * plugin services.
 */
public class RequestContext {

    private final HttpServerExchange exchange;
    private final Syncmoney plugin;
    private final String extensionName;
    private final String routePath;

    public RequestContext(HttpServerExchange exchange, Syncmoney plugin,
                        String extensionName, String routePath) {
        this.exchange = exchange;
        this.plugin = plugin;
        this.extensionName = extensionName;
        this.routePath = routePath;
    }

    /**
     * Get the HTTP exchange.
     *
     * @return the Undertow HTTP exchange
     */
    public HttpServerExchange getExchange() {
        return exchange;
    }

    /**
     * Get the plugin instance.
     *
     * @return the Syncmoney plugin instance
     */
    public Syncmoney getPlugin() {
        return plugin;
    }

    /**
     * Get the extension name.
     *
     * @return extension name
     */
    public String getExtensionName() {
        return extensionName;
    }

    /**
     * Get the route path.
     *
     * @return the matched route path
     */
    public String getRoutePath() {
        return routePath;
    }

    /**
     * Get the request method.
     *
     * @return HTTP method (GET, POST, etc.)
     */
    public String getMethod() {
        return exchange.getRequestMethod().toString();
    }

    /**
     * Get the request path.
     *
     * @return the request path
     */
    public String getPath() {
        return exchange.getRelativePath();
    }

    /**
     * Get a request header value.
     *
     * @param name header name
     * @return header value or empty if not present
     */
    public Optional<String> getHeader(String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return Optional.ofNullable(value);
    }

    /**
     * Get the Authorization header (without Bearer prefix).
     *
     * @return API key or empty if not present
     */
    public Optional<String> getApiKey() {
        return getHeader("Authorization")
                .map(auth -> auth.startsWith("Bearer ")
                        ? auth.substring(7)
                        : auth);
    }

    /**
     * Get a query parameter value.
     *
     * @param name parameter name
     * @return parameter value or empty if not present
     */
    public Optional<String> getQueryParam(String name) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.peekFirst());
    }

    /**
     * Get an integer query parameter.
     *
     * @param name parameter name
     * @param defaultValue default value if not present or invalid
     * @return parameter value or default
     */
    public int getQueryParamAsInt(String name, int defaultValue) {
        return getQueryParam(name)
                .map(v -> {
                    try {
                        return Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * Get a long query parameter.
     *
     * @param name parameter name
     * @param defaultValue default value if not present or invalid
     * @return parameter value or default
     */
    public long getQueryParamAsLong(String name, long defaultValue) {
        return getQueryParam(name)
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * Get a boolean query parameter.
     *
     * @param name parameter name
     * @param defaultValue default value if not present
     * @return parameter value or default
     */
    public boolean getQueryParamAsBool(String name, boolean defaultValue) {
        return getQueryParam(name)
                .map(v -> v.equalsIgnoreCase("true")
                        || v.equalsIgnoreCase("1")
                        || v.equalsIgnoreCase("yes")
                        || v.equalsIgnoreCase("on"))
                .orElse(defaultValue);
    }

    /**
     * Get a path parameter by name.
     * Note: This only works with named path parameters in the route pattern.
     *
     * @param name parameter name
     * @return parameter value or empty if not present
     */
    public Optional<String> getPathParam(String name) {
        return Optional.empty();
    }

    /**
     * Get a path parameter by index.
     *
     * @param index parameter index (0-based)
     * @return parameter value or empty if not present
     */
    public Optional<String> getPathParam(int index) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");

        int offset = 2;
        if (parts.length > offset + index) {
            return Optional.ofNullable(parts[offset + index]);
        }
        return Optional.empty();
    }

    /**
     * Get the request body as a string.
     *
     * @return request body
     */
    public String getBody() {
        try {
            exchange.startBlocking();
            return new String(exchange.getInputStream().readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Parse the request body as JSON.
     *
     * @return parsed JSON as Map, or empty if parsing fails
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getBodyAsJson() {
        try {
            String body = getBody();
            if (body.isEmpty()) {
                return Optional.empty();
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(body, Map.class);
            return Optional.of(data);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Get the remote address of the client.
     *
     * @return client IP address
     */
    public String getRemoteAddress() {
        try {
            return exchange.getDestinationAddress().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Respond with JSON.
     *
     * @param json the JSON string to send
     */
    public void respondJson(String json) {
        exchange.getResponseHeaders().put(
                Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }

    /**
     * Respond with an error.
     *
     * @param statusCode HTTP status code
     * @param code error code
     * @param message error message
     */
    public void respondError(int statusCode, String code, String message) {
        exchange.setStatusCode(statusCode);
        respondJson(ApiResponse.error(code, message));
    }

    /**
     * Respond with success.
     *
     * @param data the data to send
     */
    public void respondSuccess(Object data) {
        respondJson(ApiResponse.success(data));
    }

    /**
     * Respond with no content.
     *
     * @param statusCode HTTP status code (typically 204)
     */
    public void respondNoContent(int statusCode) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseSender().send("");
    }
}
