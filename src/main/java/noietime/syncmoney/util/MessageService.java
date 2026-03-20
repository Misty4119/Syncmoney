package noietime.syncmoney.util;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Message service for managing plugin messages and components.
 * Encapsulates message loading, caching, and formatting logic.
 * Supports fallback to default messages from JAR when local messages are missing keys.
 */
public class MessageService {

    private final Plugin plugin;
    private final int maxCacheSize;
    private FileConfiguration messagesConfig;
    private FileConfiguration defaultMessagesConfig;
    private final ConcurrentHashMap<String, Component> componentCache;

    public MessageService(Plugin plugin) {
        this(plugin, 256);
    }

    public MessageService(Plugin plugin, int maxCacheSize) {
        this.plugin = plugin;
        this.maxCacheSize = maxCacheSize;
        this.componentCache = new ConcurrentHashMap<>();
    }

    /**
     * Loads messages configuration.
     */
    public void loadMessages() {

        loadDefaultMessages();


        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        if (file.exists()) {
            messagesConfig = YamlConfiguration.loadConfiguration(file);
        }
    }

    /**
     * Loads default messages from JAR resources.
     * Used for fallback when local messages are missing keys.
     */
    private void loadDefaultMessages() {
        try (InputStream is = plugin.getResource("messages.yml")) {
            if (is != null) {
                defaultMessagesConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8)
                );
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load default messages: " + e.getMessage());
        }
    }

    /**
     * Gets the raw message string.
     * Falls back to default messages from JAR if key is not found in local config.
     *
     * @param key message key
     * @return message string, returns key if not found in both local and default
     */
    public String getMessage(String key) {
        String message = getMessageFromConfig(messagesConfig, key);
        if (message != null) {
            return message;
        }


        message = getMessageFromConfig(defaultMessagesConfig, key);
        if (message != null) {
            return message;
        }

        return key;
    }

    /**
     * Gets message from a specific configuration.
     * @param config the configuration to search
     * @param key the message key
     * @return the message string, or null if not found
     */
    private String getMessageFromConfig(FileConfiguration config, String key) {
        if (config == null) {
            return null;
        }

        Object value = config.get(key);
        if (value == null) {
            return null;
        }

        String prefix = config.getString("prefix", "[Syncmoney] ");
        return value.toString().replace("{prefix}", prefix);
    }

    /**
     * Gets the parsed Component (supports minimessage format).
     * Uses message key cache to avoid repeated parsing.
     *
     * @param key message key
     * @return parsed Component
     */
    public Component getMessageComponent(String key) {
        Component cached = componentCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (componentCache.size() >= maxCacheSize) {
            componentCache.clear();
        }
        String message = getMessage(key);
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        Component component = MessageHelper.getComponent(message);
        componentCache.put(key, component);
        return component;
    }

    /**
     * Gets the parsed Component with variable replacement.
     *
     * @param key       message key
     * @param variables variable map
     * @return parsed Component
     */
    public Component getMessageComponent(String key, java.util.Map<String, String> variables) {
        Component template = getMessageComponent(key);
        if (template == null || template.equals(Component.empty())) {
            return template;
        }
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        return MessageHelper.replaceVariables(template, variables);
    }

    /**
     * Reloads messages configuration.
     *
     * @return true if successful
     */
    public boolean reload() {
        try {
            loadMessages();
            componentCache.clear();
            MessageHelper.clearComponentCache();
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload messages: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the current cache size.
     *
     * @return cache size
     */
    public int getCacheSize() {
        return componentCache.size();
    }

    /**
     * Clears the component cache.
     */
    public void clearCache() {
        componentCache.clear();
    }
}
