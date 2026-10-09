package noietime.syncmoney.sync;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the existing CMI balance/version mirror keys as one Redis operation.
 *
 * <p>This keeps reconciliation from observing a balance from one mirror update and a version
 * from another. It does not order concurrent writers or add compare-and-set semantics.
 */
public final class CMIRedisMirror {

    private final RedisManager redisManager;
    private final String redisPrefix;

    public CMIRedisMirror(RedisManager redisManager, String redisPrefix) {
        this.redisManager = redisManager;
        this.redisPrefix = redisPrefix;
    }

    public Optional<Snapshot> read(UUID uuid) {
        if (redisManager == null || redisManager.isDegraded()) {
            return Optional.empty();
        }
        try (var jedis = redisManager.getResource()) {
            List<String> values = jedis.mget(
                    RedisKeys.cmiBalance(redisPrefix, uuid),
                    RedisKeys.cmiVersion(redisPrefix, uuid));
            if (values == null || values.size() != 2 || values.get(0) == null || values.get(1) == null) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(
                    NumericUtil.normalize(values.get(0)),
                    Long.parseLong(values.get(1))));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void write(UUID uuid, BigDecimal balance, long version) {
        try (var jedis = redisManager.getResource()) {
            jedis.mset(
                    RedisKeys.cmiBalance(redisPrefix, uuid), balance.toPlainString(),
                    RedisKeys.cmiVersion(redisPrefix, uuid), String.valueOf(version));
        }
    }

    public record Snapshot(BigDecimal balance, long version) {
    }
}
