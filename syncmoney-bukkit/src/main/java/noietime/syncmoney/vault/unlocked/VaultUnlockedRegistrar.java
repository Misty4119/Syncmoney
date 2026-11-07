package noietime.syncmoney.vault.unlocked;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault2.economy.Economy;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [SYNC-VAULT2-002] Registers Syncmoney as the modern VaultUnlocked economy provider.
 */
public final class VaultUnlockedRegistrar implements AutoCloseable {

    private final Plugin plugin;
    private final SyncmoneyVaultUnlockedProvider provider;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object registrationGate = new Object();
    private volatile ScheduledTask delayedRegistrationTask;

    public VaultUnlockedRegistrar(Plugin plugin, EconomyFacade economyFacade,
            NameResolver nameResolver, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.provider = new SyncmoneyVaultUnlockedProvider(plugin, economyFacade, nameResolver, config);
    }

    /**
     * [MainThread] Registers immediately and once more after startup ordering settles.
     */
    public boolean setupEconomy() {
        if (closed.get()) {
            return false;
        }
        if (!registerEconomy()) {
            return false;
        }

        scheduleDelayedRegistration();
        return true;
    }

    private boolean registerEconomy() {
        synchronized (registrationGate) {
            if (closed.get()) {
                return false;
            }
            try {
                var services = plugin.getServer().getServicesManager();
                var existing = services.getRegistration(Economy.class);
                if (closed.get()) {
                    return false;
                }
                if (existing != null && existing.getProvider() == provider) {
                    provider.setEnabled(true);
                    return true;
                }

                if (existing != null) {
                    services.unregister(Economy.class, existing.getProvider());
                    plugin.getLogger().fine("Unregistered existing VaultUnlocked Economy provider: "
                            + existing.getProvider().getName());
                }

                if (closed.get()) {
                    return false;
                }
                services.register(Economy.class, provider, plugin, ServicePriority.Highest);
                provider.setEnabled(true);
                return true;
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "VaultUnlocked Economy registration failed.", e);
                return false;
            }
        }
    }

    private void scheduleDelayedRegistration() {
        synchronized (registrationGate) {
            if (closed.get()) {
                return;
            }
            try {
                var server = plugin.getServer();
                ScheduledTask scheduled = server.getGlobalRegionScheduler()
                        .runDelayed(plugin, task -> registerEconomy(), 20L);
                delayedRegistrationTask = scheduled;
                if (closed.get() && scheduled != null) {
                    scheduled.cancel();
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().fine("Deferred VaultUnlocked registration skipped: " + e.getMessage());
            }
        }
    }

    /** Removes the provider and prevents a delayed startup task from restoring it. */
    @Override
    public void close() {
        synchronized (registrationGate) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            provider.setEnabled(false);
            ScheduledTask scheduled = delayedRegistrationTask;
            delayedRegistrationTask = null;
            if (scheduled != null) {
                try {
                    scheduled.cancel();
                } catch (Throwable failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to cancel delayed VaultUnlocked provider registration", failure);
                }
            }
            try {
                plugin.getServer().getServicesManager().unregister(Economy.class, provider);
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to unregister VaultUnlocked provider during shutdown", failure);
            } finally {
                provider.setEnabled(false);
            }
        }
    }
}
