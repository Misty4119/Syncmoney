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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** [EntityScheduler] Wait for economic writes before crossing worlds; never discard queued writes. */
public final class PlayerTransferGuard implements Listener {
    private final Plugin plugin;
    private final EconomyWriteQueue writeQueue;
    private final long maxWaitMs;
    private final ConcurrentMap<UUID, DeferredTransfer> waitingTransfers = new ConcurrentHashMap<>();
    private final Object lifecycleGate = new Object();
    private volatile boolean stopped;

    public PlayerTransferGuard(Plugin plugin, EconomyWriteQueue writeQueue) {
        this(plugin, writeQueue, Constants.MAX_WAIT_MS);
    }

    public PlayerTransferGuard(Plugin plugin, EconomyWriteQueue writeQueue, long maxWaitMs) {
        this.plugin = plugin;
        this.writeQueue = writeQueue;
        this.maxWaitMs = Math.max(1, maxWaitMs);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // A newer teleport supersedes an older deferred destination, including same-world teleports.
        cancelWait(uuid);
        if (stopped) return;
        Location destination = event.getTo();
        if (destination == null || event.getFrom().getWorld() == destination.getWorld()
                || !writeQueue.hasPending(uuid)) return;

        event.setCancelled(true);
        DeferredTransfer deferred = new DeferredTransfer(uuid, player, destination.clone(), event.getCause(),
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxWaitMs));
        synchronized (lifecycleGate) {
            if (stopped) return;
            waitingTransfers.put(uuid, deferred);
        }

        long intervalTicks = Math.max(1, Constants.CHECK_INTERVAL_MS / 50);
        ScheduledTask scheduled = player.getScheduler().runAtFixedRate(plugin,
                task -> checkDeferredTransfer(deferred, task),
                () -> retireDeferredTransfer(deferred), intervalTicks, intervalTicks);
        deferred.attach(scheduled);
    }

    private void checkDeferredTransfer(DeferredTransfer deferred, ScheduledTask task) {
        boolean cancelStaleTask = false;
        synchronized (lifecycleGate) {
            if (stopped || deferred.retired || waitingTransfers.get(deferred.uuid) != deferred) {
                deferred.retired = true;
                waitingTransfers.remove(deferred.uuid, deferred);
                cancelStaleTask = true;
            } else if (deferred.task == null) {
                deferred.task = task;
            } else if (deferred.task != task) {
                cancelStaleTask = true;
            }
        }
        if (cancelStaleTask) {
            task.cancel();
            return;
        }

        // Queue inspection is local state; do not hold a shutdown-shared gate across owner work.
        if (writeQueue.hasPending(deferred.uuid)) {
            if (System.nanoTime() >= deferred.deadlineNanos) {
                if (retireForTimeout(deferred, task)) {
                    plugin.getLogger().warning("Deferred teleport timed out for " + deferred.uuid
                            + "; economic writes remain queued. The player can retry the teleport.");
                }
            }
            return;
        }

        boolean shouldTeleport;
        synchronized (lifecycleGate) {
            shouldTeleport = !stopped && !deferred.retired
                    && waitingTransfers.get(deferred.uuid) == deferred;
            if (shouldTeleport) {
                deferred.retired = true;
                waitingTransfers.remove(deferred.uuid, deferred);
            }
        }
        if (shouldTeleport) {
            task.cancel();
            // The operation is claimed before shutdown can close admission; never join this future.
            deferred.player.teleportAsync(deferred.destination, deferred.cause);
        }
    }

    private boolean retireForTimeout(DeferredTransfer deferred, ScheduledTask task) {
        synchronized (lifecycleGate) {
            if (stopped || deferred.retired || waitingTransfers.get(deferred.uuid) != deferred) return false;
            deferred.retired = true;
            waitingTransfers.remove(deferred.uuid, deferred);
        }
        task.cancel();
        return true;
    }

    private void retireDeferredTransfer(DeferredTransfer deferred) {
        ScheduledTask task;
        synchronized (lifecycleGate) {
            deferred.retired = true;
            waitingTransfers.remove(deferred.uuid, deferred);
            task = deferred.task;
        }
        if (task != null) task.cancel();
    }

    private final class DeferredTransfer {
        private final UUID uuid;
        private final Player player;
        private final Location destination;
        private final PlayerTeleportEvent.TeleportCause cause;
        private final long deadlineNanos;
        private ScheduledTask task;
        private boolean retired;

        private DeferredTransfer(UUID uuid, Player player, Location destination,
                                 PlayerTeleportEvent.TeleportCause cause, long deadlineNanos) {
            this.uuid = uuid;
            this.player = player;
            this.destination = destination;
            this.cause = cause;
            this.deadlineNanos = deadlineNanos;
        }

        private void attach(ScheduledTask scheduled) {
            boolean cancel;
            synchronized (lifecycleGate) {
                cancel = scheduled == null || stopped || retired || waitingTransfers.get(uuid) != this;
                if (!cancel) task = scheduled;
                else {
                    retired = true;
                    waitingTransfers.remove(uuid, this);
                }
            }
            if (scheduled != null && cancel) scheduled.cancel();
        }
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
        DeferredTransfer deferred;
        synchronized (lifecycleGate) {
            deferred = waitingTransfers.remove(uuid);
            if (deferred != null) deferred.retired = true;
        }
        ScheduledTask task = deferred == null ? null : deferred.task;
        if (task != null) task.cancel();
    }

    public void shutdown() {
        List<ScheduledTask> tasks = new ArrayList<>();
        synchronized (lifecycleGate) {
            if (stopped) return;
            stopped = true;
            for (DeferredTransfer deferred : waitingTransfers.values()) {
                deferred.retired = true;
                if (deferred.task != null) tasks.add(deferred.task);
            }
            waitingTransfers.clear();
        }
        tasks.forEach(ScheduledTask::cancel);
    }

    public boolean canSafelyTransfer(UUID uuid) { return !writeQueue.hasPending(uuid); }
    public int getPendingTransactionCount(UUID uuid) { return writeQueue.getPendingCount(uuid); }
    public int getWaitingCount() { return waitingTransfers.size(); }
}
