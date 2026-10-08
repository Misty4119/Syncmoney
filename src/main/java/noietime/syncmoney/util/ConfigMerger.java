package noietime.syncmoney.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Configuration merger utility.
 * Merges local configuration with default configuration from JAR.
 * Only adds missing keys, does NOT overwrite existing values.
 */
public final class ConfigMerger {

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    interface FileOperations {
        void copy(Path source, Path target) throws IOException;
        void write(Path target, String contents) throws IOException;
        void move(Path source, Path target, CopyOption... options) throws IOException;
        void deleteIfExists(Path target) throws IOException;
    }

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

    private static final FileOperations SYSTEM_FILE_OPERATIONS = new FileOperations() {
        @Override
        public void copy(Path source, Path target) throws IOException {
            Files.copy(source, target);
        }

        @Override
        public void write(Path target, String contents) throws IOException {
            Files.writeString(target, contents, StandardCharsets.UTF_8);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }

        @Override
        public void deleteIfExists(Path target) throws IOException {
            Files.deleteIfExists(target);
        }
    };

    private final JavaPlugin plugin;
    private final String[] configFiles;
    private final FileOperations fileOperations;

    public ConfigMerger(JavaPlugin plugin, String... configFiles) {
        this(plugin, SYSTEM_FILE_OPERATIONS, configFiles);
    }

    ConfigMerger(JavaPlugin plugin, FileOperations fileOperations, String... configFiles) {
        this.plugin = plugin;
        this.fileOperations = fileOperations;
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
        FileConfiguration defaults = loadDefaultConfig("config.yml");
        return defaults != null ? readDefaultVersion(defaults, "config.yml") : -1;
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

        try {
            return readLocalVersion(loadConfiguration(localFile.toPath()), "config.yml");
        } catch (IOException | InvalidConfigurationException e) {
            return 0;
        }
    }

    /**
     * Checks if config upgrade is needed.
     * @return true if local version is less than default version
     */
    public boolean needsUpgrade() {
        int defaultVersion = getDefaultConfigVersion();
        return defaultVersion >= 0 && getLocalConfigVersion() < defaultVersion;
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

        int defaultVersion = readDefaultVersion(defaults, filename);
        if (defaultVersion < 0) {
            plugin.getLogger().warning("Could not read a valid schema version from bundled config: " + filename);
            return false;
        }

        File localFile = new File(plugin.getDataFolder(), filename);
        if (!localFile.exists()) {
            plugin.getLogger().info("Local config not found, using default: " + filename);
            return persistCandidate(filename, localFile.toPath(), defaults, null, Map.of());
        }

        FileConfiguration local;
        try {
            local = loadConfiguration(localFile.toPath());
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Failed to parse local config " + filename + "; keeping original file: "
                    + e.getMessage());
            return false;
        }

        int localVersion = readLocalVersion(local, filename);
        if (localVersion < 0) {
            plugin.getLogger().severe("Config " + filename + " has an invalid schema version; keeping original file.");
            return false;
        }
        if (localVersion > defaultVersion) {
            plugin.getLogger().warning("Local config " + filename + " has newer schema v" + localVersion
                    + " than this plugin's v" + defaultVersion + "; leaving it unchanged.");
            return false;
        }

        Set<String> missingKeys = findMissingKeys(local, defaults);

        if (missingKeys.isEmpty()) {
            return false;
        }

        try {
            validateNoScalarParentConflicts(local, missingKeys);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("Cannot safely merge config " + filename + "; keeping original file: "
                    + e.getMessage());
            return false;
        }

        plugin.getLogger().info("Adding " + missingKeys.size() + " new config keys to " + filename + ": " + missingKeys);

        if (localVersion < defaultVersion && localVersion < 11) {
            int batchSize = local.getInt("audit.batch-size", 100);
            if (batchSize == 1) {
                plugin.getLogger().info("Tip: Consider increasing audit.batch-size from 1 to 100 for better performance.");
            }
        }

        Map<String, Object> originalValues = leafValues(local);
        originalValues.remove("messages.yml".equals(filename) ? "version" : "config-version");
        FileConfiguration merged = mergeConfigs(local, defaults);
        if (localVersion < defaultVersion) {
            setVersion(merged, filename, defaultVersion);
        }

        if ("messages.yml".equals(filename) && MESSAGES_SUGGESTED_UPDATES.containsKey(defaultVersion)) {
            plugin.getLogger().info("=== Suggested Messages Updates ===");
            for (String suggestion : MESSAGES_SUGGESTED_UPDATES.get(defaultVersion)) {
                plugin.getLogger().info("  - " + suggestion);
            }
        }

