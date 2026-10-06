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
import java.io.IOException;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(localHandler.isRecoveryReady()).thenReturn(true);
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
        assertTrue(facade.updateMemoryState(uuid, new BigDecimal("100.00"), 1L),
                "this test exercises admission after the account is already READY");
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
        when(localHandler.isRecoveryReady()).thenReturn(true);
        when(localHandler.findAccountSnapshot(any(UUID.class))).thenReturn(java.util.Optional.of(
                new LocalEconomyHandler.AccountSnapshot(new BigDecimal("100.00"), 1L)));
        EconomyWriteQueue queue = new EconomyWriteQueue(1, plugin.getLogger());
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), localHandler, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));

        UUID occupyingUuid = UUID.randomUUID();
        UUID uuid = UUID.randomUUID();
        assertTrue(facade.updateMemoryState(uuid, new BigDecimal("100.00"), 1L));
        EconomyEvent occupyingEvent = new EconomyEvent(occupyingUuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "occupying-write", 10L);
        var occupyingReservation = queue.tryReserve(occupyingUuid);
        assertTrue(occupyingReservation.commit(occupyingEvent));
        assertEquals(new BigDecimal("-1"), facade.deposit(uuid, BigDecimal.ONE,
                EconomyEvent.EventSource.VAULT_DEPOSIT));
        assertEquals(new BigDecimal("100.00"), facade.getMemoryState(uuid).balance());
        assertEquals(1, queue.size());
        queue.complete(occupyingEvent);
    }

    @Test
    void localShutdownReturnsAtDeadlineButKeepsWorkerAndAcceptedWriteUntilReceipt() throws Exception {
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
        }, unresolved -> {
            throw new IOException("injected unavailable quarantine owner");
        }, Logger.getLogger("LocalAsyncPersistenceTest"), 40L);
        worker.start();
        Thread recoveryActor = worker.recoveryThread();
        assertNotNull(recoveryActor);
        assertTrue(recoveryActor.isAlive(), "the recovery actor starts before the LOCAL writer accepts work");
        assertFalse(recoveryActor.isDaemon(), "the recovery actor owns unresolved writes until receipt or journal");
        assertTrue(persistenceEntered.await(1, TimeUnit.SECONDS));

        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            var shutdown = shutdownCaller.submit(worker::shutdown);
            shutdown.get(1, TimeUnit.SECONDS);
            assertTrue(queue.hasAcceptedWrite(uuid), "the accepted write remains owned after bounded shutdown returns");
            assertTrue(worker.workerThread().isAlive(),
                    "the worker and its persistence dependency remain alive while the write is unresolved");
            assertTrue(worker.workerThread().isDaemon(),
                    "a stuck SQLite call must not itself hold ordinary JVM shutdown after the plugin callback returns");
            assertSame(recoveryActor, worker.recoveryThread(),
                    "shutdown must signal the recovery actor created before accepting writes");
            assertTrue(recoveryActor.isAlive(),
                    "the recovery actor keeps the JVM alive while no receipt or journal exists");

            releasePersistence.countDown();
            worker.workerThread().join(2_000);
            assertFalse(worker.workerThread().isAlive(), "the worker exits once it obtains a persistence receipt");
            assertFalse(queue.hasAcceptedWrite(uuid), "ownership clears only after the receipt completes");
            recoveryActor.join(1_000);
            assertFalse(recoveryActor.isAlive(), "the recovery actor exits after the receipt");
        } finally {
            releasePersistence.countDown();
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void localShutdownRetainsAcceptedWriteAcrossRepeatedSqlFailuresUntilRecovery() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        UUID admissionProbeUuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "shutdown-retry-drain", 10L);
        CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstFailure = new CountDownLatch(1);
        CountDownLatch recoveryAttemptEntered = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            assertTrue(queue.tryReserve(uuid).commit(event));
            worker = new LocalPersistenceWorker(queue, accepted -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    firstAttemptEntered.countDown();
                    releaseFirstFailure.await();
                    failures.incrementAndGet();
                    throw new SQLException("temporary SQLite failure during shutdown");
                }
                if (attempt < 4) {
                    failures.incrementAndGet();
                    throw new SQLException("temporary SQLite failure during shutdown");
                }
                recoveryAttemptEntered.countDown();
                releaseRecovery.await();
                return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
            }, Logger.getLogger("LocalAsyncPersistenceTest"));
            worker.start();
            assertTrue(firstAttemptEntered.await(1, TimeUnit.SECONDS));

            var shutdown = shutdownCaller.submit(worker::shutdown);
            long admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean admissionClosed = false;
            while (!admissionClosed && System.nanoTime() < admissionDeadline) {
                EconomyWriteQueue.Reservation probe = queue.tryReserve(admissionProbeUuid);
                if (probe == null) {
                    admissionClosed = true;
                } else {
                    probe.close();
                    Thread.yield();
                }
            }
            assertTrue(admissionClosed, "shutdown must enter its admission-closing phase while persistence is blocked");
            releaseFirstFailure.countDown();

            assertTrue(recoveryAttemptEntered.await(5, TimeUnit.SECONDS),
                    "the worker must retry failed persistence while shutdown waits");
            assertEquals(4, attempts.get(), "three failed attempts must precede the controlled recovery attempt");
            assertEquals(3, failures.get());
            assertFalse(shutdown.isDone(), "shutdown must remain pending while the accepted write is unresolved");
            assertTrue(queue.hasAcceptedWrite(uuid), "the failed accepted write must retain queue ownership");

            releaseRecovery.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertFalse(queue.hasAcceptedWrite(uuid), "ownership clears only after persistence returns a receipt");
        } finally {
            releaseFirstFailure.countDown();
            releaseRecovery.countDown();
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void shutdownDeadlineJournalsAcceptedWriteAndStartupReplayAppliesItExactlyOnce() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        Path database = tempDir.resolve("deadline-local.db");
        LocalEconomyHandler handler = new LocalEconomyHandler(plugin, database.toString());
        UUID uuid = UUID.randomUUID();
        handler.setBalance(uuid, "Alice", new BigDecimal("100.00"), "ADMIN_SET");
        EconomyEvent event = new EconomyEvent(uuid, new BigDecimal("10.00"), new BigDecimal("110.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.VAULT_DEPOSIT,
                "deadline-local-request", System.currentTimeMillis() + 1_000);

        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        assertTrue(queue.tryReserve(uuid).commit(event));
        CountDownLatch persistenceEntered = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CountDownLatch workerStopped = new CountDownLatch(1);
        worker = new LocalPersistenceWorker(queue, accepted -> {
            persistenceEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releasePersistence.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Model an uninterruptible SQLite call so close deferral is exercised.
                }
            }
            return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
        }, events -> handler.quarantineAcceptedWrites(events, ignored -> "Alice"),
                plugin.getLogger(), 50L, handler::registerPersistenceWorker, () -> {
                    handler.persistenceWorkerStopped();
                    workerStopped.countDown();
                });
        worker.start();

        ExecutorService closeCaller = Executors.newSingleThreadExecutor();
        LocalEconomyHandler reopened = null;
        try {
            assertTrue(persistenceEntered.await(1, TimeUnit.SECONDS));
            long start = System.nanoTime();
            worker.shutdown();
            long shutdownMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(shutdownMillis < 1_000, "shutdown must hand off accepted work after its bounded drain window");
            long handoffDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queue.hasAcceptedWrite(uuid) && System.nanoTime() < handoffDeadline) {
                Thread.sleep(5);
            }
            assertFalse(queue.hasAcceptedWrite(uuid), "durable journal ownership replaces the in-memory reservation");

            var close = closeCaller.submit(handler::close);
            close.get(250, TimeUnit.MILLISECONDS);
            assertTrue(java.nio.file.Files.size(database.resolveSibling("local-persistence.journal")) > 0,
                    "the accepted event must be forced to the recovery journal before ownership is released");

            releasePersistence.countDown();
            assertTrue(workerStopped.await(2, TimeUnit.SECONDS), "the interrupted worker should finish and close SQLite");
            reopened = new LocalEconomyHandler(plugin, database.toString());
            assertTrue(reopened.isRecoveryReady(), "startup replay must complete before LOCAL writes are enabled");
            assertEquals(new BigDecimal("110.00"), reopened.findBalance(uuid).orElseThrow());
            assertEquals(2, reopened.getTransactionHistory(uuid, 10).size());

            reopened.persistAccepted(event, "Alice");
            assertEquals(new BigDecimal("110.00"), reopened.findBalance(uuid).orElseThrow(),
                    "replaying an already committed request must not apply its delta twice");
            assertEquals(2, reopened.getTransactionHistory(uuid, 10).size(),
                    "request-id deduplication must also preserve one audit row");
            assertEquals(0L, java.nio.file.Files.size(database.resolveSibling("local-persistence.journal")),
                    "the journal is cleared only after every entry has a committed SQLite receipt");
        } finally {
            releasePersistence.countDown();
            if (worker != null) {
                worker.shutdown();
            }
            workerStopped.await(2, TimeUnit.SECONDS);
            closeCaller.shutdownNow();
            if (reopened != null) {
                reopened.close();
            }
            handler.close();
        }
    }

    @Test
    void shutdownReturnsAfterJournalFailureWhileWorkerRetainsAndRetriesAcceptedWrite() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "shutdown-journal-failure", 10L);
        CountDownLatch persistenceAttempted = new CountDownLatch(1);
        CountDownLatch quarantineAttempted = new CountDownLatch(1);
        AtomicBoolean persistenceRecovered = new AtomicBoolean();
        AtomicInteger quarantineAttempts = new AtomicInteger();
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            assertTrue(queue.tryReserve(uuid).commit(event));
            worker = new LocalPersistenceWorker(queue, accepted -> {
                persistenceAttempted.countDown();
                if (!persistenceRecovered.get()) {
                    throw new SQLException("injected persistent SQLite failure");
                }
                return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
            }, unresolved -> {
                quarantineAttempts.incrementAndGet();
                quarantineAttempted.countDown();
                throw new IOException("injected full/read-only journal filesystem");
            }, Logger.getLogger("LocalAsyncPersistenceTest"), 30L);
            worker.start();
            Thread recoveryActor = worker.recoveryThread();
            assertNotNull(recoveryActor);
            assertTrue(recoveryActor.isAlive(), "recovery ownership is established before the drain deadline");
            assertTrue(persistenceAttempted.await(1, TimeUnit.SECONDS));

            var shutdown = shutdownCaller.submit(worker::shutdown);
            assertTrue(quarantineAttempted.await(2, TimeUnit.SECONDS),
                    "shutdown must attempt durable quarantine after its drain deadline");
            shutdown.get(500, TimeUnit.MILLISECONDS);

            assertTrue(quarantineAttempts.get() >= 1, "shutdown starts durable quarantine retries asynchronously");
            assertTrue(queue.hasAcceptedWrite(uuid), "failed quarantine must retain accepted write ownership");
            assertTrue(worker.workerThread().isAlive(), "the persistence worker remains available to retry");
            assertTrue(worker.workerThread().isDaemon());
            assertSame(recoveryActor, worker.recoveryThread(),
                    "the deadline signals the already-running recovery owner");
            assertTrue(recoveryActor.isAlive());
            assertFalse(recoveryActor.isDaemon(),
                    "the recovery actor prevents process exit while persistence and journal remain unresolved");

            persistenceRecovered.set(true);
            worker.workerThread().join(2_000);
            assertFalse(worker.workerThread().isAlive(), "the worker exits after persistence recovers");
            assertFalse(queue.hasAcceptedWrite(uuid), "ownership clears only after a persistence receipt");
            recoveryActor.join(1_000);
            assertFalse(recoveryActor.isAlive(), "the recovery actor exits once SQLite acknowledges the write");
        } finally {
            persistenceRecovered.set(true);
            if (worker != null && worker.workerThread() != null) {
                worker.workerThread().join(2_000);
            }
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void shutdownDoesNotWaitForAStalledJournalWriteAfterTheDrainDeadline() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "stalled-journal", 10L);
        CountDownLatch persistenceEntered = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CountDownLatch journalEntered = new CountDownLatch(1);
        CountDownLatch releaseJournal = new CountDownLatch(1);
        CountDownLatch workerStopped = new CountDownLatch(1);
        assertTrue(queue.tryReserve(uuid).commit(event));
        worker = new LocalPersistenceWorker(queue, accepted -> {
            persistenceEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releasePersistence.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Keep the fake SQLite call unresolved until the test releases it.
                }
            }
            return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
        }, unresolved -> {
            journalEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releaseJournal.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Model a filesystem operation that cannot be safely cancelled.
                }
            }
        }, Logger.getLogger("LocalAsyncPersistenceTest"), 30L, ignored -> { }, workerStopped::countDown);
        worker.start();

        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            assertTrue(persistenceEntered.await(1, TimeUnit.SECONDS));
            var shutdown = shutdownCaller.submit(worker::shutdown);
            assertTrue(journalEntered.await(2, TimeUnit.SECONDS),
                    "shutdown should hand off journal I/O after the drain deadline");
            shutdown.get(500, TimeUnit.MILLISECONDS);
            assertTrue(queue.hasAcceptedWrite(uuid),
                    "the reservation remains owned until the journal acknowledges the handoff");

            releaseJournal.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queue.hasAcceptedWrite(uuid) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(queue.hasAcceptedWrite(uuid), "durable journal acknowledgment transfers ownership");
            worker.recoveryThread().join(1_000);
            assertFalse(worker.recoveryThread().isAlive(),
                    "a stuck daemon SQLite call does not hold JVM exit after journal handoff");
            assertTrue(worker.workerThread().isAlive(), "the simulated SQLite call is still unresolved");
            releasePersistence.countDown();
            assertTrue(workerStopped.await(2, TimeUnit.SECONDS));
        } finally {
            releaseJournal.countDown();
            releasePersistence.countDown();
            shutdownCaller.shutdownNow();
            if (worker.workerThread() != null) {
                worker.workerThread().join(2_000);
            }
        }
    }

    @Test
    void unexpectedPersistenceWorkerExitClosesAdmissionAndJournalsAcceptedWrites() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "worker-stopped-unexpectedly", 10L);
        CountDownLatch journaled = new CountDownLatch(1);
        assertTrue(queue.tryReserve(uuid).commit(event));
        worker = new LocalPersistenceWorker(queue, accepted -> {
            throw new AssertionError("injected fatal persistence callback failure");
        }, unresolved -> {
            unresolved.forEach(queue::quarantine);
            journaled.countDown();
        }, Logger.getLogger("LocalAsyncPersistenceTest"), 30L);
        worker.start();

        try {
            worker.workerThread().join(1_000);
            assertFalse(worker.workerThread().isAlive(), "the injected fatal callback ends this worker");
            assertTrue(journaled.await(1, TimeUnit.SECONDS),
                    "worker failure must notify the recovery actor without waiting for plugin shutdown");
            assertFalse(queue.hasAcceptedWrite(uuid), "durable journal acknowledgment transfers accepted ownership");
            assertNull(queue.tryReserve(UUID.randomUUID()),
                    "new LOCAL writes are rejected after the only persistence worker exits");

            worker.shutdown();

            assertFalse(queue.hasAcceptedWrite(uuid));
            worker.recoveryThread().join(1_000);
            assertFalse(worker.recoveryThread().isAlive());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void unexpectedIdleWorkerExitClosesLocalAdmissionAndBlocksSnapshots() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        LocalEconomyHandler handler = new LocalEconomyHandler(plugin, tempDir.resolve("idle-worker-exit.db").toString());
        EconomyWriteQueue queue = mock(EconomyWriteQueue.class);
        org.mockito.Mockito.when(queue.poll(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new AssertionError("injected idle worker failure"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), handler, queue, mock(FallbackEconomyWrapper.class), null,
                mock(OverflowLogInterface.class));
        worker = new LocalPersistenceWorker(queue, accepted -> new LocalPersistenceReceipt(
                accepted.balanceAfter(), accepted.version()), events -> handler.quarantineAcceptedWrites(events,
                ignored -> "Alice"), Logger.getLogger("LocalAsyncPersistenceTest"), 100L,
                handler::registerPersistenceWorker, handler::persistenceWorkerStopped,
                handler::persistenceWorkerFailed, handler::persistenceRecoveryHandoffCompleted);

        try {
            worker.start();
            worker.workerThread().join(1_000);

            assertFalse(worker.workerThread().isAlive(), "the injected idle failure ends the worker");
            assertFalse(handler.isRecoveryReady(), "an exited worker invalidates LOCAL recovery readiness");
            assertFalse(facade.isLocalRecoveryReady(), "migration must not snapshot pre-replay LOCAL data");
            assertThrows(IllegalStateException.class, () -> handler.getAllBalancesSorted(10));
            assertNull(queue.tryReserve(UUID.randomUUID()), "idle worker failure permanently closes LOCAL admission");
        } finally {
            if (worker != null) {
                worker.shutdown();
            }
            if (facade != null) {
                facade.shutdown();
                facade = null;
            }
            handler.close();
        }
    }

    @Test
    void localSnapshotReadinessWaitsForAcceptedWritesToReceiveReceipts() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        LocalEconomyHandler handler = mock(LocalEconomyHandler.class);
        when(handler.isRecoveryReady()).thenReturn(true);
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), handler, queue, mock(FallbackEconomyWrapper.class), null,
                mock(OverflowLogInterface.class));

        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.TEN, BigDecimal.TEN, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "migration-pending", 10L);
        assertTrue(facade.isLocalRecoveryReady(), "an idle, recovered LOCAL store may be snapshotted");
        assertTrue(queue.tryReserve(uuid).commit(event));

        assertFalse(facade.isLocalRecoveryReady(),
                "accepted writes remain outside SQLite until their persistence receipt completes");

        queue.complete(event, new BigDecimal("10.00"), 2L);
        assertTrue(facade.isLocalRecoveryReady(), "a completed persistence receipt restores snapshot readiness");
    }

    @Test
    void unavailableRecoveryActorNeverRetriesJournalSynchronouslyOnShutdownCaller() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(2);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "recovery-actor-dead", 10L);
        assertTrue(queue.tryReserve(uuid).commit(event));
        AtomicBoolean persistenceRecovered = new AtomicBoolean();
        CountDownLatch persistenceEntered = new CountDownLatch(1);
        worker = new LocalPersistenceWorker(queue, accepted -> {
            persistenceEntered.countDown();
            if (!persistenceRecovered.get()) {
                throw new SQLException("injected SQLite failure");
            }
            return new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version());
        }, unresolved -> {
            throw new AssertionError("injected fatal recovery actor failure");
        }, Logger.getLogger("LocalAsyncPersistenceTest"), 40L);
        worker.start();
        worker.recoveryThread().setUncaughtExceptionHandler((thread, failure) -> { });
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();

        try {
            assertTrue(persistenceEntered.await(1, TimeUnit.SECONDS));
            Future<?> shutdown = shutdownCaller.submit(worker::shutdown);
            shutdown.get(1, TimeUnit.SECONDS);

            worker.recoveryThread().join(1_000);
            assertFalse(worker.recoveryThread().isAlive(), "the injected fatal actor error terminates the actor");
            assertTrue(queue.hasAcceptedWrite(uuid), "failed handoff retains ownership until SQLite receipts the event");

            persistenceRecovered.set(true);
            worker.workerThread().join(2_000);
            assertFalse(worker.workerThread().isAlive());
            assertFalse(queue.hasAcceptedWrite(uuid), "a later SQLite receipt releases the accepted write");
        } finally {
            persistenceRecovered.set(true);
            if (worker.workerThread() != null) {
                worker.workerThread().join(2_000);
            }
            shutdownCaller.shutdownNow();
            worker.shutdown();
        }
    }

    @Test
    void workerStartFailureClearsRegisteredSQLiteWorkerBeforeHandlerClose() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        LocalEconomyHandler handler = new LocalEconomyHandler(plugin, tempDir.resolve("worker-start-failure.db").toString());
        var connectionField = LocalEconomyHandler.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) connectionField.get(handler);
        worker = new LocalPersistenceWorker(new EconomyWriteQueue(2), accepted ->
                new LocalPersistenceReceipt(accepted.balanceAfter(), accepted.version()), events -> { },
                Logger.getLogger("LocalAsyncPersistenceTest"), 100L, registeredThread -> {
                    handler.registerPersistenceWorker(registeredThread);
                    throw new IllegalStateException("injected worker registration/start failure");
                }, handler::persistenceWorkerStopped, handler::persistenceWorkerFailed,
                handler::persistenceRecoveryHandoffCompleted);

        try {
            assertThrows(IllegalStateException.class, worker::start);
            handler.close();
            assertTrue(connection.isClosed(), "a failed start must not leave a dead worker registered with SQLite");
        } finally {
            worker.shutdown();
            handler.close();
        }
    }

    @Test
    void shutdownJournalsReservationsThatCommitAfterDeadWorkerHandoffStarts() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(3);
        UUID uuid = UUID.randomUUID();
        EconomyEvent first = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "dead-worker-first", 11L);
        EconomyEvent second = new EconomyEvent(uuid, BigDecimal.ONE, new BigDecimal("2.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "dead-worker-second", 12L);
        assertTrue(queue.tryReserve(uuid).commit(first));
        EconomyWriteQueue.Reservation laterReservation = queue.tryReserve(uuid);
        assertTrue(laterReservation != null);

        CountDownLatch firstJournaled = new CountDownLatch(1);
        CountDownLatch secondJournaled = new CountDownLatch(1);
        AtomicInteger journalCalls = new AtomicInteger();
        worker = new LocalPersistenceWorker(queue, accepted -> {
            throw new AssertionError("injected fatal persistence callback failure");
        }, unresolved -> {
            unresolved.forEach(queue::quarantine);
            if (journalCalls.incrementAndGet() == 1) {
                firstJournaled.countDown();
            } else {
                secondJournaled.countDown();
            }
        }, Logger.getLogger("LocalAsyncPersistenceTest"), 2_000L);
        worker.start();
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();

        try {
            worker.workerThread().join(1_000);
            assertFalse(worker.workerThread().isAlive(), "the injected fatal callback ends this worker");
            Future<?> shutdown = shutdownCaller.submit(worker::shutdown);

            assertTrue(firstJournaled.await(1, TimeUnit.SECONDS),
                    "shutdown must journal committed writes after the persistence worker dies");
            assertTrue(laterReservation.commit(second), "a reservation accepted before shutdown may still commit");
            assertTrue(secondJournaled.await(1, TimeUnit.SECONDS),
                    "handoff must continue until every pre-shutdown reservation is journaled");
            shutdown.get(1, TimeUnit.SECONDS);
            assertFalse(queue.hasOutstandingWrites());
            worker.recoveryThread().join(1_000);
            assertFalse(worker.recoveryThread().isAlive());
        } finally {
            laterReservation.close();
            shutdownCaller.shutdownNow();
            worker.shutdown();
        }
    }

    @Test
    void shutdownCancelsUncommittedReservationsAfterDeadWorkerDrainDeadline() throws Exception {
        EconomyWriteQueue queue = new EconomyWriteQueue(3);
        UUID uuid = UUID.randomUUID();
        EconomyEvent committed = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.ONE, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "dead-worker-committed", 13L);
        EconomyEvent late = new EconomyEvent(uuid, BigDecimal.ONE, new BigDecimal("2.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "dead-worker-uncommitted", 14L);
        assertTrue(queue.tryReserve(uuid).commit(committed));
        EconomyWriteQueue.Reservation reservation = queue.tryReserve(uuid);
        assertTrue(reservation != null);

        CountDownLatch journaled = new CountDownLatch(1);
        worker = new LocalPersistenceWorker(queue, accepted -> {
            throw new AssertionError("injected fatal persistence callback failure");
        }, unresolved -> {
            unresolved.forEach(queue::quarantine);
            journaled.countDown();
        }, Logger.getLogger("LocalAsyncPersistenceTest"), 100L);
        worker.start();

        try {
            worker.workerThread().join(1_000);
            assertFalse(worker.workerThread().isAlive(), "the injected fatal callback ends this worker");
            worker.shutdown();

            assertTrue(journaled.await(1, TimeUnit.SECONDS), "committed work is journaled");
            worker.recoveryThread().join(2_000);
            assertFalse(worker.recoveryThread().isAlive(),
                    "a never-committed reservation must not hold host shutdown past its bounded deadline");
            assertFalse(queue.hasOutstandingWrites());
            assertFalse(reservation.commit(late), "a reservation cancelled at the drain deadline cannot mutate state");
        } finally {
            reservation.close();
            worker.shutdown();
        }
    }

    @Test
    void corruptRecoveryJournalPreservesEvidenceAndRejectsLocalMutations() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        Path database = tempDir.resolve("corrupt-journal-local.db");
        LocalEconomyHandler initial = new LocalEconomyHandler(plugin, database.toString());
        initial.close();
        Path journal = database.resolveSibling("local-persistence.journal");
        byte[] corruptRecord = "SYNCLOCAL1\ttruncated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path expected = tempDir.resolve("corrupt-journal-expected.bin");
        java.nio.file.Files.write(expected, corruptRecord);
        java.nio.file.Files.write(journal, corruptRecord);

        LocalEconomyHandler recovered = new LocalEconomyHandler(plugin, database.toString());
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.playerProtection().isPlayerProtectionEnabledInLocalMode()).thenReturn(false);
        facade = new EconomyFacade(plugin, config, mock(CacheManager.class), mock(RedisManager.class),
                mock(DatabaseManager.class), recovered, queue, mock(FallbackEconomyWrapper.class),
                null, mock(OverflowLogInterface.class));

        try {
            assertFalse(recovered.isRecoveryReady(), "corrupt recovery evidence must keep LOCAL writes disabled");
            assertEquals(new BigDecimal("-1"), facade.deposit(UUID.randomUUID(), BigDecimal.TEN,
                    EconomyEvent.EventSource.VAULT_DEPOSIT));
            assertEquals(0, queue.size());
            assertEquals(-1, java.nio.file.Files.mismatch(journal, expected),
                    "startup must preserve an invalid journal for operator recovery");
        } finally {
            facade.shutdown();
            recovered.close();
        }
    }

    @Test
    void unresolvedRecoveryBlocksDirectBalanceAndAggregateReadsAndCachedFacadeReadiness() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        Path database = tempDir.resolve("unresolved-read-local.db");
        LocalEconomyHandler initial = new LocalEconomyHandler(plugin, database.toString());
        UUID uuid = UUID.randomUUID();
        initial.setBalance(uuid, "Alice", new BigDecimal("75.00"), "ADMIN_SET");
        initial.close();
        Path journal = database.resolveSibling("local-persistence.journal");
        java.nio.file.Files.writeString(journal, "SYNCLOCAL1\ttruncated");

        LocalEconomyHandler recovered = new LocalEconomyHandler(plugin, database.toString());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        EconomyFacade localFacade = new EconomyFacade(plugin, config, mock(CacheManager.class),
                mock(RedisManager.class), mock(DatabaseManager.class), recovered,
                new EconomyWriteQueue(4), mock(FallbackEconomyWrapper.class), null,
                mock(OverflowLogInterface.class));
        try {
            assertFalse(recovered.isRecoveryReady());
            assertThrows(IllegalStateException.class, () -> recovered.getBalance(uuid));
            assertThrows(IllegalStateException.class, () -> recovered.getAllBalancesSorted(10));
            assertThrows(IllegalStateException.class, recovered::getTotalBalance);
            assertThrows(IllegalStateException.class, recovered::getTotalPlayerCount);

            localFacade.updateMemoryState(uuid, new BigDecimal("90.00"), 3L);
            assertNull(localFacade.getBalanceSync(uuid),
                    "synchronous balance reads must not expose cached state before LOCAL recovery is ready");
            assertFalse(localFacade.ensureLoaded(uuid),
                    "a cached balance cannot make the account ready while LOCAL recovery is unresolved");
            assertThrows(IllegalStateException.class, () -> localFacade.getBalance(uuid));
        } finally {
            localFacade.shutdown();
            recovered.close();
        }
    }

    @Test
    void closeDefersForRegisteredPersistenceWorkerEvenBeforeItsThreadStarts() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        LocalEconomyHandler handler = new LocalEconomyHandler(plugin, tempDir.resolve("close-race-local.db").toString());
        Thread registeredButNotStarted = new Thread(() -> { });
        handler.registerPersistenceWorker(registeredButNotStarted);
        var connectionField = LocalEconomyHandler.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) connectionField.get(handler);

        handler.close();
        assertFalse(connection.isClosed(),
                "close must not race between worker registration and Thread.start");

        handler.persistenceWorkerStopped();
        assertTrue(connection.isClosed(),
                "SQLite closes when the registered worker reports that it stopped");
    }

    @Test
    void closeRequestReturnsWhileColdSqliteOperationOwnsHandlerMonitor() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        LocalEconomyHandler handler = new LocalEconomyHandler(plugin,
                tempDir.resolve("close-with-cold-read.db").toString());
        var connectionField = LocalEconomyHandler.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) connectionField.get(handler);
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        Thread coldRead = new Thread(() -> {
            synchronized (handler) {
                monitorHeld.countDown();
                try {
                    releaseMonitor.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "test-blocked-local-cold-read");
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();
        try {
            coldRead.start();
            assertTrue(monitorHeld.await(1, TimeUnit.SECONDS));
            var close = shutdownCaller.submit(() -> {
                handler.close();
                return true;
            });

            boolean closeReturnedBeforeReadReleased;
            try {
                close.get(250, TimeUnit.MILLISECONDS);
                closeReturnedBeforeReadReleased = true;
            } catch (TimeoutException timeout) {
                closeReturnedBeforeReadReleased = false;
            }

            releaseMonitor.countDown();
            coldRead.join(TimeUnit.SECONDS.toMillis(1));
            assertTrue(close.get(1, TimeUnit.SECONDS), "close request must finish after the handler lock is released");
            long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!connection.isClosed() && System.nanoTime() < closeDeadline) {
                Thread.sleep(5);
            }
            assertTrue(connection.isClosed(), "deferred close must release SQLite after the cold read completes");
            assertTrue(closeReturnedBeforeReadReleased,
                    "plugin shutdown must not block indefinitely waiting for a cold SQLite read monitor");
        } finally {
            releaseMonitor.countDown();
            coldRead.join(TimeUnit.SECONDS.toMillis(1));
            shutdownCaller.shutdownNow();
            handler.close();
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
    void duplicateRequestIdWithDifferentPayloadIsRejectedWithoutChangingPersistedBalance() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalAsyncPersistenceTest"));
        FileConfiguration fileConfiguration = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfiguration);
        when(fileConfiguration.getString("server-name", "local")).thenReturn("test-server");

        LocalEconomyHandler handler = new LocalEconomyHandler(plugin, tempDir.resolve("request-payload-local.db").toString());
        UUID uuid = UUID.randomUUID();
        handler.setBalance(uuid, "Alice", new BigDecimal("100.00"), "ADMIN_SET");
        long timestamp = System.currentTimeMillis() + 1_000;
        EconomyEvent accepted = new EconomyEvent(uuid, new BigDecimal("10.00"), new BigDecimal("110.00"), 2L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.VAULT_DEPOSIT,
                "same-request-different-payload", timestamp);
        try {
            handler.persistAccepted(accepted, "Alice");
            EconomyEvent conflictingRetry = new EconomyEvent(uuid, new BigDecimal("25.00"),
                    new BigDecimal("125.00"), 3L, EconomyEvent.EventType.DEPOSIT,
                    EconomyEvent.EventSource.VAULT_DEPOSIT, accepted.requestId(), timestamp);

            assertThrows(SQLException.class, () -> handler.persistAccepted(conflictingRetry, "Alice"),
                    "a request id is an identity for one immutable accepted payload");
            assertEquals(new BigDecimal("110.00"), handler.getBalance(uuid));
            assertEquals(2, handler.getTransactionHistory(uuid, 10).size(),
                    "the conflicting payload must not create a second balance or audit effect");
        } finally {
            handler.close();
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
