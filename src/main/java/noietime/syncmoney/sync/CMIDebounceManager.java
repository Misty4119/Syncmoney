package noietime.syncmoney.sync;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<UUID, BukkitTask> pendingTasks;

    public CMIDebounceManager(Plugin plugin, int debounceTicks) {
        this.plugin = plugin;
        this.debounceTicks = debounceTicks;
        this.pendingTasks = new ConcurrentHashMap<>();
    }

    /**
     * Schedule a debounced action.
     * If a task already exists for the UUID, it will be cancelled and replaced.
     */
    public void scheduleDebounced(UUID uuid, Runnable action) {
        BukkitTask existing = pendingTasks.get(uuid);
        if (existing != null) {
            existing.cancel();
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> {
                pendingTasks.remove(uuid);
                action.run();
            },
            debounceTicks
        );

        pendingTasks.put(uuid, task);
    }

    /**
     * Schedule a debounced action with parameter.
     */
    public void scheduleDebounced(UUID uuid, Consumer<UUID> action) {
        BukkitTask existing = pendingTasks.get(uuid);
        if (existing != null) {
            existing.cancel();
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> {
                pendingTasks.remove(uuid);
                action.accept(uuid);
            },
            debounceTicks
        );

        pendingTasks.put(uuid, task);
    }

    /**
     * Cancel a specific player's pending task.
     */
    public void cancel(UUID uuid) {
        BukkitTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Cancel all pending tasks.
     */
    public void cancelAll() {
        pendingTasks.values().forEach(BukkitTask::cancel);
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
