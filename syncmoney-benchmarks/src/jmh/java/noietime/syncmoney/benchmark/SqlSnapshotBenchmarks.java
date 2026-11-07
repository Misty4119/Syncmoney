package noietime.syncmoney.benchmark;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.storage.persistence.PlayerSnapshot;
import noietime.syncmoney.storage.persistence.SqlDialect;
import noietime.syncmoney.storage.persistence.SqlSnapshotWriter;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runs the production version-guarded SQL snapshot writer against an isolated in-memory SQLite database. */
public class SqlSnapshotBenchmarks {

    @State(Scope.Thread)
    public static class Fixture {
        @Param({"1", "50", "200", "500"})
        public int batchSize;

        Connection connection;
        List<PlayerSnapshot> snapshots;
        List<UUID> accounts;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE players (player_uuid TEXT PRIMARY KEY, player_name TEXT, "
                        + "balance NUMERIC NOT NULL, version INTEGER NOT NULL, last_server TEXT, updated_at TIMESTAMP)");
            }
            connection.setAutoCommit(false);
            accounts = new ArrayList<>(batchSize);
            List<PlayerSnapshot> initial = new ArrayList<>(batchSize);
            List<PlayerSnapshot> next = new ArrayList<>(batchSize);
            Instant now = Instant.parse("2026-10-09T00:00:00Z");
            for (int index = 0; index < batchSize; index++) {
                UUID uuid = UUID.nameUUIDFromBytes(("sql-batch-benchmark-" + index)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                accounts.add(uuid);
                initial.add(new PlayerSnapshot(new ConfirmedAccount(uuid, new BigDecimal("100.00"), 1L),
                        "benchmark", "benchmark-node", now));
                next.add(new PlayerSnapshot(new ConfirmedAccount(uuid, new BigDecimal("101.00"), 2L),
                        "benchmark", "benchmark-node", now.plusMillis(1L)));
            }
            snapshots = List.copyOf(next);
            SqlSnapshotWriter.persistPlayers(connection, initial, SqlDialect.SQLITE);
            connection.commit();
        }

        @Setup(Level.Iteration)
        public void resetDataset() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE players SET balance = 100.00, version = 1");
            }
            connection.commit();
        }

        @TearDown(Level.Trial)
        public void verifyAndClose() throws SQLException {
            try {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT COUNT(*), MIN(version), MAX(version), MIN(balance), MAX(balance) FROM players")) {
                    if (!rows.next() || rows.getInt(1) != batchSize || rows.getLong(2) != 2L || rows.getLong(3) != 2L
                            || rows.getBigDecimal(4).compareTo(new BigDecimal("101.00")) != 0
                            || rows.getBigDecimal(5).compareTo(new BigDecimal("101.00")) != 0) {
                        throw new IllegalStateException("SQL snapshot benchmark lost rows or diverged from its expected receipt set");
                    }
                }
            } finally {
                connection.close();
            }
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counts {
        public long offered;
        public long accepted;
        public long rejected;
        public long persisted;

        @Setup(Level.Iteration)
        public void reset() {
            offered = accepted = rejected = persisted = 0L;
        }
    }

    @Benchmark
    public int persistSnapshotBatch(Fixture fixture, Counts counts) throws SQLException {
        counts.offered += fixture.snapshots.size();
        counts.accepted += fixture.snapshots.size();
        try {
            SqlSnapshotWriter.persistPlayers(fixture.connection, fixture.snapshots, SqlDialect.SQLITE);
            fixture.connection.commit();
            counts.persisted += fixture.snapshots.size();
            return fixture.snapshots.size();
        } catch (SQLException | RuntimeException failure) {
            fixture.connection.rollback();
            counts.rejected += fixture.snapshots.size();
            throw failure;
        }
    }
}
