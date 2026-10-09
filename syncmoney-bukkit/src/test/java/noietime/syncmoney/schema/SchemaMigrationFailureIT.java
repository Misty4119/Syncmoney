package noietime.syncmoney.schema;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaMigrationFailureIT {

    @TempDir
    Path tempDir;

    @Test
    void newerAuditSchemaIsRejectedBeforeEnsureHelpersMutateIt() throws Exception {
        try (HikariDataSource dataSource = dataSource("newer-audit")) {
            createVersionTable(dataSource, 1, 12);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE syncmoney_audit_log (id VARCHAR(36) PRIMARY KEY, future_field TEXT)");
            }

            assertThrows(RuntimeException.class, () -> new SchemaManager(plugin(), dataSource, "sqlite"));

            Set<String> columns = columns(dataSource);
            assertAll(
                    () -> assertEquals(Set.of("id", "future_field"), columns,
                            "startup must not opportunistically add columns to a newer schema"),
                    () -> assertEquals(Set.of(), indexes(dataSource),
                            "startup must not add indexes to a newer schema"),
                    () -> assertEquals(1, version(dataSource, "1")),
                    () -> assertEquals(12, version(dataSource, "audit")));
        }
    }

    @Test
    void failedRequiredDdlDoesNotAdvanceAuditVersion() throws Exception {
        try (HikariDataSource backingDataSource = dataSource("ddl-failure")) {
            createVersionTable(backingDataSource, 1, 10);
            createVersionTenAuditTable(backingDataSource);
            HikariDataSource rejectingDataSource = rejectDdl(backingDataSource,
                    sql -> sql.toLowerCase().contains("add column request_id"));

            assertThrows(RuntimeException.class, () -> new SchemaManager(plugin(), rejectingDataSource, "sqlite"));

            assertAll(
                    () -> assertEquals(1, version(backingDataSource, "1")),
                    () -> assertEquals(10, version(backingDataSource, "audit"),
                            "the audit version must not advance past a failed DDL step"),
                    () -> assertFalse(columns(backingDataSource).contains("request_id")));
        }
    }

    @Test
    void incompleteExistingAuditTableDoesNotAdvanceToVersionTen() throws Exception {
        try (HikariDataSource dataSource = dataSource("incomplete-v10")) {
            createVersionTable(dataSource, 1, 9);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE syncmoney_audit_log (id VARCHAR(36) PRIMARY KEY)");
            }

            assertThrows(RuntimeException.class, () -> new SchemaManager(plugin(), dataSource, "sqlite"));

            assertEquals(9, version(dataSource, "audit"),
                    "version 10 must not be recorded unless its baseline table columns exist");
            assertEquals(1, version(dataSource, "1"));
        }
    }

    @Test
    void auditSchemaChecksDoNotOverwriteTheIndependentMainVersion() throws Exception {
        try (HikariDataSource dataSource = dataSource("independent-versions")) {
            createVersionTable(dataSource, 1, 11);
            createCurrentAuditTable(dataSource);

            new SchemaManager(plugin(), dataSource, "sqlite");

            assertEquals(1, version(dataSource, "1"));
            assertEquals(11, version(dataSource, "audit"));
        }
    }

    @Test
    void failedAuditVersionWriteDoesNotAdvanceSchemaMetadata() throws Exception {
        try (HikariDataSource backingDataSource = dataSource("version-write-failure")) {
            createVersionTable(backingDataSource, 1, 10);
            createCurrentAuditTable(backingDataSource);
            HikariDataSource rejectingDataSource = rejectDdl(backingDataSource, sql -> {
                String normalized = sql.toLowerCase();
                return normalized.contains("insert") && normalized.contains("syncmoney_schema_version");
            });

            assertThrows(RuntimeException.class,
                    () -> new SchemaManager(plugin(), rejectingDataSource, "sqlite"));

            assertEquals(1, version(backingDataSource, "1"));
            assertEquals(10, version(backingDataSource, "audit"));
        }
    }

    @Test
    void metadataInspectionIsScopedToTheConnectedDatabaseNamespace() throws Exception {
        try (HikariDataSource backingDataSource = dataSource("metadata-scope")) {
            createVersionTable(backingDataSource, 1, 11);
            createCurrentAuditTable(backingDataSource);
            createIndex(backingDataSource, "idx_audit_timestamp", "timestamp DESC, sequence DESC");
            createIndex(backingDataSource, "idx_audit_player", "player_uuid, timestamp DESC, sequence DESC");
            createIndex(backingDataSource, "idx_audit_type", "type, timestamp DESC, sequence DESC");

            HikariDataSource scopedDataSource = requireScopedMetadata(backingDataSource);

            assertDoesNotThrow(() -> new SchemaManager(plugin(), scopedDataSource, "postgresql"));
        }
    }

    @Test
    void sameNamedIndexWithWrongColumnsDoesNotSatisfyMigrationPostcondition() throws Exception {
        try (HikariDataSource dataSource = dataSource("wrong-index")) {
            createVersionTable(dataSource, 1, 10);
            createCurrentAuditTable(dataSource);
            createIndex(dataSource, "idx_audit_timestamp", "player_uuid");

            assertThrows(RuntimeException.class, () -> new SchemaManager(plugin(), dataSource, "sqlite"));

            assertEquals(10, version(dataSource, "audit"));
        }
    }

    @Test
    void wildcardMatchingSiblingTableColumnCannotMakeTargetColumnLookPresent() throws Exception {
        try (HikariDataSource dataSource = dataSource("sibling-table-pattern")) {
            createVersionTable(dataSource, 1, 10);
            createVersionTenAuditTable(dataSource);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE syncmoney_audit_log ADD COLUMN sequence INTEGER DEFAULT 0");
            }
            createIndex(dataSource, "idx_audit_timestamp", "timestamp DESC, sequence DESC");
            createIndex(dataSource, "idx_audit_player", "player_uuid, timestamp DESC, sequence DESC");
            createIndex(dataSource, "idx_audit_type", "type, timestamp DESC, sequence DESC");

            HikariDataSource misleadingMetadata = appendSiblingAuditColumn(dataSource);
            new SchemaManager(plugin(), misleadingMetadata, "postgresql");

            assertTrue(columnsForTable(dataSource, "syncmoney_audit_log").contains("request_id"),
                    "migration must add a column absent from the target table even when a sibling metadata row has it");
            assertEquals(11, version(dataSource, "audit"));
        }
    }

    @Test
    void sqliteWrongIndexDirectionDoesNotSatisfyMigrationPostcondition() throws Exception {
        try (HikariDataSource dataSource = dataSource("wrong-index-direction")) {
            createVersionTable(dataSource, 1, 10);
            createCurrentAuditTable(dataSource);
            createIndex(dataSource, "idx_audit_timestamp", "timestamp ASC, sequence DESC");

            assertThrows(RuntimeException.class, () -> new SchemaManager(plugin(), dataSource, "sqlite"));

            assertEquals(10, version(dataSource, "audit"),
                    "the audit version must not advance when SQLite reports an incorrect index direction");
        }
    }

    @Test
    void unknownSqlIndexDirectionDoesNotSatisfyMigrationPostcondition() throws Exception {
        try (HikariDataSource backingDataSource = dataSource("unknown-index-direction")) {
            createVersionTable(backingDataSource, 1, 10);
            createCurrentAuditTable(backingDataSource);
            createIndex(backingDataSource, "idx_audit_timestamp", "timestamp DESC, sequence DESC");
            createIndex(backingDataSource, "idx_audit_player", "player_uuid, timestamp DESC, sequence DESC");
            createIndex(backingDataSource, "idx_audit_type", "type, timestamp DESC, sequence DESC");
            HikariDataSource unknownDirectionDataSource = hideIndexDirections(backingDataSource);

            assertThrows(RuntimeException.class,
                    () -> new SchemaManager(plugin(), unknownDirectionDataSource, "postgresql"));

            assertEquals(10, version(backingDataSource, "audit"),
                    "unknown index ordering must not permit the audit version to advance");
            assertEquals(1, version(backingDataSource, "1"));
        }
    }

    private Set<String> columnsForTable(HikariDataSource dataSource, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet result = connection.getMetaData().getColumns(null, null, tableName, "%")) {
            while (result.next()) {
                if (tableName.equalsIgnoreCase(result.getString("TABLE_NAME"))) {
                    columns.add(result.getString("COLUMN_NAME").toLowerCase());
                }
            }
        }
        return columns;
    }

    private HikariDataSource appendSiblingAuditColumn(HikariDataSource delegate) throws SQLException {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> wrapColumnMetadataConnection(delegate.getConnection()));
        return dataSource;
    }

    private Connection wrapColumnMetadataConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    try {
                        if ("getCatalog".equals(method.getName())) return "target_catalog";
                        if ("getSchema".equals(method.getName())) return "main";
                        Object value = method.invoke(connection, args);
                        if ("getMetaData".equals(method.getName())) {
                            return appendSiblingColumnMetadata((java.sql.DatabaseMetaData) value);
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private java.sql.DatabaseMetaData appendSiblingColumnMetadata(java.sql.DatabaseMetaData metadata) {
        return (java.sql.DatabaseMetaData) Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(),
                new Class<?>[] { java.sql.DatabaseMetaData.class }, (proxy, method, args) -> {
                    if ("getColumns".equals(method.getName())) {
                        Object[] exactTableArgs = args.clone();
                        exactTableArgs[0] = null;
                        exactTableArgs[1] = null;
                        String escape = metadata.getSearchStringEscape();
                        if (escape != null && !escape.isEmpty()) {
                            exactTableArgs[2] = unescapeMetadataPattern((String) exactTableArgs[2], escape);
                        }
                        try {
                            ResultSet delegate = (ResultSet) method.invoke(metadata, exactTableArgs);
                            return appendSiblingColumnRow(delegate);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                    if ("getIndexInfo".equals(method.getName())) {
                        Object[] exactNamespaceArgs = args.clone();
                        exactNamespaceArgs[0] = null;
                        exactNamespaceArgs[1] = null;
                        try {
                            ResultSet delegate = (ResultSet) method.invoke(metadata, exactNamespaceArgs);
                            return wrapNamespaceResultSet(delegate, "target_catalog", "main");
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                    try {
                        return method.invoke(metadata, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private ResultSet appendSiblingColumnRow(ResultSet delegate) {
        boolean[] siblingRow = { false };
        boolean[] appended = { false };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        if (delegate.next()) {
                            siblingRow[0] = false;
                            return true;
                        }
                        if (!appended[0]) {
                            siblingRow[0] = true;
                            appended[0] = true;
                            return true;
                        }
                        return false;
                    }
                    if (siblingRow[0] && "getString".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof String column) {
                        return switch (column.toUpperCase()) {
                            case "TABLE_CAT" -> "target_catalog";
                            case "TABLE_NAME" -> "syncmoneyxauditxlog";
                            case "TABLE_SCHEM" -> "main";
                            case "COLUMN_NAME" -> "request_id";
                            default -> null;
                        };
                    }
                    if (!siblingRow[0] && "getString".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof String column) {
                        if ("TABLE_CAT".equalsIgnoreCase(column)) return "target_catalog";
                        if ("TABLE_SCHEM".equalsIgnoreCase(column)) return "main";
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private HikariDataSource dataSource(String suffix) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve(suffix + ".db"));
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("schema-migration-test"));
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler())
                .thenReturn(mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class));
        return plugin;
    }

    private void createVersionTable(HikariDataSource dataSource, int mainVersion, int auditVersion)
            throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE syncmoney_schema_version ("
                    + "id VARCHAR(50) PRIMARY KEY, version INT NOT NULL DEFAULT 0, "
                    + "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, description VARCHAR(255))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO syncmoney_schema_version (id, version) VALUES (?, ?)")) {
                insert.setString(1, "1");
                insert.setInt(2, mainVersion);
                insert.executeUpdate();
                insert.setString(1, "audit");
                insert.setInt(2, auditVersion);
                insert.executeUpdate();
            }
        }
    }

    private void createVersionTenAuditTable(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE syncmoney_audit_log ("
                    + "id VARCHAR(36) PRIMARY KEY, timestamp BIGINT NOT NULL, type VARCHAR(20) NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(16), amount DECIMAL(20,2) NOT NULL, "
                    + "balance_after DECIMAL(20,2) NOT NULL, source VARCHAR(30) NOT NULL, server VARCHAR(50) NOT NULL, "
                    + "target_uuid VARCHAR(36), target_name VARCHAR(16), reason VARCHAR(255), merged_count INT DEFAULT 1)");
        }
    }

    private void createCurrentAuditTable(HikariDataSource dataSource) throws SQLException {
        createVersionTenAuditTable(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE syncmoney_audit_log ADD COLUMN request_id VARCHAR(36)");
            statement.executeUpdate("ALTER TABLE syncmoney_audit_log ADD COLUMN sequence INTEGER DEFAULT 0");
        }
    }

    private void createIndex(HikariDataSource dataSource, String name, String columns) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + name + " ON syncmoney_audit_log (" + columns + ")");
        }
    }

    private int version(HikariDataSource dataSource, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT version FROM syncmoney_schema_version WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private Set<String> columns(HikariDataSource dataSource) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet result = connection.getMetaData().getColumns(null, null, "syncmoney_audit_log", "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return columns;
    }

    private Set<String> indexes(HikariDataSource dataSource) throws SQLException {
        Set<String> indexes = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet result = connection.getMetaData().getIndexInfo(null, null, "syncmoney_audit_log", false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null && name.toLowerCase().startsWith("idx_audit_")) {
                    indexes.add(name.toLowerCase());
                }
            }
        }
        return indexes;
    }

    private HikariDataSource rejectDdl(HikariDataSource delegate, Predicate<String> rejects) throws SQLException {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> wrapConnection(delegate.getConnection(), rejects));
        return dataSource;
    }

    private HikariDataSource requireScopedMetadata(HikariDataSource delegate) throws SQLException {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> wrapScopedConnection(delegate.getConnection()));
        return dataSource;
    }

    private HikariDataSource hideIndexDirections(HikariDataSource delegate) throws SQLException {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> wrapUnknownDirectionConnection(delegate.getConnection()));
        return dataSource;
    }

    private Connection wrapUnknownDirectionConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    try {
                        if ("getCatalog".equals(method.getName())) return "target_catalog";
                        if ("getSchema".equals(method.getName())) return "target_schema";
                        Object value = method.invoke(connection, args);
                        if ("getMetaData".equals(method.getName())) {
                            return hideIndexDirections(wrapScopedMetadata((java.sql.DatabaseMetaData) value));
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private java.sql.DatabaseMetaData hideIndexDirections(java.sql.DatabaseMetaData metadata) {
        return (java.sql.DatabaseMetaData) Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(),
                new Class<?>[] { java.sql.DatabaseMetaData.class }, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(metadata, args);
                        if ("getIndexInfo".equals(method.getName())) {
                            return hideIndexDirections((ResultSet) value);
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private ResultSet hideIndexDirections(ResultSet delegate) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName()) && args != null && args.length == 1
                            && "ASC_OR_DESC".equalsIgnoreCase(String.valueOf(args[0]))) {
                        return null;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Connection wrapScopedConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    try {
                        if ("getCatalog".equals(method.getName())) return "target_catalog";
                        if ("getSchema".equals(method.getName())) return "target_schema";
                        Object value = method.invoke(connection, args);
                        if ("getMetaData".equals(method.getName())) {
                            return wrapScopedMetadata((java.sql.DatabaseMetaData) value);
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private java.sql.DatabaseMetaData wrapScopedMetadata(java.sql.DatabaseMetaData metadata) {
        return (java.sql.DatabaseMetaData) Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(),
                new Class<?>[] { java.sql.DatabaseMetaData.class }, (proxy, method, args) -> {
                    if ("getColumns".equals(method.getName()) || "getIndexInfo".equals(method.getName())) {
                        String schema = "target_schema";
                        String table = "syncmoney_audit_log";
                        if ("getColumns".equals(method.getName())) {
                            String escape = metadata.getSearchStringEscape();
                            if (escape != null && !escape.isEmpty()) {
                                schema = schema.replace(escape, escape + escape).replace("_", escape + "_")
                                        .replace("%", escape + "%");
                                table = table.replace(escape, escape + escape).replace("_", escape + "_")
                                        .replace("%", escape + "%");
                            }
                        }
                        if (!"target_catalog".equals(args[0]) || !schema.equals(args[1])
                                || ("getColumns".equals(method.getName()) && !table.equals(args[2]))) {
                            throw new SQLException("Metadata lookup used an unexpected target namespace");
                        }
                        Object[] exactNamespaceArgs = args.clone();
                        exactNamespaceArgs[0] = null;
                        exactNamespaceArgs[1] = null;
                        if ("getColumns".equals(method.getName())) {
                            String escape = metadata.getSearchStringEscape();
                            if (escape != null && !escape.isEmpty()) {
                                exactNamespaceArgs[2] = unescapeMetadataPattern((String) exactNamespaceArgs[2], escape);
                            }
                        }
                        try {
                            return wrapNamespaceResultSet((ResultSet) method.invoke(metadata, exactNamespaceArgs),
                                    "target_catalog", "target_schema");
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                    try {
                        return method.invoke(metadata, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private String unescapeMetadataPattern(String value, String escape) {
        return value.replace(escape + "_", "_").replace(escape + "%", "%");
    }

    private ResultSet wrapNamespaceResultSet(ResultSet delegate, String catalog, String schema) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof String column) {
                        if ("TABLE_CAT".equalsIgnoreCase(column)) return catalog;
                        if ("TABLE_SCHEM".equalsIgnoreCase(column)) return schema;
                        if ("ASC_OR_DESC".equalsIgnoreCase(column)) {
                            String reported = delegate.getString(column);
                            if (reported != null) return reported;
                            String index = delegate.getString("INDEX_NAME");
                            int position = delegate.getInt("ORDINAL_POSITION");
                            return expectedIndexDirection(index, position);
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private String expectedIndexDirection(String index, int position) {
        if (index == null) return null;
        return switch (index.toLowerCase()) {
            case "idx_audit_timestamp" -> position <= 2 ? "D" : null;
            case "idx_audit_player", "idx_audit_type" -> position == 1 ? "A" : position <= 3 ? "D" : null;
            default -> null;
        };
    }

    private Connection wrapConnection(Connection connection, Predicate<String> rejects) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(connection, args);
                        if ("createStatement".equals(method.getName()) && (args == null || args.length == 0)) {
                            return wrapStatement((Statement) value, rejects);
                        }
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof String) {
                            return wrapPreparedStatement((PreparedStatement) value, (String) args[0], rejects);
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private PreparedStatement wrapPreparedStatement(PreparedStatement statement, String sql,
            Predicate<String> rejects) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName()) && (args == null || args.length == 0)
                            && rejects.test(sql)) {
                        throw new SQLException("Injected version write failure");
                    }
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Statement wrapStatement(Statement statement, Predicate<String> rejects) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] { Statement.class },
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName()) && args != null && args.length > 0
                            && args[0] instanceof String sql && rejects.test(sql)) {
                        throw new SQLException("Injected DDL failure: " + sql);
                    }
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
