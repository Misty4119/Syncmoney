package noietime.syncmoney.baltop;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaltopCommandTest {

    @Test
    void coldSelfBalanceUsesNonBlockingLoadAndReturnsNotReadyMessage() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BaltopCommandTest"));
        when(plugin.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        BaltopManager baltopManager = mock(BaltopManager.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.hasPermission("syncmoney.money")).thenReturn(true);
        when(baltopManager.getPlayerRank(uuid)).thenReturn(1);
        when(economyFacade.getBalanceForPlaceholder(uuid)).thenReturn(null);
        BaltopCommand command = new BaltopCommand(plugin, config, baltopManager, economyFacade);

        assertTrue(command.onCommand(player, null, "baltop", new String[]{"me"}));

        verify(economyFacade).getBalanceForPlaceholder(uuid);
        verify(economyFacade, never()).getBalance(uuid);
        verify(plugin).getMessage("baltop.self-balance-not-ready");
    }
}
