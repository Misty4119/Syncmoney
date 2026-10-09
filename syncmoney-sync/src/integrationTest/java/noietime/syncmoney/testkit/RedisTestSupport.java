package noietime.syncmoney.testkit;

import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import redis.clients.jedis.Jedis;

/** Creates authenticated clients for the isolated Redis process used by sync integration tests. */
public final class RedisTestSupport {

    private RedisTestSupport() { }

    public static Jedis connect(DisposableRedisServer redis) {
        return connect(redis, redis.host(), redis.port());
    }

    public static Jedis connect(DisposableRedisServer redis, String host, int port) {
        Jedis jedis = new Jedis(host, port, 500, 1_000);
        try {
            jedis.auth(redis.password());
            return jedis;
        } catch (RuntimeException failure) {
            jedis.close();
            throw failure;
        }
    }
}
