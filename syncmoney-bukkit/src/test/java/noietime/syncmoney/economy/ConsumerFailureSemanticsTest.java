package noietime.syncmoney.economy;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.protocol.RedisOperationFingerprint;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.RedisMutationException;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsumerFailureSemanticsTest {

    @Test
    void rejectedAuthorityMutationWaitsForReceiptThenReleasesWithoutRetrying() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest-rejected");
        CountDownLatch firstAttempt = new CountDownLatch(1);
        CountDownLatch authorityReadStarted = new CountDownLatch(1);
        CountDownLatch releaseAuthorityRead = new CountDownLatch(1);
        EconomyEvent event = event(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.ADMIN_GIVE, "deposit-rejected");
        RedisMutationException rejected = new RedisMutationException(
                RedisMutationException.ExecutionState.REJECTED,
                "Redis explicitly rejected the mutation without applying it", null);
        String operationId = event.requestId();
        String fingerprint = RedisOperationFingerprint.add(account, event.delta());
        when(harness.cacheManager.readOperationReceipt(operationId, fingerprint, List.of(account)))
                .thenReturn(Optional.of(new CacheManager.OperationReceipt(operationId, fingerprint,
                        CacheManager.OperationStatus.REJECTED, "insufficient funds", List.of())));
        when(harness.cacheManager.readAuthorityBalanceSnapshotStrict(account)).thenAnswer(invocation -> {
            authorityReadStarted.countDown();
            if (!releaseAuthorityRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release authority snapshot read");
            }
            return Optional.of(new CacheManager.CachedBalance(new BigDecimal("100.00"), 7L));
        });
        doAnswer(invocation -> {
            firstAttempt.countDown();
            throw rejected;
        }).when(harness.cacheManager).atomicAddBalanceReceipt(eq(account), any(BigDecimal.class), anyString());

        try {
            harness.startWith(event);
            assertTrue(firstAttempt.await(5, TimeUnit.SECONDS), "the accepted event should reach Redis once");
            assertTrue(authorityReadStarted.await(5, TimeUnit.SECONDS),
                    "a known rejection should reconcile against the authority pair");
            assertTrue(harness.queue.hasOutstandingWrites(),
                    "accepted ownership remains until the authority snapshot is available");
            releaseAuthorityRead.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (harness.queue.hasOutstandingWrites() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertAll(
                    () -> verify(harness.cacheManager, times(1))
                            .atomicAddBalanceReceipt(eq(account), any(BigDecimal.class), anyString()),
                    () -> verify(harness.cacheManager).readAuthorityBalanceSnapshotStrict(account),
                    () -> assertFalse(harness.queue.hasOutstandingWrites(),
                            "a confirmed rejection receipt completes reconciliation and releases ownership")
            );
        } finally {
            releaseAuthorityRead.countDown();
            harness.consumer.stop();
            if (harness.queue.hasOutstandingWrites()) {
                harness.queue.completeRejected(event, new BigDecimal("100.00"), 7L);
            }
            harness.close();
        }
    }

    @Test
    void degradedRedisRetainsSignedWithdrawalUntilRecovery() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        AtomicBoolean redisDegraded = new AtomicBoolean(true);
        CountDownLatch degradedStateObserved = new CountDownLatch(1);
        when(harness.redisManager.isDegraded()).thenAnswer(invocation -> {
            boolean degraded = redisDegraded.get();
            if (degraded) degradedStateObserved.countDown();
            return degraded;
        });
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.cacheManager.getBalance(account)).thenReturn(new BigDecimal("100.00"));
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        AtomicInteger redisWrites = new AtomicInteger();
        AtomicReference<BigDecimal> redisDelta = new AtomicReference<>();
        doAnswer(invocation -> {
            redisWrites.incrementAndGet();
            redisDelta.set(invocation.getArgument(1));
            return new ConfirmedAccount(account, new BigDecimal("90.00"), 9L);
        }).when(harness.cacheManager).atomicAddBalanceReceipt(eq(account), any(BigDecimal.class), anyString());
        AtomicInteger dbOffers = new AtomicInteger();
        doAnswer(invocation -> {
            dbOffers.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }).when(harness.dbWriteQueue).offerAndTrack(any());

        try {
            harness.startWith(event(account, new BigDecimal("-10.00"), new BigDecimal("90.00"),
                    EconomyEvent.EventType.WITHDRAW, EconomyEvent.EventSource.PLUGIN_WITHDRAW, "withdraw-degraded"));
            assertTrue(degradedStateObserved.await(5, TimeUnit.SECONDS), "the consumer should observe degraded Redis");
            assertEquals(1, harness.queue.size(), "accepted work remains queued while its authority is unavailable");
            assertEquals(0, redisWrites.get(), "the consumer must not send a new Redis mutation during known outage");
            assertEquals(0, dbOffers.get(), "the consumer must not make SQL a fallback authority");

            redisDegraded.set(false);
            harness.close();

            assertEquals(1, redisWrites.get(), "the retained withdrawal is applied once after Redis recovers");
            assertEquals(new BigDecimal("-10.00"), redisDelta.get(), "withdrawal remains a signed negative delta");
            assertEquals(1, dbOffers.get(), "the normal persistence step follows confirmed Redis application");
        } finally {
            redisDegraded.set(false);
            harness.close();
        }
    }

    @Test
    void degradedRedisRetainsDepositUntilRecovery() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        AtomicBoolean redisDegraded = new AtomicBoolean(true);
        CountDownLatch degradedStateObserved = new CountDownLatch(1);
        when(harness.redisManager.isDegraded()).thenAnswer(invocation -> {
            boolean degraded = redisDegraded.get();
            if (degraded) degradedStateObserved.countDown();
            return degraded;
        });
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        AtomicInteger redisWrites = new AtomicInteger();
        AtomicReference<BigDecimal> redisDelta = new AtomicReference<>();
        doAnswer(invocation -> {
            redisWrites.incrementAndGet();
            redisDelta.set(invocation.getArgument(1));
            return new ConfirmedAccount(account, new BigDecimal("110.00"), 9L);
        }).when(harness.cacheManager).atomicAddBalanceReceipt(eq(account), any(BigDecimal.class), anyString());
        AtomicInteger dbOffers = new AtomicInteger();
        doAnswer(invocation -> {
            dbOffers.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }).when(harness.dbWriteQueue).offerAndTrack(any());

        try {
            harness.startWith(event(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                    EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.ADMIN_GIVE, "deposit-degraded"));
            assertTrue(degradedStateObserved.await(5, TimeUnit.SECONDS), "the consumer should observe degraded Redis");
            assertEquals(1, harness.queue.size(), "accepted deposit remains queued while its authority is unavailable");
            assertEquals(0, redisWrites.get(), "the consumer must not send a new Redis mutation during known outage");
            assertEquals(0, dbOffers.get(), "the consumer must not make SQL a fallback authority");

            redisDegraded.set(false);
            harness.close();

            assertEquals(1, redisWrites.get(), "the retained deposit is applied once after Redis recovers");
            assertEquals(new BigDecimal("10.00"), redisDelta.get(), "deposit remains a positive signed delta");
            assertEquals(1, dbOffers.get(), "the normal persistence step follows confirmed Redis application");
        } finally {
            redisDegraded.set(false);
            harness.close();
        }
    }

    @Test
    void degradedRedisUsesAbsoluteSetAfterRecovery() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        AtomicBoolean redisDegraded = new AtomicBoolean(true);
        CountDownLatch degradedStateObserved = new CountDownLatch(1);
        when(harness.redisManager.isDegraded()).thenAnswer(invocation -> {
            boolean degraded = redisDegraded.get();
            if (degraded) degradedStateObserved.countDown();
            return degraded;
        });
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.cacheManager.getBalance(account)).thenReturn(new BigDecimal("80.00"));
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        AtomicInteger redisWrites = new AtomicInteger();
        AtomicReference<BigDecimal> redisTarget = new AtomicReference<>();
        doAnswer(invocation -> {
            redisWrites.incrementAndGet();
            if (redisDegraded.get()) return null;
            redisTarget.set(invocation.getArgument(1));
            return new ConfirmedAccount(account, redisTarget.get(), 9L);
        }).when(harness.cacheManager).atomicSetBalanceReceipt(eq(account), any(BigDecimal.class), anyString());
        AtomicInteger dbOffers = new AtomicInteger();
        doAnswer(invocation -> {
            dbOffers.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }).when(harness.dbWriteQueue).offerAndTrack(any());

        try {
            harness.startWith(event(account, new BigDecimal("-60.00"), new BigDecimal("40.00"),
                    EconomyEvent.EventType.SET_BALANCE, EconomyEvent.EventSource.ADMIN_SET, "set-degraded"));
            assertTrue(degradedStateObserved.await(5, TimeUnit.SECONDS), "the consumer should observe degraded Redis");
            assertEquals(1, harness.queue.size(), "accepted SET remains queued while its authority is unavailable");
            assertEquals(0, redisWrites.get(), "the consumer must not issue SET while Redis is known unavailable");
            assertEquals(0, dbOffers.get(), "the consumer must not persist a degraded SET as a new authority value");

            redisDegraded.set(false);
            harness.close();

            assertEquals(1, redisWrites.get(), "the retained SET is applied once after Redis recovers");
            assertEquals(new BigDecimal("40.00"), redisTarget.get(), "SET uses the absolute target after recovery");
            assertEquals(1, dbOffers.get(), "the normal persistence step follows confirmed Redis application");
        } finally {
            redisDegraded.set(false);
            harness.close();
        }
    }

    @Test
    void failedRedisSetRetainsAbsoluteTargetWithoutArithmeticFallback() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        AtomicBoolean redisDegraded = new AtomicBoolean();
        CountDownLatch firstRedisAttempt = new CountDownLatch(1);
        CountDownLatch degradedStateObserved = new CountDownLatch(1);
        CountDownLatch dbOfferObserved = new CountDownLatch(1);
        when(harness.redisManager.isDegraded()).thenAnswer(invocation -> {
            boolean degraded = redisDegraded.get();
            if (degraded) degradedStateObserved.countDown();
            return degraded;
        });
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.cacheManager.getBalance(account)).thenReturn(new BigDecimal("80.00"));
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");

        AtomicInteger redisWrites = new AtomicInteger();
        List<BigDecimal> targets = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            targets.add(invocation.getArgument(1));
            if (redisWrites.incrementAndGet() == 1) {
                redisDegraded.set(true);
                firstRedisAttempt.countDown();
                return null;
            }
            return new ConfirmedAccount(account, invocation.getArgument(1), 9L);
        }).when(harness.cacheManager).atomicSetBalanceReceipt(eq(account), any(BigDecimal.class), anyString());

        AtomicInteger dbOffers = new AtomicInteger();
        doAnswer(invocation -> {
            dbOffers.incrementAndGet();
            dbOfferObserved.countDown();
            return CompletableFuture.completedFuture(true);
        }).when(harness.dbWriteQueue).offerAndTrack(any());

        try {
            harness.startWith(event(account, new BigDecimal("-60.00"), new BigDecimal("40.00"),
                    EconomyEvent.EventType.SET_BALANCE, EconomyEvent.EventSource.ADMIN_SET,
                    "set-null-result"));
            assertTrue(firstRedisAttempt.await(5, TimeUnit.SECONDS), "the first Redis SET should be attempted");
            assertTrue(degradedStateObserved.await(5, TimeUnit.SECONDS),
                    "the consumer should retain the event after Redis reports degraded");
            assertEquals(0, harness.queue.size(), "the failed event is retained in retry tracking");
            assertEquals(List.of(new BigDecimal("40.00")), targets,
                    "the failed authority attempt uses the absolute SET target");
            assertEquals(0, dbOffers.get(), "SQL must not become authority after the Redis failure");

            redisDegraded.set(false);
            assertTrue(dbOfferObserved.await(5, TimeUnit.SECONDS), "the accepted SET should resume after recovery");
            harness.close();

            assertAll(
                    () -> assertEquals(List.of(new BigDecimal("40.00"), new BigDecimal("40.00")), targets,
                            "retry keeps the absolute target instead of applying current plus delta"),
                    () -> assertEquals(2, redisWrites.get(), "one failed attempt and one recovery attempt are expected"),
                    () -> assertEquals(1, dbOffers.get(), "the ordinary persistence step follows Redis recovery"),
                    () -> verify(harness.cacheManager, never()).getBalance(account)
            );
        } finally {
            redisDegraded.set(false);
            harness.close();
        }
    }

    @Test
    void laterQueuedMutationWaitsForEarlierFailedSet() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        List<String> redisOperations = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger setAttempts = new AtomicInteger();
        AtomicReference<BigDecimal> authorityBalance = new AtomicReference<>(new BigDecimal("80.00"));
        CountDownLatch laterMutationApplied = new CountDownLatch(1);

        doAnswer(invocation -> {
            BigDecimal target = invocation.getArgument(1);
            redisOperations.add("SET:" + target.toPlainString());
            if (setAttempts.incrementAndGet() == 1) {
                return null;
            }
            authorityBalance.set(target);
            return new ConfirmedAccount(account, target, 9L);
        }).when(harness.cacheManager).atomicSetBalanceReceipt(eq(account), any(BigDecimal.class), anyString());
        doAnswer(invocation -> {
            BigDecimal delta = invocation.getArgument(1);
            redisOperations.add("ADD:" + delta.toPlainString());
            BigDecimal updated = authorityBalance.updateAndGet(balance -> balance.add(delta));
            laterMutationApplied.countDown();
            return new ConfirmedAccount(account, updated, 10L);
        }).when(harness.cacheManager).atomicAddBalanceReceipt(eq(account), any(BigDecimal.class), anyString());

        EconomyEvent set = event(account, new BigDecimal("-40.00"), new BigDecimal("40.00"),
                EconomyEvent.EventType.SET_BALANCE, EconomyEvent.EventSource.ADMIN_SET, "set-before-add");
        EconomyEvent add = event(account, new BigDecimal("10.00"), new BigDecimal("50.00"),
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.ADMIN_GIVE, "add-after-set");
        assertTrue(harness.queue.offerBlocking(set, 1, TimeUnit.SECONDS), "SET event must enter the queue");
        assertTrue(harness.queue.offerBlocking(add, 1, TimeUnit.SECONDS), "ADD event must enter the queue");

        try {
            harness.consumer.start();
            assertTrue(laterMutationApplied.await(5, TimeUnit.SECONDS), "the later ADD should eventually apply");
            harness.close();

            assertAll(
                    () -> assertEquals(List.of("SET:40.00", "SET:40.00", "ADD:10.00"), redisOperations,
                            "a later queued mutation must not overtake an unresolved earlier SET"),
                    () -> assertEquals(new BigDecimal("50.00"), authorityBalance.get(),
                            "FIFO application preserves the final shared balance")
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void postRedisFailureDoesNotApplyDeltaTwice() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.config.getServerName()).thenReturn("test-server");
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        when(harness.cacheManager.getVersion(account)).thenReturn(8L);

        AtomicReference<BigDecimal> durableBalance = new AtomicReference<>(new BigDecimal("100.00"));
        AtomicInteger redisMutations = new AtomicInteger();
        AtomicInteger dbOffers = new AtomicInteger();
        CountDownLatch dbOfferAttempts = new CountDownLatch(2);
        when(harness.cacheManager.atomicAddBalanceReceipt(eq(account), eq(new BigDecimal("10.00")), anyString()))
                .thenAnswer(invocation -> {
            redisMutations.incrementAndGet();
            BigDecimal after = durableBalance.updateAndGet(balance -> balance.add(new BigDecimal("10.00")));
            return new ConfirmedAccount(account, after, 9L);
        });
        when(harness.dbWriteQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            dbOfferAttempts.countDown();
            if (dbOffers.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(false);
            }
            return CompletableFuture.completedFuture(true);
        });

        try {
            harness.startWith(event(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                    EconomyEvent.EventType.DEPOSIT, "post-redis-failure"));
            assertTrue(dbOfferAttempts.await(5, TimeUnit.SECONDS),
                    "the downstream DB enqueue should be retried after its first failure");
            harness.close();

            assertAll(
                    () -> assertEquals(1, redisMutations.get(),
                            "a confirmed Redis mutation must not be repeated when downstream work fails"),
                    () -> assertEquals(new BigDecimal("110.00"), durableBalance.get(),
                            "the repeated event currently changes durable balance to 120.00"),
                    () -> verify(harness.dbWriteQueue, times(2))
                            .offerAndTrack(any(DbWriteQueue.DbWriteTask.class))
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void shutdownRetriesFailedDownstreamWorkWithoutReapplyingRedis() throws Exception {
        UUID account = UUID.randomUUID();
        ConsumerHarness harness = new ConsumerHarness("ConsumerFailureSemanticsTest");
        when(harness.config.isDbEnabled()).thenReturn(true);
        when(harness.config.getServerName()).thenReturn("test-server");
        when(harness.nameResolver.getNameCachedOnly(account)).thenReturn("account");
        when(harness.cacheManager.getVersion(account)).thenReturn(8L);
        AtomicInteger redisMutations = new AtomicInteger();
        AtomicInteger dbOffers = new AtomicInteger();
        CountDownLatch dbOfferEntered = new CountDownLatch(1);
        CountDownLatch allowDbFailure = new CountDownLatch(1);
        CountDownLatch dbOfferAttempts = new CountDownLatch(2);
        when(harness.cacheManager.atomicAddBalanceReceipt(eq(account), eq(new BigDecimal("10.00")), anyString()))
                .thenAnswer(invocation -> {
            redisMutations.incrementAndGet();
            return new ConfirmedAccount(account, new BigDecimal("110.00"), 9L);
        });
        when(harness.dbWriteQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            int attempt = dbOffers.incrementAndGet();
            dbOfferAttempts.countDown();
            if (attempt == 1) {
                dbOfferEntered.countDown();
                if (!allowDbFailure.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("DB failure barrier timed out");
                }
                return CompletableFuture.completedFuture(false);
            }
            return CompletableFuture.completedFuture(true);
        });

        try {
            harness.startWith(event(account, new BigDecimal("10.00"), new BigDecimal("110.00"),
                    EconomyEvent.EventType.DEPOSIT, "shutdown-pending-retry"));
            assertTrue(dbOfferEntered.await(5, TimeUnit.SECONDS), "the consumer should reach the failing DB step");
            assertEquals(0, harness.queue.size(), "the worker has taken the event from the public queue");

            harness.stop();
            allowDbFailure.countDown();
            harness.consumer.shutdown();
            boolean downstreamRetryObserved = dbOfferAttempts.await(5, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(downstreamRetryObserved,
                            "shutdown should retry accepted downstream work before the consumer exits"),
                    () -> assertEquals(1, redisMutations.get(),
                            "a downstream-only retry must not apply the accepted Redis delta twice"),
                    () -> verify(harness.dbWriteQueue, times(2))
                            .offerAndTrack(any(DbWriteQueue.DbWriteTask.class)),
                    () -> assertEquals(0, harness.queue.size(), "the retry is no longer in the public queue")
            );
        } finally {
            allowDbFailure.countDown();
            harness.close();
        }
    }

    private static EconomyEvent event(UUID account, BigDecimal delta, BigDecimal after,
            EconomyEvent.EventType type, String requestId) {
        return event(account, delta, after, type, EconomyEvent.EventSource.TEST, requestId);
    }

    private static EconomyEvent event(UUID account, BigDecimal delta, BigDecimal after,
            EconomyEvent.EventType type, EconomyEvent.EventSource source, String requestId) {
        return new EconomyEvent(account, delta, after, 8L, type, source,
                requestId, System.currentTimeMillis());
    }

    private static final class ConsumerHarness implements AutoCloseable {
        private final Plugin plugin = mock(Plugin.class);
        private final SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        private final EconomyWriteQueue queue = new EconomyWriteQueue(10);
        private final CacheManager cacheManager = mock(CacheManager.class);
        private final RedisManager redisManager = mock(RedisManager.class);
        private final DbWriteQueue dbWriteQueue = mock(DbWriteQueue.class);
        private final NameResolver nameResolver = mock(NameResolver.class);
        private final EconomyEventConsumer consumer;

        private ConsumerHarness(String loggerName) {
            when(plugin.getLogger()).thenReturn(Logger.getLogger(loggerName));
            when(config.isPubsubEnabled()).thenReturn(false);
            when(config.isDbEnabled()).thenReturn(false);
            consumer = new EconomyEventConsumer(plugin, config, queue, cacheManager, redisManager,
                    dbWriteQueue, null, null, nameResolver, null, null, null,
                    mock(DatabaseManager.class));
        }

        private void startWith(EconomyEvent event) throws InterruptedException {
            assertTrue(queue.offerBlocking(event, 1, TimeUnit.SECONDS), "fixture event must enter the queue");
            consumer.start();
        }

        private void stop() {
            consumer.stop();
        }

        @Override
        public void close() {
            consumer.stop();
            consumer.shutdown();
        }
    }
}
