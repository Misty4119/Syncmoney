package noietime.syncmoney.api.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.economy.CMIEconomyHandler;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CmiEntityBalanceQueryTest {

    @Test
    @SuppressWarnings("unchecked")
    void coldCmiReadRunsOnTheOnlinePlayersEntityScheduler() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        Player player = mock(Player.class);
        EntityScheduler entity = mock(EntityScheduler.class);
        CMIEconomyHandler cmi = mock(CMIEconomyHandler.class);
        UUID uuid = UUID.randomUUID();
        BigDecimal expected = new BigDecimal("84.10");
        AtomicBoolean onEntityOwner = new AtomicBoolean();
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(server.getPlayer(uuid)).thenReturn(player);
        when(player.getScheduler()).thenReturn(entity);
        when(global.run(eq(plugin), any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(entity.run(eq(plugin), any(Consumer.class), any(Runnable.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            Runnable retired = invocation.getArgument(2);
            onEntityOwner.set(true);
            task.accept(mock(ScheduledTask.class));
            onEntityOwner.set(false);
            return mock(ScheduledTask.class);
        });
        when(cmi.getExistingBalanceForApi(uuid)).thenAnswer(invocation -> {
            assertTrue(onEntityOwner.get(), "CMI player access must execute on the player's owner scheduler");
            return Optional.of(expected);
        });
        CmiEntityBalanceQuery query = new CmiEntityBalanceQuery(plugin, cmi);

        Optional<BigDecimal> loaded = query.load(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(Optional.of(expected), loaded);
        verify(server).getPlayer(uuid);
        verify(entity).run(eq(plugin), any(Consumer.class), any(Runnable.class));
        verify(cmi).getExistingBalanceForApi(uuid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offlineCmiAccountWithoutLocalSnapshotIsUnknownAndNeverCreatesUser() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        CMIEconomyHandler cmi = mock(CMIEconomyHandler.class);
        UUID uuid = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(server.getPlayer(uuid)).thenReturn(null);
        when(global.run(eq(plugin), any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        CmiEntityBalanceQuery query = new CmiEntityBalanceQuery(plugin, cmi);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> query.load(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        verify(cmi, never()).getExistingBalanceForApi(uuid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlinePlayerWithoutLoadedCmiUserIsUnknownRatherThanConfirmedAbsent() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        Player player = mock(Player.class);
        EntityScheduler entity = mock(EntityScheduler.class);
        CMIEconomyHandler cmi = mock(CMIEconomyHandler.class);
        UUID uuid = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(server.getPlayer(uuid)).thenReturn(player);
        when(player.getScheduler()).thenReturn(entity);
        when(global.run(eq(plugin), any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(entity.run(eq(plugin), any(Consumer.class), any(Runnable.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(cmi.getExistingBalanceForApi(uuid)).thenReturn(Optional.empty());
        CmiEntityBalanceQuery query = new CmiEntityBalanceQuery(plugin, cmi);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> query.load(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        verify(cmi).getExistingBalanceForApi(uuid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retiredEntitySchedulerCompletesExceptionally() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        Player player = mock(Player.class);
        EntityScheduler entity = mock(EntityScheduler.class);
        CMIEconomyHandler cmi = mock(CMIEconomyHandler.class);
        UUID uuid = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(server.getPlayer(uuid)).thenReturn(player);
        when(player.getScheduler()).thenReturn(entity);
        when(global.run(eq(plugin), any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(entity.run(eq(plugin), any(Consumer.class), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable retired = invocation.getArgument(2);
            retired.run();
            return null;
        });
        CmiEntityBalanceQuery query = new CmiEntityBalanceQuery(plugin, cmi);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> query.load(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalStateException);
        verify(cmi, never()).getExistingBalanceForApi(uuid);
    }
}
