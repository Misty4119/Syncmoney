package noietime.syncmoney.migration;

import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationSourceFingerprintTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void singleSourcePagesUseTheSameStableOrderAsTheFingerprintSnapshot() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceFingerprintTest"));
        Path pluginData = temporaryDirectory.resolve("plugins/Syncmoney");
        Path cmiSettings = temporaryDirectory.resolve("plugins/CMI/Settings");
        Files.createDirectories(pluginData);
        Files.createDirectories(cmiSettings);
        Files.writeString(cmiSettings.resolve("DataBaseInfo.yml"), "storage:\n  method: SQLite\n");
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());

        Path databaseFile = temporaryDirectory.resolve("plugins/CMI/cmi.sqlite.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('zeta', '00000000-0000-0000-0000-000000000082', '20.00', 2)");
            statement.execute("INSERT INTO users VALUES ('Alpha', '00000000-0000-0000-0000-000000000081', '10.00', 1)");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseReader reader = new CMIDatabaseReader(plugin, config);
        reader.initialize();
        try {
            MigrationSourceFingerprint.Result snapshot = reader.getSourceSnapshotFingerprint();
            List<CMIDatabaseReader.CMIPlayerData> firstPage = reader.readPlayers(0, 1);
            List<CMIDatabaseReader.CMIPlayerData> secondPage = reader.readPlayers(1, 1);

            assertEquals(2, snapshot.playerCount());
            assertTrue(snapshot.value().matches("[0-9a-f]{64}"), "the stored fingerprint is a SHA-256 hex digest");
            assertEquals("Alpha", firstPage.get(0).playerName());
            assertEquals("zeta", secondPage.get(0).playerName());
        } finally {
            reader.shutdown();
        }
    }

    @Test
    void multiSourceMergeTieIsStableWhenSqlRowOrderChanges() throws Exception {
        Path firstDatabase = temporaryDirectory.resolve("multi-first.sqlite");
        Path secondDatabase = temporaryDirectory.resolve("multi-second.sqlite");
        createTiedRows(firstDatabase, false);
        createTiedRows(secondDatabase, true);

        MigrationSourceFingerprint.Result firstSnapshot;
        CMIDatabaseReader.CMIPlayerData firstMerged;
        CMIMultiServerReader firstReader = createMultiReader(firstDatabase);
        firstReader.initialize();
        try {
            firstSnapshot = firstReader.getSourceSnapshotFingerprint();
            firstMerged = firstReader.readAndMergePlayers().get(0);
        } finally {
            firstReader.shutdown();
        }

        MigrationSourceFingerprint.Result secondSnapshot;
        CMIDatabaseReader.CMIPlayerData secondMerged;
        CMIMultiServerReader secondReader = createMultiReader(secondDatabase);
        secondReader.initialize();
        try {
            secondSnapshot = secondReader.getSourceSnapshotFingerprint();
            secondMerged = secondReader.readAndMergePlayers().get(0);
        } finally {
            secondReader.shutdown();
        }

        assertEquals(firstSnapshot.value(), secondSnapshot.value(),
                "same logical source rows must yield the same merged snapshot despite SQL row order");
        assertEquals(firstMerged, secondMerged,
                "the migration row selected for a merge tie must not depend on database iteration order");
    }

    private void createTiedRows(Path database, boolean reverse) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            String first = "INSERT INTO users VALUES ('same-player', '00000000-0000-0000-0000-000000000081', '10.00', 7)";
            String second = "INSERT INTO users VALUES ('same-player', '00000000-0000-0000-0000-000000000082', '20.00', 7)";
            statement.execute(reverse ? second : first);
            statement.execute(reverse ? first : second);
        }
    }

    private CMIMultiServerReader createMultiReader(Path database) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationSourceFingerprintTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getCMISqlitePaths()).thenReturn(List.of(database.toString()));
        when(config.migration().getCMIMergeStrategy()).thenReturn("latest");
        return new CMIMultiServerReader(plugin, config);
    }
}
