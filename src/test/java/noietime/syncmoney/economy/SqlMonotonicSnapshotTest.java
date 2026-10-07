package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlMonotonicSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void localSqliteSnapshotIgnoresOlderVersionAndRetainsRealFormat() throws Exception {
        Path database = tempDir.resolve("monotonic-local.db");
        LocalEconomyHandler handler = newHandler(database);
        try {
            UUID player = UUID.randomUUID();
            writeSnapshot(handler, player, "PlayerA", new BigDecimal("100.00"), 41);
            writeSnapshot(handler, player, "OldName", new BigDecimal("90.00"), 40);

            try (Statement statement = connection(handler).createStatement();
                    ResultSet row = statement.executeQuery(
                            "SELECT balance, version, typeof(balance) FROM player_balances WHERE uuid = '" + player + "'")) {
                assertTrue(row.next());
                assertEquals(0, new BigDecimal("100.00").compareTo(row.getBigDecimal(1)),
                        "version 40 must not overwrite the balance persisted at version 41");
                assertEquals(41, row.getInt(2));
                assertEquals("real", row.getString(3), "the existing SQLite REAL balance format must remain");
            }
        } finally {
            handler.close();
        }
    }

    @Test
    void localSqliteEqualVersionDifferentBalanceIsRejected() throws Exception {
        Path database = tempDir.resolve("equal-conflict-local.db");
        LocalEconomyHandler handler = newHandler(database);
        try {
            UUID player = UUID.randomUUID();
            writeSnapshot(handler, player, "PlayerA", new BigDecimal("100.00"), 41);

            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> writeSnapshot(handler, player, "PlayerA", new BigDecimal("99.00"), 41));
            connection(handler).rollback();

            assertTrue(failure.getCause().getClass().getSimpleName().contains("Conflict"),
                    "equal-version economic disagreement must be surfaced as a persistence conflict");
        } finally {
            handler.close();
        }
    }

    private LocalEconomyHandler newHandler(Path database) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SqlMonotonicSnapshotTest"));
        return new LocalEconomyHandler(plugin, database.toString());
    }

    private static void writeSnapshot(LocalEconomyHandler handler, UUID uuid, String name,
            BigDecimal balance, int version) throws Exception {
        Method upsert = LocalEconomyHandler.class.getDeclaredMethod(
                "upsertBalance", UUID.class, String.class, BigDecimal.class, int.class);
        upsert.setAccessible(true);
        upsert.invoke(handler, uuid, name, balance, version);
        connection(handler).commit();
    }

    private static Connection connection(LocalEconomyHandler handler) throws Exception {
        Field field = LocalEconomyHandler.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(handler);
    }
}