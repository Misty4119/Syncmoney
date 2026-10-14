package noietime.syncmoney.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.AuditConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.sql.PreparedStatement;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

class HybridAuditManagerConfirmedPersistenceTest {
    @TempDir
    Path temp;

    @Test
    void closeWaitsForActiveMigrationAndReportsRequeuedWork() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(logger);
        HybridAuditManager manager = new HybridAuditManager(plugin, config(true), unavailableRedis(), null, null);

        try {
            Field queueField = HybridAuditManager.class.getDeclaredField("migrationQueue");
            queueField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<AuditRecord> migrationQueue =
                    (ConcurrentLinkedQueue<AuditRecord>) queueField.get(manager);
            migrationQueue.add(record("close-race"));
            Field migratingField = HybridAuditManager.class.getDeclaredField("migrating");
            migratingField.setAccessible(true);
            AtomicBoolean migrating = (AtomicBoolean) migratingField.get(manager);
            migrating.set(true);

            CountDownLatch closeStarted = new CountDownLatch(1);
            Thread close = new Thread(() -> {
                closeStarted.countDown();
                manager.close();
            });
            close.start();
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(close.isAlive(), "close must wait for the active migration before draining its queue");

            migrating.set(false);
            Field lifecycleLockField = HybridAuditManager.class.getDeclaredField("migrationLifecycleLock");
            lifecycleLockField.setAccessible(true);
            Object lifecycleLock = lifecycleLockField.get(manager);
            synchronized (lifecycleLock) {
                lifecycleLock.notifyAll();
            }
            close.join(5_000);
            assertFalse(close.isAlive(), "close must finish after the active migration releases ownership");
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=hybridAudit status=pending enabled=true queued=1 inFlight=0 retry=0 lateRejected=0")));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void closeLogsFinalHybridMigrationQueueAndInFlightState() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(logger);
        HybridAuditManager manager = new HybridAuditManager(plugin, config(true), unavailableRedis(), null, null);

        try {
            manager.close();

            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=hybridAudit status=drained enabled=true queued=0 inFlight=0 retry=0 lateRejected=0")));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void redisOnlyOrdinaryAuditDoesNotQueueSqlMigration() throws Exception {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = config(true);
        when(plugin.getSyncmoneyConfig()).thenReturn(config);
        HybridAuditManager manager = new HybridAuditManager(plugin, config, unavailableRedis(), null, null);
        Field queueField = HybridAuditManager.class.getDeclaredField("migrationQueue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<AuditRecord> migrationQueue =
                (ConcurrentLinkedQueue<AuditRecord>) queueField.get(manager);

        for (int i = 0; i < 9; i++) {
            manager.log(record("redis-only-" + i));
        }

        assertEquals(0, migrationQueue.size(),
                "Redis-only audit has no SQL migration destination and must not retain an in-memory backlog");
        manager.close();
    }

    @Test
    void closeDrainsMultipleMigrationBatches() throws Exception {
        try (HikariDataSource dataSource = dataSource("multi-batch-close")) {
            Syncmoney plugin = plugin();
            SyncmoneyConfig config = config(false);
            new AuditLogger(plugin, config, dataSource);
            HybridAuditManager manager = new HybridAuditManager(plugin, config, null, dataSource, null);
            Field queueField = HybridAuditManager.class.getDeclaredField("migrationQueue");
            queueField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<AuditRecord> migrationQueue =
                    (ConcurrentLinkedQueue<AuditRecord>) queueField.get(manager);
            for (int i = 0; i < 25; i++) {
                migrationQueue.add(record("hybrid-multi-batch-" + i));
            }

            manager.close();

            assertEquals(0, migrationQueue.size(), "the hybrid close drain must consume all queued batches");
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                result.next();
                assertEquals(25, result.getInt(1));
            }
        }
    }

    @Test
    void closeWaitsForConfirmedAuditWriteAndRejectsNewWritesAfterClosing() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        HikariDataSource dataSource = blockingDataSource(writeEntered, releaseWrite);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(logger);
        HybridAuditManager manager = new HybridAuditManager(plugin, config(false), null, dataSource, null);
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                manager.persistConfirmedRecord(record("hybrid-close-in-flight"));
            } catch (Throwable failure) {
                writeFailure.set(failure);
            }
        });
        CountDownLatch closeStarted = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            manager.close();
        });

        try {
            writer.start();
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
            closer.start();
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(closer.isAlive(), "close must wait while a confirmed audit write owns persistence");

            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            assertFalse(writer.isAlive() || closer.isAlive(), "audit write and close must finish");
            assertEquals(null, writeFailure.get());
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=hybridAudit status=drained enabled=true queued=0 inFlight=0 retry=0 lateRejected=0")));
            assertThrows(SQLException.class, () -> manager.persistConfirmedRecord(record("after-hybrid-close")));
            assertTrue(messages.stream().anyMatch(message -> message.startsWith(
                    "T21_DRAIN owner=hybridAudit status=pending enabled=true queued=0 inFlight=0 retry=0 ")
                    && message.endsWith("lateRejected=1")),
                    "a rejected confirmed write must make the hybrid audit owner visibly pending");
        } finally {
            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            logger.removeHandler(capture);
        }
    }

    @Test
    void closeKeepsWaitingAfterInterruptionUntilConfirmedAuditWriteFinishes() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        HikariDataSource dataSource = blockingDataSource(writeEntered, releaseWrite);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(logger);
        HybridAuditManager manager = new HybridAuditManager(plugin, config(false), null, dataSource, null);
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                manager.persistConfirmedRecord(record("hybrid-interrupted-close"));
            } catch (Throwable failure) {
                writeFailure.set(failure);
            }
        });
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicBoolean closeInterruptRestored = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            manager.close();
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
            assertFalse(writer.isAlive() || closer.isAlive(), "audit write and close must finish");
            assertEquals(null, writeFailure.get());
            assertTrue(closeInterruptRestored.get(), "close must restore interruption after safe draining");
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=hybridAudit status=drained enabled=true queued=0 inFlight=0 retry=0 lateRejected=0")));
        } finally {
            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            logger.removeHandler(capture);
        }
    }

    @Test
    void schedulerRejectionReleasesMigrationOwnershipForCloseDrain() throws Exception {
        try (HikariDataSource dataSource = dataSource("migration-scheduler-rejection")) {
            Syncmoney plugin = plugin();
            SyncmoneyConfig config = config(true);
            new AuditLogger(plugin, config, dataSource);
            Server server = mock(Server.class);
            AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
            doThrow(new IllegalStateException("scheduler is stopping"))
                    .when(asyncScheduler).runNow(any(Plugin.class), any());
            HybridAuditManager manager = new HybridAuditManager(plugin, config, unavailableRedis(), dataSource, null);
            Field queueField = HybridAuditManager.class.getDeclaredField("migrationQueue");
            queueField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<AuditRecord> migrationQueue =
                    (ConcurrentLinkedQueue<AuditRecord>) queueField.get(manager);
            for (int i = 0; i < 10; i++) migrationQueue.add(record("scheduler-rejection-" + i));
            Method checkAndTriggerMigration = HybridAuditManager.class.getDeclaredMethod("checkAndTriggerMigration");
            checkAndTriggerMigration.setAccessible(true);

            assertDoesNotThrow(() -> checkAndTriggerMigration.invoke(manager),
                    "a rejected scheduler submission must leave records owned by the queue for close to drain");
            Field migratingField = HybridAuditManager.class.getDeclaredField("migrating");
            migratingField.setAccessible(true);
            assertFalse(((AtomicBoolean) migratingField.get(manager)).get(),
                    "a rejected submission must release migration ownership");

            manager.close();

            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                result.next();
                assertEquals(10, result.getInt(1), "close must synchronously drain after scheduler rejection");
            }
        }
    }

    @Test
    void ordinaryHybridAuditLogAfterCloseIsRejectedWithoutPersistingOrQueueing() throws Exception {
        try (HikariDataSource dataSource = dataSource("late-hybrid-log")) {
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.ALL);
            var messages = new CopyOnWriteArrayList<String>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            logger.addHandler(capture);
            Syncmoney plugin = mock(Syncmoney.class);
            when(plugin.getLogger()).thenReturn(logger);
            SyncmoneyConfig config = config(false);
            new AuditLogger(plugin, config, dataSource);
            HybridAuditManager manager = new HybridAuditManager(plugin, config, null, dataSource, null);

            try {
                manager.close();

                manager.log(record("late-hybrid-log"));

                try (Connection connection = dataSource.getConnection();
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                    result.next();
                    assertEquals(0, result.getInt(1), "a late ordinary audit call must not reach SQL");
                }
                assertTrue(messages.stream().anyMatch(message -> message.startsWith(
                        "T21_DRAIN owner=hybridAudit status=pending enabled=true queued=0 ")
                        && message.endsWith("lateRejected=1")),
                        "a rejected ordinary audit call must make the hybrid audit owner visibly pending");
            } finally {
                logger.removeHandler(capture);
            }
        }
    }

    @Test
    void closeWaitsForAnActiveOrdinaryHybridAuditLog() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        HikariDataSource dataSource = blockingDataSource(writeEntered, releaseWrite);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        var messages = new CopyOnWriteArrayList<String>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(logger);
        HybridAuditManager manager = new HybridAuditManager(plugin, config(false), null, dataSource, null);
        Thread writer = new Thread(() -> manager.log(record("hybrid-active-log")));
        CountDownLatch closeStarted = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            manager.close();
        });

        try {
            writer.start();
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
            closer.start();
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(closer.isAlive(), "close must wait while an ordinary log owns audit persistence");

            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            assertFalse(writer.isAlive() || closer.isAlive(), "ordinary log and close must finish");
            assertTrue(messages.stream().anyMatch(message -> message.equals(
                    "T21_DRAIN owner=hybridAudit status=drained enabled=true queued=0 inFlight=0 retry=0 lateRejected=0")));
        } finally {
            releaseWrite.countDown();
            writer.join(5_000);
            closer.join(5_000);
            logger.removeHandler(capture);
        }
    }

    @Test
    void sqlReceiptSatisfiesConfirmedAuditWhenRedisRecentWindowIsUnavailable() throws Exception {
        try (HikariDataSource dataSource = dataSource("sql-fallback")) {
            Syncmoney plugin = plugin();
            SyncmoneyConfig config = config(true);
            new AuditLogger(plugin, config, dataSource);
            HybridAuditManager manager = new HybridAuditManager(plugin, config, unavailableRedis(), dataSource, null);

            manager.persistConfirmedRecord(record("hybrid-audit-1"));

            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM syncmoney_audit_log")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void confirmedAuditSurfacesFailureWhenRedisIsItsOnlyConfiguredStore() {
        Syncmoney plugin = plugin();
        SyncmoneyConfig config = config(true);
        HybridAuditManager manager = new HybridAuditManager(plugin, config, unavailableRedis(), null, null);

        assertThrows(SQLException.class, () -> manager.persistConfirmedRecord(record("redis-only-audit-1")));
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
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doAnswer(invocation -> {
            writeEntered.countDown();
            if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new SQLException("test write release timed out");
            return new int[]{1};
        }).when(statement).executeBatch();
        return dataSource;
    }

    private static Syncmoney plugin() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HybridAuditManagerConfirmedPersistenceTest"));
        return plugin;
    }

    private static SyncmoneyConfig config(boolean redisEnabled) {
        AuditConfig audit = mock(AuditConfig.class);
        when(audit.isAuditEnabled()).thenReturn(true);
        when(audit.isAuditRedisEnabled()).thenReturn(redisEnabled);
        when(audit.getAuditBatchSize()).thenReturn(10);
        when(audit.getAuditFlushIntervalMs()).thenReturn(0L);
        when(audit.getAuditRedisWindowSize()).thenReturn(10);
        when(audit.getAuditMigrationThreshold()).thenReturn(10);
        when(audit.getAuditMigrationBatchSize()).thenReturn(10);
        when(audit.getAuditMigrationIntervalMs()).thenReturn(0L);
        when(audit.getAuditRedisFlushIntervalMs()).thenReturn(0L);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.audit()).thenReturn(audit);
        return config;
    }

    private static RedisManager unavailableRedis() {
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        when(redis.getResource()).thenThrow(new IllegalStateException("Redis audit backend unavailable"));
        return redis;
    }

    private static AuditRecord record(String id) {
        return new AuditRecord(id, 1_800_000_000_000L, 0, AuditRecord.AuditType.DEPOSIT,
                UUID.randomUUID(), "player", BigDecimal.TEN, new BigDecimal("110.00"),
                EconomyEvent.EventSource.VAULT_DEPOSIT, "server", null, null, null, 1);
    }
}
