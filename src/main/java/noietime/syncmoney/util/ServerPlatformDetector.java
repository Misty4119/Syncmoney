package noietime.syncmoney.util;

import org.bukkit.Bukkit;
import org.bukkit.Server;

/**
 * Server platform detection utility.
 * Supports accurate identification of Paper, Purpur, and Folia.
 */
public final class ServerPlatformDetector {

    public enum Platform {
        SPIGOT, PAPER, PURPUR, FOLIA
    }

    private static Platform cachedPlatform;

    public static Platform detect() {
        if (cachedPlatform != null) {
            return cachedPlatform;
        }

        Server server = Bukkit.getServer();
        String name = server.getName();

        if (name.contains("Folia")) {
            cachedPlatform = Platform.FOLIA;
        } else if (name.contains("Purpur")) {
            cachedPlatform = Platform.PURPUR;
        } else if (name.contains("Paper")) {
            cachedPlatform = Platform.PAPER;
        } else {
            cachedPlatform = Platform.SPIGOT;
        }

        return cachedPlatform;
    }

    public static boolean isFolia() {
        return detect() == Platform.FOLIA;
    }

    public static boolean hasAsyncScheduler() {
        try {
            Bukkit.getServer().getClass().getMethod("getAsyncScheduler");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
