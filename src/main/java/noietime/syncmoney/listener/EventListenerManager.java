package noietime.syncmoney.listener;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.uuid.NameResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages event listener registration for the plugin.
 * Centralizes all listener registration logic.
 */
public class EventListenerManager {

    private final Syncmoney plugin;
    private final List<org.bukkit.event.Listener> registeredListeners = new CopyOnWriteArrayList<>();

    public EventListenerManager(Syncmoney plugin) {
        this.plugin = plugin;
    }

    /**
     * Register all event listeners.
     */
    public void registerAll() {
        EconomyFacade economyFacade = plugin.getEconomyFacade();
        BaltopManager baltopManager = plugin.getBaltopManager();
        NameResolver nameResolver = plugin.getNameResolver();

        if (economyFacade != null && nameResolver != null) {
            PlayerJoinListener joinListener = new PlayerJoinListener(plugin, economyFacade, nameResolver, baltopManager);
            register(joinListener);
        }

        if (economyFacade != null && nameResolver != null) {
            PlayerQuitListener quitListener = new PlayerQuitListener(plugin, economyFacade, nameResolver);
            register(quitListener);
        }

        plugin.getLogger().fine("All event listeners registered via EventListenerManager.");
    }

    /**
     * Register a single listener.
     */
    private void register(org.bukkit.event.Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        registeredListeners.add(listener);
        plugin.getLogger().fine("Listener registered: " + listener.getClass().getSimpleName());
    }

    /**
     * Get all registered listeners.
     */
    public List<org.bukkit.event.Listener> getRegisteredListeners() {
        return registeredListeners;
    }
}
