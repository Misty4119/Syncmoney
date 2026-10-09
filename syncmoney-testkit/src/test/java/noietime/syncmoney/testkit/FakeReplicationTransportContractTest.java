package noietime.syncmoney.testkit;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;
import noietime.syncmoney.testkit.failure.FailureSchedule;
import noietime.syncmoney.testkit.scheduler.ManualExecutor;
import noietime.syncmoney.testkit.transport.FakeReplicationTransport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeReplicationTransportContractTest {

    private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void reusingOperationIdWithNewerSnapshotDoesNotReturnAnOlderReceipt() {
        FakeReplicationTransport transport = new FakeReplicationTransport();
        List<ConfirmedAccount> firstPair = List.of(
                account(ACCOUNT_A, "90.00", 2), account(ACCOUNT_B, "10.00", 2));
        List<ConfirmedAccount> newerPair = List.of(
                account(ACCOUNT_A, "80.00", 3), account(ACCOUNT_B, "20.00", 3));

        ReplicationReceipt first = transport.replicate("transfer-1", firstPair);
        ReplicationReceipt newer = transport.replicate("transfer-1", newerPair);

        assertEquals("transfer-1", first.operationId());
        assertEquals("transfer-1", newer.operationId());
        assertEquals(newerPair, newer.accounts());
        assertEquals(account(ACCOUNT_A, "80.00", 3), transport.find(ACCOUNT_A).orElseThrow());
        assertEquals(account(ACCOUNT_B, "20.00", 3), transport.find(ACCOUNT_B).orElseThrow());
    }

    @Test
    void precommitFailureDoesNotMutateAndUnknownOutcomeIsNotBlindlyRetried() {
        FailureSchedule failures = new FailureSchedule();
        FakeReplicationTransport transport = new FakeReplicationTransport(failures, new ManualExecutor());
        List<ConfirmedAccount> initial = List.of(account(ACCOUNT_A, "100.00", 1));
        List<ConfirmedAccount> next = List.of(account(ACCOUNT_A, "90.00", 2));
        transport.replicate("deposit-1", initial);

        failures.scheduleOnce(FailureSchedule.Point.FAIL_BEFORE_COMMIT);
        FailureSchedule.InjectedFailure before = assertThrows(FailureSchedule.InjectedFailure.class,
                () -> transport.replicate("withdraw-1", next));
        assertEquals(FailureSchedule.Outcome.NOT_COMMITTED, before.outcome());
        assertEquals(initial.getFirst(), transport.find(ACCOUNT_A).orElseThrow());

        failures.scheduleOnce(FailureSchedule.Point.DROP_RESPONSE);
        FailureSchedule.InjectedFailure dropped = assertThrows(FailureSchedule.InjectedFailure.class,
                () -> transport.replicate("withdraw-1", next));
        assertEquals(FailureSchedule.Outcome.UNKNOWN, dropped.outcome());
        assertEquals(next.getFirst(), transport.find(ACCOUNT_A).orElseThrow());
    }

    @Test
    void staleMemberRejectsWholeSnapshotPairWithoutApplyingTheNewerMember() {
        FakeReplicationTransport transport = new FakeReplicationTransport();
        ConfirmedAccount initialA = account(ACCOUNT_A, "100.00", 1);
        ConfirmedAccount initialB = account(ACCOUNT_B, "50.00", 3);
        transport.replicate("seed-a", List.of(initialA));
        transport.replicate("seed-b", List.of(initialB));

        assertThrows(FakeReplicationTransport.StaleAuthorityException.class,
                () -> transport.replicate("stale-pair", List.of(
                        account(ACCOUNT_A, "90.00", 2), account(ACCOUNT_B, "60.00", 2))));

        assertEquals(initialA, transport.find(ACCOUNT_A).orElseThrow());
        assertEquals(initialB, transport.find(ACCOUNT_B).orElseThrow());
    }

    @Test
    void equalVersionConflictRejectsWholeSnapshotPairWithoutApplyingTheNewerMember() {
        FakeReplicationTransport transport = new FakeReplicationTransport();
        ConfirmedAccount initialA = account(ACCOUNT_A, "100.00", 1);
        ConfirmedAccount initialB = account(ACCOUNT_B, "50.00", 1);
        transport.replicate("seed-a", List.of(initialA));
        transport.replicate("seed-b", List.of(initialB));

        assertThrows(FakeReplicationTransport.VersionConflictException.class,
                () -> transport.replicate("conflicting-pair", List.of(
                        account(ACCOUNT_A, "99.00", 1), account(ACCOUNT_B, "60.00", 2))));

        assertEquals(initialA, transport.find(ACCOUNT_A).orElseThrow());
        assertEquals(initialB, transport.find(ACCOUNT_B).orElseThrow());
    }

    @Test
    void duplicateAndOutOfOrderDeliveriesAreDeterministicUnderManualExecution() {
        FailureSchedule failures = new FailureSchedule();
        ManualExecutor executor = new ManualExecutor();
        FakeReplicationTransport transport = new FakeReplicationTransport(failures, executor);
        ReplicationReceipt receipt = transport.replicate("transfer-2", List.of(
                account(ACCOUNT_A, "90.00", 2), account(ACCOUNT_B, "10.00", 2)));
        List<ConfirmedAccount> delivered = new ArrayList<>();
        failures.scheduleOnce(FailureSchedule.Point.DELIVER_DUPLICATE);
        failures.scheduleOnce(FailureSchedule.Point.DELIVER_OUT_OF_ORDER);

        transport.deliver(receipt, delivered::add);
        assertEquals(4, executor.queuedTaskCount());
        assertEquals(4, executor.drain());

        assertEquals(List.of(receipt.accounts().get(1), receipt.accounts().get(0),
                receipt.accounts().get(1), receipt.accounts().get(0)), delivered);
    }

    private static ConfirmedAccount account(UUID accountId, String balance, long version) {
        return new ConfirmedAccount(accountId, new BigDecimal(balance), version);
    }
}
