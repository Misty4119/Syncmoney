package noietime.syncmoney.vault;

import noietime.syncmoney.config.SyncmoneyConfig;

import java.util.List;

/**
 * [SYNC-VAULT-002] Detects the calling plugin using StackWalker.
 * Helps track which plugin initiated a Vault economy operation.
 */
public class VaultPluginDetector {

    private final SyncmoneyConfig config;

    public VaultPluginDetector(SyncmoneyConfig config) {
        this.config = config;
    }

    /**
     * [SYNC-VAULT-002] Detects the calling plugin (using StackWalker).
     */
    public String detectCallingPlugin() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(stream -> stream
                        .map(StackWalker.StackFrame::getClassName)
                        .filter(this::isKnownPluginClass)
                        .findFirst()
                        .map(this::extractPluginName)
                        .orElse("Unknown"));
    }

    /**
     * [SYNC-VAULT-002] Checks if the class is a known plugin class.
     * Uses config for plugin class list.
     */
    public boolean isKnownPluginClass(String className) {
        List<String> knownClasses = config.vault().getKnownPluginClasses();
        if (knownClasses == null || knownClasses.isEmpty()) {
            knownClasses = List.of(
                    "QuickShop-Hikari", "PlayerPoints", "Jobs", "DailyShop",
                    "CrateReloaded", "zAuctionHouse", "BankSystem", "TokenManager");
        }
        return knownClasses.stream().anyMatch(className::contains);
    }

    /**
     * Extracts plugin name from class name.
     */
    public String extractPluginName(String className) {
        String[] parts = className.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return "Unknown";
    }
}
