package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Database configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class DatabaseConfig {

    private final FileConfiguration config;

    public DatabaseConfig(FileConfiguration config) {
        this.config = config;
    }

    public String getDatabaseHost() {
        return config.getString("database.host", "localhost");
    }

    public int getDatabasePort() {
        return config.getInt("database.port", 3306);
    }

    public String getDatabaseUsername() {
        return config.getString("database.username", "root");
    }

    public String getDatabasePassword() {
        return config.getString("database.password", "");
    }

    public String getDatabaseName() {
        return config.getString("database.database", "syncmoney");
    }

    public int getDatabasePoolSize() {
        return config.getInt("database.pool-size", 10);
    }

    public int getDatabaseMinimumIdle() {
        return config.getInt("database.minimum-idle", 2);
    }

    public int getDatabaseConnectionTimeout() {
        return config.getInt("database.connection-timeout", 10000);
    }

    public long getDatabaseMaxLifetime() {
        return config.getLong("database.max-lifetime", 600000L);
    }

    public long getDatabaseIdleTimeout() {
        return config.getLong("database.idle-timeout", 300000L);
    }

    /**
     * [SYNC-CONFIG-014] Gets database type (mysql or postgresql)
     */
    public String getDatabaseType() {
        return config.getString("database.type", "mysql").toLowerCase();
    }
}
