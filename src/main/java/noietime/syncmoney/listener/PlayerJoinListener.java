package noietime.syncmoney.listener;

import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.FormatUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Player join listener.
 * Load strategy: Redis -> DB -> New player initialization
 *
 * [EntityScheduler] This listener runs on main thread, but database operations should use AsyncScheduler.
 */
public final class PlayerJoinListener implements Listener {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;
    private final BaltopManager baltopManager;

    public PlayerJoinListener(Plugin plugin, EconomyFacade economyFacade,
            NameResolver nameResolver, BaltopManager baltopManager) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.nameResolver = nameResolver;
        this.baltopManager = baltopManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        nameResolver.cacheName(name, uuid);

        player.getScheduler().run(plugin, task -> {
            loadPlayerData(uuid, name);
        }, null);
    }

    /**
     * Load player balance data.
     * Priority: Redis → DB → New player
     */
    private void loadPlayerData(UUID uuid, String name) {
        try {
            BigDecimal balance = economyFacade.getBalance(uuid);

            if (baltopManager != null) {
                baltopManager.updatePlayerRank(uuid, balance.doubleValue());
            }

            plugin.getLogger()
                    .info("Player " + name + " data warm-up completed: balance=" + FormatUtil.formatCurrency(balance));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to pre-load player data for " + name + ": " + e.getMessage());
        }
    }
}
