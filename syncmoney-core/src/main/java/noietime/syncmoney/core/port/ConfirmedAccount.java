package noietime.syncmoney.core.port;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
/** A balance and authority version returned by one confirmed write. */
public record ConfirmedAccount(UUID playerUuid, BigDecimal balance, long version) {
    public ConfirmedAccount {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(balance, "balance");
        balance = balance.setScale(2, RoundingMode.HALF_UP);
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
    }
}
