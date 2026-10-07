package noietime.syncmoney.economy;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TransferCompensationTest {

    @Test
    void receiverCancellationRestoresDurableSender() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferCompensationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(false);
        RedisManager redisManager = mock(RedisManager.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransferCompensationTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(sender, new EconomyFacade.EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        state.put(receiver, new EconomyFacade.EconomyState(BigDecimal.ZERO, 3L, System.currentTimeMillis()));

        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.resolvePlayerName(sender)).thenReturn("sender");
        when(facade.resolvePlayerName(receiver)).thenReturn("receiver");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        when(facade.getBalance(sender)).thenAnswer(invocation -> state.get(sender).balance());
        when(facade.getBalance(receiver)).thenAnswer(invocation -> state.get(receiver).balance());

        FallbackEconomyWrapper fallback = new FallbackEconomyWrapper(plugin, redisManager);
        TransactionWriter writer = new TransactionWriter(
                facade, plugin, config, null, queue, fallback, mock(OverflowLogInterface.class),
                state, new AtomicLong(), false);
        doAnswer(invocation -> writer.withdraw(sender, amount, EconomyEvent.EventSource.TEST))
                .when(facade).withdraw(eq(sender), eq(amount), eq(EconomyEvent.EventSource.TEST));
        doAnswer(invocation -> writer.deposit(receiver, amount, EconomyEvent.EventSource.TEST))
                .when(facade).deposit(eq(receiver), eq(amount), eq(EconomyEvent.EventSource.TEST));

        SyncmoneyEventBus eventBus = eventBus(plugin);
        SyncmoneyListener<AsyncPreTransactionEvent> cancelReceiverDeposit = event -> {
            if (receiver.equals(event.getPlayerUuid())
                    && event.getType() == AsyncPreTransactionEvent.TransactionType.DEPOSIT) {
                event.setCancelled(true, "receiver declined");
            }
        };
        eventBus.register(AsyncPreTransactionEvent.class, cancelReceiverDeposit);

        CacheManager cacheManager = mock(CacheManager.class);
        Map<UUID, BigDecimal> durableBalances = new ConcurrentHashMap<>(Map.of(
                sender, new BigDecimal("100.00"), receiver, BigDecimal.ZERO));
        AtomicReference<CountDownLatch> durableWrites = new AtomicReference<>();
        when(cacheManager.atomicAddBalanceReceipt(any(UUID.class), any(BigDecimal.class))).thenAnswer(invocation -> {
            UUID uuid = invocation.getArgument(0);
            BigDecimal delta = invocation.getArgument(1);
            BigDecimal after = durableBalances.compute(uuid,
                    (key, current) -> (current == null ? BigDecimal.ZERO : current).add(delta));
            CountDownLatch writes = durableWrites.get();
            if (writes != null) writes.countDown();
            return new ConfirmedAccount(uuid, after, 8L);
        });

        EconomyEventConsumer consumer = new EconomyEventConsumer(
                plugin, config, queue, cacheManager, redisManager, null,
                null, null, null, null, null, null, null);
        try {
            TransferOrchestrator orchestrator = new TransferOrchestrator(
                    facade, plugin, config, cacheManager, state);
            BigDecimal transferResult = orchestrator.atomicTransfer(
                    sender, receiver, amount, EconomyEvent.EventSource.TEST);

            assertEquals(0, state.get(sender).balance().compareTo(new BigDecimal("100.00")),
                    "compensation restores the sender's memory projection");
            assertEquals(0, state.get(receiver).balance().compareTo(BigDecimal.ZERO),
                    "the cancelled receiver deposit must not mutate memory");
            assertEquals(1, queue.size(), "the accepted sender withdrawal remains queued");

            durableWrites.set(new CountDownLatch(1));
            consumer.start();
            assertTrue(durableWrites.get().await(5, TimeUnit.SECONDS),
                    "the consumer should apply the accepted sender withdrawal");
            consumer.stop();
            consumer.shutdown();

            when(cacheManager.getBalance(sender)).thenReturn(durableBalances.get(sender));
            when(cacheManager.getBalance(receiver)).thenReturn(durableBalances.get(receiver));
            when(cacheManager.getVersion(any(UUID.class))).thenReturn(0L);
            EconomyFacade reloaded = new EconomyFacade(
                    plugin, config, cacheManager, redisManager, null, null,
                    new EconomyWriteQueue(10), fallback, null, mock(OverflowLogInterface.class));
            try {
                BigDecimal reloadedSender = reloaded.getBalance(sender);
                BigDecimal reloadedReceiver = reloaded.getBalance(receiver);
                assertAll(
                        () -> assertEquals(-1, transferResult.compareTo(BigDecimal.ZERO),
                                "receiver cancellation should reject the transfer"),
                        () -> assertEquals(new BigDecimal("100.00"), reloadedSender,
                                "reloading should restore the sender's original balance"),
                        () -> assertEquals(0, reloadedReceiver.compareTo(BigDecimal.ZERO)),
                        () -> assertEquals(new BigDecimal("100.00"), reloadedSender.add(reloadedReceiver),
                                "the transfer must preserve durable total supply")
                );
            } finally {
                reloaded.shutdown();
            }
        } finally {
            consumer.stop();
            consumer.shutdown();
            eventBus.unregister(AsyncPreTransactionEvent.class, cancelReceiverDeposit);
        }
    }

    @Test
    void throwingPostObserverDoesNotAbortAcceptedTransfer() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferCompensationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isPubsubEnabled()).thenReturn(false);
        when(config.isDbEnabled()).thenReturn(false);
        RedisManager redisManager = mock(RedisManager.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransferCompensationTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(sender, new EconomyFacade.EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        state.put(receiver, new EconomyFacade.EconomyState(BigDecimal.ZERO, 3L, System.currentTimeMillis()));

        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.resolvePlayerName(sender)).thenReturn("sender");
        when(facade.resolvePlayerName(receiver)).thenReturn("receiver");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        when(facade.getBalance(sender)).thenAnswer(invocation -> state.get(sender).balance());
        when(facade.getBalance(receiver)).thenAnswer(invocation -> state.get(receiver).balance());

        TransactionWriter writer = new TransactionWriter(
                facade, plugin, config, null, queue, new FallbackEconomyWrapper(plugin, redisManager),
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);
        doAnswer(invocation -> writer.withdraw(sender, amount, EconomyEvent.EventSource.TEST))
                .when(facade).withdraw(eq(sender), eq(amount), eq(EconomyEvent.EventSource.TEST));
        doAnswer(invocation -> writer.deposit(receiver, amount, EconomyEvent.EventSource.TEST))
                .when(facade).deposit(eq(receiver), eq(amount), eq(EconomyEvent.EventSource.TEST));

        SyncmoneyEventBus eventBus = eventBus(plugin);
        SyncmoneyListener<PostTransactionEvent> throwingObserver = event -> {
            if (receiver.equals(event.getPlayerUuid())
                    && event.getType() == AsyncPreTransactionEvent.TransactionType.DEPOSIT) {
                throw new IllegalStateException("observer failure");
            }
        };
        eventBus.register(PostTransactionEvent.class, throwingObserver);
        try {
            TransferOrchestrator orchestrator = new TransferOrchestrator(
                    facade, plugin, config, mock(CacheManager.class), state);
            BigDecimal result = orchestrator.atomicTransfer(sender, receiver, amount, EconomyEvent.EventSource.TEST);
            List<EconomyEvent> events = new java.util.ArrayList<>();
            EconomyEvent event;
            while ((event = queue.poll(0, TimeUnit.MILLISECONDS)) != null) events.add(event);

            assertAll(
                    () -> assertEquals(0, result.compareTo(new BigDecimal("90.00")),
                            "a runtime exception from a post observer is logged and does not reject the transfer"),
                    () -> assertEquals(0, state.get(sender).balance().compareTo(new BigDecimal("90.00"))),
                    () -> assertEquals(0, state.get(receiver).balance().compareTo(new BigDecimal("10.00"))),
                    () -> assertEquals(2, events.size(), "both accepted transfer legs remain queued"),
                    () -> assertEquals(0, events.stream().map(EconomyEvent::delta)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ZERO),
                            "the accepted pair preserves total funds")
            );
        } finally {
            eventBus.unregister(PostTransactionEvent.class, throwingObserver);
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
