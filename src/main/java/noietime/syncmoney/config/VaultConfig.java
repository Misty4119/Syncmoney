package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * [SYNC-CONFIG] Vault intercept configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class VaultConfig {

    private final FileConfiguration config;

    public VaultConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-154] Whether Vault intercept is enabled.
     */
    public boolean isVaultInterceptEnabled() {
        return config.getBoolean("economy.sync.vault-intercept", true);
    }

    /**
     * [SYNC-CONFIG-154] Whether Vault intercept handles deposit operations.
     */
    public boolean isVaultInterceptDeposit() {
        return config.getBoolean("economy.sync.vault-intercept-deposit", true);
    }

    /**
     * [SYNC-CONFIG-154] Whether Vault intercept handles withdraw operations.
     */
    public boolean isVaultInterceptWithdraw() {
        return config.getBoolean("economy.sync.vault-intercept-withdraw", true);
    }

    /**
     * [SYNC-CONFIG-154] Gets the source plugin whitelist for Vault intercept.
     */
    public List<String> getVaultSourceWhitelist() {
        return config.getStringList("economy.sync.vault-whitelist");
    }

    /**
     * [SYNC-CONFIG-154] Gets commands that should be ignored by Vault intercept.
     */
    public List<String> getVaultIgnoreCommands() {
        return config.getStringList("economy.sync.ignore-commands");
    }

    /**
     * [SYNC-CONFIG-154] Gets known plugin classes for economy detection.
     */
    public List<String> getKnownPluginClasses() {
        return config.getStringList("economy.sync.known-plugin-classes");
    }
}
