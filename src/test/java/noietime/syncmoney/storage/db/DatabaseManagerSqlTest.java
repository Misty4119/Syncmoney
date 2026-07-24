package noietime.syncmoney.storage.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerSqlTest {

    @Test
    void schemaVersionUpsert_usesPostgresqlConflictSyntax() {
        String sql = DatabaseManager.getSchemaVersionUpsertSql("postgresql");

        assertTrue(sql.contains("ON CONFLICT (id)"));
        assertTrue(sql.contains("EXCLUDED.version"));
        assertFalse(sql.contains("ON DUPLICATE KEY"));
    }

    @Test
    void schemaVersionUpsert_usesMysqlDuplicateKeySyntax() {
        String sql = DatabaseManager.getSchemaVersionUpsertSql("mysql");

        assertTrue(sql.contains("ON DUPLICATE KEY"));
        assertTrue(sql.contains("VALUES(version)"));
        assertFalse(sql.contains("ON CONFLICT"));
    }
}
