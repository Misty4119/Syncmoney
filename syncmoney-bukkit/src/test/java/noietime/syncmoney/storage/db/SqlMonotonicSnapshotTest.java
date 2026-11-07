package noietime.syncmoney.storage.db;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.storage.persistence.PlayerSnapshot;
import noietime.syncmoney.storage.persistence.SnapshotConflictException;
import noietime.syncmoney.storage.persistence.SqlDialect;
import noietime.syncmoney.storage.persistence.SqlSnapshotWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMonotonicSnapshotTest {
    private static final Instant FIRST_WRITE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void olderSnapshotIsNoOpAndEqualSnapshotCanRefreshMetadata() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection connection = newSharedPlayersConnection()) {
            SqlSnapshotWriter.persistPlayers(connection,
                    List.of(snapshot(player, "PlayerA", "server-a", "100.00", 41, FIRST_WRITE)), SqlDialect.SQLITE);
            SqlSnapshotWriter.persistPlayers(connection,
                    List.of(snapshot(player, "StaleName", "stale-server", "90.00", 40,
                            FIRST_WRITE.plusSeconds(10))), SqlDialect.SQLITE);

            assertRow(connection, player, "PlayerA", "server-a", "100.00", 41,
                    FIRST_WRITE.toEpochMilli());

            SqlSnapshotWriter.persistPlayers(connection,
                    List.of(snapshot(player, "CurrentName", "server-b", "100.00", 41,
                            FIRST_WRITE.plusSeconds(20))), SqlDialect.SQLITE);

            assertRow(connection, player, "CurrentName", "server-b", "100.00", 41,
                    FIRST_WRITE.plusSeconds(20).toEpochMilli());
        }
    }

    @Test
    void equalVersionDifferentBalanceRollsBackTheWholeBatch() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (Connection connection = newSharedPlayersConnection()) {
            SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(first, "First", "server-a", "100.00", 41, FIRST_WRITE),
                    snapshot(second, "Second", "server-a", "50.00", 41, FIRST_WRITE)), SqlDialect.SQLITE);

            connection.setAutoCommit(false);
            assertThrows(SnapshotConflictException.class, () -> SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(first, "First", "server-a", "120.00", 42, FIRST_WRITE.plusSeconds(1)),
                    snapshot(second, "Second", "server-b", "49.00", 41, FIRST_WRITE.plusSeconds(1))),
                    SqlDialect.SQLITE));
            connection.rollback();

            assertRow(connection, first, "First", "server-a", "100.00", 41, FIRST_TIME_MILLIS);
            assertRow(connection, second, "Second", "server-a", "50.00", 41, FIRST_TIME_MILLIS);
        }
    }

    @Test
    void transferPairWithOneStaleRowDoesNotPersistEitherAccount() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        try (Connection connection = newSharedPlayersConnection()) {
            connection.setAutoCommit(false);
            SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(sender, "Sender", "server-a", "100.00", 10, FIRST_WRITE),
                    snapshot(receiver, "Receiver", "server-a", "50.00", 10, FIRST_WRITE)), SqlDialect.SQLITE);
            connection.commit();

            // The sender row has already advanced beyond this transfer receipt, while the
            // receiver row could still accept it. A transfer snapshot must be all-or-none.
            SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(sender, "Sender", "server-b", "90.00", 9, FIRST_WRITE.plusSeconds(1)),
                    snapshot(receiver, "Receiver", "server-b", "60.00", 11, FIRST_WRITE.plusSeconds(1))),
                    SqlDialect.SQLITE);
            connection.commit();

            assertRow(connection, sender, "Sender", "server-a", "100.00", 10, FIRST_TIME_MILLIS);
            assertRow(connection, receiver, "Receiver", "server-a", "50.00", 10, FIRST_TIME_MILLIS);
        }
    }

    @Test
    void staleFirstTransferLegDoesNotHideEqualVersionConflictInSecondLeg() throws Exception {
        UUID staleFirst = new UUID(0L, 1L);
        UUID conflictingSecond = new UUID(0L, 2L);
        try (Connection connection = newSharedPlayersConnection()) {
            SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(staleFirst, "First", "server-a", "100.00", 10, FIRST_WRITE),
                    snapshot(conflictingSecond, "Second", "server-a", "50.00", 10, FIRST_WRITE)),
                    SqlDialect.SQLITE);
            SqlSnapshotWriter.persistPlayers(connection,
                    List.of(snapshot(staleFirst, "First", "server-b", "110.00", 11,
                                    FIRST_WRITE.plusSeconds(1))), SqlDialect.SQLITE);

            assertThrows(SnapshotConflictException.class, () -> SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(staleFirst, "First", "server-c", "90.00", 10, FIRST_WRITE.plusSeconds(2)),
                    snapshot(conflictingSecond, "Second", "server-c", "55.00", 10,
                            FIRST_WRITE.plusSeconds(2))), SqlDialect.SQLITE));

            assertRow(connection, staleFirst, "First", "server-b", "110.00", 11,
                    FIRST_WRITE.plusSeconds(1).toEpochMilli());
            assertRow(connection, conflictingSecond, "Second", "server-a", "50.00", 10,
                    FIRST_TIME_MILLIS);
        }
    }

    private static Connection newSharedPlayersConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE players (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(16),
                        balance DECIMAL(20,2) NOT NULL,
                        version BIGINT NOT NULL,
                        last_server VARCHAR(64),
                        updated_at TIMESTAMP
                    )
                    """);
        }
        return connection;
    }

    private static PlayerSnapshot snapshot(UUID player, String name, String server,
            String balance, long version, Instant updatedAt) {
        return new PlayerSnapshot(new ConfirmedAccount(player, new BigDecimal(balance), version),
                name, server, updatedAt);
    }

    private static final long FIRST_TIME_MILLIS = FIRST_WRITE.toEpochMilli();

    private static void assertRow(Connection connection, UUID player, String name, String server,
            String balance, long version, long updatedAtMillis) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT player_name, last_server, balance, version, updated_at
                FROM players WHERE player_uuid = ?
                """)) {
            statement.setString(1, player.toString());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next(), "snapshot row should exist");
                assertEquals(name, row.getString("player_name"));
                assertEquals(server, row.getString("last_server"));
                assertEquals(0, new BigDecimal(balance).compareTo(row.getBigDecimal("balance")));
                assertEquals(version, row.getLong("version"));
                assertEquals(updatedAtMillis, row.getLong("updated_at"));
            }
        }
    }
}
