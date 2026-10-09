package noietime.syncmoney.economy;
import noietime.syncmoney.core.state.AccountState;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

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
    void rejectedTransferToAbsentReceiverResolvesWithoutCreatingZeroAccount() {
        UUID sender = UUID.randomUUID();
        UUID absentReceiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferCompensationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(sender, new AccountState(new BigDecimal("5.00"), 1L,
                System.currentTimeMillis()));
        state.computeIfAbsentUnverified(absentReceiver,
                uuid -> new AccountState(BigDecimal.ZERO, 0L, System.currentTimeMillis()));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, absentReceiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.getCircuitBreaker()).thenReturn(null);

        RedisManager redis = mock(RedisManager.class);
        CacheManager cache = spy(new CacheManager(plugin, redis, false));
        doReturn(CacheManager.TransferResult.insufficientFunds()).when(cache)
                .atomicTransfer(sender, absentReceiver, amount);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.mget("syncmoney:balance:" + sender, "syncmoney:version:" + sender,
                "syncmoney:balance:" + absentReceiver, "syncmoney:version:" + absentReceiver))
                .thenReturn(java.util.Arrays.asList("5.00", "1", null, null));
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, cache, state, null);

        CacheManager.TransferResult result = orchestrator.executeAtomicTransfer(sender, absentReceiver, amount);

        assertAll(
                () -> assertSame(CacheManager.TransferResult.insufficientFunds(), result),
                () -> assertFalse(state.isUnavailable(sender), "the rejected operation must release the sender"),
                () -> assertFalse(state.isUnavailable(absentReceiver), "the rejected operation must release the absent receiver"),
                () -> assertEquals(new BigDecimal("5.00"), state.get(sender).balance()),
                () -> assertNull(state.get(absentReceiver), "absence must remove the synthetic legacy zero"),
                () -> assertNull(state.getConfirmedStateForApi(absentReceiver),
                        "absence must remain a cached API miss")
        );
    }

    @Test
    void playerGuardCallbacksRunBeforePairedAccountLocks() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferCompensationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(4, Logger.getLogger("TransferCompensationTest"));
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(sender, new AccountState(new BigDecimal("100.00"), 7L,
                System.currentTimeMillis()));
        state.put(receiver, new AccountState(BigDecimal.ZERO, 3L,
                System.currentTimeMillis()));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.resolvePlayerName(sender)).thenReturn("sender");
        when(facade.resolvePlayerName(receiver)).thenReturn("receiver");
        PlayerTransactionGuard guard = mock(PlayerTransactionGuard.class);
        when(facade.getPlayerTransactionGuard()).thenReturn(guard);
        java.util.concurrent.atomic.AtomicInteger guardCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(guard.checkTransaction(any(UUID.class), any(BigDecimal.class), any(BigDecimal.class),
                any(EconomyEvent.EventType.class), any(EconomyEvent.EventSource.class)))
                .thenAnswer(invocation -> {
                    guardCalls.incrementAndGet();
                    CountDownLatch stateMutationCompleted = new CountDownLatch(1);
                    Thread probe = new Thread(() -> {
                        state.compute(sender, (uuid, current) -> current);
                        stateMutationCompleted.countDown();
                    }, "transfer-guard-lock-probe");
                    probe.start();
                    assertTrue(stateMutationCompleted.await(1, TimeUnit.SECONDS),
                            "guard callbacks must not run while paired account locks are held");
                    probe.join(1_000);
                    return PlayerTransactionGuard.CheckResult.ALLOWED;
                });

        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)),
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, mock(CacheManager.class), state, writer);

        BigDecimal result = orchestrator.atomicTransfer(sender, receiver,
                new BigDecimal("10.00"), EconomyEvent.EventSource.TEST);

        assertAll(
                () -> assertEquals(new BigDecimal("90.00"), result),
                () -> assertEquals(2, guardCalls.get()),
                () -> assertEquals(new BigDecimal("90.00"), state.get(sender).balance()),
                () -> assertEquals(new BigDecimal("10.00"), state.get(receiver).balance())
        );
    }

    @Test
    void pluginAtomicTransferDoesNotHoldMemoryAccountLocksDuringRedisCall() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferCompensationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(4, Logger.getLogger("TransferCompensationTest"));
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(sender, new AccountState(new BigDecimal("100.00"), 7L,
                System.currentTimeMillis()));
        state.put(receiver, new AccountState(BigDecimal.ZERO, 3L,
                System.currentTimeMillis()));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.getCircuitBreaker()).thenReturn(null);
        CacheManager cacheManager = mock(CacheManager.class);
        CountDownLatch redisEntered = new CountDownLatch(1);
        CountDownLatch releaseRedis = new CountDownLatch(1);
        when(cacheManager.atomicTransfer(sender, receiver, new BigDecimal("10.00"))).thenAnswer(invocation -> {
            redisEntered.countDown();
            if (!releaseRedis.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release Redis transfer");
            }
            return new CacheManager.TransferResult(new BigDecimal("90.00"),
                    new BigDecimal("10.00"), 8L, 4L);
        });
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, cacheManager, state, null);

        AtomicReference<CacheManager.TransferResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread transferThread = new Thread(() -> {
            try {
                result.set(orchestrator.pluginAtomicTransfer(sender, receiver,
                        new BigDecimal("10.00"), "test-plugin"));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "plugin-transfer-redis-test");
        CountDownLatch readReturned = new CountDownLatch(1);
        Thread readThread = new Thread(() -> {
            state.get(sender);
            readReturned.countDown();
        }, "plugin-transfer-memory-read-test");

        transferThread.start();
        try {
            assertTrue(redisEntered.await(2, TimeUnit.SECONDS), "the transfer should reach Redis");
            assertTrue(state.isUnavailable(sender) && state.isUnavailable(receiver),
                    "both accounts must be unavailable while the direct transfer is unresolved");
            assertNull(state.tryReserveWrite(sender, state.get(sender)),
                    "ordinary writes must not be admitted during the direct transfer");
            readThread.start();
            assertTrue(readReturned.await(250, TimeUnit.MILLISECONDS),
                    "a warm in-memory read must not wait for the transfer's Redis round trip");
        } finally {
            releaseRedis.countDown();
            transferThread.join(5_000);
            if (readThread.getState() != Thread.State.NEW) readThread.join(5_000);
        }

        assertAll(
                () -> assertFalse(transferThread.isAlive()),
                () -> assertFalse(readThread.isAlive()),
                () -> assertNull(failure.get()),
                () -> assertNotNull(result.get())
        );
    }

    @Test
    void transferAdmissionRejectsWhenSecondAccountCannotReserveBeforeAnyMutation() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferCompensationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(1, Logger.getLogger("TransferCompensationTest"));
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(sender, new AccountState(new BigDecimal("100.00"), 7L,
                System.currentTimeMillis()));
        state.put(receiver, new AccountState(BigDecimal.ZERO, 3L,
                System.currentTimeMillis()));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.resolvePlayerName(sender)).thenReturn("sender");
        when(facade.resolvePlayerName(receiver)).thenReturn("receiver");
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        FallbackEconomyWrapper fallback = new FallbackEconomyWrapper(plugin, mock(RedisManager.class));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue, fallback,
                mock(OverflowLogInterface.class), state, new AtomicLong(), false);
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, mock(CacheManager.class), state, writer);

        BigDecimal result = orchestrator.atomicTransfer(sender, receiver, new BigDecimal("10.00"),
                EconomyEvent.EventSource.TEST);

        assertAll(
                () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO)),
                () -> assertEquals(new BigDecimal("100.00"), state.get(sender).balance()),
                () -> assertEquals(BigDecimal.ZERO, state.get(receiver).balance()),
                () -> assertEquals(0, queue.size()),
                () -> assertFalse(queue.hasAcceptedWrite(sender)),
                () -> assertFalse(queue.hasAcceptedWrite(receiver))
        );
    }

    @Test
    void receiverCancellationDoesNotAcceptEitherTransferLeg() throws Exception {
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
        state.put(sender, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        state.put(receiver, new AccountState(BigDecimal.ZERO, 3L, System.currentTimeMillis()));

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
                    facade, plugin, config, cacheManager, state, writer);
            BigDecimal transferResult = orchestrator.atomicTransfer(
                    sender, receiver, amount, EconomyEvent.EventSource.TEST);

            assertEquals(0, state.get(sender).balance().compareTo(new BigDecimal("100.00")),
                    "compensation restores the sender's memory projection");
            assertEquals(0, state.get(receiver).balance().compareTo(BigDecimal.ZERO),
                    "the cancelled receiver deposit must not mutate memory");
            boolean noQueuedTransferLeg = queue.size() == 0;

            durableWrites.set(new CountDownLatch(1));
            consumer.start();
            if (!noQueuedTransferLeg) {
                assertTrue(durableWrites.get().await(5, TimeUnit.SECONDS),
                        "the consumer should finish any accepted transfer write");
            }
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
                        () -> assertTrue(noQueuedTransferLeg,
                                "receiver cancellation must not leave one accepted transfer leg"),
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
        state.put(sender, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        state.put(receiver, new AccountState(BigDecimal.ZERO, 3L, System.currentTimeMillis()));

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
                    facade, plugin, config, mock(CacheManager.class), state, writer);
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
