package noietime.syncmoney.sync;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

class PubsubSubscriberLifecycleTest {

    @Test
    void stopPreventsSubscriptionStartupThatWasStillCheckingConfiguration() throws Exception {
        CountDownLatch configurationCheckEntered = new CountDownLatch(1);
        CountDownLatch continueStartup = new CountDownLatch(1);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PubsubSubscriberLifecycleTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isPubsubEnabled()).thenAnswer(invocation -> {
            configurationCheckEntered.countDown();
            if (!continueStartup.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test startup release timed out");
            }
            return true;
        });
        when(config.getServerName()).thenReturn("lifecycle-test");
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        PubsubSubscriber subscriber = new PubsubSubscriber(plugin, config,
                mock(CacheManager.class), mock(EconomyFacade.class), mock(DebounceManager.class),
                redisManager, true);

        Thread starter = new Thread(subscriber::startSubscription, "pubsub-start-test");
        starter.start();
        try {
            assertTrue(configurationCheckEntered.await(5, TimeUnit.SECONDS),
                    "startup did not reach the configuration check");

            subscriber.stopSubscription();
            continueStartup.countDown();
            starter.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(starter.isAlive(), "subscription startup should return");
            assertFalse(subscriber.isRunning(), "stop must win over a startup still checking configuration");
            subscriber.startSubscription();
            assertFalse(subscriber.isRunning(), "a stopped subscriber must not be restartable");
        } finally {
            continueStartup.countDown();
            subscriber.stopSubscription();
            starter.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void stopClosesTheActiveSubscriptionBeforeReturning() throws Exception {
        CountDownLatch subscribeEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscribe = new CountDownLatch(1);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PubsubSubscriberLifecycleTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isPubsubEnabled()).thenReturn(true);
        when(config.getServerName()).thenReturn("lifecycle-test");
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        Jedis jedis = mock(Jedis.class);
        PubsubSubscriber subscriber = new PubsubSubscriber(plugin, config,
                mock(CacheManager.class), mock(EconomyFacade.class), mock(DebounceManager.class),
                redisManager, true, () -> jedis);

        doAnswer(invocation -> {
            subscribeEntered.countDown();
            while (true) {
                try {
                    releaseSubscribe.await();
                    return null;
                } catch (InterruptedException ignored) {
                    // Model a socket read that only a connection close can release.
                }
            }
        }).when(jedis).subscribe(any(JedisPubSub.class), any(String[].class));
        doAnswer(invocation -> {
            releaseSubscribe.countDown();
            return null;
        }).when(jedis).close();

        try {
            subscriber.startSubscription();
            assertTrue(subscribeEntered.await(5, TimeUnit.SECONDS), "subscription did not start");

            subscriber.stopSubscription();

            assertFalse(subscriberThread(subscriber).isAlive(),
                    "stop must not return while the subscriber still owns Redis callbacks");
            org.mockito.Mockito.verify(jedis, atLeastOnce()).close();
        } finally {
            releaseSubscribe.countDown();
            subscriber.stopSubscription();
            subscriberThread(subscriber).join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private static Thread subscriberThread(PubsubSubscriber subscriber) throws ReflectiveOperationException {
        Field field = PubsubSubscriber.class.getDeclaredField("subscriberThread");
        field.setAccessible(true);
        return (Thread) field.get(subscriber);
    }
}
