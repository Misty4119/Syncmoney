package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.AuditConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

class AuditLoggerConfirmedPersistenceTest {
    @TempDir
    Path temp;

    @Test
    void closeLogsDisabledAuditOwnerWithZeroPendingCounters() {
        Logger javaLogger = Logger.getAnonymousLogger();
        javaLogger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        javaLogger.addHandler(capture);
        AuditLogger logger = logger(null, javaLogger);

        try {
            logger.close();

            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=audit status=disabled enabled=false buffered=0 inFlight=0 retry=0 deadLetter=0 dropped=0 lateRejected=0")));
        } finally {
            javaLogger.removeHandler(capture);
        }
    }

    @Test
    void closeDrainsAllBufferedBatches() throws Exception {
        try (HikariDataSource dataSource = dataSource("batch-boundary")) {
            Logger javaLogger = Logger.getAnonymousLogger();
            javaLogger.setLevel(Level.ALL);
            var messages = new CopyOnWriteArrayList<String>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            javaLogger.addHandler(capture);
            try {
                AuditLogger logger = logger(dataSource, javaLogger);
                Queue<AuditRecord> buffer = queue(logger, "buffer");
                for (int i = 0; i < 11; i++) {
                    buffer.add(record("batch-boundary-" + i, UUID.randomUUID(), "player", "server"));
                }

                logger.close();

                assertEquals(11, countAuditRows(dataSource));
                assertEquals(0, logger.getBufferSize(), "close must drain records across batch boundaries");
                assertTrue(messages.stream().anyMatch(message -> message.equals(
                        "T21_DRAIN owner=audit status=drained enabled=true buffered=0 inFlight=0 retry=0 deadLetter=0 dropped=0 lateRejected=0")));
            } finally {
                javaLogger.removeHandler(capture);
            }
        }
    }

    @Test
    void deadLetterRetryPreservesRecordsBeyondOneBatch() throws Exception {
        try (HikariDataSource dataSource = dataSource("dead-letter-boundary")) {
            AuditLogger logger = logger(dataSource);
            Queue<AuditRecord> deadLetters = queue(logger, "deadLetterQueue");
            for (int i = 0; i < 11; i++) {
                deadLetters.add(record("dead-letter-boundary-" + i, UUID.randomUUID(), "player", "server"));
            }

            logger.retryDeadLetterQueue();

            assertEquals(10, countAuditRows(dataSource));
            assertEquals(1, logger.getDeadLetterQueueSize(), "the record beyond batchSize must remain retryable");
        }
    }

    @Test
    void closeWaitsForAsyncFlushBeforeDraining() throws Exception {
        try (HikariDataSource dataSource = dataSource("async-flush-close")) {
            Logger javaLogger = Logger.getAnonymousLogger();
            javaLogger.setLevel(Level.ALL);
            var messages = new CopyOnWriteArrayList<String>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            javaLogger.addHandler(capture);
            AuditLogger logger = logger(dataSource, javaLogger);
            Field flushingField = AuditLogger.class.getDeclaredField("flushing");
            flushingField.setAccessible(true);
            AtomicBoolean flushing = (AtomicBoolean) flushingField.get(logger);
            flushing.set(true);
            queue(logger, "buffer").add(record("async-flush-close", UUID.randomUUID(), "player", "server"));
            Field lifecycleLockField = AuditLogger.class.getDeclaredField("persistenceLifecycleLock");
            lifecycleLockField.setAccessible(true);
            Object lifecycleLock = lifecycleLockField.get(logger);
            CountDownLatch closeStarted = new CountDownLatch(1);
            Thread closer = new Thread(() -> {
                closeStarted.countDown();
                logger.close();
            });

            try {
                closer.start();
                assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertTrue(closer.isAlive(), "close must await an async flush already accepted by the owner");

                flushing.set(false);
                synchronized (lifecycleLock) {
                    lifecycleLock.notifyAll();
                }
                closer.join(5_000);
                assertTrue(!closer.isAlive(), "close must continue after the async flush releases ownership");
                assertEquals(1, countAuditRows(dataSource));
                assertTrue(messages.stream().anyMatch(message -> message.equals(
                        "T21_DRAIN owner=audit status=drained enabled=true buffered=0 inFlight=0 retry=0 deadLetter=0 dropped=0 lateRejected=0")));
            } finally {
                flushing.set(false);
                synchronized (lifecycleLock) {
                    lifecycleLock.notifyAll();
                }
                closer.join(5_000);
                javaLogger.removeHandler(capture);
            }
        }
    }

