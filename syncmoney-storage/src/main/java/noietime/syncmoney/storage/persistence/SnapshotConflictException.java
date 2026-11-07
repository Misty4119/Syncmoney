package noietime.syncmoney.storage.persistence;
import java.sql.SQLException;
import java.util.UUID;
public final class SnapshotConflictException extends SQLException {
    public SnapshotConflictException(UUID uuid, long version) {
        super("SQL snapshot conflict for " + uuid + " at version " + version
                + ": the same authority version has a different balance");
    }
}
