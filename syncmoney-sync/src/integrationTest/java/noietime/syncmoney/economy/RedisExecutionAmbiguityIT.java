package noietime.syncmoney.economy;
import noietime.syncmoney.testkit.RedisTestSupport;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent.EventSource;
import noietime.syncmoney.economy.EconomyEvent.EventType;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.RedisResponseDropProxy;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisExecutionAmbiguityIT {
    private static DisposableRedisServer redis;
    private static RedisResponseDropProxy proxy;
    private static CacheManager cacheManager;
    private static RedisManager redisManager;

    @BeforeAll
    static void startRedisAndCacheManager() throws Exception {
        redis = DisposableRedisServer.start();
        proxy = RedisResponseDropProxy.start(redis.port());

        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RedisExecutionAmbiguityIT"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return CacheManager.class.getClassLoader().getResourceAsStream(path);
        });
        redisManager = mock(RedisManager.class);
        when(redisManager.getResource()).thenAnswer(invocation -> RedisTestSupport.connect(redis, "127.0.0.1", proxy.port()));
        cacheManager = new CacheManager(plugin, redisManager, true);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (proxy != null) proxy.close();
        if (redis != null) redis.close();
    }

    @Test
    void committedWriteWithDroppedResponseIsQuarantinedWithoutRetry() throws Exception {
        UUID account = UUID.randomUUID();
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.set("syncmoney:balance:" + account, "100.00");
            jedis.set("syncmoney:version:" + account, "7");
        }

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(false);
        DbWriteQueue dbWriteQueue = mock(DbWriteQueue.class);
        NameResolver nameResolver = mock(NameResolver.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        AtomicInteger completions = new AtomicInteger();
        queue.setWriteCompletionHandler((event, balance, version) -> completions.incrementAndGet());
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RedisExecutionAmbiguityIT-consumer"));
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue,
                cacheManager, redisManager, dbWriteQueue, null, null, nameResolver, null,
                (ShadowSyncTask) null, null, mock(DatabaseManager.class));

        EconomyEvent event = new EconomyEvent(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                8L, EventType.DEPOSIT, EventSource.TEST, "response-lost-request", System.currentTimeMillis());
        assertTrue(queue.offer(event), "the accepted event must enter the real consumer queue");
        proxy.dropNextEvalShaResponse();

        consumer.start();
        consumer.shutdown();

        assertTrue(proxy.didDropResponse(), "the fixture must drop the post-commit EVALSHA response");
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            assertEquals(0, new BigDecimal("110.00").compareTo(
                            new BigDecimal(jedis.get("syncmoney:balance:" + account))),
                    "Redis committed once before the proxy discarded the EVALSHA response");
            assertEquals("8", jedis.get("syncmoney:version:" + account),
                    "the uncertain event must not be submitted a second time");
        }
        assertEquals(0, completions.get(), "an ambiguous event must stay quarantined, not complete normally");
        assertTrue(queue.hasOutstandingWrites(), "quarantine must retain the accepted write reservation");
    }
}
