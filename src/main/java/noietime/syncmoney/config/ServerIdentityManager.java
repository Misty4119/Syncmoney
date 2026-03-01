package noietime.syncmoney.config;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.UUID;

/**
 * Server identity manager.
 * Responsible for generating, loading, and saving server unique identifiers.
 *
 * Priority order:
 * 1. User-defined server-name (passed from SyncmoneyConfig)
 * 2. Saved ID in server-id.json
 * 3. Newly generated UUID
 */
public final class ServerIdentityManager {

    private static final String ID_FILE_NAME = "server-id.json";
    private static final int MAX_SERVER_NAME_LENGTH = 50;

    private final Plugin plugin;
    private final String customServerName;

    private String cachedServerId;
    private boolean initialized = false;

    /**
     * @param plugin Plugin instance
     * @param customServerName User-defined server-name in config.yml (may be empty)
     */
    public ServerIdentityManager(Plugin plugin, String customServerName) {
        this.plugin = plugin;
        this.customServerName = customServerName;
    }

    /**
     * Validates server-name format.
     * @param name server-name
     * @return Validation result record (isValid, errorMessage)
     */
    private record ValidationResult(boolean isValid, String errorMessage) {}

    /**
     * Validates server-name format.
     */
    private ValidationResult validateServerName(String name) {
        if (name == null || name.isEmpty()) {
            return new ValidationResult(false, "server-name is empty");
        }

        if (name.length() > MAX_SERVER_NAME_LENGTH) {
            return new ValidationResult(false, "server-name exceeds " + MAX_SERVER_NAME_LENGTH + " characters");
        }

        if (!name.matches("^[a-zA-Z_][a-zA-Z0-9_-]*$")) {
            return new ValidationResult(false, "server-name contains invalid characters (only a-z, A-Z, 0-9, _, - allowed, cannot start with number)");
        }

        return new ValidationResult(true, null);
    }

    /**
     * Gets server identity name.
     * Uses custom value if defined by user, otherwise uses auto-generated ID.
     */
    public String getServerName() {
        if (customServerName != null && !customServerName.isEmpty()) {
            ValidationResult validation = validateServerName(customServerName);
            if (!validation.isValid()) {
                plugin.getLogger().warning("Invalid server-name: " + validation.errorMessage() +
                    ". Using auto-generated ID instead.");
                return getOrCreateServerId();
            }
            return customServerName;
        }

        return getOrCreateServerId();
    }

    /**
     * Gets server ID (UUID), auto-generates and persists if not exists.
     */
    private synchronized String getOrCreateServerId() {
        if (cachedServerId != null) {
            return cachedServerId;
        }

        File idFile = new File(plugin.getDataFolder(), ID_FILE_NAME);

        if (idFile.exists()) {
            try (FileReader reader = new FileReader(idFile)) {
                ServerIdData data = parseIdFile(reader);
                if (data != null && data.serverId != null) {
                    cachedServerId = data.serverId;
                    plugin.getLogger().info("Loaded server ID: " + cachedServerId);
                    return cachedServerId;
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to load server-id.json, regenerating...");
            }
        }

        cachedServerId = UUID.randomUUID().toString();

        saveServerId(cachedServerId);

        plugin.getLogger().info("Generated new server ID: " + cachedServerId);
        return cachedServerId;
    }

    /**
     * Persists server ID to file.
     */
    private void saveServerId(String serverId) {
        File idFile = new File(plugin.getDataFolder(), ID_FILE_NAME);

        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            String json = String.format(
                "{\"serverId\":\"%s\",\"generatedAt\":\"%s\"}",
                serverId,
                java.time.Instant.now().toString()
            );

            try (FileWriter writer = new FileWriter(idFile)) {
                writer.write(json);
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save server-id.json: " + e.getMessage());
        }
    }

    /**
     * Parses ID file.
     */
    private ServerIdData parseIdFile(FileReader reader) {
        try {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                sb.append((char) c);
            }

            String content = sb.toString();
            int start = content.indexOf("\"serverId\":\"");
            if (start == -1) return null;

            start += 12;
            int end = content.indexOf("\"", start);
            if (end == -1) return null;

            String serverId = content.substring(start, end);
            return new ServerIdData(serverId);

        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Simple ID data class.
     */
    private static class ServerIdData {
        final String serverId;

        ServerIdData(String serverId) {
            this.serverId = serverId;
        }
    }
}
