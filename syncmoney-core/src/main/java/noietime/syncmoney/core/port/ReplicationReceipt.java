package noietime.syncmoney.core.port;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
/** Immutable result of one authority mutation, including every affected account pair. */
public record ReplicationReceipt(String operationId, List<ConfirmedAccount> accounts) {
    public ReplicationReceipt {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
    }
    /**
     * Find an account snapshot when present. Rejected-write reconciliation receipts may omit
     * authority-confirmed absent accounts; their UUIDs are carried by the corresponding event.
     */
    public Optional<ConfirmedAccount> findAccount(java.util.UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return accounts.stream().filter(account -> account.playerUuid().equals(playerUuid)).findFirst();
    }

    public ConfirmedAccount accountFor(java.util.UUID playerUuid) {
        return findAccount(playerUuid)
                .orElseThrow(() -> new IllegalArgumentException("receipt does not contain " + playerUuid));
    }
}
