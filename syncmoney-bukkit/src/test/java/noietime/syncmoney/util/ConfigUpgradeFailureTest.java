package noietime.syncmoney.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.CopyOption;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ConfigUpgradeFailureTest {

    @TempDir
    Path dataFolder;

    @Test
    void upgradesMissingDefaultsAndAdvancesVersionAfterSuccess() throws Exception {
        Path configFile = writeOldConfig();
        byte[] original = Files.readAllBytes(configFile);

        assertTrue(merger().mergeAndSave("config.yml"));

        var upgraded = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile.toFile());
        assertEquals(12, upgraded.getInt("config-version"));
        assertEquals("operator-value", upgraded.getString("server-name"));
        assertEquals("operator-value", upgraded.getString("operator-extension.custom-label"));
        assertEquals(750, upgraded.getInt("new-default.retry-limit"));

        try (var backups = Files.list(dataFolder)) {
            Path backup = backups.filter(path -> path.getFileName().toString().endsWith(".bak"))
                    .findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(backup));
        }
    }

    @Test
    void doesNotOverwriteAnExistingBackupWhenUpgrading() throws Exception {
        Path configFile = writeOldConfig();
        Path existingBackup = dataFolder.resolve("config.yml.v11.bak");
        byte[] previousBackup = "previous operator backup".getBytes(StandardCharsets.UTF_8);
        Files.write(existingBackup, previousBackup);

        assertTrue(merger().mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(previousBackup, Files.readAllBytes(existingBackup));
    }

    @Test
    void repeatedUpgradeDoesNotRewriteConfigOrCreateAnotherBackup() throws Exception {
        Path configFile = writeOldConfig();
        byte[] original = Files.readAllBytes(configFile);
        int[] fileOperationCalls = {0};
        ConfigMerger configMerger = merger(countingOperations(fileOperationCalls));

        assertTrue(configMerger.mergeAndSave("config.yml"));

        byte[] upgraded = Files.readAllBytes(configFile);
        int callsAfterUpgrade = fileOperationCalls[0];
        assertTrue(callsAfterUpgrade > 0);
        Path backup;
        try (var backups = Files.list(dataFolder)) {
            var backupFiles = backups.filter(path -> path.getFileName().toString().endsWith(".bak")).toList();
            assertEquals(1, backupFiles.size());
            backup = backupFiles.get(0);
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(backup));

        assertFalse(configMerger.mergeAndSave("config.yml"));

        assertEquals(callsAfterUpgrade, fileOperationCalls[0], "rerun must perform no file writes or replacements");
        org.junit.jupiter.api.Assertions.assertArrayEquals(upgraded, Files.readAllBytes(configFile));
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(backup));
        try (var backups = Files.list(dataFolder)) {
            assertEquals(1, backups.filter(path -> path.getFileName().toString().endsWith(".bak")).count());
        }
    }

    @Test
    void backupFailureLeavesTheOriginalConfigUntouched() throws Exception {
        Path configFile = writeOldConfig();
        byte[] original = Files.readAllBytes(configFile);

        assertFalse(merger(failingOperations(true, false, false)).mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
    }

    @Test
    void schemaTwelveWithoutMissingDefaultsIsNotRewritten() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = ("config-version: 12\nserver-name: custom\nqueue-capacity: 50000\n"
                + "new-default:\n  retry-limit: 750\neconomy:\n  starting-balance: 1000.00\n"
                + "operator-extension:\n  custom-label: operator-value\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, original);

        assertFalse(merger().mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
        assertFalse(Files.exists(dataFolder.resolve("config.yml.v12.bak")));
    }

    @Test
    void newerConfigSchemaIsNotDowngradedOrRewritten() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = ("config-version: 13\nserver-name: custom\nfuture-option: keep\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, original);

        assertFalse(merger().mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
        assertFalse(Files.exists(dataFolder.resolve("config.yml.v13.bak")));
    }

    @Test
    void sameSchemaWithMissingDefaultsIsBackedUpAndKeepsItsVersion() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = ("config-version: 12\nserver-name: operator-value\n"
                + "operator-extension:\n  custom-label: operator-value\n").getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, original);

        assertTrue(merger().mergeAndSave("config.yml"));

        var upgraded = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile.toFile());
        assertEquals(12, upgraded.getInt("config-version"));
        assertEquals("operator-value", upgraded.getString("server-name"));
        assertEquals("operator-value", upgraded.getString("operator-extension.custom-label"));
        assertEquals(50000, upgraded.getInt("queue-capacity"));
        try (var backups = Files.list(dataFolder)) {
            Path backup = backups.filter(path -> path.getFileName().toString().matches(
                    "config\\.yml\\.v12\\.\\d{8}T\\d{6}\\.\\d{3}Z-[0-9a-f-]{36}\\.bak"))
                    .findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(backup));
        }
    }

    @Test
    void malformedLocalYamlIsNotRewritten() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = "config-version: 11\noperator-section: [unterminated\n".getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, original);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("config-upgrade-test"));
            assertFalse(merger().mergeAndSave("config.yml"));
        }

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
    }

    @Test
    void invalidPresentSchemaVersionIsNotRewritten() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = ("config-version: invalid\nserver-name: operator-value\n"
                + "operator-extension: keep-me\n").getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, original);

        assertFalse(merger().mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
        try (var backups = Files.list(dataFolder)) {
            assertFalse(backups.anyMatch(path -> path.getFileName().toString().endsWith(".bak")));
        }
    }

    @Test
    void scalarParentConflictDoesNotReplaceOperatorValue() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        byte[] original = ("config-version: 11\nserver-name: operator-value\neconomy: operator-value\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, original);

        assertFalse(merger().mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
        try (var backups = Files.list(dataFolder)) {
            assertFalse(backups.anyMatch(path -> path.getFileName().toString().endsWith(".bak")));
        }
    }

    @Test
    void tempWriteFailureLeavesOriginalConfigUntouched() throws Exception {
        Path configFile = writeOldConfig();
        byte[] original = Files.readAllBytes(configFile);

        assertFalse(merger(failingOperations(false, true, false)).mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
    }

    @Test
    void replaceFailureLeavesOriginalConfigUntouched() throws Exception {
        Path configFile = writeOldConfig();
        byte[] original = Files.readAllBytes(configFile);

        assertFalse(merger(failingOperations(false, false, true)).mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
    }

    @Test
    void semanticallyChangedSerializedCandidateIsRejected() throws Exception {
        Path configFile = writeOldConfig();
        byte[] original = Files.readAllBytes(configFile);

        assertFalse(merger(wrongCandidateOperations()).mergeAndSave("config.yml"));

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(configFile));
    }

    private Path writeOldConfig() throws Exception {
        Path configFile = dataFolder.resolve("config.yml");
        Files.writeString(configFile, "config-version: 11\nserver-name: operator-value\n"
                + "operator-extension:\n  custom-label: operator-value\n", StandardCharsets.UTF_8);
        return configFile;
    }

    private ConfigMerger merger() {
        return merger(null);
    }

    private ConfigMerger merger(ConfigMerger.FileOperations fileOperations) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("config-upgrade-test"));
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> new ByteArrayInputStream(
                        ("config-version: 12\nserver-name: default\nqueue-capacity: 50000\n"
                        + "new-default:\n  retry-limit: 750\n"
                        + "economy:\n  starting-balance: 1000.00\n")
                        .getBytes(StandardCharsets.UTF_8)));
        return fileOperations == null
                ? new ConfigMerger(plugin, "config.yml")
                : new ConfigMerger(plugin, fileOperations, "config.yml");
    }

    private ConfigMerger.FileOperations failingOperations(boolean failCopy, boolean failWrite, boolean failMove) {
        return new ConfigMerger.FileOperations() {
            @Override
            public void copy(Path source, Path target) throws IOException {
                if (failCopy) {
                    throw new IOException("Injected backup failure");
                }
                Files.copy(source, target);
            }

            @Override
            public void write(Path target, String contents) throws IOException {
                if (failWrite) {
                    throw new IOException("Injected temporary write failure");
                }
                Files.writeString(target, contents, StandardCharsets.UTF_8);
            }

            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                if (failMove) {
                    throw new IOException("Injected replacement failure");
                }
                Files.move(source, target, options);
            }

            @Override
            public void deleteIfExists(Path target) throws IOException {
                Files.deleteIfExists(target);
            }
        };
    }

    private ConfigMerger.FileOperations countingOperations(int[] calls) {
        return new ConfigMerger.FileOperations() {
            @Override
            public void copy(Path source, Path target) throws IOException {
                calls[0]++;
                Files.copy(source, target);
            }

            @Override
            public void write(Path target, String contents) throws IOException {
                calls[0]++;
                Files.writeString(target, contents, StandardCharsets.UTF_8);
            }

            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                calls[0]++;
                Files.move(source, target, options);
            }

            @Override
            public void deleteIfExists(Path target) throws IOException {
                calls[0]++;
                Files.deleteIfExists(target);
            }
        };
    }

    private ConfigMerger.FileOperations wrongCandidateOperations() {
        return new ConfigMerger.FileOperations() {
            @Override
            public void copy(Path source, Path target) throws IOException {
                Files.copy(source, target);
            }

            @Override
            public void write(Path target, String contents) throws IOException {
                Files.writeString(target, "config-version: 12\nserver-name: replaced\n", StandardCharsets.UTF_8);
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
    }
}
