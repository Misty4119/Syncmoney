package noietime.syncmoney.migration;

import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.*;

/**
 * CMI multi-server database merger.
 * Used to merge player balances from multiple CMI databases (Latest strategy).
 *
 * Merge strategy:
 * - Latest: Select balance from server with most recent last login time
 */
public final class CMIMultiServerMerger {

    private final Plugin plugin;
    private final Map<UUID, PlayerLatestBalance> latestBalances = new HashMap<>();

    /**
     * Player latest balance record.
     * @param uuid player UUID
     * @param playerName player name
     * @param balance balance
     * @param lastLogoffTime last logoff time
     */
    public record PlayerLatestBalance(UUID uuid, String playerName, BigDecimal balance, long lastLogoffTime) {}

    public CMIMultiServerMerger(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads and merges from multiple databases.
     * @param readers list of CMI database readers
     */
    public void mergeFromMultipleDatabases(List<CMIDatabaseReader> readers) {
        for (CMIDatabaseReader reader : readers) {
            try {
                int totalPlayers = reader.getTotalPlayerCount();
                int offset = 0;
                int batchSize = 100;

                plugin.getLogger().info("Reading from CMI database: " + reader.getTableName());

                while (offset < totalPlayers) {
                    List<CMIDatabaseReader.CMIPlayerData> players = reader.readPlayers(offset, batchSize);

                    for (CMIDatabaseReader.CMIPlayerData player : players) {
                        if (player.uuid() != null) {
                            updateIfNewer(player);
                        }
                    }

                    offset += batchSize;
                }

                plugin.getLogger().info("Finished reading from database, total players: " + latestBalances.size());

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to read from CMI database: " + e.getMessage());
            }
        }
    }

    /**
     * Updates if new data's last logoff time is newer.
     */
    private void updateIfNewer(CMIDatabaseReader.CMIPlayerData player) {
        UUID uuid = player.uuid();
        PlayerLatestBalance existing = latestBalances.get(uuid);

        if (existing == null) {
            latestBalances.put(uuid, new PlayerLatestBalance(
                    uuid,
                    player.playerName(),
                    player.balance(),
                    player.lastLogoffTime()
            ));
        } else if (player.lastLogoffTime() > existing.lastLogoffTime()) {
            latestBalances.put(uuid, new PlayerLatestBalance(
                    uuid,
                    player.playerName(),
                    player.balance(),
                    player.lastLogoffTime()
            ));
            plugin.getLogger().fine("Updated player " + player.playerName() +
                    " balance to " + player.balance() + " (lastLogoff: " + player.lastLogoffTime() + ")");
        }
    }

    /**
     * Gets merged player list.
     * @return list of player balances
     */
    public List<PlayerLatestBalance> getMergedPlayers() {
        return new ArrayList<>(latestBalances.values());
    }

    /**
     * Gets merged player count.
     * @return player count
     */
    public int getPlayerCount() {
        return latestBalances.size();
    }

    /**
     * Clears all data.
     */
    public void clear() {
        latestBalances.clear();
    }
}
