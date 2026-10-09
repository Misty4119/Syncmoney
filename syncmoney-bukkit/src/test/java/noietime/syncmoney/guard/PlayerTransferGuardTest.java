package noietime.syncmoney.guard;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.economy.EconomyWriteQueue;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PlayerTransferGuardTest {
    @Test
    void quittingNeverDiscardsUnconsumedEconomicWrites() {
        Plugin plugin = mock(Plugin.class);
        EconomyWriteQueue queue = mock(EconomyWriteQueue.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        new PlayerTransferGuard(plugin, queue).onPlayerQuit(event);
        verifyNoInteractions(queue);
    }

    @Test
    void waitsForAcceptedWritesThenTeleportsAsyncWithOriginalDestinationAndCause() {
        Plugin plugin = mock(Plugin.class);
        EconomyWriteQueue queue = mock(EconomyWriteQueue.class);
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);
        Location from = mock(Location.class);
        Location destination = mock(Location.class);
        when(from.getWorld()).thenReturn(fromWorld);
        when(destination.getWorld()).thenReturn(toWorld);
        when(destination.clone()).thenReturn(destination);
        PlayerTeleportEvent.TeleportCause cause = PlayerTeleportEvent.TeleportCause.COMMAND;
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(destination);
        when(event.getCause()).thenReturn(cause);
        when(queue.hasPending(uuid)).thenReturn(true, true, false);

        AtomicReference<Consumer<ScheduledTask>> ownerWork = new AtomicReference<>();
        ScheduledTask task = mock(ScheduledTask.class);
        doAnswer(invocation -> {
            ownerWork.set(invocation.getArgument(1));
            return task;
        }).when(scheduler).runAtFixedRate(eq(plugin), any(), any(), anyLong(), anyLong());
        when(player.teleportAsync(destination, cause)).thenReturn(CompletableFuture.completedFuture(true));

        PlayerTransferGuard guard = new PlayerTransferGuard(plugin, queue, 1000);
        guard.onPlayerTeleport(event);

        verify(event).setCancelled(true);
        assertEquals(1, guard.getWaitingCount());
        ownerWork.get().accept(task);
        verify(player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        assertEquals(1, guard.getWaitingCount(), "transfer must remain deferred while the write is pending");

        ownerWork.get().accept(task);

        verify(player).teleportAsync(destination, cause);
        verify(task).cancel();
        assertEquals(0, guard.getWaitingCount(), "completed deferred transfer is no longer tracked");
    }

    @Test
    void shutdownCancelsAWaitTaskRegisteredAtTheSameTime() {
        Plugin plugin = mock(Plugin.class);
        EconomyWriteQueue queue = mock(EconomyWriteQueue.class);
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);
        Location from = mock(Location.class);
        Location to = mock(Location.class);
        when(from.getWorld()).thenReturn(fromWorld);
        when(to.getWorld()).thenReturn(toWorld);
        when(to.clone()).thenReturn(to);
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.PLUGIN);
        when(queue.hasPending(uuid)).thenReturn(true);

        ScheduledTask task = mock(ScheduledTask.class);
        AtomicReference<PlayerTransferGuard> guardRef = new AtomicReference<>();
        AtomicReference<Consumer<ScheduledTask>> ownerWork = new AtomicReference<>();
        doAnswer(invocation -> {
            ownerWork.set(invocation.getArgument(1));
            guardRef.get().shutdown();
            return task;
        }).when(scheduler).runAtFixedRate(eq(plugin), any(), any(), anyLong(), anyLong());

        PlayerTransferGuard guard = new PlayerTransferGuard(plugin, queue, 1000);
        guardRef.set(guard);
        guard.onPlayerTeleport(event);

        assertEquals(0, guard.getWaitingCount(), "shutdown must not miss a concurrently registered task");
        verify(task).cancel();
        ownerWork.get().accept(task);
        verify(player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void shutdownDoesNotWaitForEntityOwnerTeleportCallback() throws Exception {
        Plugin plugin = mock(Plugin.class);
        EconomyWriteQueue queue = mock(EconomyWriteQueue.class);
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);
        Location from = mock(Location.class);
        Location to = mock(Location.class);
        when(from.getWorld()).thenReturn(fromWorld);
        when(to.getWorld()).thenReturn(toWorld);
        when(to.clone()).thenReturn(to);
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.PLUGIN);
        when(queue.hasPending(uuid)).thenReturn(true, false);

        AtomicReference<java.util.function.Consumer<ScheduledTask>> ownerWork = new AtomicReference<>();
        ScheduledTask task = mock(ScheduledTask.class);
        doAnswer(invocation -> {
            ownerWork.set(invocation.getArgument(1));
            return task;
        }).when(scheduler).runAtFixedRate(eq(plugin), any(), any(), anyLong(), anyLong());
        CountDownLatch teleportEntered = new CountDownLatch(1);
        CountDownLatch releaseTeleport = new CountDownLatch(1);
        doAnswer(invocation -> {
            teleportEntered.countDown();
            if (!releaseTeleport.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test teleport release timed out");
            }
            return CompletableFuture.completedFuture(true);
        }).when(player).teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN);

        PlayerTransferGuard guard = new PlayerTransferGuard(plugin, queue, 1000);
        guard.onPlayerTeleport(event);
        Thread ownerCallback = new Thread(() -> ownerWork.get().accept(task),
                "transfer-owner-shutdown-test");
        ownerCallback.start();
        Thread shutdown = null;
        try {
            assertTrue(teleportEntered.await(5, TimeUnit.SECONDS), "owner teleport did not start");
            CountDownLatch shutdownFinished = new CountDownLatch(1);
            shutdown = new Thread(() -> {
                guard.shutdown();
                shutdownFinished.countDown();
            }, "transfer-guard-shutdown-nonblocking-test");
            shutdown.start();
            assertTrue(shutdownFinished.await(1, TimeUnit.SECONDS),
                    "shutdown must not wait for a player-region teleport callback");
        } finally {
            releaseTeleport.countDown();
            ownerCallback.join(TimeUnit.SECONDS.toMillis(5));
            if (shutdown != null) shutdown.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertTrue(guard.getWaitingCount() == 0, "shutdown retires tracked transfer waits");
    }
}
