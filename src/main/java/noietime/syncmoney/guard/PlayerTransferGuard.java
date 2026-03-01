package noietime.syncmoney.guard;

import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.util.Constants;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Cross-server portal protection mechanism.
 * Prevents players from teleporting during economic transactions to avoid data inconsistency.
 *
 * [MainThread] This listener runs on main thread.
 */
public final class PlayerTransferGuard implements Listener {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final EconomyWriteQueue writeQueue;
    private final Logger logger;


    private final ConcurrentMap<UUID, TransferWait> waitingTransfers;


    private record TransferWait(UUID playerUuid, long startTime, PlayerTeleportEvent event) {}

    public PlayerTransferGuard(Plugin plugin, EconomyFacade economyFacade, EconomyWriteQueue writeQueue) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.writeQueue = writeQueue;
        this.logger = plugin.getLogger();
        this.waitingTransfers = new ConcurrentHashMap<>();
    }

    /**
     * Intercept teleport event, check if there are pending economic transactions.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!writeQueue.hasPending(uuid)) {
            return;
        }

        TransferWait wait = new TransferWait(uuid, System.currentTimeMillis(), event);
        waitingTransfers.put(uuid, wait);

        event.setCancelled(true);

        AtomicInteger waited = new AtomicInteger(0);

        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, (task) -> {
            if (!writeQueue.hasPending(uuid)) {
                waitingTransfers.remove(uuid);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, teleportTask -> {
                    player.teleport(event.getTo());
                });
                logger.fine("Player " + player.getName() + " transferred after pending transactions completed.");
                task.cancel();
                return;
            }

            int currentWaited = waited.addAndGet(Constants.CHECK_INTERVAL_MS);

            if (currentWaited >= Constants.MAX_WAIT_MS) {
                waitingTransfers.remove(uuid);
                logger.warning("Player " + player.getName() + " transfer forced after " + Constants.MAX_WAIT_MS + "ms wait (pending: " + writeQueue.getPendingCount(uuid) + " events)");
                plugin.getServer().getGlobalRegionScheduler().run(plugin, teleportTask -> {
                    player.teleport(event.getTo());
                });
                task.cancel();
            }
        }, Constants.CHECK_INTERVAL_MS, Constants.CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Handle player quit event, clear wait record and handle pending transactions.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (writeQueue.hasPending(uuid)) {
            logger.info("Player " + event.getPlayer().getName() + " is quitting with pending transactions. Attempting to drain...");

            int drained = writeQueue.drainPendingForPlayer(uuid, 10);

            if (drained > 0) {
                logger.info("Drained " + drained + " pending events for " + event.getPlayer().getName());
            }

            if (writeQueue.hasPending(uuid)) {
                long startWait = System.currentTimeMillis();
                while (writeQueue.hasPending(uuid) && (System.currentTimeMillis() - startWait) < 200) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (writeQueue.hasPending(uuid)) {
                    logger.warning("Player " + event.getPlayer().getName() + " quit with " +
                        writeQueue.getPendingCount(uuid) + " pending transactions (data already saved to Redis).");
                } else {
                    logger.info("Player " + event.getPlayer().getName() + " pending transactions cleared.");
                }
            } else {
                logger.info("Player " + event.getPlayer().getName() + " pending transactions drained successfully.");
            }
        }

        waitingTransfers.remove(uuid);
    }

    /**
     * Handle player kick event, clear wait record.
     */
    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        waitingTransfers.remove(uuid);
    }

    /**
     * Check if player can safely transfer (no pending transactions).
     * @param uuid Player UUID
     * @return true if can safely transfer
     */
    public boolean canSafelyTransfer(UUID uuid) {
        return !writeQueue.hasPending(uuid);
    }

    /**
     * Get player's pending transaction count.
     * @param uuid Player UUID
     * @return Pending transaction count
     */
    public int getPendingTransactionCount(UUID uuid) {
        return writeQueue.getPendingCount(uuid);
    }

    /**
     * Get number of players waiting to transfer.
     * @return Count
     */
    public int getWaitingCount() {
        return waitingTransfers.size();
    }
}
