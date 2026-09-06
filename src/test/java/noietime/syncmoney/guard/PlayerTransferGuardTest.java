package noietime.syncmoney.guard;

import noietime.syncmoney.economy.EconomyWriteQueue;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class PlayerTransferGuardTest {
    @Test
    void quittingNeverDiscardsUnconsumedEconomicWrites() {
        Plugin plugin = mock(Plugin.class);
        EconomyWriteQueue queue = mock(EconomyWriteQueue.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        new PlayerTransferGuard(plugin, queue).onPlayerQuit(event);
        verifyNoInteractions(queue);
    }
}
