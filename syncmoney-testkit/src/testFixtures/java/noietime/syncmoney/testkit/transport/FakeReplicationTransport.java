package noietime.syncmoney.testkit.transport;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;
import noietime.syncmoney.testkit.failure.FailureSchedule;
import noietime.syncmoney.testkit.port.ReplicationTransportPort;
import noietime.syncmoney.testkit.scheduler.ManualExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Version-ordered absolute-snapshot transport fake with deterministic delivery faults. */
public final class FakeReplicationTransport implements ReplicationTransportPort {

    private final HashMap<UUID, ConfirmedAccount> accounts = new HashMap<>();
    private final FailureSchedule failures;
    private final ManualExecutor deliveries;

    public FakeReplicationTransport() {
        this(new FailureSchedule(), new ManualExecutor());
    }

    public FakeReplicationTransport(FailureSchedule failures, ManualExecutor deliveries) {
        this.failures = Objects.requireNonNull(failures, "failures");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
    }

    @Override
    public synchronized ReplicationReceipt replicate(String operationId, List<ConfirmedAccount> requestedAccounts) {
        Objects.requireNonNull(operationId, "operationId");
        // The ID is receipt correlation metadata only. No operation history is kept, so an
        // ambiguous caller must reconcile authority state instead of replaying a mutation.
        ReplicationReceipt requested = new ReplicationReceipt(operationId, canonical(requestedAccounts));
        validateAgainstCurrent(requested.accounts());
        failures.failIfScheduled(FailureSchedule.Point.FAIL_BEFORE_COMMIT,
                FailureSchedule.Outcome.NOT_COMMITTED);

        requested.accounts().forEach(account -> accounts.put(account.playerUuid(), account));

        failures.failIfScheduled(FailureSchedule.Point.FAIL_AFTER_COMMIT,
                FailureSchedule.Outcome.UNKNOWN);
        failures.failIfScheduled(FailureSchedule.Point.DROP_RESPONSE,
                FailureSchedule.Outcome.UNKNOWN);
        return requested;
    }

    @Override
    public synchronized Optional<ConfirmedAccount> find(UUID accountId) {
        return Optional.ofNullable(accounts.get(Objects.requireNonNull(accountId, "accountId")));
    }

    @Override
    public void deliver(ReplicationReceipt receipt, Consumer<ConfirmedAccount> receiver) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(receiver, "receiver");
        List<ConfirmedAccount> ordered = new ArrayList<>(receipt.accounts());
        if (failures.consume(FailureSchedule.Point.DELIVER_OUT_OF_ORDER)) {
            ordered.sort(Comparator.comparing(ConfirmedAccount::playerUuid).reversed());
        }
        int deliveriesPerAccount = failures.consume(FailureSchedule.Point.DELIVER_DUPLICATE) ? 2 : 1;
        for (int delivery = 0; delivery < deliveriesPerAccount; delivery++) {
            for (ConfirmedAccount account : ordered) deliveries.execute(() -> receiver.accept(account));
        }
    }

    private void validateAgainstCurrent(List<ConfirmedAccount> requested) {
        for (ConfirmedAccount candidate : requested) {
            ConfirmedAccount current = accounts.get(candidate.playerUuid());
            if (current == null || candidate.version() > current.version()) continue;
            if (candidate.version() < current.version()) {
                throw new StaleAuthorityException(candidate.playerUuid(), candidate.version(), current.version());
            }
            if (candidate.balance().compareTo(current.balance()) != 0) {
                throw new VersionConflictException(candidate.playerUuid(), candidate.version());
            }
        }
    }

    private static List<ConfirmedAccount> canonical(List<ConfirmedAccount> requested) {
        Objects.requireNonNull(requested, "requestedAccounts");
        List<ConfirmedAccount> sorted = requested.stream()
                .map(account -> Objects.requireNonNull(account, "account"))
                .sorted(Comparator.comparing(account -> account.playerUuid().toString()))
                .toList();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).playerUuid().equals(sorted.get(index).playerUuid())) {
                throw new IllegalArgumentException("replication receipt contains a duplicate account UUID");
            }
        }
        return sorted;
    }

    public static final class StaleAuthorityException extends RuntimeException {
        public StaleAuthorityException(UUID accountId, long incomingVersion, long currentVersion) {
            super("Stale authority snapshot for " + accountId + ": " + incomingVersion + " < " + currentVersion);
        }
    }

    public static final class VersionConflictException extends RuntimeException {
        public VersionConflictException(UUID accountId, long version) {
            super("Conflicting authority snapshot for " + accountId + " at version " + version);
        }
    }

}
