package noietime.syncmoney.listener;

import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Player quit listener.
 * Design principle: Non-blocking, no synchronous execution, relies on existing event queue for background writes.
 *
 * [MainThread] This listener runs on main thread, but only performs lightweight operations.
 */
public final class PlayerQuitListener implements Listener {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;

    public PlayerQuitListener(Plugin plugin, EconomyFacade economyFacade, NameResolver nameResolver) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.nameResolver = nameResolver;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        nameResolver.invalidate(name);

        var state = economyFacade.getMemoryState(uuid);
        if (state != null) {
            plugin.getLogger().fine("Player " + name + " quit: balance=" + state.balance() + " v" + state.version());
        }


        // plugin.getServer().getAsyncScheduler().runDelayed(...)
    }
}
