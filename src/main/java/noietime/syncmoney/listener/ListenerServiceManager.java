package noietime.syncmoney.listener;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.EconomyServiceManager;
import noietime.syncmoney.guard.PlayerTransferGuard;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.uuid.NameResolver;

/**
 * Unified listener service manager.
 * Manages all event listener registration for the plugin.
 */
public class ListenerServiceManager {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final EconomyWriteQueue economyWriteQueue;
    private final EconomyServiceManager economyServiceManager;

    private PlayerJoinListener playerJoinListener;
    private PlayerQuitListener playerQuitListener;
    private PlayerTransferGuard playerTransferGuard;
    private CMIEconomyListener cmiListener;
    private EventListenerManager eventListenerManager;

    public ListenerServiceManager(Syncmoney plugin, EconomyServiceManager economyServiceManager) {
        this.plugin = plugin;
        this.config = plugin.getSyncmoneyConfig();
        this.economyFacade = economyServiceManager.getEconomyFacade();
        this.economyWriteQueue = economyServiceManager.getEconomyWriteQueue();
        this.economyServiceManager = economyServiceManager;
    }

    public void initialize() {
        if (config.isCMIMode()) {
            CMIEconomyHandler cmiHandler = economyServiceManager.getEconomyModeRouter().getCmiHandler();
            if (cmiHandler != null) {
                this.cmiListener = new CMIEconomyListener(plugin, cmiHandler, config);
                registerListener(cmiListener, "CMI Economy Listener");
            }
        }

        if (config.transferGuard().isTransferGuardEnabled()) {
            this.playerTransferGuard = new PlayerTransferGuard(
                    plugin,
                    economyWriteQueue);
            registerListener(playerTransferGuard, "Player Transfer Guard");
            plugin.getLogger().fine("Transfer guard enabled (max wait: "
                    + config.transferGuard().getTransferGuardMaxWaitMs() + "ms)");
        }

        NameResolver nameResolver = economyServiceManager.getNameResolver();
        BaltopManager baltopManager = plugin.getBaltopManager();

        if (economyFacade != null && nameResolver != null) {
            this.playerJoinListener = new PlayerJoinListener(plugin, economyFacade, nameResolver, baltopManager);
            registerListener(playerJoinListener, "Player Join Listener");

            this.playerQuitListener = new PlayerQuitListener(plugin, economyFacade, nameResolver);
            registerListener(playerQuitListener, "Player Quit Listener");
        }

        plugin.getLogger().fine("Listener layer initialized");
    }

    private void registerListener(org.bukkit.event.Listener listener, String name) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getLogger().fine(name + " registered");
    }

    public void shutdown() {
        plugin.getLogger().fine("Shutting down listener layer...");
        plugin.getLogger().fine("Listener layer shutdown complete");
    }

    public PlayerJoinListener getPlayerJoinListener() {
        return playerJoinListener;
    }

    public PlayerQuitListener getPlayerQuitListener() {
        return playerQuitListener;
    }

    public PlayerTransferGuard getPlayerTransferGuard() {
        return playerTransferGuard;
    }

    public CMIEconomyListener getCmiListener() {
        return cmiListener;
    }

    public EventListenerManager getEventListenerManager() {
        return eventListenerManager;
    }
}
