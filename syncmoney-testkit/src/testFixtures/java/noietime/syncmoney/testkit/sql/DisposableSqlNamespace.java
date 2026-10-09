package noietime.syncmoney.testkit.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

/** Owns a random schema/database created solely for one integration test and drops it on close. */
public final class DisposableSqlNamespace implements AutoCloseable {

    public enum Server {
        POSTGRESQL,
        MYSQL_COMPATIBLE
    }

    private final Connection administrator;
    private final Server server;
    private final String name;
    private boolean closed;

    private DisposableSqlNamespace(Connection administrator, Server server, String name) {
        this.administrator = administrator;
        this.server = server;
        this.name = name;
    }

    public static DisposableSqlNamespace create(Connection administrator, Server server) throws SQLException {
        Objects.requireNonNull(administrator, "administrator");
        Objects.requireNonNull(server, "server");
        String name = "syncmoney_it_" + UUID.randomUUID().toString().replace("-", "");
        String create = server == Server.POSTGRESQL ? "CREATE SCHEMA " : "CREATE DATABASE ";
        try (Statement statement = administrator.createStatement()) {
            statement.execute(create + name);
        }
        return new DisposableSqlNamespace(administrator, server, name);
    }

    public String name() {
        return name;
    }

    public void select(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (server == Server.POSTGRESQL) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + name);
            }
        } else {
            connection.setCatalog(name);
        }
    }

    @Override
    public void close() throws SQLException {
        if (closed) return;
        String drop = server == Server.POSTGRESQL ? "DROP SCHEMA " + name + " CASCADE"
                : "DROP DATABASE " + name;
        try (Statement statement = administrator.createStatement()) {
            statement.execute(drop);
            closed = true;
        }
    }
}
