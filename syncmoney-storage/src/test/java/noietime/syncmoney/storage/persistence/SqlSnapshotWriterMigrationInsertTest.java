package noietime.syncmoney.storage.persistence;

import noietime.syncmoney.core.money.MoneyPolicy;
import noietime.syncmoney.core.port.ConfirmedAccount;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSnapshotWriterMigrationInsertTest {
    @Test
    void mysqlMigrationDuplicateUsesInsertOnlyOutcome() throws Exception {
        AtomicReference<String> capturedSql = new AtomicReference<>();
        Connection connection = connectionReportingUpdateCount(capturedSql, 0);
        PlayerSnapshot snapshot = snapshot();

        assertFalse(SqlSnapshotWriter.insertPlayerIfAbsentForMigration(connection, snapshot, SqlDialect.MYSQL));

        String sql = capturedSql.get().toUpperCase(Locale.ROOT);
        assertTrue(sql.startsWith("INSERT IGNORE INTO PLAYERS"),
                "the MySQL migration statement must leave duplicate rows as a no-op insert");
        assertFalse(sql.contains("ON DUPLICATE KEY UPDATE"),
                "no-op duplicate updates depend on Connector/J affected-row configuration");
    }

    @Test
    void mysqlMigrationInsertReportsSuccessfulInsertion() throws Exception {
        AtomicReference<String> capturedSql = new AtomicReference<>();
        Connection connection = connectionReportingUpdateCount(capturedSql, 1);

        assertTrue(SqlSnapshotWriter.insertPlayerIfAbsentForMigration(connection, snapshot(), SqlDialect.MYSQL));
    }

    @Test
    void mysqlMigrationInsertRejectsBalanceAboveSharedMaximumBeforePreparingStatement() {
        AtomicInteger preparedStatements = new AtomicInteger();
        Connection connection = connectionCountingPreparedStatements(preparedStatements);
        PlayerSnapshot snapshot = snapshot(MoneyPolicy.MAX_SHARED_BALANCE.add(new BigDecimal("0.01")));

        assertThrows(SQLException.class,
                () -> SqlSnapshotWriter.insertPlayerIfAbsentForMigration(connection, snapshot, SqlDialect.MYSQL));

        assertEquals(0, preparedStatements.get());
    }

    @Test
    void mysqlMigrationReplaceRejectsBalanceAboveSharedMaximumBeforePreparingStatement() {
        AtomicInteger preparedStatements = new AtomicInteger();
        Connection connection = connectionCountingPreparedStatements(preparedStatements);
        PlayerSnapshot snapshot = snapshot(MoneyPolicy.MAX_SHARED_BALANCE.add(new BigDecimal("0.01")));

        assertThrows(SQLException.class,
                () -> SqlSnapshotWriter.replacePlayerForMigration(connection, snapshot, SqlDialect.MYSQL));

        assertEquals(0, preparedStatements.get());
    }

    @Test
    void mysqlMigrationWritesAcceptExactSharedMaximum() throws Exception {
        Connection connection = connectionReportingUpdateCount(new AtomicReference<>(), 1);
        PlayerSnapshot snapshot = snapshot(MoneyPolicy.MAX_SHARED_BALANCE);

        assertTrue(SqlSnapshotWriter.insertPlayerIfAbsentForMigration(connection, snapshot, SqlDialect.MYSQL));
        SqlSnapshotWriter.replacePlayerForMigration(connection, snapshot, SqlDialect.MYSQL);
    }

    @Test
    void mysqlMigrationRejectsNegativeBalanceBeforePreparingStatement() {
        AtomicInteger preparedStatements = new AtomicInteger();
        Connection connection = connectionCountingPreparedStatements(preparedStatements);
        PlayerSnapshot snapshot = snapshot(new BigDecimal("-0.01"));

        assertThrows(SQLException.class,
                () -> SqlSnapshotWriter.insertPlayerIfAbsentForMigration(connection, snapshot, SqlDialect.MYSQL));
        assertThrows(SQLException.class,
                () -> SqlSnapshotWriter.replacePlayerForMigration(connection, snapshot, SqlDialect.MYSQL));

        assertEquals(0, preparedStatements.get());
    }

    private static PlayerSnapshot snapshot() {
        return snapshot(new BigDecimal("12.50"));
    }

    private static PlayerSnapshot snapshot(BigDecimal balance) {
        return new PlayerSnapshot(new ConfirmedAccount(UUID.randomUUID(), balance, 3L),
                "Synthetic", "test-server", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Connection connectionCountingPreparedStatements(AtomicInteger preparedStatements) {
        InvocationHandler connectionHandler = (proxy, method, arguments) -> {
            if (method.getName().equals("prepareStatement")) {
                preparedStatements.incrementAndGet();
                throw new AssertionError("invalid migration prepared SQL before rejecting the balance");
            }
            return defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, connectionHandler);
    }

    private static Connection connectionReportingUpdateCount(AtomicReference<String> capturedSql, int updateCount) {
        InvocationHandler statementHandler = (proxy, method, arguments) -> {
            if (method.getName().equals("executeUpdate")) return updateCount;
            return defaultValue(method.getReturnType());
        };
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, statementHandler);

        InvocationHandler connectionHandler = (proxy, method, arguments) -> {
            if (method.getName().equals("prepareStatement")) {
                capturedSql.set((String) arguments[0]);
                return statement;
            }
            return defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, connectionHandler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0f;
        if (returnType == double.class) return 0.0d;
        if (returnType == char.class) return '\0';
        throw new IllegalArgumentException("Unknown primitive return type: " + returnType);
    }
}
