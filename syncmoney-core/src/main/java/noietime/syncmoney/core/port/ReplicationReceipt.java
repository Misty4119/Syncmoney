package noietime.syncmoney.core.port;
import java.util.List;
import java.util.Objects;
/** Immutable result of one authority mutation, including every affected account pair. */
public record ReplicationReceipt(String operationId, List<ConfirmedAccount> accounts) {
    public ReplicationReceipt {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        if (accounts.isEmpty()) throw new IllegalArgumentException("a confirmed receipt must contain an account");
    }
    public ConfirmedAccount accountFor(java.util.UUID playerUuid) {
        return accounts.stream().filter(account -> account.playerUuid().equals(playerUuid)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("receipt does not contain " + playerUuid));
    }
}
