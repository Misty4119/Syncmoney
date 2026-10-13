package noietime.syncmoney.baltop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaltopManagerSqlTest {

    @Test
    void mysqlFamilyQuotesTheLegacyRankColumnInSchemaAndUpsertSql() {
        assertRankIdentifierQuoted(BaltopManager.buildSchemaSql(false), "`rank` INT");

        String upsertSql = BaltopManager.buildUpsertSql(false);
        assertRankIdentifierQuoted(upsertSql, "balance, `rank`, last_update");
        assertRankIdentifierQuoted(upsertSql, "`rank` = VALUES(`rank`)");
    }

    @Test
    void postgresQuotesTheLegacyRankColumnInSchemaAndUpsertSql() {
        assertRankIdentifierQuoted(BaltopManager.buildSchemaSql(true), "\"rank\" INT");

        String upsertSql = BaltopManager.buildUpsertSql(true);
        assertRankIdentifierQuoted(upsertSql, "balance, \"rank\", last_update");
        assertRankIdentifierQuoted(upsertSql, "\"rank\" = EXCLUDED.\"rank\"");
    }

    private static void assertRankIdentifierQuoted(String sql, String expectedFragment) {
        assertTrue(sql.contains(expectedFragment), () -> "Expected SQL to contain '" + expectedFragment
                + "' but was: " + sql);
        assertTrue(!sql.contains(" balance, rank,"), () -> "SQL must not use the reserved identifier unquoted: " + sql);
    }
}
