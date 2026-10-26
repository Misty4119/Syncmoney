package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.config.CircuitBreakerConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import noietime.syncmoney.migration.MigrationLock;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void cmiMutationAfterPermitAdmissionRejectsSetBalanceBeforeMemoryCommit() throws Exception {
        UUID account = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.isRecoveryReady()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(4, Logger.getLogger("TransactionWriterOutcomeTest"));
        MemoryStateManager state = Mockito.spy(new MemoryStateManager(plugin, queue));
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        CountDownLatch atPreCommitRead = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicInteger accountReads = new AtomicInteger();
        doAnswer(invocation -> {
            AccountState current = (AccountState) invocation.callRealMethod();
            if (accountReads.incrementAndGet() == 2) {
                atPreCommitRead.countDown();
                assertTrue(allowCommit.await(5, TimeUnit.SECONDS), "test must release the pre-commit read");
            }
            return current;
        }).when(state).get(account);

        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, localHandler, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class), true),
                mock(OverflowLogInterface.class), state, new AtomicLong(), true);

        var acquireLease = MigrationLock.class.getDeclaredMethod("tryAcquireMigrationLease");
        acquireLease.setAccessible(true);
        AutoCloseable migrationLease = (AutoCloseable) acquireLease.invoke(null);
        var beginSnapshot = migrationLease.getClass().getDeclaredMethod("beginSourceSnapshot");
        beginSnapshot.setAccessible(true);
        assertTrue((Boolean) beginSnapshot.invoke(migrationLease));
        var beginWrite = migrationLease.getClass().getDeclaredMethod("beginWrite");
        beginWrite.setAccessible(true);
        AtomicReference<BigDecimal> result = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread migrationWriter = new Thread(() -> {
            AutoCloseable permit = null;
            try {
                permit = (AutoCloseable) beginWrite.invoke(migrationLease);
                result.set(writer.setBalance(account, new BigDecimal("150.00"),
                        EconomyEvent.EventSource.ADMIN_SET));
            } catch (Throwable failure) {
                workerFailure.set(failure);
            } finally {
                if (permit != null) {
                    try {
                        permit.close();
                    } catch (Exception failure) {
                        workerFailure.compareAndSet(null, failure);
                    }
                }
            }
        }, "migration-transaction-writer-test");

        try {
            migrationWriter.start();
            assertTrue(atPreCommitRead.await(5, TimeUnit.SECONDS), "migration write should reach the pre-commit boundary");
            assertTrue(MigrationLock.recordCmiAuthorityMutation());
            allowCommit.countDown();
            migrationWriter.join(5_000);

            assertTrue(!migrationWriter.isAlive(), "migration writer should finish after the source fence is released");
            assertNull(workerFailure.get());
            assertEquals(new BigDecimal("-1"), result.get());
            assertEquals(new BigDecimal("100.00"), state.get(account).balance());
            assertEquals(0, queue.size());
            verify(localHandler, never()).setBalance(account, null, new BigDecimal("150.00"), "ADMIN_SET");
        } finally {
            allowCommit.countDown();
            migrationWriter.join(5_000);
            migrationLease.close();
            MigrationLock.unlock();
            MigrationLock.disable();
        }
    }

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
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
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
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.isRecoveryReady()).thenReturn(true);
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
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));
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
    void sharedDepositRejectsProjectedBalanceAboveDecimal20_2Limit() {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(maximum, 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin,
                mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.deposit(account, new BigDecimal("0.01"), EconomyEvent.EventSource.TEST);
        AccountState finalState = state.get(account);

        assertAll(
                () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO),
                        "a shared deposit beyond DECIMAL(20,2) must be rejected"),
                () -> assertEquals(0, finalState.balance().compareTo(maximum),
                        "rejected shared deposit must not change the balance"),
                () -> assertEquals(7L, finalState.version(),
                        "rejected shared deposit must not advance the version"),
                () -> assertEquals(0, queue.size(),
                        "rejected shared deposit must not enqueue persistence")
        );
    }

    @Test
    void sharedWithdrawRejectsLegacyBalanceAboveDecimal20_2LimitBeforeMutation() {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal legacyBalance = maximum.add(new BigDecimal("0.01"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(legacyBalance, 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin,
                mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.withdraw(account, new BigDecimal("0.01"), EconomyEvent.EventSource.TEST);

        assertAll(
                () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO),
                        "a legacy over-ceiling account must not accept a withdrawal that Redis will reject"),
                () -> assertEquals(0, legacyBalance.compareTo(state.get(account).balance()),
                        "the rejected withdrawal must preserve the legacy balance"),
                () -> assertEquals(7L, state.get(account).version(),
                        "the rejected withdrawal must not advance the version"),
                () -> assertEquals(0, queue.size(), "the rejected withdrawal must not enqueue a write")
        );
    }

    @Test
    void sharedTransferRejectsLegacyOverCeilingSenderBeforeMutation() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal legacyBalance = maximum.add(new BigDecimal("0.01"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(sender)).thenReturn("sender");
        when(facade.resolvePlayerName(receiver)).thenReturn("receiver");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(sender, new AccountState(legacyBalance, 7L, System.currentTimeMillis()));
        state.put(receiver, new AccountState(BigDecimal.ZERO, 3L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin,
                mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.transfer(sender, receiver, new BigDecimal("0.01"), EconomyEvent.EventSource.TEST);

        assertAll(
                () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO),
                        "a transfer from a legacy over-ceiling account must be rejected before Redis admission"),
                () -> assertEquals(0, legacyBalance.compareTo(state.get(sender).balance()),
                        "the rejected transfer must preserve the sender balance"),
                () -> assertEquals(7L, state.get(sender).version(),
                        "the rejected transfer must not advance the sender version"),
                () -> assertEquals(0, state.get(receiver).balance().compareTo(BigDecimal.ZERO),
                        "the rejected transfer must preserve the receiver balance"),
                () -> assertEquals(3L, state.get(receiver).version(),
                        "the rejected transfer must not advance the receiver version"),
                () -> assertEquals(0, queue.size(), "the rejected transfer must not enqueue either leg")
        );
    }

    @Test
    void sharedAdminSetCanApplyAnExplicitInRangeReconciliation() throws InterruptedException {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal legacyBalance = maximum.add(new BigDecimal("0.01"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(legacyBalance, 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin,
                mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.setBalance(account, maximum, EconomyEvent.EventSource.ADMIN_SET);
        EconomyEvent queued = queue.poll(0, TimeUnit.MILLISECONDS);

        assertAll(
                () -> assertEquals(0, result.compareTo(maximum),
                        "an explicit admin-selected in-range value can reconcile the account"),
                () -> assertEquals(0, state.get(account).balance().compareTo(maximum)),
                () -> assertEquals(8L, state.get(account).version()),
                () -> assertNotNull(queued, "the explicit reconciliation must be persisted through the write queue"),
                () -> assertEquals(EconomyEvent.EventType.SET_BALANCE, queued.type())
        );
        queue.complete(queued);
    }

    @Test
    void sharedAdminSetRejectsAuditDeltaAboveDecimal20_2BeforeMutation() {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal legacyBalance = maximum.multiply(new BigDecimal("3"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.audit().isAuditEnabled()).thenReturn(true);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(legacyBalance, 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.setBalance(account, maximum, EconomyEvent.EventSource.ADMIN_SET);

        assertAll(
                () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO),
                        "an audit amount outside DECIMAL(20,2) must be rejected before acceptance"),
                () -> assertEquals(0, legacyBalance.compareTo(state.get(account).balance()),
                        "rejected audit delta must preserve the over-ceiling balance for operator review"),
                () -> assertEquals(7L, state.get(account).version(),
                        "rejected audit delta must not advance the account version"),
                () -> assertEquals(0, queue.size(),
                        "rejected audit delta must not enqueue a persistence write")
        );
    }

    @Test
    void sharedAdminSetChecksAuditDeltaAgainstBalanceAtMutationTime() {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal legacyBalance = maximum.multiply(new BigDecimal("3"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.audit().isAuditEnabled()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        MemoryStateManager state = Mockito.spy(new MemoryStateManager(plugin, queue));
        AccountState initialState = new AccountState(BigDecimal.ZERO, 1L, System.currentTimeMillis());
        state.put(account, initialState);
        doAnswer(invocation -> {
            EconomyWriteQueue.Reservation reservation =
                    (EconomyWriteQueue.Reservation) invocation.callRealMethod();
            assertNotNull(reservation);
            assertEquals(MemoryStateManager.RemoteUpdateResult.APPLIED,
                    state.applyRemoteUpdateResult(account, legacyBalance, 2L),
                    "a newer shared snapshot may arrive after the initial setBalance read");
            return reservation;
        }).when(state).tryReserveWrite(account, initialState);
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.setBalance(account, maximum, EconomyEvent.EventSource.ADMIN_SET);

        assertAll(
                () -> assertEquals(-1, result.compareTo(BigDecimal.ZERO),
                        "the audit delta must be validated against the state observed under the mutation lock"),
                () -> assertEquals(0, legacyBalance.compareTo(state.get(account).balance()),
                        "the rejected set must preserve the newer remote balance"),
                () -> assertEquals(2L, state.get(account).version(),
                        "the rejected set must not advance the newer remote version"),
                () -> assertEquals(0, queue.size(),
                        "a rejected delta must not enqueue a persistence or audit write")
        );
    }

    @Test
    void sharedAdminSetUsesMutationTimeDeltaForAcceptedWrite() throws InterruptedException {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal remoteBalance = maximum.multiply(new BigDecimal("2"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.audit().isAuditEnabled()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        MemoryStateManager state = Mockito.spy(new MemoryStateManager(plugin, queue));
        AccountState initialState = new AccountState(BigDecimal.ZERO, 1L, System.currentTimeMillis());
        state.put(account, initialState);
        doAnswer(invocation -> {
            EconomyWriteQueue.Reservation reservation =
                    (EconomyWriteQueue.Reservation) invocation.callRealMethod();
            assertNotNull(reservation);
            assertEquals(MemoryStateManager.RemoteUpdateResult.APPLIED,
                    state.applyRemoteUpdateResult(account, remoteBalance, 2L),
                    "a newer shared snapshot may arrive after the initial setBalance read");
            return reservation;
        }).when(state).tryReserveWrite(account, initialState);
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        BigDecimal result = writer.setBalance(account, maximum, EconomyEvent.EventSource.ADMIN_SET);
        EconomyEvent accepted = queue.poll(0, TimeUnit.MILLISECONDS);

        assertAll(
                () -> assertEquals(0, result.compareTo(maximum)),
                () -> assertEquals(0, state.get(account).balance().compareTo(maximum)),
                () -> assertEquals(3L, state.get(account).version()),
                () -> assertNotNull(accepted),
                () -> assertEquals(0, accepted.delta().compareTo(maximum.negate()),
                        "the queued audit delta must match the balance observed under the mutation lock"),
                () -> assertEquals(0, accepted.balanceAfter().compareTo(maximum))
        );
        queue.complete(accepted);
    }

    @Test
    void localAdminSetRetainsWideBalanceDeltaWhenAuditEnabled() throws Exception {
        UUID account = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("999999999999999999.99").multiply(new BigDecimal("3"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.audit().isAuditEnabled()).thenReturn(true);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.isRecoveryReady()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new AccountState(initialBalance, 7L, System.currentTimeMillis()));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, localHandler, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class), true), null,
                state, new AtomicLong(), true);

        BigDecimal result = writer.setBalance(account, BigDecimal.ZERO, EconomyEvent.EventSource.ADMIN_SET);
        EconomyEvent accepted = queue.poll(0, TimeUnit.MILLISECONDS);

        assertAll(
                () -> assertEquals(0, result.compareTo(BigDecimal.ZERO),
                        "LOCAL keeps its wider balance policy when audit is enabled"),
                () -> assertEquals(0, state.get(account).balance().compareTo(BigDecimal.ZERO)),
                () -> assertEquals(8L, state.get(account).version()),
                () -> assertNotNull(accepted, "the accepted LOCAL set retains its persistence event")
        );
        queue.complete(accepted);
    }

    @Test
    void localDepositRetainsBalancesAboveSharedBackendLimit() throws Exception {
        UUID account = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000000000000000000.00");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.isRecoveryReady()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new AccountState(initialBalance, 7L, System.currentTimeMillis()));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, localHandler, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class), true), null,
                state, new AtomicLong(), true);

        BigDecimal result = writer.deposit(account, new BigDecimal("0.01"), EconomyEvent.EventSource.TEST);
        EconomyEvent accepted = queue.poll(0, TimeUnit.MILLISECONDS);
        AccountState finalState = state.get(account);
        if (accepted != null) {
            queue.complete(accepted);
        }

        assertAll(
                () -> assertEquals(0, result.compareTo(new BigDecimal("1000000000000000000.01")),
                        "the shared backend bound must not narrow LOCAL policy"),
                () -> assertEquals(0, finalState.balance().compareTo(result)),
                () -> assertEquals(8L, finalState.version()),
                () -> assertNotNull(accepted, "the LOCAL mutation should retain its accepted write")
        );
    }

    @Test
    void concurrentSharedDepositsRecheckProjectedBalanceAtCommit() throws Exception {
        UUID account = UUID.randomUUID();
        BigDecimal maximum = new BigDecimal("999999999999999999.99");
        BigDecimal initialBalance = maximum.subtract(new BigDecimal("0.01"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        MemoryStateManager state = new MemoryStateManager(plugin);
        state.put(account, new AccountState(initialBalance, 7L, System.currentTimeMillis()));
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        TransactionWriter writer = new TransactionWriter(facade, plugin,
                mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS), null, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class)), null,
                state, new AtomicLong(), false);

        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            when(busPlugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
            SyncmoneyEventBus.init(busPlugin);
        }
        SyncmoneyEventBus bus = SyncmoneyEventBus.getInstance();
        CountDownLatch firstPreflightReached = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        AtomicReference<Thread> firstThreadRef = new AtomicReference<>();
        AtomicReference<BigDecimal> firstResult = new AtomicReference<>();
        SyncmoneyListener<AsyncPreTransactionEvent> barrier = event -> {
            if (account.equals(event.getPlayerUuid())
                    && event.getType() == AsyncPreTransactionEvent.TransactionType.DEPOSIT
                    && Thread.currentThread() == firstThreadRef.get()) {
                firstPreflightReached.countDown();
                try {
                    if (!allowFirstCommit.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("shared deposit interleaving barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("shared deposit interleaving barrier interrupted", interrupted);
                }
            }
        };
        bus.register(AsyncPreTransactionEvent.class, barrier);

        Thread firstDeposit = new Thread(() -> firstResult.set(
                writer.deposit(account, new BigDecimal("0.01"), EconomyEvent.EventSource.TEST)),
                "first-shared-deposit");
        firstThreadRef.set(firstDeposit);
        firstDeposit.start();
        try {
            assertTrue(firstPreflightReached.await(5, TimeUnit.SECONDS),
                    "first deposit should pause after capturing its preflight balance");
            BigDecimal secondResult = writer.deposit(
                    account, new BigDecimal("0.01"), EconomyEvent.EventSource.TEST);
            allowFirstCommit.countDown();
            firstDeposit.join(TimeUnit.SECONDS.toMillis(5));

            assertTrue(!firstDeposit.isAlive(), "first deposit should finish after the barrier is released");
            AccountState finalState = state.get(account);
            List<EconomyEvent> queued = new ArrayList<>();
            EconomyEvent event;
            while ((event = queue.poll(0, TimeUnit.MILLISECONDS)) != null) {
                queued.add(event);
            }
            assertAll(
                    () -> assertEquals(0, secondResult.compareTo(maximum),
                            "the first accepted deposit should reach but not exceed the storage limit"),
                    () -> assertEquals(-1, firstResult.get().compareTo(BigDecimal.ZERO),
                            "the stale preflight must be rejected by the commit-time balance check"),
                    () -> assertEquals(0, finalState.balance().compareTo(maximum),
                            "concurrent deposits must leave the shared balance within the storage limit"),
                    () -> assertEquals(8L, finalState.version(),
                            "only the accepted deposit may advance the version"),
                    () -> assertEquals(1, queued.size(),
                            "only the accepted deposit may be queued")
            );
        } finally {
            allowFirstCommit.countDown();
            firstDeposit.join(TimeUnit.SECONDS.toMillis(5));
            bus.unregister(AsyncPreTransactionEvent.class, barrier);
        }
    }

    @Test
    void concurrentLocalDepositsRecheckVersionCeilingAtCommit() throws Exception {
        assertConcurrentLocalVersionCeiling(true);
    }

    @Test
    void concurrentLocalWithdrawalsRecheckVersionCeilingAtCommit() throws Exception {
        assertConcurrentLocalVersionCeiling(false);
    }

    private void assertConcurrentLocalVersionCeiling(boolean deposit) throws Exception {
        UUID account = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("100.00");
        BigDecimal amount = new BigDecimal("0.01");
        BigDecimal expectedBalance = deposit
                ? initialBalance.add(amount)
                : initialBalance.subtract(amount);
        AsyncPreTransactionEvent.TransactionType transactionType = deposit
                ? AsyncPreTransactionEvent.TransactionType.DEPOSIT
                : AsyncPreTransactionEvent.TransactionType.WITHDRAW;
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.resolvePlayerName(account)).thenReturn("account");
        when(facade.getCircuitBreaker()).thenReturn(null);
        when(facade.getPlayerTransactionGuard()).thenReturn(null);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.isRecoveryReady()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(10, Logger.getLogger("TransactionWriterOutcomeTest"));
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(account, new AccountState(initialBalance, Integer.MAX_VALUE - 2L, System.currentTimeMillis()));
        TransactionWriter writer = new TransactionWriter(facade, plugin, config, localHandler, queue,
                new FallbackEconomyWrapper(plugin, mock(RedisManager.class), true), null,
                state, new AtomicLong(), true);

        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney busPlugin = mock(Syncmoney.class);
            when(busPlugin.getLogger()).thenReturn(Logger.getLogger("TransactionWriterOutcomeTest"));
            SyncmoneyEventBus.init(busPlugin);
        }
        SyncmoneyEventBus bus = SyncmoneyEventBus.getInstance();
        CountDownLatch firstPreflightReached = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        AtomicReference<Thread> firstThreadRef = new AtomicReference<>();
        AtomicReference<BigDecimal> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        SyncmoneyListener<AsyncPreTransactionEvent> barrier = event -> {
            if (account.equals(event.getPlayerUuid())
                    && event.getType() == transactionType
                    && Thread.currentThread() == firstThreadRef.get()) {
                firstPreflightReached.countDown();
                try {
                    if (!allowFirstCommit.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("local version interleaving barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("local version interleaving barrier interrupted", interrupted);
                }
            }
        };
        bus.register(AsyncPreTransactionEvent.class, barrier);

        Thread firstWrite = new Thread(() -> {
            try {
                firstResult.set(deposit
                        ? writer.deposit(account, amount, EconomyEvent.EventSource.TEST)
                        : writer.withdraw(account, amount, EconomyEvent.EventSource.TEST));
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        }, deposit ? "first-local-deposit" : "first-local-withdrawal");
        firstThreadRef.set(firstWrite);
        firstWrite.start();

        try {
            assertTrue(firstPreflightReached.await(5, TimeUnit.SECONDS),
                    "the first LOCAL write should pause after its preflight version check");
            BigDecimal secondResult = deposit
                    ? writer.deposit(account, amount, EconomyEvent.EventSource.TEST)
                    : writer.withdraw(account, amount, EconomyEvent.EventSource.TEST);
            allowFirstCommit.countDown();
            firstWrite.join(TimeUnit.SECONDS.toMillis(5));

            assertTrue(!firstWrite.isAlive(), "the first LOCAL write should finish after releasing the barrier");
            assertNull(firstFailure.get());
            AccountState finalState = state.get(account);
            List<EconomyEvent> queued = new ArrayList<>();
            EconomyEvent event;
            while ((event = queue.poll(0, TimeUnit.MILLISECONDS)) != null) {
                queued.add(event);
                queue.complete(event);
            }

            assertAll(
                    () -> assertEquals(0, secondResult.compareTo(expectedBalance),
                            "the second LOCAL write should be accepted at version Integer.MAX_VALUE - 1"),
                    () -> assertEquals(-1, firstResult.get().compareTo(BigDecimal.ZERO),
                            "the first LOCAL write must be rejected using the locked current version"),
                    () -> assertEquals(0, finalState.balance().compareTo(expectedBalance)),
                    () -> assertEquals(Integer.MAX_VALUE - 1L, finalState.version()),
                    () -> assertEquals(1, queued.size(), "only the within-range write should be queued")
            );
        } finally {
            allowFirstCommit.countDown();
            firstWrite.join(TimeUnit.SECONDS.toMillis(5));
            bus.unregister(AsyncPreTransactionEvent.class, barrier);
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
            AccountState snapshot = (AccountState) invocation.callRealMethod();
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
        state.put(account, new AccountState(new BigDecimal("100.00"), 7L, System.currentTimeMillis()));

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
            AccountState finalState = state.get(account);
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
