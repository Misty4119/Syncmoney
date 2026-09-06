package noietime.syncmoney.vault.unlocked;

import net.milkbowl.vault2.economy.Economy;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.logging.Level;

/**
 * [SYNC-VAULT2-002] Registers Syncmoney as the modern VaultUnlocked economy provider.
 */
public final class VaultUnlockedRegistrar {

    private final Plugin plugin;
    private final SyncmoneyVaultUnlockedProvider provider;

    public VaultUnlockedRegistrar(Plugin plugin, EconomyFacade economyFacade,
            NameResolver nameResolver, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.provider = new SyncmoneyVaultUnlockedProvider(plugin, economyFacade, nameResolver, config);
    }

    /**
     * [MainThread] Registers immediately and once more after startup ordering settles.
     */
    public boolean setupEconomy() {
        if (!registerEconomy()) {
            return false;
        }

        scheduleDelayedRegistration();
        return true;
    }

    private boolean registerEconomy() {
        try {
            var services = plugin.getServer().getServicesManager();
            var existing = services.getRegistration(Economy.class);
            if (existing != null && existing.getProvider() == provider) {
                provider.setEnabled(true);
                return true;
            }

            if (existing != null) {
                services.unregister(Economy.class, existing.getProvider());
                plugin.getLogger().fine("Unregistered existing VaultUnlocked Economy provider: "
                        + existing.getProvider().getName());
            }

            services.register(Economy.class, provider, plugin, ServicePriority.Highest);
            provider.setEnabled(true);
            return true;
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "VaultUnlocked Economy registration failed.", e);
            return false;
        }
    }

    private void scheduleDelayedRegistration() {
        try {
            var server = plugin.getServer();
            server.getGlobalRegionScheduler().runDelayed(plugin, task -> registerEconomy(), 20L);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().fine("Deferred VaultUnlocked registration skipped: " + e.getMessage());
        }
    }
}
