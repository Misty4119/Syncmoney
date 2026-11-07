package noietime.syncmoney.vault;

import org.bukkit.plugin.Plugin;

/**
 * [SYNC-VAULT-020] Detects which API generation is supplied by the installed
 * plugin named {@code Vault}.
 *
 * <p>VaultUnlocked deliberately keeps the Bukkit plugin name {@code Vault} for
 * drop-in compatibility, so plugin-name checks cannot distinguish it from
 * legacy Vault. The modern API class is the capability boundary.
 */
public final class VaultRuntimeDetector {

    static final String VAULT_PLUGIN_NAME = "Vault";
    static final String VAULT2_ECONOMY_CLASS = "net.milkbowl.vault2.economy.Economy";

    public enum Runtime {
        NONE,
        VAULT,
        VAULT_UNLOCKED
    }

    private VaultRuntimeDetector() {
    }

    /**
     * [MainThread] Detects the installed Vault-compatible runtime.
     */
    public static Runtime detect(Plugin plugin) {
        if (plugin == null || plugin.getServer() == null) {
            return Runtime.NONE;
        }

        Plugin vault = plugin.getServer().getPluginManager().getPlugin(VAULT_PLUGIN_NAME);
        if (vault == null || !vault.isEnabled()) {
            return Runtime.NONE;
        }

        return hasVault2(vault.getClass().getClassLoader())
                ? Runtime.VAULT_UNLOCKED
                : Runtime.VAULT;
    }

    static boolean hasVault2(ClassLoader classLoader) {
        try {
            Class.forName(VAULT2_ECONOMY_CLASS, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
