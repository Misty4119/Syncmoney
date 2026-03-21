package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * [SYNC-CONFIG] Redis configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class RedisConfig {

    private final FileConfiguration config;

    public RedisConfig(FileConfiguration config) {
        this.config = config;
    }

    public String getRedisHost() {
        return config.getString("redis.host", "localhost");
    }

    public int getRedisPort() {
        return config.getInt("redis.port", 6379);
    }

    public String getRedisPassword() {
        return config.getString("redis.password", "");
    }

    public int getRedisDatabase() {
        return config.getInt("redis.database", 0);
    }

    public int getRedisPoolSize() {
        return config.getInt("redis.pool-size", 20);
    }
}