    @Test
    void closeRetriesAllDeadLetterBatches() throws Exception {
        try (HikariDataSource dataSource = dataSource("dead-letter-close")) {
            AuditLogger logger = logger(dataSource);
            Queue<AuditRecord> deadLetters = queue(logger, "deadLetterQueue");
            for (int i = 0; i < 11; i++) {
                deadLetters.add(record("dead-letter-close-" + i, UUID.randomUUID(), "player", "server"));
            }

            logger.close();

            assertEquals(11, countAuditRows(dataSource));
            assertEquals(0, logger.getDeadLetterQueueSize(), "close must retry all dead-letter batches");
        }
    }

    @Test
    void closeWaitsForConfirmedAuditWriteAndRejectsNewWritesAfterClosing() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        HikariDataSource dataSource = blockingDataSource(writeEntered, releaseWrite);
        Logger javaLogger = Logger.getAnonymousLogger();
        javaLogger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        javaLogger.addHandler(capture);
        AuditLogger logger = logger(dataSource, javaLogger);
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                logger.persistConfirmedRecord(record("close-in-flight", UUID.randomUUID(), "player", "server"));
            } catch (Throwable failure) {
                writeFailure.set(failure);
            }
        });
        Thread closer = new Thread(logger::close);

        try {
            writer.start();
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
            CountDownLatch closeStarted = new CountDownLatch(1);
            closer = new Thread(() -> {
                closeStarted.countDown();
                logger.close();
            });
            closer.start();
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(closer.isAlive(), "close must wait while a confirmed audit write owns persistence");

            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            assertTrue(!writer.isAlive() && !closer.isAlive(), "audit write and close must finish");
            assertEquals(null, writeFailure.get());
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=audit status=drained enabled=true buffered=0 inFlight=0 retry=0 deadLetter=0 dropped=0 lateRejected=0")));
            assertThrows(SQLException.class, () -> logger.persistConfirmedRecord(
                    record("after-close", UUID.randomUUID(), "player", "server")));
            assertTrue(messages.stream().anyMatch(message -> message.startsWith(
                    "T21_DRAIN owner=audit status=pending enabled=true buffered=0 ")
                    && message.endsWith("lateRejected=1")),
                    "a rejected confirmed write must make the audit owner visibly pending");
        } finally {
            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            javaLogger.removeHandler(capture);
        }
    }

    @Test
    void closeKeepsWaitingAfterInterruptionUntilConfirmedAuditWriteFinishes() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        HikariDataSource dataSource = blockingDataSource(writeEntered, releaseWrite);
        Logger javaLogger = Logger.getAnonymousLogger();
        javaLogger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        javaLogger.addHandler(capture);
        AuditLogger logger = logger(dataSource, javaLogger);
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                logger.persistConfirmedRecord(record("interrupted-close", UUID.randomUUID(), "player", "server"));
            } catch (Throwable failure) {
                writeFailure.set(failure);
            }
        });
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicBoolean closeInterruptRestored = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            logger.close();
            closeInterruptRestored.set(Thread.currentThread().isInterrupted());
        });

        try {
            writer.start();
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
            closer.start();
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            closer.interrupt();
            Thread.sleep(100);
            assertTrue(closer.isAlive(), "interruption must not release the database owner before its write finishes");

            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            assertTrue(!writer.isAlive() && !closer.isAlive(), "audit write and close must finish");
            assertEquals(null, writeFailure.get());
            assertTrue(closeInterruptRestored.get(), "close must restore interruption after safe draining");
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=audit status=drained enabled=true buffered=0 inFlight=0 retry=0 deadLetter=0 dropped=0 lateRejected=0")));
        } finally {
            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            javaLogger.removeHandler(capture);
        }
    }

    @Test
    void ordinaryAuditLogAfterCloseIsRejectedAndMakesDrainPending() throws Exception {
        try (HikariDataSource dataSource = dataSource("late-log")) {
            Logger javaLogger = Logger.getAnonymousLogger();
            javaLogger.setLevel(Level.ALL);
            var messages = new CopyOnWriteArrayList<String>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            javaLogger.addHandler(capture);
            try {
                AuditLogger logger = logger(dataSource, javaLogger);
                logger.close();

                logger.log(record("late-after-close", UUID.randomUUID(), "player", "server"));

                assertEquals(0, logger.getBufferSize(), "a late call must not strand a record after the final flush");
                assertTrue(messages.stream().anyMatch(message -> message.startsWith(
                        "T21_DRAIN owner=audit status=pending enabled=true buffered=0 ")
                        && message.endsWith("lateRejected=1")));
            } finally {
                javaLogger.removeHandler(capture);
            }
        }
    }

    @Test
    void closeWaitsForAnActiveOrdinaryAuditLogBeforeFinalFlush() throws Exception {
        try (HikariDataSource dataSource = dataSource("active-ordinary-log")) {
            Logger javaLogger = Logger.getAnonymousLogger();
            javaLogger.setLevel(Level.ALL);
            var messages = new CopyOnWriteArrayList<String>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            javaLogger.addHandler(capture);
            AuditLogger logger = logger(dataSource, javaLogger);
            Field mergeLockField = AuditLogger.class.getDeclaredField("mergeLock");
            mergeLockField.setAccessible(true);
            Object mergeLock = mergeLockField.get(logger);
            Field activeLogCallsField = AuditLogger.class.getDeclaredField("activeLogCalls");
            activeLogCallsField.setAccessible(true);
            Field lifecycleLockField = AuditLogger.class.getDeclaredField("persistenceLifecycleLock");
            lifecycleLockField.setAccessible(true);
            Object lifecycleLock = lifecycleLockField.get(logger);
            Thread writer = new Thread(() -> logger.log(
                    record("active-ordinary-log", UUID.randomUUID(), "player", "server")));
            CountDownLatch closeStarted = new CountDownLatch(1);
            Thread closer = new Thread(() -> {
                closeStarted.countDown();
                logger.close();
            });

            try {
                synchronized (mergeLock) {
                    writer.start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    int activeCalls = 0;
                    while (activeCalls == 0 && System.nanoTime() < deadline) {
                        synchronized (lifecycleLock) {
                            activeCalls = activeLogCallsField.getInt(logger);
                        }
                        Thread.yield();
                    }
                    assertEquals(1, activeCalls,
                            "ordinary log admission must be recorded before it waits on audit mutation");

                    closer.start();
                    assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
                    Thread.sleep(100);
                    assertTrue(closer.isAlive(), "close must wait while an ordinary log owns audit admission");
                }

                writer.join(5_000);
                closer.join(5_000);
                assertTrue(!writer.isAlive() && !closer.isAlive(), "ordinary log and close must finish");
                try (Connection connection = dataSource.getConnection();
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                    result.next();
                    assertEquals(1, result.getInt(1), "close must flush the admitted ordinary record");
                }
                assertTrue(messages.stream().anyMatch(message -> message.startsWith(
                        "T21_DRAIN owner=audit status=drained enabled=true buffered=0 inFlight=0 ")
                        && message.endsWith("lateRejected=0")));
            } finally {
                writer.join(5_000);
                closer.join(5_000);
                javaLogger.removeHandler(capture);
            }
        }
    }

    @Test
    void closeAndLateLogPublishAuditDrainMarkersInLifecycleOrder() throws Exception {
        try (HikariDataSource dataSource = dataSource("late-close-marker-ordering")) {
            CountDownLatch closeMarkerEntered = new CountDownLatch(1);
            CountDownLatch releaseCloseMarker = new CountDownLatch(1);
            AtomicBoolean blockFirstMarker = new AtomicBoolean(true);
            Logger javaLogger = new Logger("AuditLoggerCloseMarkerOrderingTest", null) {
                @Override
                public void info(String message) {
                    if (message.startsWith("T21_DRAIN owner=audit") && blockFirstMarker.compareAndSet(true, false)) {
                        closeMarkerEntered.countDown();
                        try {
                            if (!releaseCloseMarker.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("timed out waiting to release the close marker");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("close marker was interrupted", interrupted);
                        }
                    }
                    super.info(message);
                }
            };
            javaLogger.setLevel(Level.ALL);
            javaLogger.setUseParentHandlers(false);
            var messages = new CopyOnWriteArrayList<String>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            javaLogger.addHandler(capture);
            AuditLogger logger = logger(dataSource, javaLogger);
            CountDownLatch lateLogStarted = new CountDownLatch(1);
            CountDownLatch lateLogFinished = new CountDownLatch(1);
            Thread closer = new Thread(logger::close, "audit-close-marker-ordering");
            Thread lateWriter = new Thread(() -> {
                lateLogStarted.countDown();
                logger.log(record("late-during-close-marker", UUID.randomUUID(), "player", "server"));
                lateLogFinished.countDown();
            }, "audit-late-log-marker-ordering");

            try {
                closer.start();
                assertTrue(closeMarkerEntered.await(2, TimeUnit.SECONDS), "close must reach its final drain marker");
                lateWriter.start();
                assertTrue(lateLogStarted.await(2, TimeUnit.SECONDS));
                boolean completedBeforeCloseMarker = lateLogFinished.await(100, TimeUnit.MILLISECONDS);
                assertTrue(!completedBeforeCloseMarker,
                        "a late log must wait until the close drain marker is published");

                releaseCloseMarker.countDown();
                assertTrue(lateLogFinished.await(2, TimeUnit.SECONDS));
                closer.join(2_000);
                lateWriter.join(2_000);
                assertTrue(!closer.isAlive() && !lateWriter.isAlive(), "close and late log must finish");

                var drainMarkers = messages.stream().filter(message -> message.startsWith("T21_DRAIN owner=audit ")).toList();
                assertEquals(2, drainMarkers.size());
                assertTrue(drainMarkers.get(0).contains("status=drained") && drainMarkers.get(0).endsWith("lateRejected=0"));
                assertTrue(drainMarkers.get(1).contains("status=pending") && drainMarkers.get(1).endsWith("lateRejected=1"));
            } finally {
                releaseCloseMarker.countDown();
                closer.join(2_000);
                lateWriter.join(2_000);
                javaLogger.removeHandler(capture);
            }
        }
    }

    @Test
    void retryAfterCommittedAuditRowIsIdempotent() throws Exception {
        try (HikariDataSource dataSource = dataSource("idempotent")) {
            AuditLogger logger = logger(dataSource);
            UUID player = UUID.randomUUID();
            AuditRecord first = record("audit-confirmed-1", player, "first-name", "server-a");
            AuditRecord retry = record(first.id(), player, "renamed-player", "server-b");

            logger.persistConfirmedRecord(first);
            logger.persistConfirmedRecord(retry);

            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void confirmedAuditPersistencePropagatesBackendFailure() throws Exception {
        try (HikariDataSource dataSource = dataSource("unavailable")) {
            AuditLogger logger = logger(dataSource);
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("DROP TABLE syncmoney_audit_log");
            }

            assertThrows(SQLException.class, () -> logger.persistConfirmedRecord(record(
                    "audit-failure-1", UUID.randomUUID(), "player", "server")));
        }
    }

    private HikariDataSource dataSource(String suffix) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + temp.resolve(suffix + ".db"));
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    private static HikariDataSource blockingDataSource(CountDownLatch writeEntered, CountDownLatch releaseWrite)
            throws SQLException {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(0);
        doAnswer(invocation -> {
            writeEntered.countDown();
            if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new SQLException("test write release timed out");
            return new int[]{1};
        }).when(statement).executeBatch();
        return dataSource;
    }

    private static int countAuditRows(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
            result.next();
            return result.getInt(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Queue<AuditRecord> queue(AuditLogger logger, String fieldName) throws Exception {
        Field field = AuditLogger.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Queue<AuditRecord>) field.get(logger);
    }

    private static AuditLogger logger(HikariDataSource dataSource) {
        return logger(dataSource, Logger.getLogger("AuditLoggerConfirmedPersistenceTest"));
    }

    private static AuditLogger logger(HikariDataSource dataSource, Logger javaLogger) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(javaLogger);
        AuditConfig auditConfig = mock(AuditConfig.class);
        when(auditConfig.isAuditEnabled()).thenReturn(true);
        when(auditConfig.getAuditFlushIntervalMs()).thenReturn(0L);
        when(auditConfig.getAuditBatchSize()).thenReturn(10);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.audit()).thenReturn(auditConfig);
        return new AuditLogger(plugin, config, dataSource);
    }

    private static AuditRecord record(String id, UUID player, String playerName, String server) {
        return new AuditRecord(id, 1_800_000_000_000L, 0, AuditRecord.AuditType.DEPOSIT,
                player, playerName, BigDecimal.TEN, new BigDecimal("110.00"),
                EconomyEvent.EventSource.VAULT_DEPOSIT, server, null, null, null, 1);
    }
}
