package noietime.syncmoney.storage.persistence;
import noietime.syncmoney.core.port.ConfirmedAccount;
import java.time.Instant;
import java.util.Objects;
/** Authority-confirmed balance/version with descriptive SQL metadata. */
public record PlayerSnapshot(ConfirmedAccount account, String playerName, String lastServer, Instant updatedAt) {
    public PlayerSnapshot {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
