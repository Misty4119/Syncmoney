package noietime.syncmoney.sync;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.protocol.RedisOperationFingerprint;
import noietime.syncmoney.testkit.RedisTestSupport;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisMissingAccountMutationIT {
    private static DisposableRedisServer redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = DisposableRedisServer.start();
    }

    @BeforeEach
    void clearRedis() {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.flushDB();
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) redis.close();
    }

    @Test
    void strictFirstAccountAddSucceedsOnlyWhileBothRedisKeysRemainAbsent() throws Exception {
        String balance = "syncmoney:balance:new-account";
        String version = "syncmoney:version:new-account";
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            ScriptReceipt result = add(jedis, balance, version, "5.00", "REQUIRE_ABSENT");

            assertApplied(result, List.of("5.00", "1"));
            assertEquals("5.00", jedis.get(balance));
            assertEquals("1", jedis.get(version));
        }
    }

    @Test
    void strictFirstAccountAddRejectsAAccountPairThatAppearsAfterSqlAbsenceWasRead() throws Exception {
        String balance = "syncmoney:balance:appeared-account";
        String version = "syncmoney:version:appeared-account";
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            // This represents another writer creating the account after the caller's strict SQL read.
            jedis.set(balance, "900.00");
            jedis.set(version, "41");

            ScriptReceipt failure = add(jedis, balance, version, "5.00", "REQUIRE_ABSENT");

            assertRejected(failure, "ACCOUNT_APPEARED");
            assertEquals(List.of("900.00", "41"), failure.accountValues());
            assertEquals("900.00", jedis.get(balance));
            assertEquals("41", jedis.get(version));
        }
    }

    @Test
    void strictExistingAccountAddRejectsMissingRedisKeysInsteadOfStartingAtZero() throws Exception {
        String balance = "syncmoney:balance:missing-account";
        String version = "syncmoney:version:missing-account";
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            ScriptReceipt failure = add(jedis, balance, version, "5.00", "REQUIRE_EXISTING");

            assertRejected(failure, "ACCOUNT_MISSING");
            assertEquals(List.of(), failure.accountValues());
            assertEquals(null, jedis.get(balance));
            assertEquals(null, jedis.get(version));
        }
    }

    private static ScriptReceipt add(Jedis jedis, String balance, String version, String amount, String mode)
            throws Exception {
        UUID account = testAccount(balance);
        String fingerprint = RedisOperationFingerprint.add(account, new BigDecimal(amount));
        String operationId = "RedisMissingAccountMutationIT|" + RedisOperationFingerprint.sha256(
                balance + "|" + version + "|" + amount + "|" + mode);
        try (InputStream input = RedisMissingAccountMutationIT.class.getResourceAsStream("/atomic_add_balance.lua")) {
            if (input == null) throw new IllegalStateException("Missing atomic_add_balance.lua");
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Object raw = jedis.eval(script,
                    List.of(balance, version, RedisKeys.operationReceipt(operationId)),
                    List.of(amount, mode, fingerprint));
            if (!(raw instanceof String receipt)) throw new IOException("Expected a serialized Lua operation receipt");
            return parseReceipt(receipt, fingerprint);
        }
    }

    private static UUID testAccount(String balanceKey) {
        return UUID.nameUUIDFromBytes(("RedisMissingAccountMutationIT|" + balanceKey).getBytes(StandardCharsets.UTF_8));
    }

    private static ScriptReceipt parseReceipt(String raw, String expectedFingerprint) throws IOException {
        String[] fields = raw.split("\\|", -1);
        if (fields.length < 5 || !"v1".equals(fields[0])) throw new IOException("Invalid Lua receipt envelope");
        if (!expectedFingerprint.equals(fields[1])) throw new IOException("Lua receipt fingerprint mismatch");
        int accountCount;
        try {
            accountCount = Integer.parseInt(fields[4]);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Lua receipt account count", exception);
        }
        if (accountCount < 0 || fields.length != 5 + accountCount * 2) {
            throw new IOException("Invalid Lua receipt account fields");
        }
        return new ScriptReceipt(fields[2], fields[3], List.of(fields).subList(5, fields.length));
    }

    private static void assertApplied(ScriptReceipt receipt, List<String> accountValues) {
        assertEquals("APPLIED", receipt.status());
        assertEquals("-", receipt.reason());
        assertEquals(accountValues, receipt.accountValues());
    }

    private static void assertRejected(ScriptReceipt receipt, String reason) {
        assertEquals("REJECTED", receipt.status());
        assertEquals(reason, receipt.reason());
    }

    private record ScriptReceipt(String status, String reason, List<String> accountValues) { }
}
