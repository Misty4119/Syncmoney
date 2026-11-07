package noietime.syncmoney.storage.persistence;
import java.util.Locale;
public enum SqlDialect {
    POSTGRESQL, MYSQL, SQLITE;
    public static SqlDialect fromDatabaseType(String databaseType) {
        if (databaseType == null) throw new IllegalArgumentException("database type is required");
        return switch (databaseType.toLowerCase(Locale.ROOT)) {
            case "postgresql", "postgres" -> POSTGRESQL;
            case "mysql", "mariadb" -> MYSQL;
            case "sqlite" -> SQLITE;
            default -> throw new IllegalArgumentException("unsupported SQL dialect: " + databaseType);
        };
    }
}
