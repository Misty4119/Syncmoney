package noietime.syncmoney.sync;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and version-orders the existing CMI balance/version mirror keys.
 *
 * <p>The compare-and-write operation is one Redis script, so concurrent servers cannot replace a
 * newer mirror with a delayed older message. Versions are compared as decimal strings: Lua
 * {@code tonumber} would lose precision for the timestamp-derived versions used by CMI.
 */
public final class CMIRedisMirror {

    private static final String COMPARE_AND_WRITE_SCRIPT = """
            local function canonical_integer(value)
                if not value or not string.match(value, '^%d+$') then return nil end
                local canonical = string.gsub(value, '^0+', '')
                if canonical == '' then return '0' end
                return canonical
            end

            local function canonical_decimal(value)
                if not value or not string.match(value, '^%-?%d+%.?%d*$') then return nil end
                local negative = string.sub(value, 1, 1) == '-'
                local unsigned = negative and string.sub(value, 2) or value
                local integer, fraction = string.match(unsigned, '^(%d+)%.?(%d*)$')
                if not integer then return nil end
                integer = string.gsub(integer, '^0+', '')
                if integer == '' then integer = '0' end
                fraction = string.gsub(fraction, '0+$', '')
                if integer == '0' and fraction == '' then negative = false end
                local sign = negative and '-' or ''
                if fraction == '' then return sign .. integer end
                return sign .. integer .. '.' .. fraction
            end

            local incoming_version = canonical_integer(ARGV[2])
            local incoming_balance = canonical_decimal(ARGV[1])
            if not incoming_version or incoming_version == '0' or not incoming_balance then return -1 end

            local current_balance = redis.call('GET', KEYS[1])
            local current_version = redis.call('GET', KEYS[2])
            if not current_balance and not current_version then
                redis.call('MSET', KEYS[1], ARGV[1], KEYS[2], ARGV[2])
                return 1
            end
            if not current_balance or not current_version then return 0 end

            local canonical_current_version = canonical_integer(current_version)
            local canonical_current_balance = canonical_decimal(current_balance)
            if not canonical_current_version or not canonical_current_balance then return 0 end

            if #incoming_version < #canonical_current_version then return 0 end
            if #incoming_version == #canonical_current_version then
                for index = 1, #incoming_version do
                    local incoming_digit = string.byte(incoming_version, index)
                    local current_digit = string.byte(canonical_current_version, index)
                    if incoming_digit < current_digit then return 0 end
                    if incoming_digit > current_digit then break end
                end
            end
            if canonical_current_version ~= current_version then return 0 end
            if incoming_version == canonical_current_version then
                if incoming_balance == canonical_current_balance then return 1 end
                return 0
            end

            redis.call('MSET', KEYS[1], ARGV[1], KEYS[2], ARGV[2])
            return 1
            """;

    private final RedisManager redisManager;
    private final String redisPrefix;

    public CMIRedisMirror(RedisManager redisManager, String redisPrefix) {
        this.redisManager = redisManager;
        this.redisPrefix = redisPrefix;
    }

    public Optional<Snapshot> read(UUID uuid) {
        if (redisManager == null || uuid == null) {
            return Optional.empty();
        }
        try {
            if (redisManager.isDegraded()) {
                return Optional.empty();
            }
            try (var jedis = redisManager.getResource()) {
                List<String> values = jedis.mget(
                        RedisKeys.cmiBalance(redisPrefix, uuid),
                        RedisKeys.cmiVersion(redisPrefix, uuid));
                if (values == null || values.size() != 2 || values.get(0) == null || values.get(1) == null) {
                    return Optional.empty();
                }
                long version = Long.parseLong(values.get(1));
                if (version <= 0 || !Long.toString(version).equals(values.get(1))) {
                    return Optional.empty();
                }
                return Optional.of(new Snapshot(
                        NumericUtil.normalize(values.get(0)),
                        version));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Atomically writes the pair only when the incoming version is newer or an exact replay. */
    public WriteResult compareAndWrite(UUID uuid, BigDecimal balance, long version) {
        if (uuid == null || balance == null || version <= 0) {
            return WriteResult.REJECTED;
        }
        if (redisManager == null) {
            return WriteResult.UNAVAILABLE;
        }
        try {
            if (redisManager.isDegraded()) {
                return WriteResult.UNAVAILABLE;
            }
            try (var jedis = redisManager.getResource()) {
                Object result = jedis.eval(COMPARE_AND_WRITE_SCRIPT, 2,
                        RedisKeys.cmiBalance(redisPrefix, uuid),
                        RedisKeys.cmiVersion(redisPrefix, uuid),
                        NumericUtil.normalize(balance).toPlainString(),
                        Long.toString(version));
                if (result instanceof Number number && number.longValue() == 1L) {
                    return WriteResult.ACCEPTED;
                }
                return WriteResult.REJECTED;
            }
        } catch (Exception ignored) {
            return WriteResult.UNAVAILABLE;
        }
    }

    public enum WriteResult {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE
    }

    public record Snapshot(BigDecimal balance, long version) {
    }
}
