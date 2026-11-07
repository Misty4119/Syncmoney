package noietime.syncmoney.util;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerLookupUtilTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void defersPlayerAccessToTheEntityOwner() {
        Plugin plugin = mock(Plugin.class);
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        Runnable action = mock(Runnable.class);
        PlayerLookupUtil.runOnPlayerScheduler(plugin, player, action);
        verifyNoInteractions(action);
        ArgumentCaptor<Consumer<ScheduledTask>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), callback.capture(), isNull());
        verify(player, never()).isOnline();
        callback.getValue().accept(mock(ScheduledTask.class));
        verify(action).run();
    }

    @Test
    void missingPlayersAreSkippedAndOtherPlayersAreRoutedToTheirOwner() {
        Plugin plugin = mock(Plugin.class);
        Runnable action = mock(Runnable.class);
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        PlayerLookupUtil.runOnPlayerScheduler(plugin, null, action);
        PlayerLookupUtil.runOnPlayerScheduler(plugin, player, action);
        verifyNoInteractions(action);
        verify(player, never()).isOnline();
        verify(scheduler).run(eq(plugin), any(), isNull());
    }
}
