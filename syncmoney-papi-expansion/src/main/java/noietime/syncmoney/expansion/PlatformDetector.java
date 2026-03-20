package noietime.syncmoney.expansion;

/**
 * [SYNC-PAPI-022] Platform detection utility for Folia vs Paper/Purpur.
 */
public final class PlatformDetector {

    private PlatformDetector() {}

    /**
     * [SYNC-PAPI-023] Check if the server is running Folia.
     */
    public static boolean isFolia() {
        var server = org.bukkit.Bukkit.getServer();
        return server != null && server.getName().contains("Folia");
    }
}
