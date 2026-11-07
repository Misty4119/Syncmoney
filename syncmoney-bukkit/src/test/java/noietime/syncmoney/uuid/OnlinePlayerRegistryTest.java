package noietime.syncmoney.uuid;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlinePlayerRegistryTest {

    @Test
    void registerMaintainsLocalNamesWithoutRedisAndUnregisterRemovesThem() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(Set.of());
        when(player.isOnline()).thenReturn(true);
        when(player.hasMetadata("NPC")).thenReturn(false);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Alex");

        OnlinePlayerRegistry registry = new OnlinePlayerRegistry(plugin, config, null);

        registry.register(player);

        assertEquals(List.of("Alex"), registry.suggestOnlinePlayerNames("al", false));
        assertEquals(Set.of(uuid), registry.getLocalOnlinePlayerIds());
        verify(server, never()).getOnlinePlayers();

        registry.unregister(uuid);

        assertTrue(registry.suggestOnlinePlayerNames("", false).isEmpty());
        assertTrue(registry.getLocalOnlinePlayerIds().isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void startupSnapshotReadsPlayerStateOnlyInsideItsEntityTask() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(player.isOnline()).thenReturn(true);
        when(player.hasMetadata("NPC")).thenReturn(false);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Taylor");

        List<Consumer<ScheduledTask>> globalTasks = new ArrayList<>();
        List<Consumer<ScheduledTask>> entityTasks = new ArrayList<>();
        doAnswer(invocation -> {
            globalTasks.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any());
        doAnswer(invocation -> {
            entityTasks.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(entityScheduler).run(eq(plugin), any(), isNull());

        OnlinePlayerRegistry registry = new OnlinePlayerRegistry(plugin, config, null);

        registry.seedOnlinePlayers();

        assertEquals(1, globalTasks.size());
        verify(server, never()).getOnlinePlayers();
        globalTasks.get(0).accept(mock(ScheduledTask.class));
        assertEquals(1, entityTasks.size());
        verify(player, never()).getUniqueId();
        verify(player, never()).getName();

        entityTasks.get(0).accept(mock(ScheduledTask.class));

        assertEquals(List.of("Taylor"), registry.suggestOnlinePlayerNames("t", false));
        assertEquals(Set.of(uuid), registry.getLocalOnlinePlayerIds());
    }

    @Test
    void registerExcludesNpcPlayersFromLocalSnapshots() {
        Plugin plugin = mock(Plugin.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.hasMetadata("NPC")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        OnlinePlayerRegistry registry = new OnlinePlayerRegistry(plugin, config, null);

        registry.register(player);

        assertTrue(registry.suggestOnlinePlayerNames("", false).isEmpty());
        assertTrue(registry.getLocalOnlinePlayerIds().isEmpty());
    }
}
