package noietime.syncmoney.storage.persistence;
import noietime.syncmoney.core.money.MoneyPolicy;
import noietime.syncmoney.core.port.ConfirmedAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Version-guarded SQL persistence for shared and existing LOCAL SQLite player rows. */
public final class SqlSnapshotWriter {
    private static final String SELECT_SHARED = "SELECT version, balance FROM players WHERE player_uuid = ?";
    private static final String SELECT_SHARED_FOR_UPDATE = SELECT_SHARED + " FOR UPDATE";
    private static final String SELECT_LOCAL = "SELECT version, balance FROM player_balances WHERE uuid = ?";
    private static final String INSERT_POSTGRESQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid) DO UPDATE SET
                player_name = EXCLUDED.player_name,
                balance = EXCLUDED.balance,
                version = EXCLUDED.version,
                last_server = EXCLUDED.last_server,
                updated_at = EXCLUDED.updated_at
            WHERE players.version < EXCLUDED.version
               OR (players.version = EXCLUDED.version AND players.balance = EXCLUDED.balance)
            """;
    private static final String REPLACE_MYSQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name), balance = VALUES(balance), version = VALUES(version),
                last_server = VALUES(last_server), updated_at = VALUES(updated_at)
            """;
    private static final String REPLACE_POSTGRESQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid) DO UPDATE SET
                player_name = EXCLUDED.player_name, balance = EXCLUDED.balance, version = EXCLUDED.version,
                last_server = EXCLUDED.last_server, updated_at = EXCLUDED.updated_at
            """;
    private static final String INSERT_IF_ABSENT_MYSQL = """
            INSERT IGNORE INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_IF_ABSENT_POSTGRESQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid) DO NOTHING
            """;
    private static final String INSERT_MYSQL = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = IF(VALUES(version) > version
                    OR (VALUES(version) = version AND VALUES(balance) = balance), VALUES(player_name), player_name),
                balance = IF(VALUES(version) > version, VALUES(balance), balance),
                last_server = IF(VALUES(version) > version
                    OR (VALUES(version) = version AND VALUES(balance) = balance), VALUES(last_server), last_server),
                updated_at = IF(VALUES(version) > version
                    OR (VALUES(version) = version AND VALUES(balance) = balance), VALUES(updated_at), updated_at),
                version = GREATEST(version, VALUES(version))
            """;
    private static final String INSERT_SQLITE = """
            INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid) DO UPDATE SET
                player_name = excluded.player_name, balance = excluded.balance, version = excluded.version,
                last_server = excluded.last_server, updated_at = excluded.updated_at
            WHERE players.version < excluded.version
               OR (players.version = excluded.version AND players.balance = excluded.balance)
            """;
    private static final String INSERT_LOCAL_SQLITE = """
            INSERT INTO player_balances (uuid, name, balance, version, last_update, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET
                name = excluded.name, balance = excluded.balance, version = excluded.version,
                last_update = excluded.last_update
            WHERE player_balances.version < excluded.version
               OR (player_balances.version = excluded.version AND player_balances.balance = excluded.balance)
            """;

    private SqlSnapshotWriter() {}

    public static void persistPlayers(Connection connection, List<PlayerSnapshot> snapshots, SqlDialect dialect)
            throws SQLException {
        persistPlayersWithPairStaleDetection(connection, snapshots, dialect);
    }

    /**
     * Persist one task's account set. A two-account transfer snapshot is a unit: if
     * either SQL row has already advanced beyond the confirmed receipt, neither row
     * from that transfer is applied. The caller can then refresh the authority pair.
     *
     * @return {@code false} when a transfer pair was stale and the complete pair was skipped
     */
    public static boolean persistPlayersWithPairStaleDetection(Connection connection,
            List<PlayerSnapshot> snapshots, SqlDialect dialect) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(snapshots, "snapshots");
        String sql = upsertSql(Objects.requireNonNull(dialect, "dialect"));
        if (snapshots.size() != 2
                || snapshots.get(0).account().playerUuid().equals(snapshots.get(1).account().playerUuid())) {
            for (PlayerSnapshot snapshot : snapshots) persistShared(connection, snapshot, sql, dialect);
            return true;
        }

        boolean ownsTransaction = connection.getAutoCommit();
        if (ownsTransaction) connection.setAutoCommit(false);
        java.sql.Savepoint savepoint = null;
        try {
            if (!ownsTransaction) savepoint = connection.setSavepoint();
            List<PlayerSnapshot> ordered = snapshots.stream()
                    .sorted(java.util.Comparator.comparing(snapshot ->
                            snapshot.account().playerUuid().toString()))
                    .toList();
            boolean stalePair = false;
            for (PlayerSnapshot snapshot : ordered) {
                persistShared(connection, snapshot, sql, dialect);
                StoredSnapshot stored = selectStoredSnapshot(connection,
                        snapshot.account().playerUuid(), dialect);
                if (stored.version() > snapshot.account().version()) {
                    // Inspect both rows before classifying the transfer as stale. The
                    // other row may contain an equal-version/different-balance conflict,
                    // which must remain an error rather than being hidden by early return.
                    stalePair = true;
                }
            }
            if (stalePair) {
                if (ownsTransaction) connection.rollback();
                else connection.rollback(savepoint);
                if (ownsTransaction) connection.commit();
                else connection.releaseSavepoint(savepoint);
                return false;
            }
            if (ownsTransaction) connection.commit();
            else connection.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException | RuntimeException e) {
            try {
                if (ownsTransaction) connection.rollback();
                else if (savepoint != null) connection.rollback(savepoint);
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            if (ownsTransaction) connection.setAutoCommit(true);
        }
    }

    private record StoredSnapshot(BigDecimal balance, long version) { }

    private static StoredSnapshot selectStoredSnapshot(Connection connection, UUID uuid,
            SqlDialect dialect) throws SQLException {
        String sql = dialect == SqlDialect.SQLITE ? SELECT_SHARED : SELECT_SHARED_FOR_UPDATE;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Upserted player snapshot row is missing: " + uuid);
                return new StoredSnapshot(result.getBigDecimal(2), result.getLong(1));
            }
        }
    }

    /** Explicit maintenance-authorized migration write while the destination authority is quiesced. */
    public static void replacePlayerForMigration(Connection connection, PlayerSnapshot snapshot, SqlDialect dialect)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(snapshot, "snapshot");
        ConfirmedAccount account = snapshot.account();
        requireSharedMigrationBalance(account);
        String sql = switch (Objects.requireNonNull(dialect, "dialect")) {
            case MYSQL -> REPLACE_MYSQL;
            case POSTGRESQL -> REPLACE_POSTGRESQL;
            case SQLITE -> throw new IllegalArgumentException("shared migration writes require MySQL or PostgreSQL");
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.playerUuid().toString());
            statement.setString(2, snapshot.playerName());
            statement.setBigDecimal(3, account.balance());
            statement.setLong(4, account.version());
            statement.setString(5, snapshot.lastServer());
            statement.setTimestamp(6, Timestamp.from(snapshot.updatedAt()));
            statement.executeUpdate();
        }
    }

    /** Insert a migration row only when the shared destination has no account row. */
    public static boolean insertPlayerIfAbsentForMigration(Connection connection, PlayerSnapshot snapshot,
            SqlDialect dialect) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(snapshot, "snapshot");
        ConfirmedAccount account = snapshot.account();
        requireSharedMigrationBalance(account);
        String sql = switch (Objects.requireNonNull(dialect, "dialect")) {
            case MYSQL -> INSERT_IF_ABSENT_MYSQL;
            case POSTGRESQL, SQLITE -> INSERT_IF_ABSENT_POSTGRESQL;
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.playerUuid().toString());
            statement.setString(2, snapshot.playerName());
            statement.setBigDecimal(3, account.balance());
            statement.setLong(4, account.version());
            statement.setString(5, snapshot.lastServer());
            statement.setTimestamp(6, Timestamp.from(snapshot.updatedAt()));
            return statement.executeUpdate() > 0;
        }
    }

    private static void requireSharedMigrationBalance(ConfirmedAccount account) throws SQLException {
        if (!MoneyPolicy.isNonNegative(account.balance())) {
            throw new SQLException("Migration balance must be non-negative");
        }
        if (account.balance().compareTo(MoneyPolicy.MAX_SHARED_BALANCE) > 0) {
            throw new SQLException("Migration balance exceeds shared SQL DECIMAL(20,2) maximum");
        }
    }

    public static void persistLocalBalance(Connection connection, UUID uuid, String name,
            BigDecimal balance, int version, long updatedAtMillis) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(balance, "balance");
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        BigDecimal normalized = balance.setScale(2, RoundingMode.HALF_UP);
        try (PreparedStatement statement = connection.prepareStatement(INSERT_LOCAL_SQLITE)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name == null ? uuid.toString() : name);
            statement.setBigDecimal(3, normalized);
            statement.setInt(4, version);
            statement.setLong(5, updatedAtMillis);
            statement.setLong(6, updatedAtMillis);
            statement.executeUpdate();
        }
        verifyEqualVersion(connection, SELECT_LOCAL, uuid, version, normalized);
    }

    public static String upsertSql(SqlDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> INSERT_POSTGRESQL;
            case MYSQL -> INSERT_MYSQL;
            case SQLITE -> INSERT_SQLITE;
        };
    }

    private static void persistShared(Connection connection, PlayerSnapshot snapshot, String sql,
            SqlDialect dialect) throws SQLException {
        ConfirmedAccount account = snapshot.account();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.playerUuid().toString());
            statement.setString(2, snapshot.playerName());
            statement.setBigDecimal(3, account.balance());
            statement.setLong(4, account.version());
            statement.setString(5, snapshot.lastServer());
            statement.setTimestamp(6, Timestamp.from(snapshot.updatedAt()));
            statement.executeUpdate();
        }
        String verificationSql = dialect == SqlDialect.SQLITE ? SELECT_SHARED : SELECT_SHARED_FOR_UPDATE;
        verifyEqualVersion(connection, verificationSql, account.playerUuid(),
                account.version(), account.balance());
    }

    private static void verifyEqualVersion(Connection connection, String selectSql, UUID uuid,
            long incomingVersion, BigDecimal incomingBalance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return;
                long storedVersion = result.getLong(1);
                BigDecimal storedBalance = result.getBigDecimal(2);
                if (storedVersion == incomingVersion && storedBalance.compareTo(incomingBalance) != 0) {
                    throw new SnapshotConflictException(uuid, incomingVersion);
                }
            }
        }
    }
}