        return persistCandidate(filename, localFile.toPath(), merged, localFile.toPath(), originalValues);
    }

    private boolean persistCandidate(String filename, Path target, FileConfiguration candidate, Path backupSource,
            Map<String, Object> originalValues) {
        String serialized = candidate.saveToString();
        Path backup = null;
        Path temporary = null;
        boolean backupVerified = false;
        boolean replacementAttempted = false;

        try {
            if (backupSource != null) {
                int localVersion = readLocalVersion(loadConfiguration(backupSource), filename);
                if (localVersion < 0) {
                    throw new IOException("Original config has an invalid schema version");
                }
                String backupName = target.getFileName() + ".v" + localVersion + "."
                        + BACKUP_TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID() + ".bak";
                backup = target.resolveSibling(backupName);
                fileOperations.copy(backupSource, backup);
                if (Files.mismatch(backupSource, backup) != -1) {
                    throw new IOException("Created config backup does not match the original file");
                }
                backupVerified = true;
                plugin.getLogger().info("Backup created: " + backup.getFileName());
            }

            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            fileOperations.write(temporary, serialized);

            FileConfiguration persistedCandidate = loadConfiguration(temporary);
            if (!sameLeafValues(candidate, persistedCandidate) || !preservesLeafValues(originalValues, persistedCandidate)) {
                throw new IOException("Serialized config candidate failed semantic validation");
            }

            replacementAttempted = true;
            replace(temporary, target);
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            boolean originalSafe = !replacementAttempted || restoreOriginal(target, backup, backupVerified);
            plugin.getLogger().severe("Failed to safely save config " + filename
                    + (originalSafe ? "; original was retained" : "; original could not be verified; preserve backup "
                            + (backup == null ? "(none created)" : backup.getFileName()))
                    + ": " + e.getMessage());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    fileOperations.deleteIfExists(temporary);
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to remove temporary config file " + temporary.getFileName()
                            + ": " + e.getMessage());
                }
            }
            if (backup != null && !backupVerified) {
                try {
                    Files.deleteIfExists(backup);
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to remove incomplete config backup " + backup.getFileName()
                            + ": " + e.getMessage());
                }
            }
        }
    }

    private boolean restoreOriginal(Path target, Path backup, boolean backupVerified) {
        try {
            if (backupVerified && backup != null) {
                if (Files.exists(target) && Files.mismatch(backup, target) == -1) {
                    return true;
                }

                Path restore = Files.createTempFile(target.toAbsolutePath().getParent(),
                        target.getFileName() + ".restore.", ".tmp");
                try {
                    Files.copy(backup, restore, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(restore, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(restore, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(restore);
                }
                return Files.exists(target) && Files.mismatch(backup, target) == -1;
            }

            if (backup == null) {
                Files.deleteIfExists(target);
                return !Files.exists(target);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to restore config from backup "
                    + (backup == null ? "(none)" : backup.getFileName()) + ": " + e.getMessage());
            return false;
        }
    }

    private void replace(Path temporary, Path target) throws IOException {
        try {
            fileOperations.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            fileOperations.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean sameLeafValues(FileConfiguration expected, FileConfiguration actual) {
        Set<String> expectedKeys = leafKeys(expected);
        if (!expectedKeys.equals(leafKeys(actual))) {
            return false;
        }
        for (String key : expectedKeys) {
            if (!Objects.deepEquals(expected.get(key), actual.get(key))) {
                return false;
            }
        }
        return true;
    }

    private boolean preservesLeafValues(Map<String, Object> expected, FileConfiguration actual) {
        for (Map.Entry<String, Object> value : expected.entrySet()) {
            if (!actual.contains(value.getKey()) || actual.isConfigurationSection(value.getKey())
                    || !Objects.deepEquals(value.getValue(), actual.get(value.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private Set<String> leafKeys(FileConfiguration configuration) {
        Set<String> leaves = new LinkedHashSet<>();
        for (String key : configuration.getKeys(true)) {
            if (!configuration.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        return leaves;
    }

    private Map<String, Object> leafValues(FileConfiguration configuration) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : leafKeys(configuration)) {
            values.put(key, configuration.get(key));
        }
        return values;
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
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            return defaults;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Failed to read default config " + filename + ": " + e.getMessage());
            return null;
        }
    }

    private FileConfiguration loadConfiguration(Path path) throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(path.toFile());
        return configuration;
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

    private void validateNoScalarParentConflicts(FileConfiguration local, Set<String> missingKeys) {
        for (String key : missingKeys) {
            int separator = key.indexOf('.');
            while (separator >= 0) {
                String parent = key.substring(0, separator);
                if (local.contains(parent) && !(local.get(parent) instanceof org.bukkit.configuration.ConfigurationSection)) {
                    throw new IllegalArgumentException("existing scalar value conflicts with default path " + key);
                }
                separator = key.indexOf('.', separator + 1);
            }
        }
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
            Object raw = local.get("version");
            if (raw == null) return 0;
            if (raw instanceof Number number) return exactVersion(number);
            if (raw instanceof String value) return parseVersion(value);
            return -1;
        }
        if (!local.contains("config-version")) return 0;
        Object raw = local.get("config-version");
        return raw instanceof Number number ? exactVersion(number) : -1;
    }

    /**
     * Reads the schema version from the bundled default config.
     * config.yml uses "config-version" (int); messages.yml uses "version" (string).
     */
    private int readDefaultVersion(FileConfiguration defaults, String filename) {
        if ("messages.yml".equals(filename)) {
            Object raw = defaults.get("version");
            if (raw instanceof Number number) return exactVersion(number);
            return raw instanceof String value ? parseVersion(value) : -1;
        }
        if (!defaults.contains("config-version")) return -1;
        Object raw = defaults.get("config-version");
        return raw instanceof Number number ? exactVersion(number) : -1;
    }

    private int exactVersion(Number number) {
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
            return -1;
        }
        return (int) value;
    }

    private int parseVersion(String value) {
        try {
            int version = Integer.parseInt(value.trim());
            return version >= 0 ? version : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setVersion(FileConfiguration configuration, String filename, int version) {
        if ("messages.yml".equals(filename)) {
            configuration.set("version", String.valueOf(version));
        } else {
            configuration.set("config-version", version);
        }
    }
}
