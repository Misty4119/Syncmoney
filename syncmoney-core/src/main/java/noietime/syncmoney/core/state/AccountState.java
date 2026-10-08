package noietime.syncmoney.core.state;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable, platform-independent account snapshot; mutation boundaries own normalization. */
public record AccountState(BigDecimal balance, long version, long lastAccessTime) {

    public AccountState {
        balance = Objects.requireNonNull(balance, "balance");
    }
}
