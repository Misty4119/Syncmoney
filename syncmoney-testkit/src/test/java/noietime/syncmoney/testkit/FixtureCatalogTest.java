package noietime.syncmoney.testkit;

import noietime.syncmoney.testkit.fixtures.FixtureCatalog;
import noietime.syncmoney.testkit.sqlite.DisposableSqliteDatabase;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureCatalogTest {

    @Test
    void publishesSyntheticLegacyProtocolGoldensAndAReal132SqliteSchema() throws Exception {
        assertTrue(FixtureCatalog.legacyBalanceV1Json().contains("9007199254740993"));
        assertTrue(FixtureCatalog.legacyCmiV1Json().contains("CMI_BALANCE_UPDATE"));

        try (DisposableSqliteDatabase database = DisposableSqliteDatabase.open()) {
            database.executeScript(FixtureCatalog.legacyLocalSqliteV132Sql());
            try (Statement statement = database.connection().createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT uuid, balance, version FROM player_balances ORDER BY uuid")) {
                assertTrue(rows.next());
                assertEquals(FixtureCatalog.PLAYER_A.toString(), rows.getString("uuid"));
                assertEquals(100.0d, rows.getDouble("balance"));
                assertEquals(7, rows.getInt("version"));
                assertTrue(rows.next());
                assertEquals(FixtureCatalog.PLAYER_B.toString(), rows.getString("uuid"));
                assertEquals(0.0d, rows.getDouble("balance"));
                assertEquals(3, rows.getInt("version"));
                assertFalse(rows.next());
            }

            try (Statement statement = database.connection().createStatement();
                 ResultSet columns = statement.executeQuery("PRAGMA table_info(transactions)")) {
                while (columns.next()) assertFalse("request_id".equals(columns.getString("name")));
            }
        }
    }
}
