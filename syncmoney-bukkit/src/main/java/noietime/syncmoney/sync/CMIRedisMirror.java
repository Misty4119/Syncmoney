package noietime.syncmoney.sync;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Reads and version-orders the existing CMI balance/version mirror keys.
 *
 * <p>The compare-and-write operation is one Redis script, so concurrent servers cannot replace a
 * newer mirror with a delayed older message. Versions are compared as decimal strings: Lua
 * {@code tonumber} would lose precision for the timestamp-derived versions used by CMI.
 */
public final class CMIRedisMirror {

    private static final int MAX_DELTA_RETRIES = 8;
    private static final long DELTA_RECEIPT_TTL_MILLIS = 10 * 60 * 1000L;

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

    private static final String APPLY_DELTA_SCRIPT = """
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

            local expected_version = ARGV[1]
            local next_balance = canonical_decimal(ARGV[2])
            local next_version = canonical_integer(ARGV[3])
            local fingerprint = ARGV[4]
            local local_balance = canonical_decimal(ARGV[5])
            local receipt_ttl = tonumber(ARGV[6])
            if not next_balance or not next_version or next_version == '0'
                    or not fingerprint or fingerprint == '' or not local_balance
                    or not receipt_ttl or receipt_ttl < 1 then
                return {-1}
            end

            local receipt = redis.call('GET', KEYS[3])
            if receipt then
                local fingerprint_length = string.len(fingerprint)
                if string.sub(receipt, 1, fingerprint_length) ~= fingerprint
                        or string.sub(receipt, fingerprint_length + 1, fingerprint_length + 1) ~= '|' then
                    return {-1}
                end
                local result = string.sub(receipt, fingerprint_length + 2)
                local separator = string.find(result, '|', 1, true)
                if not separator then return {-1} end
                local receipt_balance = canonical_decimal(string.sub(result, 1, separator - 1))
                local receipt_version = canonical_integer(string.sub(result, separator + 1))
                if not receipt_balance or not receipt_version or receipt_version == '0' then return {-1} end
                return {2, receipt_balance, receipt_version}
            end

            local current_balance = redis.call('GET', KEYS[1])
            local current_version = redis.call('GET', KEYS[2])
            local canonical_current_balance = nil
            local canonical_current_version = nil
            if current_balance or current_version then
                if not current_balance or not current_version then return {-1} end
                canonical_current_balance = canonical_decimal(current_balance)
                canonical_current_version = canonical_integer(current_version)
                if not canonical_current_balance or not canonical_current_version
                        or canonical_current_version == '0'
                        or canonical_current_version ~= current_version then
                    return {-1}
                end
            end

            if expected_version == '' then
                if current_balance or current_version then return {0} end
            else
                if not current_balance or not current_version or current_version ~= expected_version then return {0} end
                if #next_version < #canonical_current_version then return {-1} end
                if #next_version == #canonical_current_version then
                    for index = 1, #next_version do
                        local incoming_digit = string.byte(next_version, index)
                        local current_digit = string.byte(canonical_current_version, index)
                        if incoming_digit < current_digit then return {-1} end
                        if incoming_digit > current_digit then break end
                    end
                end
            end

            redis.call('MSET', KEYS[1], next_balance, KEYS[2], next_version)
            local value = fingerprint .. '|' .. next_balance .. '|' .. next_version
            redis.call('SET', KEYS[3], value, 'PX', receipt_ttl)
            return {1, next_balance, next_version}
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

    /**
     * Applies a CMI event's local balance delta to the latest shared mirror value. The local
     * absolute balance is used only to initialize a missing mirror; it may come from an offline
     * CMI account whose copy on this server is stale. The Redis compare-and-set and operation
     * receipt make concurrent updates and retries safe without doing decimal arithmetic in Lua.
     */
    public ApplyResult applyDelta(UUID uuid, BigDecimal localBalance, BigDecimal delta,
                                  String operationId, LongSupplier versionSupplier) {
        if (uuid == null || localBalance == null || delta == null || operationId == null
                || operationId.isBlank() || operationId.length() > 512 || versionSupplier == null) {
            return new ApplyResult(WriteResult.REJECTED, null);
        }
        BigDecimal normalizedLocal = NumericUtil.normalize(localBalance);
        BigDecimal normalizedDelta = NumericUtil.normalize(delta);
        String fingerprint = normalizedDelta.toPlainString() + "|" + normalizedLocal.toPlainString();
        String balanceKey = RedisKeys.cmiBalance(redisPrefix, uuid);
        String versionKey = RedisKeys.cmiVersion(redisPrefix, uuid);
        String receiptKey = balanceKey + ":event:" + operationId;

        if (redisManager == null) {
            return new ApplyResult(WriteResult.UNAVAILABLE, null);
        }
        try {
            for (int attempt = 0; attempt < MAX_DELTA_RETRIES; attempt++) {
                if (redisManager.isDegraded()) {
                    return new ApplyResult(WriteResult.UNAVAILABLE, null);
                }
                Optional<Snapshot> current = read(uuid);
                BigDecimal candidate = current
                        .map(snapshot -> NumericUtil.normalize(snapshot.balance().add(normalizedDelta)))
                        .orElse(normalizedLocal);
                if (candidate.signum() < 0) {
                    return new ApplyResult(WriteResult.REJECTED, null);
                }
                long version = versionSupplier.getAsLong();
                if (version <= 0) {
                    return new ApplyResult(WriteResult.REJECTED, null);
                }
                String expectedVersion = "";
                if (current.isPresent()) {
                    long currentVersion = current.orElseThrow().version();
                    if (currentVersion == Long.MAX_VALUE) {
                        return new ApplyResult(WriteResult.REJECTED, null);
                    }
                    version = Math.max(version, currentVersion + 1L);
                    expectedVersion = Long.toString(currentVersion);
                }

                try (var jedis = redisManager.getResource()) {
                    Object result = jedis.eval(APPLY_DELTA_SCRIPT, 3,
                            balanceKey, versionKey, receiptKey,
                            expectedVersion, candidate.toPlainString(), Long.toString(version),
                            fingerprint, normalizedLocal.toPlainString(),
                            Long.toString(DELTA_RECEIPT_TTL_MILLIS));
                    DeltaScriptResult scriptResult = parseDeltaScriptResult(result);
                    if (scriptResult.status() == 0) {
                        continue;
                    }
                    if (scriptResult.status() < 0 || scriptResult.snapshot() == null) {
                        return new ApplyResult(WriteResult.REJECTED, null);
                    }
                    return new ApplyResult(WriteResult.ACCEPTED, scriptResult.snapshot());
                }
            }
            return new ApplyResult(WriteResult.REJECTED, null);
        } catch (Exception ignored) {
            return new ApplyResult(WriteResult.UNAVAILABLE, null);
        }
    }

    private static DeltaScriptResult parseDeltaScriptResult(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return new DeltaScriptResult(-1, null);
        }
        try {
            int status = Integer.parseInt(redisString(values.get(0)));
            if (status <= 0 || values.size() < 3) {
                return new DeltaScriptResult(status, null);
            }
            BigDecimal balance = NumericUtil.normalize(redisString(values.get(1)));
            long version = Long.parseLong(redisString(values.get(2)));
            if (version <= 0) {
                return new DeltaScriptResult(-1, null);
            }
            return new DeltaScriptResult(status, new Snapshot(balance, version));
        } catch (RuntimeException ignored) {
            return new DeltaScriptResult(-1, null);
        }
    }

    private static String redisString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    public enum WriteResult {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE
    }

    public record Snapshot(BigDecimal balance, long version) {
    }

    public record ApplyResult(WriteResult status, Snapshot snapshot) {
    }

    private record DeltaScriptResult(int status, Snapshot snapshot) {
    }
}
