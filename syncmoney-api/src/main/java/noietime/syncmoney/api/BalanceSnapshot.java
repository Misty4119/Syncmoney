package noietime.syncmoney.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable balance observation for one account.
 *
 * <p>The balance is exact and retains the normalization of the active economy
 * mode. The snapshot intentionally exposes no storage or synchronization version.
 *
 * @param accountId account UUID
 * @param balance observed balance
 */
public record BalanceSnapshot(UUID accountId, BigDecimal balance) {

    /**
     * Creates a balance snapshot.
     *
     * @param accountId account UUID
     * @param balance observed balance
     * @throws NullPointerException if either value is null
     */
    public BalanceSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(balance, "balance");
    }
}
