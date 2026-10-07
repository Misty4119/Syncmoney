package noietime.syncmoney.sync;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisMutationSafetyIT {
    private static RedisProcessTestSupport redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisProcessTestSupport.start();
    }

    @BeforeEach
    void clearRedis() {
        try (Jedis jedis = redis.connect()) {
            jedis.flushDB();
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @Test
    void receiverBalanceWrongTypeDoesNotDebitSender() throws Exception {
        String senderBalance = "syncmoney:balance:sender";
        String senderVersion = "syncmoney:version:sender";
        String receiverBalance = "syncmoney:balance:receiver";
        String receiverVersion = "syncmoney:version:receiver";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "100.00");
            jedis.set(senderVersion, "7");
            jedis.rpush(receiverBalance, "wrong-type");
            jedis.set(receiverVersion, "3");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("10.00")));

            assertEquals("100.00", jedis.get(senderBalance));
            assertEquals("7", jedis.get(senderVersion));
            assertEquals("list", jedis.type(receiverBalance));
            assertEquals("3", jedis.get(receiverVersion));
        }
    }

    @Test
    void receiverVersionWrongTypeDoesNotChangeEitherBalance() throws Exception {
        String senderBalance = "syncmoney:balance:sender";
        String senderVersion = "syncmoney:version:sender";
        String receiverBalance = "syncmoney:balance:receiver";
        String receiverVersion = "syncmoney:version:receiver";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "100.00");
            jedis.set(senderVersion, "7");
            jedis.set(receiverBalance, "0.00");
            jedis.rpush(receiverVersion, "wrong-type");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("10.00")));

            assertEquals("100.00", jedis.get(senderBalance));
            assertEquals("0.00", jedis.get(receiverBalance));
            assertEquals("7", jedis.get(senderVersion));
            assertEquals("list", jedis.type(receiverVersion));
        }
    }

    @Test
    void versionOverflowDoesNotChangeBalance() throws Exception {
        String balance = "syncmoney:balance:overflow";
        String version = "syncmoney:version:overflow";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "9223372036854775807");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_add_balance.lua",
                    List.of(balance, version), List.of("-10.00")));

            assertEquals("100.00", jedis.get(balance));
            assertEquals("9223372036854775807", jedis.get(version));
        }
    }

    @Test
    void addReturnsExactVersionAboveLuaIntegerPrecision() throws Exception {
        String balance = "syncmoney:balance:add-large-version";
        String version = "syncmoney:version:add-large-version";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "9007199254740992");

            Object result = eval(jedis, "atomic_add_balance.lua", List.of(balance, version), List.of("1.00"));

            assertEquals(List.of("101.00", "9007199254740993"), result);
            assertEquals("9007199254740993", jedis.get(version));
        }
    }

    @Test
    void setReturnsExactVersionAboveLuaIntegerPrecision() throws Exception {
        String balance = "syncmoney:balance:set-large-version";
        String version = "syncmoney:version:set-large-version";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "9007199254740992");

            Object result = eval(jedis, "atomic_set_balance.lua", List.of(version, balance), List.of("50.00"));

            assertEquals("9007199254740993", result);
            assertEquals("9007199254740993", jedis.get(version));
        }
    }

    @Test
    void transferReturnsExactVersionsAboveLuaIntegerPrecision() throws Exception {
        String senderBalance = "syncmoney:balance:large-version-sender";
        String senderVersion = "syncmoney:version:large-version-sender";
        String receiverBalance = "syncmoney:balance:large-version-receiver";
        String receiverVersion = "syncmoney:version:large-version-receiver";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "100.00");
            jedis.set(senderVersion, "9007199254740992");
            jedis.set(receiverBalance, "0.00");
            jedis.set(receiverVersion, "9007199254740993");

            Object result = eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("10.00"));

            assertEquals(List.of("90.00", "10.00", "9007199254740993", "9007199254740994"), result);
            assertEquals("9007199254740993", jedis.get(senderVersion));
            assertEquals("9007199254740994", jedis.get(receiverVersion));
        }
    }

    @Test
    void setPreservesLegacyNegativeBalanceErrorBeforeMutation() throws Exception {
        String balance = "syncmoney:balance:set-negative";
        String version = "syncmoney:version:set-negative";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "7");

            JedisDataException rejection = assertThrows(JedisDataException.class,
                    () -> eval(jedis, "atomic_set_balance.lua", List.of(version, balance), List.of("-1.00")));

            assertTrue(rejection.getMessage().contains("NEGATIVE_BALANCE"));
            assertEquals("100.00", jedis.get(balance));
            assertEquals("7", jedis.get(version));
        }
    }

    @Test
    void addRejectsOverlongVersionBeforeChangingBalance() throws Exception {
        String balance = "syncmoney:balance:overlong-version";
        String version = "syncmoney:version:overlong-version";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "000000000000000000000000");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_add_balance.lua",
                    List.of(balance, version), List.of("10.00")));

            assertEquals("100.00", jedis.get(balance));
            assertEquals("000000000000000000000000", jedis.get(version));
        }
    }

    @Test
    void transferRejectsNonCanonicalVersionBeforeChangingEitherBalance() throws Exception {
        String senderBalance = "syncmoney:balance:noncanonical-sender";
        String senderVersion = "syncmoney:version:noncanonical-sender";
        String receiverBalance = "syncmoney:balance:noncanonical-receiver";
        String receiverVersion = "syncmoney:version:noncanonical-receiver";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "100.00");
            jedis.set(senderVersion, "7");
            jedis.set(receiverBalance, "0.00");
            jedis.set(receiverVersion, "0007");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("10.00")));

            assertEquals("100.00", jedis.get(senderBalance));
            assertEquals("0.00", jedis.get(receiverBalance));
            assertEquals("7", jedis.get(senderVersion));
            assertEquals("0007", jedis.get(receiverVersion));
        }
    }

    @Test
    void setRejectsBalanceAboveSqlDecimalRangeBeforeMutation() throws Exception {
        String balance = "syncmoney:balance:set-sql-range";
        String version = "syncmoney:version:set-sql-range";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "7");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_set_balance.lua",
                    List.of(version, balance), List.of("1000000000000000000.00")));

            assertEquals("100.00", jedis.get(balance));
            assertEquals("7", jedis.get(version));
        }
    }

    @Test
    void setAcceptsMaximumSqlDecimalBalanceExactly() throws Exception {
        String balance = "syncmoney:balance:set-sql-maximum";
        String version = "syncmoney:version:set-sql-maximum";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "7");

            Object result = eval(jedis, "atomic_set_balance.lua",
                    List.of(version, balance), List.of("999999999999999999.99"));

            assertEquals("999999999999999999.99", jedis.get(balance));
            assertEquals("8", result);
            assertEquals("8", jedis.get(version));
        }
    }

    @Test
    void addRejectsBalanceAboveSqlDecimalRangeBeforeMutation() throws Exception {
        String balance = "syncmoney:balance:add-sql-range";
        String version = "syncmoney:version:add-sql-range";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "999999999999999999.99");
            jedis.set(version, "7");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_add_balance.lua",
                    List.of(balance, version), List.of("0.01")));

            assertEquals("999999999999999999.99", jedis.get(balance));
            assertEquals("7", jedis.get(version));
        }
    }

    @Test
    void transferRejectsReceiverAboveSqlDecimalRangeBeforeMutation() throws Exception {
        String senderBalance = "syncmoney:balance:range-sender";
        String senderVersion = "syncmoney:version:range-sender";
        String receiverBalance = "syncmoney:balance:range-receiver";
        String receiverVersion = "syncmoney:version:range-receiver";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "10.00");
            jedis.set(senderVersion, "7");
            jedis.set(receiverBalance, "999999999999999999.99");
            jedis.set(receiverVersion, "3");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("0.01")));

            assertEquals("10.00", jedis.get(senderBalance));
            assertEquals("999999999999999999.99", jedis.get(receiverBalance));
            assertEquals("7", jedis.get(senderVersion));
            assertEquals("3", jedis.get(receiverVersion));
        }
    }

    @Test
    void addPreservesCentsAboveLuaDoubleIntegerPrecision() throws Exception {
        String balance = "syncmoney:balance:large-exact-balance";
        String version = "syncmoney:version:large-exact-balance";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "9007199254740992.00");
            jedis.set(version, "7");

            Object result = eval(jedis, "atomic_add_balance.lua", List.of(balance, version), List.of("0.01"));

            assertEquals("9007199254740992.01", jedis.get(balance));
            assertEquals(List.of("9007199254740992.01", "8"), result);
        }
    }

    @Test
    void versionWrongTypeDoesNotChangeBalance() throws Exception {
        String balance = "syncmoney:balance:add-version-type";
        String version = "syncmoney:version:add-version-type";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.rpush(version, "wrong-type");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_add_balance.lua",
                    List.of(balance, version), List.of("-10.00")));

            assertEquals("100.00", jedis.get(balance));
            assertEquals("list", jedis.type(version));
        }
    }

    @Test
    void setBalanceWrongTypeDoesNotIncrementVersion() throws Exception {
        String balance = "syncmoney:balance:set-type";
        String version = "syncmoney:version:set-type";
        try (Jedis jedis = redis.connect()) {
            jedis.rpush(balance, "wrong-type");
            jedis.set(version, "7");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_set_balance.lua",
                    List.of(version, balance), List.of("20.00")));

            assertEquals("7", jedis.get(version));
            assertEquals("list", jedis.type(balance));
        }
    }

    @Test
    void transferSenderVersionWrongTypeDoesNotChangeEitherBalance() throws Exception {
        String senderBalance = "syncmoney:balance:sender-version-type";
        String senderVersion = "syncmoney:version:sender-version-type";
        String receiverBalance = "syncmoney:balance:receiver-version-type";
        String receiverVersion = "syncmoney:version:receiver-version-type";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "100.00");
            jedis.rpush(senderVersion, "wrong-type");
            jedis.set(receiverBalance, "0.00");
            jedis.set(receiverVersion, "3");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("10.00")));

            assertEquals("100.00", jedis.get(senderBalance));
            assertEquals("0.00", jedis.get(receiverBalance));
            assertEquals("list", jedis.type(senderVersion));
            assertEquals("3", jedis.get(receiverVersion));
        }
    }

    @Test
    void transferReceiverVersionOverflowDoesNotChangeEitherBalance() throws Exception {
        String senderBalance = "syncmoney:balance:sender-version-overflow";
        String senderVersion = "syncmoney:version:sender-version-overflow";
        String receiverBalance = "syncmoney:balance:receiver-version-overflow";
        String receiverVersion = "syncmoney:version:receiver-version-overflow";
        try (Jedis jedis = redis.connect()) {
            jedis.set(senderBalance, "100.00");
            jedis.set(senderVersion, "7");
            jedis.set(receiverBalance, "0.00");
            jedis.set(receiverVersion, "9223372036854775807");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(senderBalance, senderVersion, receiverBalance, receiverVersion), List.of("10.00")));

            assertEquals("100.00", jedis.get(senderBalance));
            assertEquals("0.00", jedis.get(receiverBalance));
            assertEquals("7", jedis.get(senderVersion));
            assertEquals("9223372036854775807", jedis.get(receiverVersion));
        }
    }

    @Test
    void setRejectsNonFiniteBalanceWithoutMutatingEitherKey() throws Exception {
        String balance = "syncmoney:balance:set-nonfinite";
        String version = "syncmoney:version:set-nonfinite";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "7");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_set_balance.lua",
                    List.of(version, balance), List.of("1e309")));

            assertEquals("100.00", jedis.get(balance));
            assertEquals("7", jedis.get(version));
        }
    }

    @Test
    void selfTransferIsRejectedWithoutBalanceOrVersionMutation() throws Exception {
        String balance = "syncmoney:balance:self";
        String version = "syncmoney:version:self";
        try (Jedis jedis = redis.connect()) {
            jedis.set(balance, "100.00");
            jedis.set(version, "7");

            assertThrows(JedisDataException.class, () -> eval(jedis, "atomic_transfer.lua",
                    List.of(balance, version, balance, version), List.of("10.00")));

            assertEquals("100.00", jedis.get(balance));
            assertEquals("7", jedis.get(version));
        }
    }

    private static Object eval(Jedis jedis, String resourceName, List<String> keys, List<String> args)
            throws IOException {
        try (InputStream input = RedisMutationSafetyIT.class.getResourceAsStream("/" + resourceName)) {
            if (input == null) throw new IOException("Missing canonical Lua resource: " + resourceName);
            return jedis.eval(new String(input.readAllBytes(), StandardCharsets.UTF_8), keys, args);
        }
    }
}
