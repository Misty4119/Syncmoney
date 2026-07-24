package noietime.syncmoney.vault;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * [SYNC-VAULT-021] Loads the VaultUnlocked-specific registrar without linking
 * Vault2 classes on servers that only provide legacy Vault.
 */
public final class VaultUnlockedIntegrationLoader {

    private static final String REGISTRAR_CLASS =
            "noietime.syncmoney.vault.unlocked.VaultUnlockedRegistrar";

    private VaultUnlockedIntegrationLoader() {
    }

    /**
     * [MainThread] Registers Syncmoney's Vault2 provider when VaultUnlocked is available.
     *
     * @return the registrar instance when registration succeeds, otherwise {@code null}
     */
    public static Object register(Plugin plugin, EconomyFacade economyFacade,
            NameResolver nameResolver, SyncmoneyConfig config) {
        try {
            Class<?> registrarType = Class.forName(REGISTRAR_CLASS, true, plugin.getClass().getClassLoader());
            Constructor<?> constructor = registrarType.getConstructor(
                    Plugin.class, EconomyFacade.class, NameResolver.class, SyncmoneyConfig.class);
            Object registrar = constructor.newInstance(plugin, economyFacade, nameResolver, config);
            Method setup = registrarType.getMethod("setupEconomy");
            return Boolean.TRUE.equals(setup.invoke(registrar)) ? registrar : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING,
                    "VaultUnlocked was detected, but its Vault2 provider could not be registered.", e);
            return null;
        }
    }
}
