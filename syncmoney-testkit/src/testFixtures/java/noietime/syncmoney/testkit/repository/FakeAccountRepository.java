package noietime.syncmoney.testkit.repository;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.testkit.failure.FailureSchedule;
import noietime.syncmoney.testkit.port.AccountRepositoryPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Version-aware account repository fake; writes do not degrade to unconditional map replacement. */
public final class FakeAccountRepository implements AccountRepositoryPort {

    private final Map<UUID, ConfirmedAccount> accounts = new HashMap<>();
    private final FailureSchedule failures;

    public FakeAccountRepository() {
        this(new FailureSchedule());
    }

    public FakeAccountRepository(FailureSchedule failures) {
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** Seeds an initial backend snapshot before a scenario starts. */
    public synchronized void seed(ConfirmedAccount account) {
        Objects.requireNonNull(account, "account");
        if (accounts.putIfAbsent(account.playerUuid(), account) != null) {
            throw new IllegalStateException("Account fixture already seeded: " + account.playerUuid());
        }
    }

    @Override
    public synchronized Optional<ConfirmedAccount> find(UUID accountId) {
        return Optional.ofNullable(accounts.get(Objects.requireNonNull(accountId, "accountId")));
    }

    @Override
    public synchronized WriteResult write(ConfirmedAccount candidate) {
        Objects.requireNonNull(candidate, "candidate");
        failures.failIfScheduled(FailureSchedule.Point.FAIL_BEFORE_COMMIT,
                FailureSchedule.Outcome.NOT_COMMITTED);

        ConfirmedAccount current = accounts.get(candidate.playerUuid());
        WriteResult result;
        if (current == null || candidate.version() > current.version()) {
            accounts.put(candidate.playerUuid(), candidate);
            result = WriteResult.APPLIED;
        } else if (candidate.version() < current.version()) {
            result = WriteResult.STALE;
        } else if (candidate.balance().compareTo(current.balance()) == 0) {
            result = WriteResult.IDEMPOTENT;
        } else {
            throw new AccountConflictException(candidate.playerUuid(), candidate.version());
        }

        if (result == WriteResult.APPLIED) {
            failures.failIfScheduled(FailureSchedule.Point.FAIL_AFTER_COMMIT,
                    FailureSchedule.Outcome.UNKNOWN);
        }
        return result;
    }
}
