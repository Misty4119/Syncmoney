package noietime.syncmoney.listener;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CMIEconomyListenerLifecycleTest {

    @Test
    @SuppressWarnings("unchecked")
    void shutdownWaitsForAnOutboundRedisCallbackAlreadyInFlight() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyListenerLifecycleTest"));
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        ScheduledTask pollingTask = mock(ScheduledTask.class);
        when(scheduler.runAtFixedRate(eq(plugin), any(Consumer.class), anyLong(), anyLong(),
                eq(TimeUnit.MILLISECONDS))).thenReturn(pollingTask);

        AtomicReference<Consumer<ScheduledTask>> outboundWork = new AtomicReference<>();
        doAnswer(invocation -> {
            outboundWork.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(scheduler).runNow(eq(plugin), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIDetectIntervalMs()).thenReturn(60_000L);
        CMIEconomyHandler handler = mock(CMIEconomyHandler.class);
        UUID account = UUID.randomUUID();
        when(handler.getCMILocalBalance(account)).thenReturn(new BigDecimal("100.00"));
        CountDownLatch redisWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseRedisWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            redisWriteEntered.countDown();
            releaseRedisWrite.await();
            return true;
        }).when(handler).trySyncAbsoluteBalance(eq(account), eq(new BigDecimal("100.00")),
                any(BigDecimal.class), eq(true), eq("CMI"));

        CMIEconomyListener listener = new CMIEconomyListener(plugin, handler, config);
        Method publish = CMIEconomyListener.class.getDeclaredMethod(
                "publishOnPlayerScheduler", UUID.class, String.class);
        publish.setAccessible(true);
        publish.invoke(listener, account, "CMI");
        Thread outbound = new Thread(() -> outboundWork.get().accept(mock(ScheduledTask.class)),
                "cmi-outbound-callback-test");
        outbound.start();
        assertTrue(redisWriteEntered.await(5, TimeUnit.SECONDS), "CMI Redis write did not start");

        CountDownLatch shutdownStarted = new CountDownLatch(1);
        CountDownLatch shutdownFinished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            shutdownStarted.countDown();
            listener.shutdown();
            shutdownFinished.countDown();
        }, "cmi-listener-shutdown-test");
        shutdown.start();
        try {
            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS), "shutdown did not start");
            assertTrue(!shutdownFinished.await(100, TimeUnit.MILLISECONDS),
                    "shutdown must wait while an accepted Redis callback still owns its dependency");
        } finally {
            releaseRedisWrite.countDown();
            outbound.join(TimeUnit.SECONDS.toMillis(5));
            shutdown.join(TimeUnit.SECONDS.toMillis(5));
        }

        verify(handler).trySyncAbsoluteBalance(eq(account), eq(new BigDecimal("100.00")),
                any(BigDecimal.class), eq(true), eq("CMI"));
    }
}
