package noietime.syncmoney.storage.persistence;

import noietime.syncmoney.core.port.ConfirmedAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSnapshotWriterIntegrationTest {
    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void guardedUpsertAndBatchRollbackWorkOnConfiguredRealBackends() throws Exception {
        List<Backend> configured = configuredBackends();
        assertEquals(Set.of("postgresql", "mysql", "mariadb"), configured.stream()
                        .map(Backend::id).collect(Collectors.toSet()),
                "the required T05 integration gate needs PostgreSQL, MySQL, and MariaDB; configure all three SYNCMONEY_TEST_*_URL values");

        for (Backend backend : configured) {
            System.out.println("Running SQL snapshot integration against " + backend.displayName());
            exerciseIsolatedNamespace(backend);
        }
    }

    private static List<Backend> configuredBackends() {
        List<Backend> result = new ArrayList<>();
        addIfConfigured(result, "postgresql", "PostgreSQL", SqlDialect.POSTGRESQL,
                "org.postgresql.Driver");
        addIfConfigured(result, "mysql", "MySQL", SqlDialect.MYSQL,
                "com.mysql.cj.jdbc.Driver");
        addIfConfigured(result, "mariadb", "MariaDB", SqlDialect.MYSQL,
                "org.mariadb.jdbc.Driver");
        return result;
    }

    private static void addIfConfigured(List<Backend> result, String id, String displayName,
            SqlDialect dialect, String driverClass) {
        String url = System.getProperty("syncmoney.test." + id + ".url", "").trim();
        if (!url.isEmpty()) {
            result.add(new Backend(id, displayName, dialect, driverClass, url,
                    System.getProperty("syncmoney.test." + id + ".user", ""),
                    System.getProperty("syncmoney.test." + id + ".password", "")));
        }
    }

    private static void exerciseIsolatedNamespace(Backend backend) throws Exception {
        Class.forName(backend.driverClass());
        String namespace = "syncmoney_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect(backend)) {
            createNamespace(admin, backend, namespace);
            try (Connection isolated = connect(backend)) {
                selectNamespace(isolated, backend, namespace);
                createPlayersTable(isolated);
                assertSnapshotSemantics(isolated, backend.dialect());
                try (Connection contender = connect(backend)) {
                    selectNamespace(contender, backend, namespace);
                    assertConcurrentSnapshotSemantics(isolated, contender, backend);
                }
                assertLegacyWriterCanRegress(isolated, backend);
            } finally {
                dropNamespace(admin, backend, namespace);
            }
        }
    }

    private static Connection connect(Backend backend) throws SQLException {
        return DriverManager.getConnection(backend.url(), backend.user(), backend.password());
    }

    private static void createNamespace(Connection admin, Backend backend, String namespace) throws SQLException {
        String statement = backend.id().equals("postgresql")
                ? "CREATE SCHEMA " + namespace
                : "CREATE DATABASE " + namespace;
        try (Statement sql = admin.createStatement()) {
            sql.execute(statement);
        }
    }

    private static void selectNamespace(Connection connection, Backend backend, String namespace) throws SQLException {
        if (backend.id().equals("postgresql")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + namespace);
            }
        } else {
            connection.setCatalog(namespace);
        }
    }

    private static void dropNamespace(Connection admin, Backend backend, String namespace) throws SQLException {
        String statement = backend.id().equals("postgresql")
                ? "DROP SCHEMA " + namespace + " CASCADE"
                : "DROP DATABASE " + namespace;
        try (Statement sql = admin.createStatement()) {
            sql.execute(statement);
        }
    }

    private static void createPlayersTable(Connection connection) throws SQLException {
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
    }

    private static void assertSnapshotSemantics(Connection connection, SqlDialect dialect) throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SqlSnapshotWriter.persistPlayers(connection, List.of(
                snapshot(first, "First", "server-a", "100.00", 41, BASE_TIME),
                snapshot(second, "Second", "server-a", "50.00", 41, BASE_TIME)), dialect);

        SqlSnapshotWriter.persistPlayers(connection,
                List.of(snapshot(first, "StaleName", "stale-server", "90.00", 40, BASE_TIME.plusSeconds(10))),
                dialect);
        assertRow(connection, first, "First", "server-a", "100.00", 41, BASE_TIME);

        SqlSnapshotWriter.persistPlayers(connection,
                List.of(snapshot(first, "CurrentName", "server-b", "100.00", 41, BASE_TIME.plusSeconds(20))),
                dialect);
        assertRow(connection, first, "CurrentName", "server-b", "100.00", 41, BASE_TIME.plusSeconds(20));

        connection.setAutoCommit(false);
        try {
            assertThrows(SnapshotConflictException.class, () -> SqlSnapshotWriter.persistPlayers(connection, List.of(
                    snapshot(first, "First", "server-c", "120.00", 42, BASE_TIME.plusSeconds(30)),
                    snapshot(second, "Second", "server-b", "49.00", 41, BASE_TIME.plusSeconds(30))), dialect));
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
        assertRow(connection, first, "CurrentName", "server-b", "100.00", 41, BASE_TIME.plusSeconds(20));
        assertRow(connection, second, "Second", "server-a", "50.00", 41, BASE_TIME);

        assertFalse(SqlSnapshotWriter.persistPlayersWithPairStaleDetection(connection, List.of(
                snapshot(first, "StalePairFirst", "server-c", "90.00", 40, BASE_TIME.plusSeconds(40)),
                snapshot(second, "NewerPairSecond", "server-c", "60.00", 42, BASE_TIME.plusSeconds(40))),
                dialect), "a stale row must prevent either leg of a confirmed pair from persisting");
        assertRow(connection, first, "CurrentName", "server-b", "100.00", 41, BASE_TIME.plusSeconds(20));
        assertRow(connection, second, "Second", "server-a", "50.00", 41, BASE_TIME);

        UUID staleFirst = new UUID(0L, 1L);
        UUID conflictingSecond = new UUID(0L, 2L);
        SqlSnapshotWriter.persistPlayers(connection, List.of(
                snapshot(staleFirst, "StaleFirst", "server-a", "100.00", 10, BASE_TIME),
                snapshot(conflictingSecond, "ConflictSecond", "server-a", "50.00", 10, BASE_TIME)), dialect);
        SqlSnapshotWriter.persistPlayers(connection,
                List.of(snapshot(staleFirst, "NewerFirst", "server-b", "110.00", 11,
                        BASE_TIME.plusSeconds(1))), dialect);
        assertThrows(SnapshotConflictException.class,
                () -> SqlSnapshotWriter.persistPlayersWithPairStaleDetection(connection, List.of(
                        snapshot(staleFirst, "StaleFirst", "server-c", "90.00", 10,
                                BASE_TIME.plusSeconds(2)),
                        snapshot(conflictingSecond, "ConflictSecond", "server-c", "55.00", 10,
                                BASE_TIME.plusSeconds(2))), dialect),
                "a stale first UUID-sorted leg must not hide an equal-version conflict in the second leg");
        assertRow(connection, staleFirst, "NewerFirst", "server-b", "110.00", 11,
                BASE_TIME.plusSeconds(1));
        assertRow(connection, conflictingSecond, "ConflictSecond", "server-a", "50.00", 10, BASE_TIME);
    }

    private static void assertConcurrentSnapshotSemantics(Connection lockHolder, Connection contender,
            Backend backend) throws Exception {
        SqlDialect dialect = backend.dialect();
        UUID olderThenNewer = UUID.randomUUID();
        SqlSnapshotWriter.persistPlayers(lockHolder, List.of(
                snapshot(olderThenNewer, "Base", "base", "70.00", 39, BASE_TIME)), dialect);
        PlayerSnapshot oldSnapshot = snapshot(olderThenNewer, "OldWriter", "old-server",
                "80.00", 40, BASE_TIME.plusSeconds(10));
        PlayerSnapshot newSnapshot = snapshot(olderThenNewer, "NewWriter", "new-server",
                "90.00", 41, BASE_TIME.plusSeconds(20));
        assertNull(runContendedWriters(lockHolder, contender, List.of(oldSnapshot), List.of(newSnapshot), backend),
                "the newer writer should succeed after the older transaction commits");
        assertRow(lockHolder, olderThenNewer, "NewWriter", "new-server", "90.00", 41,
                BASE_TIME.plusSeconds(20));

        UUID newerThenOlder = UUID.randomUUID();
        SqlSnapshotWriter.persistPlayers(lockHolder, List.of(
                snapshot(newerThenOlder, "Base", "base", "70.00", 39, BASE_TIME)), dialect);
        PlayerSnapshot delayedOldSnapshot = snapshot(newerThenOlder, "StaleWriter", "stale-server",
                "80.00", 40, BASE_TIME.plusSeconds(10));
        PlayerSnapshot committedNewSnapshot = snapshot(newerThenOlder, "NewWriter", "new-server",
                "90.00", 41, BASE_TIME.plusSeconds(20));
        assertNull(runContendedWriters(lockHolder, contender, List.of(committedNewSnapshot),
                List.of(delayedOldSnapshot), backend),
                "the delayed stale writer should become a successful no-op after the newer commit");
        assertRow(lockHolder, newerThenOlder, "NewWriter", "new-server", "90.00", 41,
                BASE_TIME.plusSeconds(20));

        UUID conflict = UUID.randomUUID();
        UUID batchPeer = UUID.randomUUID();
        SqlSnapshotWriter.persistPlayers(lockHolder, List.of(
                snapshot(conflict, "ConflictBase", "base", "70.00", 40, BASE_TIME),
                snapshot(batchPeer, "PeerBase", "base", "50.00", 40, BASE_TIME)), dialect);
        PlayerSnapshot winning = snapshot(conflict, "Winner", "winner", "100.00", 41,
                BASE_TIME.plusSeconds(30));
        List<PlayerSnapshot> conflictingBatch = List.of(
                snapshot(batchPeer, "PeerChanged", "peer-new", "55.00", 41, BASE_TIME.plusSeconds(40)),
                snapshot(conflict, "Loser", "loser", "99.00", 41, BASE_TIME.plusSeconds(40)));
        Throwable conflictFailure = runContendedWriters(lockHolder, contender, List.of(winning),
                conflictingBatch, backend);
        assertRow(lockHolder, conflict, "Winner", "winner", "100.00", 41, BASE_TIME.plusSeconds(30));
        assertInstanceOf(SnapshotConflictException.class, conflictFailure,
                "a concurrent equal-version/different-balance write must fail the whole batch");
        assertRow(lockHolder, batchPeer, "PeerBase", "base", "50.00", 40, BASE_TIME);
    }

    private static Throwable runContendedWriters(Connection lockHolder, Connection contender,
            List<PlayerSnapshot> heldUpdates, List<PlayerSnapshot> delayedUpdates, Backend backend)
            throws Exception {
        CountDownLatch workerReady = new CountDownLatch(1);
        CountDownLatch contenderFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        lockHolder.setAutoCommit(false);
        contender.setAutoCommit(false);
        try {
            SqlSnapshotWriter.persistPlayers(lockHolder, heldUpdates, backend.dialect());
            long contenderSessionId = databaseSessionId(contender, backend.id());
            executor.execute(() -> {
                workerReady.countDown();
                try {
                    SqlSnapshotWriter.persistPlayers(contender, delayedUpdates, backend.dialect());
                    contender.commit();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                    try {
                        contender.rollback();
                    } catch (SQLException rollbackFailure) {
                        throwable.addSuppressed(rollbackFailure);
                    }
                } finally {
                    contenderFinished.countDown();
                }
            });

            assertTrue(workerReady.await(5, TimeUnit.SECONDS), "contender should reach the start barrier");
            assertTrue(awaitRowLockWait(lockHolder, backend, contenderSessionId, contenderFinished, 10_000),
                    "the second connection must be observed waiting on the database row lock before the holder commits");
            assertFalse(contenderFinished.getCount() == 0,
                    "the contender must stay active until the lock holder commits");

            lockHolder.commit();
            assertTrue(contenderFinished.await(10, TimeUnit.SECONDS),
                    "the contender should resume after the lock holder commits");
            return failure.get();
        } finally {
            try {
                lockHolder.rollback();
                contender.rollback();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "contender worker should stop");
                lockHolder.setAutoCommit(true);
                contender.setAutoCommit(true);
            }
        }
    }

    private static long databaseSessionId(Connection connection, String backendId) throws SQLException {
        String sql = backendId.equals("postgresql") ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("backend did not return a connection id");
            return result.getLong(1);
        }
    }

    private static boolean awaitRowLockWait(Connection observer, Backend backend, long sessionId,
            CountDownLatch contenderFinished, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (contenderFinished.getCount() == 0) return false;
            if (isWaitingForRowLock(observer, backend.id(), sessionId)) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean isWaitingForRowLock(Connection observer, String backendId, long sessionId)
            throws SQLException {
        if (backendId.equals("postgresql")) {
            try (var statement = observer.prepareStatement("""
                    SELECT 1 FROM pg_stat_activity
                    WHERE pid = ? AND wait_event_type = 'Lock' AND wait_event IS NOT NULL
                    """)) {
                statement.setLong(1, sessionId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }

        SQLException performanceSchemaFailure;
        try (var statement = observer.prepareStatement("""
                SELECT 1
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.threads threads
                  ON threads.THREAD_ID = waits.REQUESTING_THREAD_ID
                WHERE threads.PROCESSLIST_ID = ?
                LIMIT 1
                """)) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            performanceSchemaFailure = e;
        }

        try (var statement = observer.prepareStatement("""
                SELECT 1
                FROM information_schema.INNODB_LOCK_WAITS waits
                JOIN information_schema.INNODB_TRX transactions
                  ON transactions.trx_id = waits.requesting_trx_id
                WHERE transactions.trx_mysql_thread_id = ?
                LIMIT 1
                """)) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException legacySchemaFailure) {
            legacySchemaFailure.addSuppressed(performanceSchemaFailure);
            throw legacySchemaFailure;
        }
    }

    private static void assertLegacyWriterCanRegress(Connection connection, Backend backend) throws SQLException {
        UUID player = UUID.randomUUID();
        SqlSnapshotWriter.persistPlayers(connection,
                List.of(snapshot(player, "Current", "new-server", "90.00", 41, BASE_TIME)), backend.dialect());
        SqlSnapshotWriter.persistPlayers(connection,
                List.of(snapshot(player, "StaleNewWriter", "stale-server", "80.00", 40,
                        BASE_TIME.plusSeconds(10))), backend.dialect());
        assertRow(connection, player, "Current", "new-server", "90.00", 41, BASE_TIME);

        String legacySql = backend.id().equals("postgresql") ? """
                INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (player_uuid) DO UPDATE SET
                    player_name = EXCLUDED.player_name,
                    balance = EXCLUDED.balance,
                    version = EXCLUDED.version,
                    last_server = EXCLUDED.last_server,
                    updated_at = EXCLUDED.updated_at
                """ : """
                INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    player_name = VALUES(player_name),
                    balance = VALUES(balance),
                    version = VALUES(version),
                    last_server = VALUES(last_server),
                    updated_at = VALUES(updated_at)
                """;
        try (var statement = connection.prepareStatement(legacySql)) {
            statement.setString(1, player.toString());
            statement.setString(2, "LegacyStale");
            statement.setBigDecimal(3, new BigDecimal("80.00"));
            statement.setLong(4, 40);
            statement.setString(5, "legacy-server");
            statement.setTimestamp(6, java.sql.Timestamp.from(BASE_TIME.plusSeconds(20)));
            statement.executeUpdate();
        }
        assertRow(connection, player, "LegacyStale", "legacy-server", "80.00", 40,
                BASE_TIME.plusSeconds(20));
    }

    private static PlayerSnapshot snapshot(UUID player, String name, String server,
            String balance, long version, Instant timestamp) {
        return new PlayerSnapshot(new ConfirmedAccount(player, new BigDecimal(balance), version),
                name, server, timestamp);
    }

    private static void assertRow(Connection connection, UUID player, String name, String server,
            String balance, long version, Instant updatedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT player_name, last_server, balance, version, updated_at
                FROM players WHERE player_uuid = ?
                """)) {
            statement.setString(1, player.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new AssertionError("missing player row " + player);
                assertEquals(name, row.getString("player_name"));
                assertEquals(server, row.getString("last_server"));
                assertEquals(0, new BigDecimal(balance).compareTo(row.getBigDecimal("balance")));
                assertEquals(version, row.getLong("version"));
                assertEquals(updatedAt, row.getTimestamp("updated_at").toInstant());
            }
        }
    }

    private record Backend(String id, String displayName, SqlDialect dialect, String driverClass,
            String url, String user, String password) { }
}
