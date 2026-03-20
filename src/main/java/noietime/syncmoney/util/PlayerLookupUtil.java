package noietime.syncmoney.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

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
     * Falls back to synchronous execution on Paper/Purpur.
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
        if (ServerPlatformDetector.hasAsyncScheduler()) {
            Bukkit.getServer().getAsyncScheduler().runNow(plugin, task2 -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }
}
