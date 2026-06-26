package noietime.syncmoney.sync;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * CMI Debounce Manager.
 * Prevents redundant sync operations during rapid balance changes.
 *
 * [ThreadSafe] Uses ConcurrentHashMap for thread safety.
 */
public final class CMIDebounceManager {

    private final Plugin plugin;
    private final int debounceTicks;
    private final ConcurrentHashMap<UUID, ScheduledTask> pendingTasks;

    public CMIDebounceManager(Plugin plugin, int debounceTicks) {
        this.plugin = plugin;
        this.debounceTicks = debounceTicks;
        this.pendingTasks = new ConcurrentHashMap<>();
    }

    private long debounceDelayMs() {
        return Math.max(1L, debounceTicks * 50L);
    }

    public void scheduleDebounced(UUID uuid, Runnable action) {
        ScheduledTask existing = pendingTasks.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }

        ScheduledTask task = plugin.getServer().getAsyncScheduler().runDelayed(
            plugin,
            t -> {
                pendingTasks.remove(uuid);
                action.run();
            },
            debounceDelayMs(),
            TimeUnit.MILLISECONDS
        );

        pendingTasks.put(uuid, task);
    }

    public void scheduleDebounced(UUID uuid, Consumer<UUID> action) {
        ScheduledTask existing = pendingTasks.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }

        ScheduledTask task = plugin.getServer().getAsyncScheduler().runDelayed(
            plugin,
            t -> {
                pendingTasks.remove(uuid);
                action.accept(uuid);
            },
            debounceDelayMs(),
            TimeUnit.MILLISECONDS
        );

        pendingTasks.put(uuid, task);
    }

    /**
     * Cancel a specific player's pending task.
     */
    public void cancel(UUID uuid) {
        ScheduledTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Cancel all pending tasks.
     */
    public void cancelAll() {
        pendingTasks.values().forEach(ScheduledTask::cancel);
        pendingTasks.clear();
    }

    /**
     * Check if a player has a pending task.
     */
    public boolean hasPending(UUID uuid) {
        return pendingTasks.containsKey(uuid);
    }

    /**
     * Get the number of pending tasks.
     */
    public int getPendingCount() {
        return pendingTasks.size();
    }
}
