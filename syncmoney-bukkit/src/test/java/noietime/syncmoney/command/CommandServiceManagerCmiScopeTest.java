package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandServiceManagerCmiScopeTest {

    @Test
    void cmiModeRegistersOnlyVersionAndCreatesNoWalletCommandManagers() {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(true);
        CommandServiceManager manager = manager(plugin, config);

        manager.initialize();

        assertEquals(List.of("version"), manager.getSyncmoneyRouter().onTabComplete(
                mock(CommandSender.class), mock(Command.class), "syncmoney", new String[0]));
        assertNull(manager.getCooldownManager());
        assertNull(manager.getTransferLockManager());
    }

    @Test
    void cmiModeReloadDoesNotReadWalletCommandSettings() {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(true);
        CommandServiceManager manager = manager(plugin, config);
        manager.initialize();

        manager.reload(config);

        verify(config, never()).pay();
    }

    private static CommandServiceManager manager(Syncmoney plugin, SyncmoneyConfig config) {
        return new CommandServiceManager(plugin, config, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static Syncmoney plugin() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(CommandServiceManagerCmiScopeTest.class.getName()));
        return plugin;
    }
}
