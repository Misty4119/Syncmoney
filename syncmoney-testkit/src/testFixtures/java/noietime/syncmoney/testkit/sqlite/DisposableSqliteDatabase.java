package noietime.syncmoney.testkit.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite database backed by a uniquely owned temporary file, deleted when closed.
 * The consuming test runtime must provide an SQLite JDBC driver; this fixture intentionally does
 * not add a JDBC dependency to every test-fixture consumer.
 */
public final class DisposableSqliteDatabase implements AutoCloseable {

    private final Path directory;
    private final Path databaseFile;
    private final Connection connection;

    private DisposableSqliteDatabase(Path directory, Path databaseFile, Connection connection) {
        this.directory = directory;
        this.databaseFile = databaseFile;
        this.connection = connection;
    }

    public static DisposableSqliteDatabase open() throws IOException, SQLException {
        Path directory = Files.createTempDirectory("syncmoney-sqlite-test-");
        Path databaseFile = directory.resolve("fixture.sqlite");
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            return new DisposableSqliteDatabase(directory, databaseFile, connection);
        } catch (SQLException failure) {
            deleteOwnedDirectory(directory);
            throw failure;
        }
    }

    public Connection connection() {
        return connection;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public void executeScript(String script) throws SQLException {
        List<String> commands = splitStatements(script);
        for (int index = 0; index < commands.size(); index++) {
            String command = commands.get(index);
            try (Statement statement = connection.createStatement()) {
                statement.execute(command);
            } catch (SQLException failure) {
                throw new SQLException("Failed disposable SQLite fixture command " + (index + 1)
                        + ": " + command.strip(), failure);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';

            if (lineComment) {
                current.append(character);
                if (character == '\n' || character == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                current.append(character);
                if (character == '*' && next == '/') {
                    current.append(next);
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && character == '-' && next == '-') {
                current.append(character).append(next);
                index++;
                lineComment = true;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && character == '/' && next == '*') {
                current.append(character).append(next);
                index++;
                blockComment = true;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                current.append(character);
                if (singleQuoted && next == '\'') {
                    current.append(next);
                    index++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (character == '"' && !singleQuoted) {
                current.append(character);
                if (doubleQuoted && next == '"') {
                    current.append(next);
                    index++;
                } else {
                    doubleQuoted = !doubleQuoted;
                }
                continue;
            }
            if (character == ';' && !singleQuoted && !doubleQuoted) {
                addIfSql(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        addIfSql(statements, current);
        return List.copyOf(statements);
    }

    private static void addIfSql(List<String> statements, StringBuilder candidate) {
        if (containsSql(candidate)) statements.add(candidate.toString());
    }

    private static boolean containsSql(StringBuilder candidate) {
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            char next = index + 1 < candidate.length() ? candidate.charAt(index + 1) : '\0';
            if (lineComment) {
                if (character == '\n' || character == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (character == '*' && next == '/') {
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (character == '-' && next == '-') {
                index++;
                lineComment = true;
                continue;
            }
            if (character == '/' && next == '*') {
                index++;
                blockComment = true;
                continue;
            }
            if (!Character.isWhitespace(character)) return true;
        }
        return false;
    }

    @Override
    public void close() throws SQLException, IOException {
        SQLException sqlFailure = null;
        try {
            connection.close();
        } catch (SQLException failure) {
            sqlFailure = failure;
        }
        try {
            deleteOwnedDirectory(directory);
        } catch (IOException failure) {
            if (sqlFailure != null) sqlFailure.addSuppressed(failure);
            else throw failure;
        }
        if (sqlFailure != null) throw sqlFailure;
    }

    private static void deleteOwnedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
