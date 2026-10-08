package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QueueAdmissionFailureTest {

    @Test
    void fullQueueAndUnavailableOverflowRejectBeforeMutation() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("QueueAdmissionFailureTest"));
        RedisManager redisManager = mock(RedisManager.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(1, Logger.getLogger("QueueAdmissionFailureTest"));
        EconomyEvent queuedWork = new EconomyEvent(account, BigDecimal.ONE, new BigDecimal("101.00"), 8L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "already-queued", 1L);
        assertTrue(queue.offerBlocking(queuedWork, 1, TimeUnit.SECONDS));

        when(redisManager.getResource()).thenThrow(new IllegalStateException("Redis unavailable"));
        RedisOverflowLog overflowLog = new RedisOverflowLog(plugin, redisManager);
        TransactionWriter writer = new TransactionWriter(facade, plugin, mock(SyncmoneyConfig.class), null,
                queue, new FallbackEconomyWrapper(plugin, redisManager), overflowLog,
                state, new AtomicLong(), false);

        SyncmoneyEventBus bus = eventBus(plugin);
        List<PostTransactionEvent> posts = new ArrayList<>();
        SyncmoneyListener<PostTransactionEvent> observer = posts::add;
        bus.register(PostTransactionEvent.class, observer);
        try {
            BigDecimal result = writer.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);

            AccountState after = state.get(account);
            assertAll(
                    () -> assertEquals(BigDecimal.valueOf(-1), result,
                            "an event with no queue admission or overflow record must be rejected"),
                    () -> assertEquals(new BigDecimal("100.00"), after.balance(),
                            "failed admission must leave the account balance unchanged"),
                    () -> assertEquals(7L, after.version(),
                            "failed admission must leave the account version unchanged"),
                    () -> assertTrue(posts.stream().noneMatch(event ->
                                    event.getAmount().compareTo(new BigDecimal("10.00")) == 0 && event.isSuccess()),
                            "failed admission must not emit a success event"),
                    () -> assertEquals(1, queue.size(), "the previously accepted queue entry must remain intact"),
                    () -> assertEquals(0, overflowLog.getSize(),
                            "the configured Redis overflow writer has no recovery record when Redis is unavailable")
            );
        } finally {
            bus.unregister(PostTransactionEvent.class, observer);
        }
    }

    private static SyncmoneyEventBus eventBus(Plugin plugin) {
        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            Logger logger = plugin.getLogger();
            when(busPlugin.getLogger()).thenReturn(logger);
            SyncmoneyEventBus.init(busPlugin);
        }
        return SyncmoneyEventBus.getInstance();
    }
}
