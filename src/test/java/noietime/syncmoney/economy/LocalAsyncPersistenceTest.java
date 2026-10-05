package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAsyncPersistenceTest {

    private EconomyFacade facade;
    private LocalPersistenceWorker worker;
    private EventConsumerManager consumerManager;
    private LocalEconomyHandler sqliteHandler;

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        if (consumerManager != null) {
            consumerManager.shutdown();
        }
        if (worker != null) {
            worker.shutdown();
        }
        if (facade != null) {
            facade.shutdown();
        }
        if (sqliteHandler != null) {
            sqliteHandler.close();
        }
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void localDepositReturnsAfterBoundedAdmissionWithoutWaitingForSql() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.findAccountSnapshot(any(UUID.class))).thenReturn(java.util.Optional.of(
                new LocalEconomyHandler.AccountSnapshot(new BigDecimal("100.00"), 1L)));
        CountDownLatch sqlEntered = new CountDownLatch(1);
        CountDownLatch releaseSql = new CountDownLatch(1);

        EconomyWriteQueue queue = new EconomyWriteQueue(2, plugin.getLogger());
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));
        worker = new LocalPersistenceWorker(queue, event -> {
            sqlEntered.countDown();
            assertTrue(releaseSql.await(2, TimeUnit.SECONDS));
            return new LocalPersistenceReceipt(event.balanceAfter(), event.version());
        }, plugin.getLogger());
        UUID uuid = UUID.randomUUID();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> facade.deposit(uuid, new BigDecimal("10.00"),
                    EconomyEvent.EventSource.VAULT_DEPOSIT));
            assertEquals(new BigDecimal("110.00"), result.get(250, TimeUnit.MILLISECONDS),
                    "LOCAL Vault writes must return after bounded admission, without waiting for SQLite");
            assertEquals(new BigDecimal("115.00"), facade.pluginDeposit(uuid, new BigDecimal("5.00"), "Rewards"),
                    "LOCAL plugin writes must use the same accepted-write path");
            assertTrue(queue.hasAcceptedWrite(uuid), "accepted state remains owned until persistence completes");
            assertEquals(new BigDecimal("115.00"), facade.getMemoryState(uuid).balance());
            verify(localHandler, never()).deposit(any(UUID.class), any(), any(BigDecimal.class), anyString());
            assertEquals(2, queue.size());
            worker.start();
            assertTrue(sqlEntered.await(1, TimeUnit.SECONDS));
            assertTrue(queue.hasAcceptedWrite(uuid), "a blocked SQLite commit keeps accepted ownership");
        } finally {
            releaseSql.countDown();
            caller.shutdownNow();
            if (worker != null) {
                worker.shutdown();
            }
        }
    }

    @Test
    void fullLocalAdmissionRejectsBeforeChangingTheBalance() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        when(localHandler.findAccountSnapshot(any(UUID.class))).thenReturn(java.util.Optional.of(
                new LocalEconomyHandler.AccountSnapshot(new BigDecimal("100.00"), 1L)));
        EconomyWriteQueue queue = new EconomyWriteQueue(1, plugin.getLogger());
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));

        UUID occupyingUuid = UUID.randomUUID();
        EconomyEvent occupyingEvent = new EconomyEvent(occupyingUuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "occupying-write", 10L);
        var occupyingReservation = queue.tryReserve(occupyingUuid);
        assertTrue(occupyingReservation.commit(occupyingEvent));
        UUID uuid = UUID.randomUUID();

        assertEquals(new BigDecimal("-1"), facade.deposit(uuid, BigDecimal.ONE,
                EconomyEvent.EventSource.VAULT_DEPOSIT));
        assertEquals(new BigDecimal("100.00"), facade.getMemoryState(uuid).balance());
        assertEquals(1, queue.size());
        queue.complete(occupyingEvent);
    }

    @Test
    void localShutdownKeepsWaitingUntilAcceptedWriteIsPersisted() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "shutdown-drain", 10L);
        CountDownLatch persistenceEntered = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        assertTrue(queue.tryReserve(uuid).commit(event));
        worker = new LocalPersistenceWorker(queue, accepted -> {
            persistenceEntered.countDown();
            releasePersistence.await(20, TimeUnit.SECONDS);
            return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
        }, Logger.getLogger("LocalAsyncPersistenceTest"));
        worker.start();
        assertTrue(persistenceEntered.await(1, TimeUnit.SECONDS));

        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            var shutdown = shutdownCaller.submit(worker::shutdown);
            boolean shutdownFinishedBeforePersistence = true;
            try {
                shutdown.get(11, TimeUnit.SECONDS);
            } catch (TimeoutException expected) {
                shutdownFinishedBeforePersistence = false;
            }
            assertFalse(shutdownFinishedBeforePersistence,
                    "shutdown must not stop the worker while an accepted SQLite write is unresolved");
            assertTrue(queue.hasAcceptedWrite(uuid), "the accepted write remains owned while shutdown waits");

            releasePersistence.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertFalse(queue.hasAcceptedWrite(uuid), "shutdown returns after the receipt completes");
        } finally {
            releasePersistence.countDown();
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void localShutdownClosesAdmissionAndDrainsReservationsAcceptedBeforeShutdown() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID acceptedUuid = UUID.randomUUID();
        UUID lateUuid = UUID.randomUUID();
        EconomyWriteQueue.Reservation acceptedReservation = queue.tryReserve(acceptedUuid);
        CountDownLatch persisted = new CountDownLatch(1);
        worker = new LocalPersistenceWorker(queue, event -> {
            persisted.countDown();
            return new LocalPersistenceReceipt(event.balanceAfter(), event.version());
        }, Logger.getLogger("LocalAsyncPersistenceTest"));
        worker.start();

        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            var shutdown = shutdownCaller.submit(worker::shutdown);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean admissionClosed = false;
            while (System.nanoTime() < deadline && !shutdown.isDone()) {
                EconomyWriteQueue.Reservation lateReservation = queue.tryReserve(lateUuid);
                if (lateReservation == null) {
                    admissionClosed = true;
                    break;
                }
                lateReservation.close();
                Thread.sleep(5);
            }

            assertTrue(admissionClosed, "shutdown must close write admission before draining");
            assertFalse(shutdown.isDone(), "shutdown must wait for a reservation admitted before quiescing");
            EconomyEvent lateEvent = new EconomyEvent(lateUuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                    EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "after-shutdown-start", 10L);
            assertFalse(queue.offer(lateEvent), "non-blocking queue offers must respect shutdown admission closure");
            assertFalse(queue.offerBlocking(lateEvent, 1, TimeUnit.MILLISECONDS),
                    "timed queue offers must respect shutdown admission closure");

            EconomyEvent event = new EconomyEvent(acceptedUuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                    EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "pre-shutdown-reservation", 10L);
            assertTrue(acceptedReservation.commit(event), "pre-shutdown reservations must still be committable");
            assertTrue(persisted.await(1, TimeUnit.SECONDS), "the worker must persist the committed reservation");
            shutdown.get(2, TimeUnit.SECONDS);
            assertFalse(queue.hasAcceptedWrite(acceptedUuid), "shutdown returns only after accepted work completes");
        } finally {
            acceptedReservation.close();
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void localWorkerRetriesARejectedSqlWriteAndHoldsMigrationFenceUntilCommit() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "local-write-1", 10L);
        CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
        CountDownLatch secondAttemptEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondAttempt = new CountDownLatch(1);
        CountDownLatch secondAttemptCompleted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        Thread producerThread = Thread.currentThread();

        MigrationLock.enable();
        MigrationLock.WriteLease producerLease = MigrationLock.tryAcquireWrite();
        MigrationLock.WriteLease persistenceLease = MigrationLock.tryAcquireWrite();
        assertTrue(queue.tryReserve(uuid).commit(event, persistenceLease));
        producerLease.close();
        assertEquals(0, queue.drainAllPendingForPlayer(uuid),
                "disconnect/transfer queue cleanup must not discard accepted LOCAL SQLite work");
        assertEquals(1, queue.size());

        worker = new LocalPersistenceWorker(queue, accepted -> {
            assertFalse(Thread.currentThread() == producerThread, "SQLite persistence must run on the worker");
            if (attempts.incrementAndGet() == 1) {
                firstAttemptEntered.countDown();
                assertTrue(releaseFirstAttempt.await(2, TimeUnit.SECONDS));
                throw new SQLException("temporary SQLite failure");
            }
            secondAttemptEntered.countDown();
            assertTrue(releaseSecondAttempt.await(2, TimeUnit.SECONDS));
            secondAttemptCompleted.countDown();
            return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
        }, Logger.getLogger("LocalAsyncPersistenceTest"));
        worker.start();

        assertTrue(firstAttemptEntered.await(1, TimeUnit.SECONDS));
        assertTrue(queue.hasAcceptedWrite(uuid), "failed worker work must retain its accepted ownership");
        assertFalse(MigrationLock.lock(), "migration cannot snapshot SQLite while accepted local work is pending");
        releaseFirstAttempt.countDown();
        try {
            assertTrue(secondAttemptEntered.await(2, TimeUnit.SECONDS), "the failed event must be retried");
            assertTrue(queue.hasAcceptedWrite(uuid), "accepted ownership remains during the retry attempt");
            assertFalse(MigrationLock.lock(), "migration remains fenced during retry");
        } finally {
            releaseSecondAttempt.countDown();
        }
        assertTrue(secondAttemptCompleted.await(2, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queue.hasAcceptedWrite(uuid) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(queue.hasAcceptedWrite(uuid));
        assertTrue(MigrationLock.lock(), "persistence completion releases the migration fence");
        assertEquals(2, attempts.get());
    }

    @Test
    void localPersistenceLeaseRemainsHeldThroughReceiptReconciliation() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "local-write-receipt", 10L);
        CountDownLatch reconciliationEntered = new CountDownLatch(1);
        CountDownLatch releaseReconciliation = new CountDownLatch(1);
        AtomicInteger reconciliations = new AtomicInteger();

        MigrationLock.enable();
        MigrationLock.WriteLease persistenceLease = MigrationLock.tryAcquireWrite();
        assertTrue(queue.tryReserve(uuid).commit(event, persistenceLease));
        queue.setWriteCompletionHandler((completed, balance, version) -> {
            reconciliations.incrementAndGet();
            reconciliationEntered.countDown();
            try {
                assertTrue(releaseReconciliation.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("receipt reconciliation was interrupted", e);
            }
        });

        Thread completion = new Thread(() -> queue.complete(event, BigDecimal.ONE, 2L));
        try {
            completion.start();
            assertTrue(reconciliationEntered.await(1, TimeUnit.SECONDS));
            assertFalse(MigrationLock.lock(), "migration must wait until the persisted receipt is reconciled");
            releaseReconciliation.countDown();
            completion.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(completion.isAlive(), "write completion should finish after reconciliation is released");
            assertEquals(1, reconciliations.get());
            assertTrue(MigrationLock.lock(), "migration may start after receipt reconciliation releases its lease");
        } finally {
            releaseReconciliation.countDown();
            completion.join(TimeUnit.SECONDS.toMillis(2));
            MigrationLock.unlock();
            MigrationLock.disable();
        }
    }

    @Test
    void acceptedLocalSnapshotAndTransactionRowCommitAtomicallyAndRetryWithoutDuplication() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        Path database = tempDir.resolve("local-economy.db");
        LocalEconomyHandler localHandler = new LocalEconomyHandler(plugin, database.toString());
        UUID uuid = UUID.randomUUID();
        localHandler.setBalance(uuid, "Alice", new BigDecimal("100.00"), "ADMIN_SET");

        EconomyEvent event = new EconomyEvent(uuid, new BigDecimal("10.00"), new BigDecimal("10.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.VAULT_DEPOSIT,
                "retryable-local-write", System.currentTimeMillis() + 1_000);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_local_transaction BEFORE INSERT ON transactions "
                        + "BEGIN SELECT RAISE(ABORT, 'injected transaction failure'); END");
            }

            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                    () -> localHandler.persistAccepted(event, "Alice"));
            assertEquals(new BigDecimal("100.00"), localHandler.getBalance(uuid),
                    "balance projection must roll back when its transaction audit row fails");
            assertEquals(1, localHandler.getTransactionHistory(uuid, 10).size());

            try (var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER reject_local_transaction");
            }
            localHandler.persistAccepted(event, "Alice");
        } finally {
            localHandler.close();
        }

        LocalEconomyHandler reopened = new LocalEconomyHandler(plugin, database.toString());
        try {
            assertEquals(new BigDecimal("110.00"), reopened.getBalance(uuid));
            assertEquals(2, reopened.getTransactionHistory(uuid, 10).size(),
                    "retrying the same accepted event must not duplicate its transaction row");
        } finally {
            reopened.close();
        }
    }

    @Test
    void localModeRouterDelegatesWritesThroughTheSharedFacade() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(noietime.syncmoney.economy.EconomyMode.LOCAL);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        EconomyModeRouter.EconomyFacadeWrapper facadeWrapper = mock(EconomyModeRouter.EconomyFacadeWrapper.class);
        EconomyModeRouter router = new EconomyModeRouter(null, config);
        router.initialize(facadeWrapper, localHandler, null, null);
        UUID uuid = UUID.randomUUID();
        BigDecimal expected = new BigDecimal("110.00");
        when(facadeWrapper.deposit(uuid, BigDecimal.TEN, EconomyEvent.EventSource.VAULT_DEPOSIT))
                .thenReturn(expected);

        assertEquals(expected, router.deposit(uuid, BigDecimal.TEN, EconomyEvent.EventSource.VAULT_DEPOSIT));
        verify(facadeWrapper).deposit(uuid, BigDecimal.TEN, EconomyEvent.EventSource.VAULT_DEPOSIT);
        verify(localHandler, never()).deposit(any(UUID.class), any(), any(BigDecimal.class), anyString());
    }

    @Test
    void localEventConsumerWiresAcceptedFacadeWriteToSqliteWorker() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.isLocalMode()).thenReturn(true);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        sqliteHandler = new LocalEconomyHandler(plugin, tempDir.resolve("wired-local.db").toString());
        UUID uuid = UUID.randomUUID();
        sqliteHandler.setBalance(uuid, "Alice", new BigDecimal("100.00"), "ADMIN_SET");

        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), sqliteHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));
        assertTrue(facade.updateMemoryState(uuid, BigDecimal.ZERO, 1L),
                "fixture models a stale cold-load result before the worker reads SQLite");
        consumerManager = new EventConsumerManager(plugin, config, queue,
                mock(CacheManager.class), mock(RedisManager.class),
                mock(noietime.syncmoney.storage.db.DbWriteQueue.class), null, null,
                mock(noietime.syncmoney.uuid.NameResolver.class), null, null,
                mock(OverflowLogInterface.class), mock(DatabaseManager.class), facade, sqliteHandler);
        consumerManager.initialize();
        consumerManager.start();

        assertEquals(new BigDecimal("10.00"), facade.deposit(uuid, new BigDecimal("10.00"),
                EconomyEvent.EventSource.VAULT_DEPOSIT));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((queue.hasAcceptedWrite(uuid)
                || !new BigDecimal("110.00").equals(facade.getMemoryState(uuid).balance()))
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(queue.hasAcceptedWrite(uuid), "the local worker releases ownership after SQLite commit");
        assertEquals(new BigDecimal("110.00"), sqliteHandler.getBalance(uuid));
        assertEquals(new BigDecimal("110.00"), facade.getMemoryState(uuid).balance(),
                "the persisted SQLite receipt reconciles memory after applying the accepted delta");
        assertEquals(2, sqliteHandler.getTransactionHistory(uuid, 10).size());
    }
}
