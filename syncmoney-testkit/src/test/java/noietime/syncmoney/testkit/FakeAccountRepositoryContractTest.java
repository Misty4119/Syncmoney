package noietime.syncmoney.testkit;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.testkit.failure.FailureSchedule;
import noietime.syncmoney.testkit.repository.FakeAccountRepository;
import noietime.syncmoney.testkit.port.AccountRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeAccountRepositoryContractTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void rejectsStaleWritesAndConflictingEqualVersionsWithoutChangingTheStoredSnapshot() {
        FakeAccountRepository repository = new FakeAccountRepository();
        ConfirmedAccount initial = account("100.00", 7);
        repository.seed(initial);

        assertEquals(AccountRepositoryPort.WriteResult.STALE, repository.write(account("90.00", 6)));
        assertEquals(AccountRepositoryPort.WriteResult.IDEMPOTENT, repository.write(account("100.00", 7)));
        assertThrows(AccountRepositoryPort.AccountConflictException.class,
                () -> repository.write(account("99.00", 7)));
        assertEquals(initial, repository.find(ACCOUNT).orElseThrow());
        assertEquals(AccountRepositoryPort.WriteResult.APPLIED, repository.write(account("90.00", 8)));
        assertEquals(account("90.00", 8), repository.find(ACCOUNT).orElseThrow());
    }

    @Test
    void distinguishesFailureBeforeCommitFromUnknownOutcomeAfterCommit() {
        FailureSchedule failures = new FailureSchedule();
        FakeAccountRepository repository = new FakeAccountRepository(failures);
        ConfirmedAccount initial = account("100.00", 7);
        ConfirmedAccount next = account("90.00", 8);
        repository.seed(initial);

        failures.scheduleOnce(FailureSchedule.Point.FAIL_BEFORE_COMMIT);
        FailureSchedule.InjectedFailure before = assertThrows(FailureSchedule.InjectedFailure.class,
                () -> repository.write(next));
        assertEquals(FailureSchedule.Outcome.NOT_COMMITTED, before.outcome());
        assertEquals(initial, repository.find(ACCOUNT).orElseThrow());

        failures.scheduleOnce(FailureSchedule.Point.FAIL_AFTER_COMMIT);
        FailureSchedule.InjectedFailure after = assertThrows(FailureSchedule.InjectedFailure.class,
                () -> repository.write(next));
        assertEquals(FailureSchedule.Outcome.UNKNOWN, after.outcome());
        assertEquals(next, repository.find(ACCOUNT).orElseThrow());
        assertEquals(AccountRepositoryPort.WriteResult.IDEMPOTENT, repository.write(next));
    }

    private static ConfirmedAccount account(String balance, long version) {
        return new ConfirmedAccount(ACCOUNT, new BigDecimal(balance), version);
    }
}
