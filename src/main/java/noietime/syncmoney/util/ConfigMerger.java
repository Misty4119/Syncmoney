package noietime.syncmoney.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Configuration merger utility.
 * Merges local configuration with default configuration from JAR.
 * Only adds missing keys, does NOT overwrite existing values.
 */
public final class ConfigMerger {

    /**
     * Default config schema version (matches CURRENT_VERSION in SchemaManager)
     * 11 = v1.1.0, 10 = v1.0.0
     */
    private static final int DEFAULT_CONFIG_VERSION = 11;

    /**
     * Suggested updates for messages.yml upgrades.
     * Provides users with information about new features after upgrading.
     */
    private static final Map<Integer, List<String>> MESSAGES_SUGGESTED_UPDATES = Map.of(
        11, List.of(
            "New: web.* messages now support UI customization",
            "Tip: Check messages.yml for new notification templates like web.login.*",
            "Feature: cross-server-notifications.* keys added",
            "Tip: pay.insufficient-funds now supports {balance} placeholder"
        )
    );

    private final JavaPlugin plugin;
    private final String[] configFiles;

    public ConfigMerger(JavaPlugin plugin, String... configFiles) {
        this.plugin = plugin;
        this.configFiles = configFiles;
    }

    /**
     * Merges all configured files.
     * @return list of file names that had missing keys added
     */
    public List<String> mergeAll() {
        List<String> mergedFiles = new ArrayList<>();

        for (String filename : configFiles) {
            if (mergeAndSave(filename)) {
                mergedFiles.add(filename);
            }
        }

        return mergedFiles;
    }

    /**
     * Gets the default config version from JAR.
     * @return default config version
     */
    public int getDefaultConfigVersion() {
        return DEFAULT_CONFIG_VERSION;
    }

    /**
     * Gets the local config version from config.yml.
     * @return local config version, or 0 if not found
     */
    public int getLocalConfigVersion() {
        File localFile = new File(plugin.getDataFolder(), "config.yml");
        if (!localFile.exists()) {
            return 0;
        }

        FileConfiguration local = YamlConfiguration.loadConfiguration(localFile);
        return local.getInt("config-version", 0);
    }

    /**
     * Checks if config upgrade is needed.
     * @return true if local version is less than default version
     */
    public boolean needsUpgrade() {
        return getLocalConfigVersion() < DEFAULT_CONFIG_VERSION;
    }

    /**
     * Merges a single configuration file.
     * @param filename the config file name (e.g., "config.yml")
     * @return true if missing keys were added, false if already up to date
     */
    public boolean mergeAndSave(String filename) {
        FileConfiguration defaults = loadDefaultConfig(filename);
        if (defaults == null) {
            plugin.getLogger().warning("Could not load default config: " + filename);
            return false;
        }

        File localFile = new File(plugin.getDataFolder(), filename);
        if (!localFile.exists()) {
            plugin.getLogger().info("Local config not found, using default: " + filename);
            try {
                defaults.save(localFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save default config: " + e.getMessage());
            }
            return true;
        }

        FileConfiguration local = YamlConfiguration.loadConfiguration(localFile);

        Set<String> missingKeys = findMissingKeys(local, defaults);

        if (missingKeys.isEmpty()) {
            return false;
        }


        int localVersion = readLocalVersion(local, filename);
        int defaultVersion = readDefaultVersion(defaults, filename);

        if (localVersion < defaultVersion) {
            plugin.getLogger().info("Config upgrade needed: " + filename + " (v" + localVersion + " -> v" + defaultVersion + ")");
            plugin.getLogger().info("Adding " + missingKeys.size() + " new config keys to " + filename + ": " + missingKeys);


            try {
                Path original = localFile.toPath();
                Path backup = localFile.toPath().resolveSibling(filename + ".v" + localVersion + ".bak");
                Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Backup created: " + backup.getFileName());
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create backup: " + e.getMessage());
            }


            if (localVersion < 11) {
                int batchSize = local.getInt("audit.batch-size", 100);
                if (batchSize == 1) {
                    plugin.getLogger().info("Tip: Consider increasing audit.batch-size from 1 to 100 for better performance.");
                }
            }


            if ("messages.yml".equals(filename) && MESSAGES_SUGGESTED_UPDATES.containsKey(defaultVersion)) {
                plugin.getLogger().info("=== Suggested Messages Updates ===");
                for (String suggestion : MESSAGES_SUGGESTED_UPDATES.get(defaultVersion)) {
                    plugin.getLogger().info("  - " + suggestion);
                }
            }
        } else {
            plugin.getLogger().info("Adding missing config keys to " + filename + ": " + missingKeys);
        }

        FileConfiguration merged = mergeConfigs(local, defaults);

        try {
            merged.save(localFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save merged config " + filename + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Loads default configuration from JAR resources.
     */
    private FileConfiguration loadDefaultConfig(String filename) {
        try (InputStream is = plugin.getResource(filename)) {
            if (is == null) {
                plugin.getLogger().warning("Default config not found in JAR: " + filename);
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to read default config " + filename + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds all missing leaf-node keys in local config compared to defaults.
     * Uses flat dotted-path iteration — avoids the relative-key bug of recursive section traversal.
     */
    private Set<String> findMissingKeys(FileConfiguration local, FileConfiguration defaults) {
        Set<String> missing = new LinkedHashSet<>();
        for (String key : defaults.getKeys(true)) {

            if (!defaults.isConfigurationSection(key) && !local.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * Merges local config with defaults using flat dotted-path iteration.
     * Local values are always preserved; only missing leaf values are added.
     *
     * Previous implementation called mergeSection() which received a ConfigurationSection
     * and used getKeys(true) — those keys are relative to the section, not the root.
     * Passing them to a root FileConfiguration.set() wrote keys at the wrong (root) level,
     * producing duplicate orphan keys such as "enabled:", "server:", "security:" at root.
     * The flat iteration here uses fully-qualified dotted paths throughout, so this cannot happen.
     */
    private FileConfiguration mergeConfigs(FileConfiguration local, FileConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {



            if (!defaults.isConfigurationSection(key) && !local.contains(key)) {
                local.set(key, defaults.get(key));
            }
        }
        return local;
    }

    /**
     * Reads the schema version from a local config file.
     * config.yml uses "config-version" (int); messages.yml uses "version" (string).
     */
    private int readLocalVersion(FileConfiguration local, String filename) {
        if ("messages.yml".equals(filename)) {
            String v = local.getString("version", "0");
            try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
        }
        return local.getInt("config-version", 0);
    }

    /**
     * Reads the schema version from the bundled default config.
     * config.yml uses "config-version" (int); messages.yml uses "version" (string).
     */
    private int readDefaultVersion(FileConfiguration defaults, String filename) {
        if ("messages.yml".equals(filename)) {
            String v = defaults.getString("version", String.valueOf(DEFAULT_CONFIG_VERSION));
            try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return DEFAULT_CONFIG_VERSION; }
        }
        return defaults.getInt("config-version", DEFAULT_CONFIG_VERSION);
    }
}
