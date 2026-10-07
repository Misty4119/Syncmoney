package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalTransferPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void failedSecondSqlLegRollsBackPairAndWorkerRetriesBothLegsTogether() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalTransferPersistenceTest"));
        when(plugin.getConfig()).thenReturn(null);
        String databasePath = tempDir.resolve("local-transfer.db").toString();
        LocalEconomyHandler handler = new LocalEconomyHandler(plugin, databasePath);
        assertTrue(handler.isRecoveryReady());

        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        assertEquals(new BigDecimal("100.00"), handler.setBalance(sender, "sender",
                new BigDecimal("100.00"), "TEST"));

        String triggerName = "fail_transfer_receiver";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER " + triggerName + " BEFORE INSERT ON player_balances "
                    + "WHEN NEW.uuid = '" + receiver + "' BEGIN SELECT RAISE(ABORT, 'injected receiver failure'); END");
        }

        EconomyWriteQueue queue = new EconomyWriteQueue(8, plugin.getLogger());
        MemoryStateManager memory = new MemoryStateManager(plugin, queue);
        memory.put(sender, state("90.00", 2L));
        memory.put(receiver, state("10.00", 1L));
        String operationId = TransferRequestIds.newOperationId();
        long now = System.currentTimeMillis();
        EconomyEvent out = new EconomyEvent(sender, new BigDecimal("-10.00"), new BigDecimal("90.00"), 2L,
                EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventSource.PLAYER_TRANSFER,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT), now);
        EconomyEvent in = new EconomyEvent(receiver, new BigDecimal("10.00"), new BigDecimal("10.00"), 1L,
                EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventSource.PLAYER_TRANSFER,
                TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN), now);
        EconomyWriteQueue.Reservation outReservation = queue.tryReserve(sender);
        EconomyWriteQueue.Reservation inReservation = queue.tryReserve(receiver);
        assertNotNull(outReservation);
        assertNotNull(inReservation);
        assertTrue(queue.commitPair(outReservation, out, inReservation, in, null));

        CountDownLatch firstSqlFailure = new CountDownLatch(1);
        LocalPersistenceWorker worker = new LocalPersistenceWorker(queue,
                event -> handler.persistAccepted(event, event.playerUuid().toString()),
                (transferOut, transferIn) -> {
                    try {
                        return handler.persistAcceptedTransfer(transferOut, "sender", transferIn, "receiver");
                    } catch (Exception e) {
                        firstSqlFailure.countDown();
                        throw e;
                    }
                },
                ignored -> { }, plugin.getLogger());

        try {
            worker.start();
            assertTrue(firstSqlFailure.await(5, TimeUnit.SECONDS),
                    "the injected failure must occur after the sender row has been attempted");
            assertEquals(0, readBalance(databasePath, sender).compareTo(new BigDecimal("100.00")),
                    "SQLite rollback must keep the sender at its original durable balance");
            assertNull(readBalance(databasePath, receiver),
                    "a failed receiver insert must leave no receiver row from a partial transfer");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER " + triggerName);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queue.hasOutstandingWrites() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(queue.hasOutstandingWrites(), "the retry should complete the accepted pair together");
            worker.shutdown();

            assertEquals(new BigDecimal("90.00"), handler.findBalance(sender).orElseThrow());
            assertEquals(new BigDecimal("10.00"), handler.findBalance(receiver).orElseThrow());
            assertEquals(new BigDecimal("90.00"), memory.get(sender).balance());
            assertEquals(new BigDecimal("10.00"), memory.get(receiver).balance());
            assertEquals(2, transactionRowsForPair(databasePath, operationId));
        } finally {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS " + triggerName);
            }
            worker.shutdown();
            handler.close();
        }
    }

    private static BigDecimal readBalance(String databasePath, UUID uuid) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT balance FROM player_balances WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBigDecimal(1) : null;
            }
        }
    }

    private static int transactionRowsForPair(String databasePath, String operationId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM transactions WHERE request_id IN (?, ?)")) {
            statement.setString(1, TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_OUT));
            statement.setString(2, TransferRequestIds.requestId(operationId, EconomyEvent.EventType.TRANSFER_IN));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static EconomyFacade.EconomyState state(String balance, long version) {
        return new EconomyFacade.EconomyState(new BigDecimal(balance), version, System.currentTimeMillis());
    }
}
