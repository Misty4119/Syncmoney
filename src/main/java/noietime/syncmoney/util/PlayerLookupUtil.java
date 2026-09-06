package noietime.syncmoney.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;

/**
 * Folia-compatible Player lookup utility class.
 */
public final class PlayerLookupUtil {

    private PlayerLookupUtil() {
    }

    /**
     * Safely retrieves player (Folia-compatible).
     */
    public static Player getPlayer(UUID uuid) {
        return Bukkit.getServer().getPlayer(uuid);
    }

    /**
     * Safely retrieves player (exact match).
     */
    public static Player getPlayerExact(String name) {
        return Bukkit.getServer().getPlayerExact(name);
    }

    /**
     * Executes task on EntityScheduler (Folia optimization).
     * Paper implements the same entity scheduler API as Folia.
     */
    public static void runOnPlayerScheduler(Plugin plugin, Player player, Runnable task) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getScheduler().run(plugin, task2 -> task.run(), null);
    }

    /**
     * Executes task asynchronously (Folia optimization).
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task2 -> task.run());
    }

    /** [GlobalScheduler -> EntityScheduler] Snapshot online players, then access each on its owner. */
    public static void forEachOnlinePlayer(Plugin plugin, Consumer<Player> action) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                runOnPlayerScheduler(plugin, player, () -> action.accept(player));
            }
        });
    }

    /** [ThreadSafe] Queue an online player's work without blocking the caller. */
    public static void runForPlayer(Plugin plugin, UUID uuid, Consumer<Player> action) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null) runOnPlayerScheduler(plugin, player, () -> action.accept(player));
    }
}
