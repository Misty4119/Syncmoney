package noietime.syncmoney.testkit.port;

import noietime.syncmoney.core.port.ConfirmedAccount;

import java.util.Optional;
import java.util.UUID;

/** Test-only contract shared by deterministic fakes and backend conformance adapters. */
public interface AccountRepositoryPort {

    Optional<ConfirmedAccount> find(UUID accountId);

    WriteResult write(ConfirmedAccount account);

    enum WriteResult {
        APPLIED,
        IDEMPOTENT,
        STALE
    }

    final class AccountConflictException extends RuntimeException {
        public AccountConflictException(UUID accountId, long version) {
            super("Conflicting account snapshot for " + accountId + " at version " + version);
        }
    }
}
