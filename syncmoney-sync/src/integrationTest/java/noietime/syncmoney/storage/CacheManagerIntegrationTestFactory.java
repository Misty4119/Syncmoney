package noietime.syncmoney.storage;

import org.bukkit.plugin.Plugin;

/** Test-only access to the explicit non-CMI CacheManager construction path. */
public final class CacheManagerIntegrationTestFactory {

    private CacheManagerIntegrationTestFactory() {
    }

    public static CacheManager newSynchronizedModeCacheManager(
            Plugin plugin, RedisManager redisManager, boolean redisRequired) {
        return new CacheManager(plugin, redisManager, redisRequired, true);
    }
}
