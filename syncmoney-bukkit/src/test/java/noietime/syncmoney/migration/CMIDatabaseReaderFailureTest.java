package noietime.syncmoney.migration;

import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CMIDatabaseReaderFailureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void countQueryFailureDoesNotLookLikeAnEmptySource() throws Exception {
        Plugin plugin = createPlugin();
        Path database = createDatabase(plugin);
        Files.createFile(database);

        CMIDatabaseReader reader = new CMIDatabaseReader(plugin,
                mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        reader.initialize();

        IllegalStateException failure = assertThrows(IllegalStateException.class, reader::getTotalPlayerCount);
        assertTrue(failure.getMessage().contains("player count"));
        reader.shutdown();
    }

    @Test
    void malformedBalanceDoesNotBecomeZero() throws Exception {
        Plugin plugin = createPlugin();
        Path database = createDatabase(plugin);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('broken-balance', '00000000-0000-0000-0000-000000000001', 'not-money', 1)");
        }

        CMIDatabaseReader reader = new CMIDatabaseReader(plugin,
                mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        reader.initialize();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> reader.readPlayers(0, 10));
        assertTrue(failure.getMessage().contains("balance"));
        reader.shutdown();
    }

    @Test
    void nullBalanceFailsClosedForPagedAndSinglePlayerReads() throws Exception {
        Plugin plugin = createPlugin();
        Path database = createDatabase(plugin);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('null-balance', '00000000-0000-0000-0000-000000000001', NULL, 1)");
        }

        CMIDatabaseReader reader = new CMIDatabaseReader(plugin,
                mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        reader.initialize();

        assertThrows(IllegalStateException.class, () -> reader.readPlayers(0, 10));
        assertThrows(IllegalStateException.class, () -> reader.readPlayer("null-balance"));
        reader.shutdown();
    }

    private Plugin createPlugin() throws Exception {
        Path pluginData = temporaryDirectory.resolve("plugins/Syncmoney");
        Path cmiSettings = temporaryDirectory.resolve("plugins/CMI/Settings");
        Files.createDirectories(pluginData);
        Files.createDirectories(cmiSettings);
        Files.writeString(cmiSettings.resolve("DataBaseInfo.yml"), "storage:\n  method: SQLite\n");

        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseReaderFailureTest"));
        return plugin;
    }

    private Path createDatabase(Plugin plugin) throws Exception {
        Path database = plugin.getDataFolder().getParentFile().toPath().resolve("CMI/cmi.sqlite.db");
        Files.createDirectories(database.getParent());
        return database;
    }
}
