package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MutationOutcomeTest {

    @Test
    void degradedSharedAuthorityRejectsNewMutationsWithoutEffects() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MutationOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("MutationOutcomeTest"));
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(true);
        TransactionWriter writer = new TransactionWriter(facade, plugin, mock(SyncmoneyConfig.class), null,
                queue, new FallbackEconomyWrapper(plugin, redisManager), null, state, new AtomicLong(), false);

        SyncmoneyEventBus bus = eventBus(plugin);
        List<AsyncPreTransactionEvent> preEvents = new ArrayList<>();
        List<PostTransactionEvent> postEvents = new ArrayList<>();
        SyncmoneyListener<AsyncPreTransactionEvent> preObserver = preEvents::add;
        SyncmoneyListener<PostTransactionEvent> postObserver = postEvents::add;
        bus.register(AsyncPreTransactionEvent.class, preObserver);
        bus.register(PostTransactionEvent.class, postObserver);
        try {
            BigDecimal deposit = writer.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.VAULT_DEPOSIT);
            BigDecimal withdraw = writer.withdraw(account, new BigDecimal("10.00"), EconomyEvent.EventSource.VAULT_WITHDRAW);
            BigDecimal set = writer.setBalance(account, new BigDecimal("200.00"), EconomyEvent.EventSource.ADMIN_SET);

            AccountState finalState = state.get(account);
            assertAll(
                    () -> assertEquals(0, deposit.compareTo(BigDecimal.valueOf(-1)), "degraded authority rejects new deposits"),
                    () -> assertEquals(0, withdraw.compareTo(BigDecimal.valueOf(-1)), "degraded authority rejects new withdrawals"),
                    () -> assertEquals(0, set.compareTo(BigDecimal.valueOf(-1)), "degraded authority rejects new SET operations"),
                    () -> assertEquals(0, finalState.balance().compareTo(new BigDecimal("100.00"))),
                    () -> assertEquals(7L, finalState.version()),
                    () -> assertEquals(0, queue.size(), "rejected operations enqueue no writes"),
                    () -> assertTrue(preEvents.isEmpty(), "rejected operations do not enter the pre-transaction gate"),
                    () -> assertTrue(postEvents.isEmpty(), "rejected operations do not emit success posts")
            );
        } finally {
            bus.unregister(AsyncPreTransactionEvent.class, preObserver);
            bus.unregister(PostTransactionEvent.class, postObserver);
        }
    }

    private static SyncmoneyEventBus eventBus(Plugin plugin) {
        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            when(busPlugin.getLogger()).thenReturn(Logger.getLogger("MutationOutcomeTest"));
            SyncmoneyEventBus.init(busPlugin);
        }
        return SyncmoneyEventBus.getInstance();
    }
}
