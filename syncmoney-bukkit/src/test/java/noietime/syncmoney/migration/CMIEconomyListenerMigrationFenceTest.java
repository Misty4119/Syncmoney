package noietime.syncmoney.migration;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.listener.CMIEconomyListener;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CMIEconomyListenerMigrationFenceTest {

    private Method previousGetFromMethod;
    private Method previousGetToMethod;
    private Method previousGetUserMethod;

    @BeforeEach
    void rememberCmiReflectionState() throws ReflectiveOperationException {
        previousGetFromMethod = getStaticMethod("getFromMethod");
        previousGetToMethod = getStaticMethod("getToMethod");
        previousGetUserMethod = getStaticMethod("getUserMethod");
    }

    @AfterEach
    void restoreCmiReflectionState() throws ReflectiveOperationException {
        setStaticMethod("getFromMethod", previousGetFromMethod);
        setStaticMethod("getToMethod", previousGetToMethod);
        setStaticMethod("getUserMethod", previousGetUserMethod);
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void changedCmiEventInvalidatesSnapshotEvenDuringOutboundSuppression() throws Exception {
        UUID account = UUID.randomUUID();
        CMIEconomyListener listener = createListener(mock(CMIEconomyHandler.class));
        installEventMethods();
        listener.suppressOutbound(account, 60_000L);
        MigrationLock.MigrationLease lease = beginSnapshot();

        invokeEventHandler(listener, new BalanceChangeEvent(new CmiUser(account), 100.0, 125.0));

        assertTrue(lease.sourceChangedDuringSnapshot(),
                "outbound echo suppression must not hide a CMI authority change from migration");
        listener.shutdown();
        lease.close();
    }

    @Test
    void pollingFallbackInvalidatesSnapshotWhenAnObservedBalanceChanges() throws Exception {
        UUID account = UUID.randomUUID();
        CMIEconomyHandler handler = mock(CMIEconomyHandler.class);
        when(handler.getCMILocalBalance(account)).thenReturn(new BigDecimal("125.00"));
        CMIEconomyListener listener = createListener(handler);
        setLastKnownBalance(listener, account, new BigDecimal("100.00"));
        MigrationLock.MigrationLease lease = beginSnapshot();

        invokePollHandler(listener, account);

        assertTrue(lease.sourceChangedDuringSnapshot(),
                "the polling fallback must detect a changed online account before retrying publication");
        listener.shutdown();
        lease.close();
    }

    private static CMIEconomyListener createListener(CMIEconomyHandler handler) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyListenerMigrationFenceTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(asyncScheduler.runDelayed(any(Plugin.class), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledTask.class));

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        return new CMIEconomyListener(plugin, handler, config);
    }

    private static MigrationLock.MigrationLease beginSnapshot() {
        MigrationLock.MigrationLease lease = MigrationLock.tryAcquireMigrationLease();
        if (lease == null || !lease.beginSourceSnapshot()) {
            throw new AssertionError("test could not acquire the CMI migration snapshot lease");
        }
        return lease;
    }

    private static void installEventMethods() throws ReflectiveOperationException {
        setStaticMethod("getFromMethod", BalanceChangeEvent.class.getMethod("getFrom"));
        setStaticMethod("getToMethod", BalanceChangeEvent.class.getMethod("getTo"));
        setStaticMethod("getUserMethod", BalanceChangeEvent.class.getMethod("getUser"));
    }

    private static void setStaticMethod(String fieldName, Method method) throws ReflectiveOperationException {
        Field field = CMIEconomyListener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, method);
    }

    private static Method getStaticMethod(String fieldName) throws ReflectiveOperationException {
        Field field = CMIEconomyListener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Method) field.get(null);
    }

    private static void invokeEventHandler(CMIEconomyListener listener, Event event)
            throws ReflectiveOperationException {
        Method method = CMIEconomyListener.class.getDeclaredMethod("handleCMIEvent", Event.class);
        method.setAccessible(true);
        method.invoke(listener, event);
    }

    @SuppressWarnings("unchecked")
    private static void setLastKnownBalance(CMIEconomyListener listener, UUID uuid, BigDecimal balance)
            throws ReflectiveOperationException {
        Field field = CMIEconomyListener.class.getDeclaredField("lastKnownCMIBalance");
        field.setAccessible(true);
        ((Map<UUID, BigDecimal>) field.get(listener)).put(uuid, balance);
    }

    private static void invokePollHandler(CMIEconomyListener listener, UUID uuid)
            throws ReflectiveOperationException {
        Method method = CMIEconomyListener.class.getDeclaredMethod("publishOnPlayerScheduler", UUID.class, String.class);
        method.setAccessible(true);
        method.invoke(listener, uuid, "CMI-POLLING");
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
        public String getName() { return "migration-test"; }
    }
}
