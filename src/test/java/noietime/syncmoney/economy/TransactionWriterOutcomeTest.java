package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.config.CircuitBreakerConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionWriterOutcomeTest {

    @Test
    void pluginPreEventOnceWithAttribution() throws Exception {
        UUID account = UUID.randomUUID();
        String caller = "ExampleShop";
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        CircuitBreakerConfig breakerConfig = mock(CircuitBreakerConfig.class);
        when(breakerConfig.isCircuitBreakerEnabled()).thenReturn(true);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.circuitBreaker()).thenReturn(breakerConfig);
        EconomicCircuitBreaker breaker = mock(EconomicCircuitBreaker.class);
        when(breaker.checkTransaction(any(UUID.class), any(BigDecimal.class), any(EconomyEvent.EventSource.class)))
                .thenReturn(EconomicCircuitBreaker.CheckResult.ALLOWED);
        when(facade.getCircuitBreaker()).thenReturn(breaker);

        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        when(facade.getBalance(account)).thenAnswer(invocation -> state.get(account).balance());
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), mock(OverflowLogInterface.class),
                state, new AtomicLong(), false);

        SyncmoneyEventBus bus = eventBus(plugin);
        List<AsyncPreTransactionEvent> preEvents = new ArrayList<>();
        List<PostTransactionEvent> postEvents = new ArrayList<>();
        SyncmoneyListener<AsyncPreTransactionEvent> preObserver = preEvents::add;
        SyncmoneyListener<PostTransactionEvent> postObserver = postEvents::add;
        bus.register(AsyncPreTransactionEvent.class, preObserver);
        bus.register(PostTransactionEvent.class, postObserver);
        try {
            BigDecimal depositResult = writer.pluginDeposit(account, new BigDecimal("10.00"), caller);
            int depositPreCount = preEvents.size();
            int depositPostCount = postEvents.size();
            long depositBreakerChecks = Mockito.mockingDetails(breaker).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("checkTransaction")).count();
            long depositBreakerCompletions = Mockito.mockingDetails(breaker).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("onTransactionComplete")).count();
            EconomyEvent depositEvent = queue.poll(0, TimeUnit.MILLISECONDS);

            BigDecimal withdrawResult = writer.pluginWithdraw(account, new BigDecimal("5.00"), caller);
            int totalPreCount = preEvents.size();
            int totalPostCount = postEvents.size();
            long totalBreakerChecks = Mockito.mockingDetails(breaker).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("checkTransaction")).count();
            long totalBreakerCompletions = Mockito.mockingDetails(breaker).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("onTransactionComplete")).count();
            EconomyEvent withdrawEvent = queue.poll(0, TimeUnit.MILLISECONDS);
            List<PostTransactionEvent> depositPosts = postEvents.stream()
                    .filter(event -> event.getType() == AsyncPreTransactionEvent.TransactionType.DEPOSIT
                            && event.getAmount().compareTo(new BigDecimal("10.00")) == 0)
                    .toList();
            List<PostTransactionEvent> withdrawPosts = postEvents.stream()
                    .filter(event -> event.getType() == AsyncPreTransactionEvent.TransactionType.WITHDRAW
                            && event.getAmount().compareTo(new BigDecimal("5.00")) == 0)
                    .toList();

            assertAll(
                    () -> assertEquals(0, depositResult.compareTo(new BigDecimal("110.00"))),
                    () -> assertEquals(0, withdrawResult.compareTo(new BigDecimal("105.00"))),
                    () -> assertEquals(1, depositPreCount,
                            "plugin deposit must pass one pre-event gate"),
                    () -> assertEquals(1, totalPreCount - depositPreCount,
                            "plugin withdrawal must pass one pre-event gate"),
                    () -> assertEquals(1, depositPostCount,
                            "plugin deposit must post once"),
                    () -> assertEquals(1, totalPostCount - depositPostCount,
                            "plugin withdrawal must post once"),
                    () -> assertEquals(1, depositBreakerChecks,
                            "plugin deposit must pass the breaker gate once"),
                    () -> assertEquals(1, totalBreakerChecks - depositBreakerChecks,
                            "plugin withdrawal must pass the breaker gate once"),
                    () -> assertEquals(1, depositBreakerCompletions,
                            "plugin deposit must complete the breaker once"),
                    () -> assertEquals(1, totalBreakerCompletions - depositBreakerCompletions,
                            "plugin withdrawal must complete the breaker once"),
                    () -> assertNotNull(depositEvent),
                    () -> assertNotNull(withdrawEvent),
                    () -> assertEquals(EconomyEvent.EventSource.PLUGIN_DEPOSIT, depositEvent.source()),
                    () -> assertEquals(EconomyEvent.EventSource.PLUGIN_WITHDRAW, withdrawEvent.source()),
                    () -> assertEquals(1, depositPosts.size(), "deposit must have one success post for its amount"),
                    () -> assertEquals(1, withdrawPosts.size(), "withdrawal must have one success post for its amount"),
                    () -> assertTrue(depositPosts.stream().allMatch(event -> event.isSuccess()
                                    && (caller.equals(event.getSource()) || caller.equals(event.getReason()))),
                            "the deposit success post must retain the calling plugin identity"),
                    () -> assertTrue(withdrawPosts.stream().allMatch(event -> event.isSuccess()
                                    && (caller.equals(event.getSource()) || caller.equals(event.getReason()))),
                            "the withdrawal success post must retain the calling plugin identity")
            );
        } finally {
            bus.unregister(AsyncPreTransactionEvent.class, preObserver);
            bus.unregister(PostTransactionEvent.class, postObserver);
        }
    }

    @Test
    void localPluginMutationsCompleteBreakerOnce() throws Exception {
        UUID account = UUID.randomUUID();
        String caller = "ExampleShop";
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        when(config.circuitBreaker().isCircuitBreakerEnabled()).thenReturn(true);
        EconomicCircuitBreaker breaker = mock(EconomicCircuitBreaker.class);
        when(breaker.checkTransaction(any(UUID.class), any(BigDecimal.class), any(EconomyEvent.EventSource.class)))
                .thenReturn(EconomicCircuitBreaker.CheckResult.ALLOWED);
        when(facade.getCircuitBreaker()).thenReturn(breaker);

        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, localHandler, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class), true), null,
                state, new AtomicLong(), true);

        SyncmoneyEventBus bus = eventBus(plugin);
        List<AsyncPreTransactionEvent> preEvents = new ArrayList<>();
        List<PostTransactionEvent> postEvents = new ArrayList<>();
        SyncmoneyListener<AsyncPreTransactionEvent> preObserver = preEvents::add;
        SyncmoneyListener<PostTransactionEvent> postObserver = postEvents::add;
        bus.register(AsyncPreTransactionEvent.class, preObserver);
        bus.register(PostTransactionEvent.class, postObserver);
        try {
            BigDecimal depositResult = writer.pluginDeposit(account, new BigDecimal("10.00"), caller);
            BigDecimal withdrawResult = writer.pluginWithdraw(account, new BigDecimal("5.00"), caller);

            EconomyEvent accepted;
            while ((accepted = queue.poll(0, TimeUnit.MILLISECONDS)) != null) {
                queue.complete(accepted);
            }

            assertAll(
                    () -> assertEquals(0, depositResult.compareTo(new BigDecimal("110.00"))),
                    () -> assertEquals(0, withdrawResult.compareTo(new BigDecimal("105.00"))),
                    () -> assertEquals(2, preEvents.size(), "each local plugin mutation has one pre-event"),
                    () -> assertEquals(2, postEvents.size(), "each local plugin mutation has one post event"),
                    () -> assertTrue(preEvents.stream().allMatch(event -> caller.equals(event.getReason()))),
                    () -> assertTrue(postEvents.stream().allMatch(event -> caller.equals(event.getReason()))),
                    () -> assertEquals(105, state.get(account).balance().intValue()),
                    () -> assertEquals(0, queue.size(), "accepted LOCAL mutations are drained by the persistence worker"),
                    () -> Mockito.verify(breaker, Mockito.times(2)).checkTransaction(any(UUID.class),
                            any(BigDecimal.class), any(EconomyEvent.EventSource.class)),
                    () -> Mockito.verify(breaker, Mockito.times(2)).onTransactionComplete(any(UUID.class),
                            any(BigDecimal.class), any(BigDecimal.class)),
                    () -> verify(localHandler, never()).deposit(account, null, new BigDecimal("10.00"),
                            EconomyEvent.EventSource.PLUGIN_DEPOSIT.name()),
                    () -> verify(localHandler, never()).withdraw(account, null, new BigDecimal("5.00"),
                            EconomyEvent.EventSource.PLUGIN_WITHDRAW.name())
            );
        } finally {
            bus.unregister(AsyncPreTransactionEvent.class, preObserver);
            bus.unregister(PostTransactionEvent.class, postObserver);
        }
    }

    @Test
    void pluginPreCancellationDoesNotMutateOrPost() {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getBalance(account)).thenReturn(new BigDecimal("100.00"));
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomicCircuitBreaker breaker = mock(EconomicCircuitBreaker.class);
        when(facade.getCircuitBreaker()).thenReturn(breaker);
        CircuitBreakerConfig breakerConfig = mock(CircuitBreakerConfig.class);
        when(breakerConfig.isCircuitBreakerEnabled()).thenReturn(true);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.circuitBreaker()).thenReturn(breakerConfig);
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), mock(OverflowLogInterface.class),
                state, new AtomicLong(), false);

        SyncmoneyEventBus bus = eventBus(plugin);
        List<AsyncPreTransactionEvent> preEvents = new ArrayList<>();
        List<PostTransactionEvent> postEvents = new ArrayList<>();
        SyncmoneyListener<AsyncPreTransactionEvent> cancel = event -> {
            preEvents.add(event);
            event.setCancelled(true, "fixture cancellation");
        };
        SyncmoneyListener<PostTransactionEvent> postObserver = postEvents::add;
        bus.register(AsyncPreTransactionEvent.class, cancel);
        bus.register(PostTransactionEvent.class, postObserver);
        try {
            BigDecimal result = writer.pluginDeposit(account, new BigDecimal("10.00"), "ExampleShop");

            assertAll(
                    () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO)),
                    () -> assertEquals(1, preEvents.size(), "the cancelled plugin call has one pre-event gate"),
                    () -> assertTrue(postEvents.isEmpty(), "a cancelled plugin call must not emit success post"),
                    () -> assertEquals(0, state.get(account).balance().compareTo(new BigDecimal("100.00"))),
                    () -> assertEquals(7L, state.get(account).version()),
                    () -> assertEquals(0, queue.size()),
                    () -> verify(breaker, never()).checkTransaction(any(UUID.class), any(BigDecimal.class),
                            any(EconomyEvent.EventSource.class)),
                    () -> verify(breaker, never()).onTransactionComplete(any(UUID.class),
                            any(BigDecimal.class), any(BigDecimal.class))
            );
        } finally {
            bus.unregister(AsyncPreTransactionEvent.class, cancel);
            bus.unregister(PostTransactionEvent.class, postObserver);
        }
    }

    @Test
    void withdrawConcurrentRejectionDoesNotEmitSuccess() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);

        CountDownLatch earlierBalanceCaptured = new CountDownLatch(1);
        CountDownLatch allowFirstWithdrawToContinue = new CountDownLatch(1);
        AtomicReference<Thread> firstWithdrawThread = new AtomicReference<>();
        MemoryStateManager state = spy(new MemoryStateManager(plugin));
        ThreadLocal<Integer> stateReads = ThreadLocal.withInitial(() -> 0);
        doAnswer(invocation -> {
            EconomyState snapshot = (EconomyState) invocation.callRealMethod();
            int read = stateReads.get() + 1;
            stateReads.set(read);
            if (Thread.currentThread() == firstWithdrawThread.get() && read == 1) {
                earlierBalanceCaptured.countDown();
                if (!allowFirstWithdrawToContinue.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("withdraw interleaving barrier timed out");
                }
            }
            return snapshot;
        }).when(state).get(any(UUID.class));
        state.put(account, new EconomyState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));

        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        FallbackEconomyWrapper fallback = new FallbackEconomyWrapper(plugin, mock(RedisManager.class));
        TransactionWriter writer = new TransactionWriter(
                facade, plugin, mock(SyncmoneyConfig.class), null, queue, fallback, null,
                state, new AtomicLong(), false);

        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            when(busPlugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
            SyncmoneyEventBus.init(busPlugin);
        }
        SyncmoneyEventBus eventBus = SyncmoneyEventBus.getInstance();
        List<PostTransactionEvent> postEvents = new ArrayList<>();
        SyncmoneyListener<PostTransactionEvent> postEventObserver = postEvents::add;
        eventBus.register(PostTransactionEvent.class, postEventObserver);

        try {
            AtomicReference<BigDecimal> firstResult = new AtomicReference<>();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            Thread firstWithdraw = new Thread(() -> {
                try {
                    firstResult.set(writer.withdraw(account, new BigDecimal("10.00"), EconomyEvent.EventSource.TEST));
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            }, "first-withdraw");
            firstWithdrawThread.set(firstWithdraw);
            firstWithdraw.start();

            assertTrue(earlierBalanceCaptured.await(5, TimeUnit.SECONDS),
                    "first withdrawal should pause after capturing the earlier balance for preflight checks");

            BigDecimal secondResult = writer.withdraw(
                    account, new BigDecimal("100.00"), EconomyEvent.EventSource.TEST);
            assertEquals(0, secondResult.compareTo(BigDecimal.ZERO),
                    "the second withdrawal should consume the starting balance");
            allowFirstWithdrawToContinue.countDown();

            firstWithdraw.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(!firstWithdraw.isAlive(), "first withdrawal should finish after the barrier is released");
            if (firstFailure.get() != null) {
                throw new AssertionError("first withdrawal failed unexpectedly", firstFailure.get());
            }

            List<EconomyEvent> queued = new ArrayList<>();
            EconomyEvent event;
            while ((event = queue.poll(0, TimeUnit.MILLISECONDS)) != null) {
                queued.add(event);
            }
            EconomyState finalState = state.get(account);
            assertAll(
                    () -> assertEquals(BigDecimal.valueOf(-1), firstResult.get(),
                            "the stale withdrawal must report rejection"),
                    () -> assertEquals(0, finalState.balance().compareTo(BigDecimal.ZERO),
                            "the rejected withdrawal must not further change the balance"),
                    () -> assertEquals(8L, finalState.version(),
                            "the rejected withdrawal must not further advance the version"),
                    () -> assertEquals(1, queued.size(),
                            "only the successful balance-consuming withdrawal may be queued"),
                    () -> assertEquals(0, queued.get(0).delta().compareTo(new BigDecimal("-100.00")),
                            "the queued event must represent only the successful withdrawal"),
                    () -> assertEquals(8L, queued.get(0).version(),
                            "the queued event must retain the successful withdrawal version"),
                    () -> assertTrue(queued.stream().noneMatch(queuedEvent ->
                                    queuedEvent.type() == EconomyEvent.EventType.WITHDRAW
                                            && queuedEvent.source() == EconomyEvent.EventSource.TEST
                                            && queuedEvent.delta().compareTo(new BigDecimal("-10.00")) == 0),
                            "the rejected 10.00 withdrawal must not be queued"),
                    () -> assertEquals(1, postEvents.size(),
                            "only the successful balance-consuming withdrawal may post success"),
                    () -> assertTrue(postEvents.stream().noneMatch(postEvent ->
                                    postEvent.getAmount().compareTo(new BigDecimal("10.00")) == 0
                                            && postEvent.getSource().equals(EconomyEvent.EventSource.TEST.name())
                                            && postEvent.isSuccess()),
                            "the rejected 10.00 withdrawal must not post success")
            );
        } finally {
            allowFirstWithdrawToContinue.countDown();
            Thread worker = firstWithdrawThread.get();
            if (worker != null) {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            }
            eventBus.unregister(PostTransactionEvent.class, postEventObserver);
        }
    }

    private static SyncmoneyEventBus eventBus(Plugin plugin) {
        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            when(busPlugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
            SyncmoneyEventBus.init(busPlugin);
        }
        return SyncmoneyEventBus.getInstance();
    }

}
