package noietime.syncmoney.listener;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIApi;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CMIEconomyListenerShutdownTest {

    @Test
    @SuppressWarnings("unchecked")
    void listenerShutdownDoesNotWaitForAPlayerOwnerJoinMutation() throws Exception {
        UUID account = UUID.randomUUID();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyListenerShutdownTest"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(account);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(account)).thenReturn(player);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(entityScheduler);
        AtomicReference<Consumer<ScheduledTask>> entityWork = new AtomicReference<>();
        doAnswer(invocation -> {
            entityWork.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(entityScheduler).run(eq(plugin), any(), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        when(config.cmi().getCMIDetectIntervalMs()).thenReturn(60_000L);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(any(String[].class))).thenReturn(List.of("150.00", "2"));
        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(true);
        when(cmiApi.getBalance(account)).thenReturn(100.0);
        CountDownLatch applyAdmitted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);
        when(cmiApi.setBalance(account, 150.0)).thenReturn(true);

        CMIEconomyHandler handler = spy(new CMIEconomyHandler(plugin, config, redisManager, null, cmiApi));
        doAnswer(invocation -> {
            applyAdmitted.countDown();
            if (!releaseApply.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test join apply release timed out");
            }
            return null;
        }).when(handler).suppressOutbound(account, 750L);
        CMIEconomyListener listener = new CMIEconomyListener(plugin, handler, config);
        handler.setCmiListener(listener);
        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            handler.reconcileOnJoin(account);
            Thread ownerCallback = new Thread(
                    () -> entityWork.get().accept(mock(ScheduledTask.class)),
                    "cmi-join-owner-shutdown-test");
            ownerCallback.start();
            Thread shutdown = null;
            try {
                assertTrue(applyAdmitted.await(5, TimeUnit.SECONDS), "join write was not admitted");
                CountDownLatch shutdownFinished = new CountDownLatch(1);
                shutdown = new Thread(() -> {
                    listener.shutdown();
                    shutdownFinished.countDown();
                }, "cmi-listener-shutdown-nonblocking-test");
                shutdown.start();
                assertTrue(shutdownFinished.await(1, TimeUnit.SECONDS),
                        "shutdown must not wait for an admitted player-region CMI write");
                verify(cmiApi, never()).setBalance(account, 150.0);
            } finally {
                releaseApply.countDown();
                ownerCallback.join(TimeUnit.SECONDS.toMillis(5));
                if (shutdown != null) shutdown.join(TimeUnit.SECONDS.toMillis(5));
            }
            assertFalse(ownerCallback.isAlive(), "owner callback should finish after the admitted write is released");
            verify(cmiApi).setBalance(account, 150.0);
        }
    }

    @Test
    void queuedJoinReconciliationDoesNotMutateCmiAfterListenerShutdown() {
        UUID account = UUID.randomUUID();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyListenerShutdownTest"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(account);
        when(player.isOnline()).thenReturn(true);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(entityScheduler);

        AtomicReference<Consumer<ScheduledTask>> asyncWork = new AtomicReference<>();
        doAnswer(invocation -> {
            asyncWork.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any());
        List<Consumer<ScheduledTask>> entityWork = new ArrayList<>();
        doAnswer(invocation -> {
            entityWork.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(entityScheduler).run(eq(plugin), any(), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(any(String[].class))).thenReturn(List.of("150.00", "2"));

        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(true);
        when(cmiApi.getBalance(account)).thenReturn(100.0);
        when(cmiApi.setBalance(account, 150.0)).thenReturn(true);
        CMIEconomyHandler cmiHandler = new CMIEconomyHandler(plugin, config, redisManager,
                null, cmiApi);
        CMIEconomyListener listener = new CMIEconomyListener(plugin, cmiHandler, config);
        cmiHandler.setCmiListener(listener);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            when(server.getPlayer(account)).thenReturn(player);
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerJoin(event);
            asyncWork.get().accept(mock(ScheduledTask.class));
            assertEquals(2, entityWork.size(), "join reconcile and listener snapshot should be queued");

            listener.shutdown();
            entityWork.get(0).accept(mock(ScheduledTask.class));

            verify(cmiApi, never()).setBalance(account, 150.0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void listenerShutdownWaitsForAnInFlightRedisJoinRead() throws Exception {
        UUID account = UUID.randomUUID();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyListenerShutdownTest"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(),
                eq(TimeUnit.MILLISECONDS))).thenReturn(mock(ScheduledTask.class));
        AtomicReference<Consumer<ScheduledTask>> asyncWork = new AtomicReference<>();
        doAnswer(invocation -> {
            asyncWork.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        when(config.cmi().getCMIDetectIntervalMs()).thenReturn(60_000L);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CountDownLatch redisReadEntered = new CountDownLatch(1);
        CountDownLatch releaseRedisRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            redisReadEntered.countDown();
            releaseRedisRead.await();
            return List.of("150.00", "2");
        }).when(jedis).mget(any(String[].class));

        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(true);
        CMIEconomyHandler cmiHandler = new CMIEconomyHandler(plugin, config, redisManager,
                null, cmiApi);
        CMIEconomyListener listener = new CMIEconomyListener(plugin, cmiHandler, config);
        cmiHandler.setCmiListener(listener);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(account);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            listener.onPlayerJoin(event);
            Thread joinCallback = new Thread(() -> asyncWork.get().accept(mock(ScheduledTask.class)),
                    "cmi-join-redis-read-test");
            joinCallback.start();
            assertTrue(redisReadEntered.await(5, TimeUnit.SECONDS), "CMI mirror read did not start");

            CountDownLatch shutdownStarted = new CountDownLatch(1);
            CountDownLatch shutdownFinished = new CountDownLatch(1);
            Thread shutdown = new Thread(() -> {
                shutdownStarted.countDown();
                listener.shutdown();
                shutdownFinished.countDown();
            }, "cmi-join-shutdown-test");
            shutdown.start();
            try {
                assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS), "shutdown did not start");
                assertTrue(!shutdownFinished.await(100, TimeUnit.MILLISECONDS),
                        "shutdown must keep Redis open until the in-flight mirror read finishes");
            } finally {
                releaseRedisRead.countDown();
                joinCallback.join(TimeUnit.SECONDS.toMillis(5));
                shutdown.join(TimeUnit.SECONDS.toMillis(5));
            }
        } finally {
            releaseRedisRead.countDown();
        }
    }
}
