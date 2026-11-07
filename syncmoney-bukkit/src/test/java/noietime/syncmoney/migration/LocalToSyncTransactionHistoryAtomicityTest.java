package noietime.syncmoney.migration;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalToSyncTransactionHistoryAtomicityTest {
    private static final int ROWS = 501;

    @TempDir
    Path temp;

    @Test
    void failedHistoryImportRollsBackAndRetryDoesNotDuplicateRows() throws Exception {
        Path source = temp.resolve("local-history.sqlite");
        Path target = temp.resolve("shared-history.sqlite");
        UUID uuid = UUID.randomUUID();
        createSource(source, uuid);
        createTarget(target);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_late_audit_insert BEFORE INSERT ON syncmoney_audit_log "
                    + "WHEN NEW.sequence = 501 BEGIN SELECT RAISE(ABORT, 'injected late import failure'); END");
        }

        LocalToSyncMigrationTask task = task(target);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> migrate(task, source));
        assertNotNull(failure.getCause());
        assertEquals(0, auditCount(target), "a later batch failure must roll back earlier imported rows");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_late_audit_insert");
        }

        assertEquals(ROWS, migrate(task, source));
        assertEquals(ROWS, auditCount(target));
        assertEquals(ROWS, migrate(task, source), "retry should recognize stable source row identities");
        assertEquals(ROWS, auditCount(target), "a successful retry must not duplicate imported audit rows");
    }

    private LocalToSyncMigrationTask task(Path target) throws Exception {
        String targetUrl = "jdbc:sqlite:" + target;
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isConnected()).thenReturn(true);
        when(database.getConnection()).thenAnswer(ignored -> DriverManager.getConnection(targetUrl));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.database().getDatabaseType()).thenReturn("sqlite");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncTransactionHistoryAtomicityTest"));
        return new LocalToSyncMigrationTask(plugin, config, mock(EconomyFacade.class), database,
                mock(RedisManager.class), mock(MigrationCheckpoint.class), null);
    }

    private static int migrate(LocalToSyncMigrationTask task, Path source) throws Exception {
        Method method = LocalToSyncMigrationTask.class.getDeclaredMethod("migrateTransactions", String.class);
        method.setAccessible(true);
        return (int) method.invoke(task, "jdbc:sqlite:" + source);
    }

    private static void createSource(Path source, UUID uuid) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, name TEXT, balance REAL, version INTEGER)");
            statement.execute("INSERT INTO player_balances VALUES ('" + uuid + "', 'source-player', 100.00, 1)");
            statement.execute("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, name TEXT, "
                    + "type TEXT, amount REAL, balance_after REAL, balance_before REAL, source TEXT, timestamp INTEGER, "
                    + "sequence INTEGER, target_name TEXT)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO transactions (uuid, name, type, amount, balance_after, balance_before, source, timestamp, sequence, target_name) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int index = 1; index <= ROWS; index++) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, "source-player");
                    insert.setString(3, "DEPOSIT");
                    insert.setDouble(4, 1.0);
                    insert.setDouble(5, index);
                    insert.setDouble(6, index - 1.0);
                    insert.setString(7, "ADMIN_GIVE");
                    insert.setLong(8, 1_700_000_000_000L + index);
                    insert.setInt(9, index);
                    insert.setNull(10, java.sql.Types.VARCHAR);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private static void createTarget(Path target) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE syncmoney_audit_log (id TEXT PRIMARY KEY, timestamp INTEGER, sequence INTEGER, "
                    + "type TEXT, player_uuid TEXT, player_name TEXT, amount NUMERIC, balance_after NUMERIC, "
                    + "source TEXT, server TEXT, target_uuid TEXT, target_name TEXT, reason TEXT, merged_count INTEGER)");
        }
    }

    private static int auditCount(Path target) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
