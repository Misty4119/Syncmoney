package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerShutdownTest {
    @Test
    void shutdownDrainsAcceptedWritesBeforeReturning() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        CacheManager cache = mock(CacheManager.class);
        UUID player = UUID.randomUUID();
        when(cache.atomicSetBalance(player, BigDecimal.TEN)).thenReturn(1L);
        EconomyWriteQueue queue = new EconomyWriteQueue(100);
        for (int i = 0; i < 20; i++) assertTrue(queue.offer(new EconomyEvent(player,
                BigDecimal.TEN, BigDecimal.TEN, i, EconomyEvent.EventType.SET_BALANCE,
                EconomyEvent.EventSource.TEST, "shutdown-" + i, System.currentTimeMillis())));
        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cache,
                null, null, null, null, null, null, null, null, null);
        consumer.start();
        consumer.shutdown();
        assertTrue(queue.isEmpty());
        verify(cache, times(20)).atomicSetBalance(player, BigDecimal.TEN);
    }
}
