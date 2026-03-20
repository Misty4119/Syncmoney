package noietime.syncmoney.web.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.ConfigManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * API handler for configuration endpoints.
 * Provides access to plugin configuration, save functionality, and reload.
 */
public class ConfigApiHandler {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final ConfigManager configManager;
    private final ObjectMapper objectMapper;

    public ConfigApiHandler(Syncmoney plugin, SyncmoneyConfig config, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = config;
        this.configManager = configManager;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Register all config API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.get("api/config", exchange -> handleGetConfig(exchange));
        router.put("api/config", exchange -> handleSaveConfig(exchange));
        router.post("api/config/validate", exchange -> handleValidateConfig(exchange));
        router.post("api/config/reload", exchange -> handleReloadConfig(exchange));
    }

    /**
     * Handle GET /api/config request.
     * Returns full configuration with editable markers.
     */
    private void handleGetConfig(HttpServerExchange exchange) {
        try {
            Map<String, Object> configData = configManager.getFullConfig();
            sendJson(exchange, ApiResponse.success(configData));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to get config: " + e.getMessage());
            exchange.setStatusCode(500);
            sendJson(exchange, ApiResponse.error("GET_CONFIG_FAILED", e.getMessage()));
        }
    }

    /**
     * Handle PUT /api/config request.
     * Saves configuration changes to config.yml.
     */
    private void handleSaveConfig(HttpServerExchange exchange) {
        exchange.dispatch(() -> {
            try {
                exchange.startBlocking();
                String body = new String(exchange.getInputStream().readAllBytes(), "UTF-8");

                JsonNode rootNode = objectMapper.readTree(body);

                List<ConfigManager.ConfigChange> changes = new ArrayList<>();


                if (rootNode.has("changes") && rootNode.get("changes").isArray()) {

                    JsonNode changesNode = rootNode.get("changes");
                    for (JsonNode changeNode : changesNode) {
                        String section = changeNode.has("section") ? changeNode.get("section").asText() : null;
                        String key = changeNode.has("key") ? changeNode.get("key").asText() : null;
                        JsonNode valueNode = changeNode.get("value");

                        if (section != null && key != null && valueNode != null) {
                            Object value = parseValue(valueNode);
                            changes.add(new ConfigManager.ConfigChange(section, key, value));
                        }
                    }
                } else if (rootNode.has("section") && rootNode.has("key") && rootNode.has("value")) {

                    String section = rootNode.get("section").asText();
                    String key = rootNode.get("key").asText();
                    Object value = parseValue(rootNode.get("value"));
                    changes.add(new ConfigManager.ConfigChange(section, key, value));
                }

                if (changes.isEmpty()) {
                    sendJson(exchange, ApiResponse.error("NO_CHANGES", "No valid changes provided"));
                    return;
                }


                boolean success = configManager.saveConfigBatch(changes);

                if (!success) {
                    exchange.setStatusCode(500);
                    sendJson(exchange, ApiResponse.error("SAVE_FAILED", "Failed to save configuration"));
                    return;
                }


                boolean hotReload = !rootNode.has("hotReload") || rootNode.get("hotReload").asBoolean(true);
                if (hotReload) {
                    configManager.hotReload();
                }

                sendJson(exchange, ApiResponse.success(Map.of(
                    "message", "Configuration saved successfully",
                    "hotReloaded", hotReload
                )));

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save config: " + e.getMessage());
                exchange.setStatusCode(500);
                sendJson(exchange, ApiResponse.error("SAVE_FAILED", e.getMessage()));
            }
        });
    }

    /**
     * Handle POST /api/config/validate request.
     * Validates a configuration value before saving.
     */
    private void handleValidateConfig(HttpServerExchange exchange) {
        exchange.dispatch(() -> {
            try {
                exchange.startBlocking();
                String body = new String(exchange.getInputStream().readAllBytes(), "UTF-8");

                JsonNode rootNode = objectMapper.readTree(body);

                String section = rootNode.has("section") ? rootNode.get("section").asText() : null;
                String key = rootNode.has("key") ? rootNode.get("key").asText() : null;
                JsonNode valueNode = rootNode.get("value");

                if (section == null || key == null || valueNode == null) {
                    sendJson(exchange, ApiResponse.error("INVALID_REQUEST", "Missing section, key, or value"));
                    return;
                }

                Object value = parseValue(valueNode);
                ConfigManager.ValidationResult result = configManager.validate(section, key, value);

                sendJson(exchange, ApiResponse.success(Map.of(
                    "valid", result.valid(),
                    "message", result.message()
                )));

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to validate config: " + e.getMessage());
                exchange.setStatusCode(500);
                sendJson(exchange, ApiResponse.error("VALIDATE_FAILED", e.getMessage()));
            }
        });
    }

    /**
     * Handle POST /api/config/reload request.
     * Reloads configuration from disk.
     */
    private void handleReloadConfig(HttpServerExchange exchange) {
        exchange.dispatch(() -> {
            try {
                configManager.hotReload();
                sendJson(exchange, ApiResponse.success(Map.of("message", "Configuration reloaded successfully")));
            } catch (Exception e) {
                plugin.getLogger().severe("Config reload failed: " + e.getMessage());
                exchange.setStatusCode(500);
                sendJson(exchange, ApiResponse.error("RELOAD_FAILED", e.getMessage()));
            }
        });
    }

    /**
     * Parse JSON value to appropriate Java type.
     */
    private Object parseValue(JsonNode valueNode) {
        if (valueNode.isNull()) {
            return null;
        } else if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        } else if (valueNode.isIntegralNumber()) {
            return valueNode.asLong();
        } else if (valueNode.isFloatingPointNumber()) {
            return valueNode.asDouble();
        } else {
            return valueNode.asText();
        }
    }

    /**
     * Get request body as string.
     */
    private String getRequestBody(HttpServerExchange exchange) throws Exception {
        return exchange.getInputStream().readAllBytes().length > 0
            ? new String(exchange.getInputStream().readAllBytes(), "UTF-8")
            : "{}";
    }

    /**
     * Send JSON response.
     */
    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8"
        );
        exchange.getResponseSender().send(json);
    }
}
