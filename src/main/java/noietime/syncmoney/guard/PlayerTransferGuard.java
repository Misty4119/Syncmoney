package noietime.syncmoney.guard;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.util.Constants;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** [EntityScheduler] Wait for economic writes before crossing worlds; never discard queued writes. */
public final class PlayerTransferGuard implements Listener {
    private final Plugin plugin;
    private final EconomyWriteQueue writeQueue;
    private final ConcurrentMap<UUID, ScheduledTask> waitingTransfers = new ConcurrentHashMap<>();

    public PlayerTransferGuard(Plugin plugin, EconomyWriteQueue writeQueue) {
        this.plugin = plugin;
        this.writeQueue = writeQueue;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // A newer teleport supersedes an older deferred destination, including same-world teleports.
        cancelWait(uuid);
        Location destination = event.getTo();
        if (destination == null || event.getFrom().getWorld() == destination.getWorld()
                || !writeQueue.hasPending(uuid)) return;

        event.setCancelled(true);
        Location target = destination.clone();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Constants.MAX_WAIT_MS);
        long intervalTicks = Math.max(1, Constants.CHECK_INTERVAL_MS / 50);
        ScheduledTask scheduled = player.getScheduler().runAtFixedRate(plugin, task -> {
            if (waitingTransfers.get(uuid) != task) {
                task.cancel();
                return;
            }
            if (!writeQueue.hasPending(uuid)) {
                waitingTransfers.remove(uuid, task);
                task.cancel();
                // Cross-region teleport must load/move asynchronously; never join this future.
                player.teleportAsync(target, cause);
            } else if (System.nanoTime() >= deadline) {
                waitingTransfers.remove(uuid, task);
                task.cancel();
                plugin.getLogger().warning("Deferred teleport timed out for " + uuid
                        + "; economic writes remain queued. The player can retry the teleport.");
            }
        }, () -> waitingTransfers.remove(uuid), intervalTicks, intervalTicks);
        if (scheduled != null) waitingTransfers.put(uuid, scheduled);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // The consumer owns queued events and pending counts, even after the player leaves.
        cancelWait(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        cancelWait(event.getPlayer().getUniqueId());
    }

    private void cancelWait(UUID uuid) {
        ScheduledTask task = waitingTransfers.remove(uuid);
        if (task != null) task.cancel();
    }

    public void shutdown() {
        waitingTransfers.values().forEach(ScheduledTask::cancel);
        waitingTransfers.clear();
    }

    public boolean canSafelyTransfer(UUID uuid) { return !writeQueue.hasPending(uuid); }
    public int getPendingTransactionCount(UUID uuid) { return writeQueue.getPendingCount(uuid); }
    public int getWaitingCount() { return waitingTransfers.size(); }
}
