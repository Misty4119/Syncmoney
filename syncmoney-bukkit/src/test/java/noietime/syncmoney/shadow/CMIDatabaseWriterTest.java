package noietime.syncmoney.shadow;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CMIDatabaseWriterTest {

    @Test
    void initializesReadsUpdatesAndConfirmsPostCommitUsingCurrentCmiSqliteSchema(@TempDir Path tempDir)
            throws Exception {
        Path databasePath = tempDir.resolve("cmi.sqlite.db");
        UUID playerUuid = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement()) {
            // Synthetic CMI 9.8.10.5 users schema; this fixture contains no real player data.
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, player_uuid TEXT NOT NULL UNIQUE, "
                    + "username TEXT NOT NULL, Balance NUMERIC NOT NULL)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('"
                    + playerUuid + "', 'SyntheticAccount', 12.34)");
        }

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.shadowSync().getShadowSyncCMISQLitePath()).thenReturn(databasePath.toString());
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);

        try {
            writer.initialize();

            assertTrue(writer.isInitialized());
            assertTrue(writer.testConnection());
            CMIDatabaseWriter.BalanceReadResult initial = writer.readCMIBalanceStrict(playerUuid);
            assertEquals(CMIDatabaseWriter.BalanceReadStatus.FOUND, initial.status());
            assertEquals(new BigDecimal("12.34"), initial.balance());

            List<Boolean> updateResults = writer.batchUpdateBalanceResults(List.of(
                    new CMIDatabaseWriter.PlayerBalance(
                            "SyntheticAccount", playerUuid, new BigDecimal("25.00")),
                    new CMIDatabaseWriter.PlayerBalance(
                            "MissingSyntheticAccount", UUID.randomUUID(), new BigDecimal("40.00"))));

            assertEquals(List.of(true, false), updateResults,
                    "post-commit readback should confirm the updated synthetic row and reject the missing row");
            CMIDatabaseWriter.BalanceReadResult persisted = writer.readCMIBalanceStrict(playerUuid);
            assertEquals(CMIDatabaseWriter.BalanceReadStatus.FOUND, persisted.status());
            assertEquals(new BigDecimal("25.00"), persisted.balance());
        } finally {
            writer.close();
        }
    }

    @Test
    void duplicateUsernamesAreReadAndUpdatedByUuidAndLegacyNameApisFailClosed(@TempDir Path tempDir)
            throws Exception {
        Path databasePath = tempDir.resolve("cmi-duplicate-usernames.sqlite.db");
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, player_uuid TEXT NOT NULL UNIQUE, "
                    + "username TEXT NOT NULL, Balance NUMERIC NOT NULL)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('"
                    + firstUuid + "', 'DuplicateSyntheticName', 11.00)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('"
                    + secondUuid + "', 'DuplicateSyntheticName', 22.00)");
        }

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.shadowSync().getShadowSyncCMISQLitePath()).thenReturn(databasePath.toString());
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        try {
            writer.initialize();

            assertEquals(new BigDecimal("11.00"), writer.readCMIBalanceStrict(firstUuid).balance());
            assertEquals(new BigDecimal("22.00"), writer.readCMIBalanceStrict(secondUuid).balance());
            assertEquals(List.of(true), writer.batchUpdateBalanceResults(List.of(
                    new CMIDatabaseWriter.PlayerBalance(
                            "DuplicateSyntheticName", firstUuid, new BigDecimal("33.00")))));
            assertEquals(new BigDecimal("33.00"), writer.readCMIBalanceStrict(firstUuid).balance());
            assertEquals(new BigDecimal("22.00"), writer.readCMIBalanceStrict(secondUuid).balance(),
                    "updating one UUID must leave another UUID with the same username untouched");
            assertEquals(CMIDatabaseWriter.BalanceReadStatus.FAILED,
                    writer.readCMIBalanceStrict("DuplicateSyntheticName").status(),
                    "name-only strict reads must fail closed");
            assertNull(writer.readCMIBalance("DuplicateSyntheticName"));
            assertFalse(writer.updateBalance("DuplicateSyntheticName", new BigDecimal("99.00")),
                    "name-only updates must fail closed");
            assertEquals(new BigDecimal("22.00"), writer.readCMIBalanceStrict(secondUuid).balance());
        } finally {
            writer.close();
        }
    }

    @Test
    void duplicatePlayerUuidsFailClosedForStrictReadsAndBalanceUpdates(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("cmi-duplicate-player-uuids.sqlite.db");
        UUID duplicatedUuid = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement()) {
            // The supplied CMI schema does not declare player_uuid UNIQUE. Keep this fixture synthetic.
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, player_uuid TEXT NOT NULL, "
                    + "username TEXT NOT NULL, Balance NUMERIC NOT NULL)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('"
                    + duplicatedUuid + "', 'SyntheticDuplicateOne', 11.00)");
            statement.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('"
                    + duplicatedUuid + "', 'SyntheticDuplicateTwo', 22.00)");
        }

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.shadowSync().getShadowSyncCMISQLitePath()).thenReturn(databasePath.toString());
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        try {
            writer.initialize();

            CMIDatabaseWriter.BalanceReadResult read = writer.readCMIBalanceStrict(duplicatedUuid);
            assertEquals(CMIDatabaseWriter.BalanceReadStatus.FAILED, read.status(),
                    "a UUID matching multiple CMI rows must not select the first balance");
            assertNull(read.balance());
            assertNull(writer.readCMIBalance(duplicatedUuid),
                    "the convenience read must also fail closed for a duplicate UUID");

            assertEquals(List.of(false), writer.batchUpdateBalanceResults(List.of(
                    new CMIDatabaseWriter.PlayerBalance(
                            "SyntheticDuplicateOne", duplicatedUuid, new BigDecimal("33.00")))),
                    "a write targeting multiple rows must be rejected");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT Balance FROM users WHERE LOWER(player_uuid) = ? ORDER BY id")) {
                statement.setString(1, duplicatedUuid.toString().toLowerCase(java.util.Locale.ROOT));
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(new BigDecimal("11"), resultSet.getBigDecimal(1));
                    assertTrue(resultSet.next());
                    assertEquals(new BigDecimal("22"), resultSet.getBigDecimal(1));
                    assertFalse(resultSet.next(), "the fixture should contain exactly two matching rows");
                }
            }
        } finally {
            writer.close();
        }
    }

    @Test
    void returnsNoSuccessfulUpdatesWhenCommitFails() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(statement.executeBatch()).thenReturn(new int[] { 1 });
        doThrow(new SQLException("commit rejected")).when(connection).commit();
        installDataSource(writer, dataSource);

        try {
            int updated = writer.batchUpdateBalance(List.of(new CMIDatabaseWriter.PlayerBalance(
                    "AcceptanceCommitFailure", UUID.randomUUID(), new BigDecimal("25.00"))));

            assertEquals(0, updated);
            verify(connection).rollback();
        } finally {
            writer.close();
        }
    }

    @Test
    void doesNotCountBatchUpdateWhenPlayerRowIsMissing() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement detectColumns = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement verifyBalance = mock(PreparedStatement.class);
        PreparedStatement duplicateCheck = mock(PreparedStatement.class);
        ResultSet columnResult = mock(ResultSet.class);
        ResultSet noDuplicateRows = mock(ResultSet.class);
        ResultSet missingPlayer = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT *")) {
                return detectColumns;
            }
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            if (sql.contains("GROUP BY LOWER")) {
                return duplicateCheck;
            }
            return verifyBalance;
        });
        when(detectColumns.executeQuery()).thenReturn(columnResult);
        when(columnResult.findColumn("balance")).thenReturn(1);
        when(duplicateCheck.executeQuery()).thenReturn(noDuplicateRows);
        when(noDuplicateRows.next()).thenReturn(false);
        when(update.executeBatch()).thenReturn(new int[] { 0 });
        when(verifyBalance.executeQuery()).thenReturn(missingPlayer);
        when(missingPlayer.next()).thenReturn(false);
        installDataSource(writer, dataSource);

        try {
            int updated = writer.batchUpdateBalance(List.of(new CMIDatabaseWriter.PlayerBalance(
                    "AcceptanceMissing", UUID.randomUUID(), new BigDecimal("25.00"))));

            assertEquals(0, updated);
            verify(connection).commit();
            verify(verifyBalance).executeQuery();
        } finally {
            writer.close();
        }
    }

    @Test
    void confirmsZeroRowUpdateWhenCommittedBalanceAlreadyMatches() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement detectColumns = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement verifyBalance = mock(PreparedStatement.class);
        PreparedStatement duplicateCheck = mock(PreparedStatement.class);
        ResultSet columnResult = mock(ResultSet.class);
        ResultSet noDuplicateRows = mock(ResultSet.class);
        ResultSet persistedPlayer = mock(ResultSet.class);
        UUID playerId = UUID.randomUUID();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT *")) {
                return detectColumns;
            }
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            if (sql.contains("GROUP BY LOWER")) {
                return duplicateCheck;
            }
            return verifyBalance;
        });
        when(detectColumns.executeQuery()).thenReturn(columnResult);
        when(columnResult.findColumn("balance")).thenReturn(1);
        when(duplicateCheck.executeQuery()).thenReturn(noDuplicateRows);
        when(noDuplicateRows.next()).thenReturn(false);
        when(update.executeBatch()).thenReturn(new int[] { 0 });
        when(verifyBalance.executeQuery()).thenReturn(persistedPlayer);
        when(persistedPlayer.next()).thenReturn(true, false);
        when(persistedPlayer.getString(1)).thenReturn(playerId.toString());
        when(persistedPlayer.getObject(2)).thenReturn(new BigDecimal("25.00"));
        installDataSource(writer, dataSource);

        try {
            int updated = writer.batchUpdateBalance(List.of(new CMIDatabaseWriter.PlayerBalance(
                    "AcceptanceUnchanged", playerId, new BigDecimal("25.00"))));

            assertEquals(1, updated);
            verify(connection).commit();
            verify(verifyBalance).executeQuery();
        } finally {
            writer.close();
        }
    }

    @Test
    void doesNotConfirmCommittedBalanceThatDiffersFromRequest() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement detectColumns = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement verifyBalance = mock(PreparedStatement.class);
        PreparedStatement duplicateCheck = mock(PreparedStatement.class);
        ResultSet columnResult = mock(ResultSet.class);
        ResultSet noDuplicateRows = mock(ResultSet.class);
        ResultSet newerValue = mock(ResultSet.class);
        UUID playerId = UUID.randomUUID();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT *")) {
                return detectColumns;
            }
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            if (sql.contains("GROUP BY LOWER")) {
                return duplicateCheck;
            }
            return verifyBalance;
        });
        when(detectColumns.executeQuery()).thenReturn(columnResult);
        when(columnResult.findColumn("balance")).thenReturn(1);
        when(duplicateCheck.executeQuery()).thenReturn(noDuplicateRows);
        when(noDuplicateRows.next()).thenReturn(false);
        when(update.executeBatch()).thenReturn(new int[] { 1 });
        when(verifyBalance.executeQuery()).thenReturn(newerValue);
        when(newerValue.next()).thenReturn(true, false);
        when(newerValue.getString(1)).thenReturn(playerId.toString());
        when(newerValue.getObject(2)).thenReturn(new BigDecimal("99.00"));
        installDataSource(writer, dataSource);

        try {
            int updated = writer.batchUpdateBalance(List.of(new CMIDatabaseWriter.PlayerBalance(
                    "AcceptanceNewerValue", playerId, new BigDecimal("25.00"))));

            assertEquals(0, updated);
            verify(connection).commit();
            verify(verifyBalance).executeQuery();
        } finally {
            writer.close();
        }
    }

    @Test
    void preservesExactPrecisionForLargeCommittedBalances() throws Exception {
        BigDecimal expectedBalance = new BigDecimal("999999999999999999.99");
        UUID playerId = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement detectColumns = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement verifyBalance = mock(PreparedStatement.class);
        PreparedStatement duplicateCheck = mock(PreparedStatement.class);
        ResultSet columnResult = mock(ResultSet.class);
        ResultSet noDuplicateRows = mock(ResultSet.class);
        ResultSet persistedPlayer = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT *")) {
                return detectColumns;
            }
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            if (sql.contains("GROUP BY LOWER")) {
                return duplicateCheck;
            }
            return verifyBalance;
        });
        when(detectColumns.executeQuery()).thenReturn(columnResult);
        when(columnResult.findColumn("balance")).thenReturn(1);
        when(duplicateCheck.executeQuery()).thenReturn(noDuplicateRows);
        when(noDuplicateRows.next()).thenReturn(false);
        when(update.executeBatch()).thenReturn(new int[] { 1 });
        when(verifyBalance.executeQuery()).thenReturn(persistedPlayer);
        when(persistedPlayer.next()).thenReturn(true, false);
        when(persistedPlayer.getString(1)).thenReturn(playerId.toString());
        when(persistedPlayer.getObject(2)).thenReturn(expectedBalance);
        installDataSource(writer, dataSource);

        try {
            List<Boolean> results = writer.batchUpdateBalanceResults(List.of(new CMIDatabaseWriter.PlayerBalance(
                    "AcceptanceLargeBalance", playerId, expectedBalance)));

            assertEquals(List.of(true), results);
            verify(connection).commit();
        } finally {
            writer.close();
        }
    }

    @Test
    void reportsPerEntryConfirmationForRepeatedPlayerSnapshots() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement detectColumns = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement verifyBalance = mock(PreparedStatement.class);
        PreparedStatement duplicateCheck = mock(PreparedStatement.class);
        ResultSet columnResult = mock(ResultSet.class);
        ResultSet noDuplicateRows = mock(ResultSet.class);
        ResultSet finalPlayerValue = mock(ResultSet.class);
        UUID playerId = UUID.randomUUID();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT *")) {
                return detectColumns;
            }
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            if (sql.contains("GROUP BY LOWER")) {
                return duplicateCheck;
            }
            return verifyBalance;
        });
        when(detectColumns.executeQuery()).thenReturn(columnResult);
        when(columnResult.findColumn("balance")).thenReturn(1);
        when(duplicateCheck.executeQuery()).thenReturn(noDuplicateRows);
        when(noDuplicateRows.next()).thenReturn(false);
        when(update.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(verifyBalance.executeQuery()).thenReturn(finalPlayerValue);
        when(finalPlayerValue.next()).thenReturn(true, false);
        when(finalPlayerValue.getString(1)).thenReturn(playerId.toString());
        when(finalPlayerValue.getObject(2)).thenReturn(new BigDecimal("7.00"));
        installDataSource(writer, dataSource);
        try {
            List<Boolean> results = writer.batchUpdateBalanceResults(List.of(
                    new CMIDatabaseWriter.PlayerBalance("AcceptanceDuplicate", playerId, new BigDecimal("5.00")),
                    new CMIDatabaseWriter.PlayerBalance("acceptanceduplicate", playerId, new BigDecimal("7.00"))));

            assertEquals(List.of(false, true), results);
            verify(verifyBalance, org.mockito.Mockito.times(1)).executeQuery();
            verify(connection).commit();
        } finally {
            writer.close();
        }
    }

    @Test
    void doesNotConfirmBalancesWhenBatchedReadbackFails() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement detectColumns = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement verifyBalance = mock(PreparedStatement.class);
        PreparedStatement duplicateCheck = mock(PreparedStatement.class);
        ResultSet columnResult = mock(ResultSet.class);
        ResultSet noDuplicateRows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT *")) {
                return detectColumns;
            }
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            if (sql.contains("GROUP BY LOWER")) {
                return duplicateCheck;
            }
            return verifyBalance;
        });
        when(detectColumns.executeQuery()).thenReturn(columnResult);
        when(columnResult.findColumn("balance")).thenReturn(1);
        when(duplicateCheck.executeQuery()).thenReturn(noDuplicateRows);
        when(noDuplicateRows.next()).thenReturn(false);
        when(update.executeBatch()).thenReturn(new int[] { 1 });
        when(verifyBalance.executeQuery()).thenThrow(new SQLException("balance readback failed"));
        installDataSource(writer, dataSource);

        try {
            List<Boolean> results = writer.batchUpdateBalanceResults(List.of(new CMIDatabaseWriter.PlayerBalance(
                    "AcceptanceReadbackFailure", UUID.randomUUID(), new BigDecimal("25.00"))));

            assertEquals(List.of(false), results);
            verify(connection).commit();
            verify(verifyBalance).executeQuery();
        } finally {
            writer.close();
        }
    }

    @Test
    void strictBalanceReadDistinguishesMissingPlayerFromReadAndColumnFailures() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement readBalance = mock(PreparedStatement.class);
        ResultSet foundPlayer = mock(ResultSet.class);
        ResultSet missingPlayer = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(readBalance);
        when(readBalance.executeQuery()).thenReturn(foundPlayer, missingPlayer)
                .thenThrow(new SQLException("balance query failed"));
        when(foundPlayer.next()).thenReturn(true, false);
        when(foundPlayer.getObject(1)).thenReturn(new BigDecimal("100.126"));
        when(missingPlayer.next()).thenReturn(false);
        installDataSource(writer, dataSource);

        try {
            CMIDatabaseWriter.BalanceReadResult found = writer.readCMIBalanceStrict(UUID.randomUUID());
            CMIDatabaseWriter.BalanceReadResult absent = writer.readCMIBalanceStrict(UUID.randomUUID());
            CMIDatabaseWriter.BalanceReadResult unreadable = writer.readCMIBalanceStrict(UUID.randomUUID());

            assertEquals(CMIDatabaseWriter.BalanceReadStatus.FOUND, found.status());
            assertEquals(new BigDecimal("100.13"), found.balance());
            assertEquals(CMIDatabaseWriter.BalanceReadStatus.NOT_FOUND, absent.status());
            assertNull(absent.balance());
            assertEquals(CMIDatabaseWriter.BalanceReadStatus.FAILED, unreadable.status());
            assertNull(unreadable.balance());
        } finally {
            writer.close();
        }
    }

    @Test
    void rejectsSchemaWithoutStablePlayerUuidColumn(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("cmi-name-only.sqlite.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT NOT NULL, "
                    + "Balance NUMERIC NOT NULL)");
            statement.execute("INSERT INTO users (username, Balance) VALUES ('NameOnlySynthetic', 1.00)");
        }

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIDatabaseWriterTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.shadowSync().getShadowSyncCMISQLitePath()).thenReturn(databasePath.toString());
        CMIDatabaseWriter writer = new CMIDatabaseWriter(plugin, config);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, writer::initialize);
            assertFalse(writer.isInitialized());
        } finally {
            writer.close();
        }
    }

    private static void installDataSource(CMIDatabaseWriter writer, HikariDataSource dataSource) throws Exception {
        setField(writer, "dataSource", dataSource);
        setField(writer, "initialized", true);
        setField(writer, "useSQLite", true);
        setField(writer, "resolvedTableName", "users");
        setField(writer, "resolvedPlayerUuidColumn", "player_uuid");
        setField(writer, "resolvedPlayerNameColumn", "playername");
        setField(writer, "resolvedBalanceColumn", "balance");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
