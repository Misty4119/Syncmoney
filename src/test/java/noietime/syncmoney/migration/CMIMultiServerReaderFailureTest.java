package noietime.syncmoney.migration;

import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CMIMultiServerReaderFailureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void configuredMissingDatabasePreventsPartialInitialization() throws Exception {
        Path available = Files.createFile(temporaryDirectory.resolve("available.sqlite"));
        Path missing = temporaryDirectory.resolve("missing.sqlite");
        CMIMultiServerReader reader = createReader(List.of(available.toString(), missing.toString()));

        assertThrows(IllegalStateException.class, reader::initialize);
        reader.shutdown();
    }

    @Test
    void sqlReadFailureDoesNotReturnAnEmptyMergedSnapshot() throws Exception {
        Path database = Files.createFile(temporaryDirectory.resolve("no-users-table.sqlite"));
        CMIMultiServerReader reader = createReader(List.of(database.toString()));
        reader.initialize();

        assertThrows(IllegalStateException.class, reader::readAndMergePlayers);
        reader.shutdown();
    }

    @Test
    void nullBalanceDoesNotBecomeZeroInMergedSnapshot() throws Exception {
        Path database = temporaryDirectory.resolve("null-balance.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (username TEXT, player_uuid TEXT, Balance TEXT, LastLogoffTime INTEGER)");
            statement.execute("INSERT INTO users VALUES ('null-balance', '00000000-0000-0000-0000-000000000001', NULL, 1)");
        }

        CMIMultiServerReader reader = createReader(List.of(database.toString()));
        reader.initialize();

        assertThrows(IllegalStateException.class, reader::readAndMergePlayers);
        reader.shutdown();
    }

    private CMIMultiServerReader createReader(List<String> paths) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIMultiServerReaderFailureTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.migration().getCMISqlitePaths()).thenReturn(paths);
        when(config.migration().getCMIMergeStrategy()).thenReturn("latest");
        return new CMIMultiServerReader(plugin, config);
    }
}
