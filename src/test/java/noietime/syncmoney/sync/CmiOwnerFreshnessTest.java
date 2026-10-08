package noietime.syncmoney.sync;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.listener.CMIEconomyListener;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CmiOwnerFreshnessTest {

    private Method previousGetFromMethod;
    private Method previousGetToMethod;
    private Method previousGetUserMethod;

    @org.junit.jupiter.api.BeforeEach
    void rememberCmiReflectionState() throws ReflectiveOperationException {
        previousGetFromMethod = getStaticMethod("getFromMethod");
        previousGetToMethod = getStaticMethod("getToMethod");
        previousGetUserMethod = getStaticMethod("getUserMethod");
    }

    @AfterEach
    void resetSharedState() throws ReflectiveOperationException {
        setStaticMethod("getFromMethod", previousGetFromMethod);
        setStaticMethod("getToMethod", previousGetToMethod);
        setStaticMethod("getUserMethod", previousGetUserMethod);
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void queuedOwnerCallbackRechecksFreshnessBeforeApplyingBalanceAndSuppressesEcho() {
        UUID account = UUID.randomUUID();
        Fixture fixture = fixture(Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC), true, true);
        when(fixture.cmiApi.getBalance(account)).thenReturn(10.0);
        when(fixture.cmiApi.setBalance(account, 20.0)).thenReturn(true);

        fixture.handler.process(message(account, "10.00", 10L));
        fixture.handler.process(message(account, "20.00", 20L));

        assertEquals(2, fixture.scheduledWork.size());
        fixture.scheduledWork.get(0).accept(mock(ScheduledTask.class));
        verify(fixture.cmiApi, never()).getBalance(account);
        verify(fixture.cmiApi, never()).setBalance(eq(account), any(Double.class));

        fixture.scheduledWork.get(1).accept(mock(ScheduledTask.class));

        verify(fixture.cmiApi).setBalance(account, 20.0);
        verify(fixture.cmiHandler).suppressOutbound(account, 750L);
        verify(fixture.cmiHandler).notifyInboundApplied(eq(account), eq(new BigDecimal("20.00")), eq(20L));
    }

    @Test
    void retiredEntityCallbackDoesNotMutateCmiAndReleasesTheMigrationFence() {
        UUID account = UUID.randomUUID();
        Fixture fixture = fixture(Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC), true, true);
        MigrationLock.enable();

        fixture.handler.process(message(account, "20.00", 20L));

        assertNotNull(fixture.retiredWork.get());
        assertFalse(MigrationLock.lock(), "the scheduled owner callback retains the write lease");
        fixture.retiredWork.get().run();

        assertTrue(MigrationLock.lock(), "retirement releases the CMI write lease");
        verify(fixture.cmiApi, never()).getBalance(account);
        verify(fixture.cmiApi, never()).setBalance(eq(account), any(Double.class));
    }

    @Test
    void offlinePlayerIsNotMutatedByInboundCallback() {
        UUID account = UUID.randomUUID();
        Fixture fixture = fixture(Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC), true, false);

        fixture.handler.process(message(account, "20.00", 20L));

        assertTrue(fixture.scheduledWork.isEmpty());
        verify(fixture.player, never()).getScheduler();
        verify(fixture.cmiApi, never()).getBalance(account);
        verify(fixture.cmiApi, never()).setBalance(eq(account), any(Double.class));
    }

    @Test
    void expiredWatermarkAllowsAnOlderInboundVersionAgain() {
        UUID account = UUID.randomUUID();
        MutableClock clock = new MutableClock(1_000_000L);
        Fixture fixture = fixture(clock, false, false);

        fixture.handler.process(message(account, "200.00", 200L));
        clock.advanceMillis(10 * 60 * 1000L + 1);
        fixture.handler.process(message(UUID.randomUUID(), "1.00", 1L));
        fixture.handler.process(message(account, "100.00", 100L));

        verify(fixture.cmiHandler).notifyInboundApplied(eq(account), eq(new BigDecimal("100.00")), eq(100L));
    }

    @Test
    void independentHandlersCanSubmitAnOlderLegacyMirrorValue() {
        UUID account = UUID.randomUUID();
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CmiOwnerFreshnessTest"));
        SyncmoneyConfig config = config();
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.isDegraded()).thenReturn(false);
        when(redis.getResource()).thenReturn(jedis);
        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(false);
        CMIEconomyHandler cmiHandler = mock(CMIEconomyHandler.class);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        CMIPubsubHandler firstHandler = new CMIPubsubHandler(plugin, config, redis, cmiApi, cmiHandler, clock);
        CMIPubsubHandler secondHandler = new CMIPubsubHandler(plugin, config, redis, cmiApi, cmiHandler, clock);
        String balanceKey = RedisKeys.cmiBalance("syncmoney:cmi", account);
        String versionKey = RedisKeys.cmiVersion("syncmoney:cmi", account);

        firstHandler.process(message(account, "200.00", 200L));
        secondHandler.process(message(account, "100.00", 100L));

        verify(jedis).mset(balanceKey, "200.00", versionKey, "200");
        verify(jedis).mset(balanceKey, "100.00", versionKey, "100");
    }

    @Test
    void inboundEchoSuppressionPreventsSchedulingAnotherOutboundPublish() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CmiOwnerFreshnessTest"));
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CMIEconomyHandler cmiHandler = mock(CMIEconomyHandler.class);
        CMIEconomyListener listener = new CMIEconomyListener(plugin, cmiHandler, config);
        installEventMethods();
        listener.suppressOutbound(account, 60_000L);

        invokeEventHandler(listener, new BalanceChangeEvent(new CmiUser(account), 10.0, 20.0));

        verify(cmiHandler).invalidateCachedBalanceForApi(account);
        verify(scheduler, never()).runDelayed(eq(plugin), any(), anyLong(), eq(TimeUnit.MILLISECONDS));
        assertTrue(listener.isOutboundSuppressed(account));
        listener.shutdown();
    }

    private static Fixture fixture(Clock clock, boolean cmiAvailable, boolean playerOnline) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CmiOwnerFreshnessTest"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        SyncmoneyConfig config = config();

        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.isDegraded()).thenReturn(false);
        when(redis.getResource()).thenReturn(jedis);

        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(cmiAvailable);
        CMIEconomyHandler cmiHandler = mock(CMIEconomyHandler.class);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(playerOnline);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(server.getPlayer(any(UUID.class))).thenReturn(player);

        List<Consumer<ScheduledTask>> scheduledWork = new ArrayList<>();
        AtomicReference<Runnable> retiredWork = new AtomicReference<>();
        doAnswer(invocation -> {
            scheduledWork.add(invocation.getArgument(1));
            retiredWork.set(invocation.getArgument(2));
            return mock(ScheduledTask.class);
        }).when(entityScheduler).run(eq(plugin), any(), any());

        CMIPubsubHandler handler = new CMIPubsubHandler(plugin, config, redis, cmiApi, cmiHandler, clock);
        return new Fixture(handler, cmiApi, cmiHandler, player, scheduledWork, retiredWork);
    }

    private static SyncmoneyConfig config() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.getServerName()).thenReturn("local");
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        when(config.crossServer().isCrossServerNotificationsEnabled()).thenReturn(false);
        return config;
    }

    private static PubSubMessage message(UUID account, String balance, long version) {
        return new PubSubMessage(account.toString(), new BigDecimal(balance), version,
                "remote", "message-" + version, version);
    }

    private static void installEventMethods() throws ReflectiveOperationException {
        setStaticMethod("getFromMethod", BalanceChangeEvent.class.getMethod("getFrom"));
        setStaticMethod("getToMethod", BalanceChangeEvent.class.getMethod("getTo"));
        setStaticMethod("getUserMethod", BalanceChangeEvent.class.getMethod("getUser"));
    }

    private static void invokeEventHandler(CMIEconomyListener listener, Event event)
            throws ReflectiveOperationException {
        Method method = CMIEconomyListener.class.getDeclaredMethod("handleCMIEvent", Event.class);
        method.setAccessible(true);
        method.invoke(listener, event);
    }

    private static Method getStaticMethod(String fieldName) throws ReflectiveOperationException {
        Field field = CMIEconomyListener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Method) field.get(null);
    }

    private static void setStaticMethod(String fieldName, Method method) throws ReflectiveOperationException {
        Field field = CMIEconomyListener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, method);
    }

    private record Fixture(CMIPubsubHandler handler, CMIApi cmiApi, CMIEconomyHandler cmiHandler,
                           Player player, List<Consumer<ScheduledTask>> scheduledWork,
                           AtomicReference<Runnable> retiredWork) {
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    public static final class BalanceChangeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final CmiUser user;
        private final double from;
        private final double to;

        public BalanceChangeEvent(CmiUser user, double from, double to) {
            this.user = user;
            this.from = from;
            this.to = to;
        }

        public CmiUser getUser() { return user; }
        public double getFrom() { return from; }
        public double getTo() { return to; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class CmiUser {
        private final UUID uuid;

        public CmiUser(UUID uuid) { this.uuid = uuid; }
        public UUID getUniqueId() { return uuid; }
        public String getName() { return "cmi-owner-test"; }
    }
}
