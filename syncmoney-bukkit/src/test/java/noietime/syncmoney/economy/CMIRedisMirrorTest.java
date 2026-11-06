package noietime.syncmoney.economy;

import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIApi;
import noietime.syncmoney.sync.CMIRedisMirror;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import redis.clients.jedis.Jedis;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.ServerSocket;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CMIRedisMirrorTest {

    @Test
    void readReturnsOneAtomicBalanceVersionSnapshot() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000013");
        String prefix = "cmi:";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(balanceKey, versionKey)).thenReturn(Arrays.asList("12.50", "12345"));

        Optional<CMIRedisMirror.Snapshot> result = new CMIRedisMirror(redisManager, prefix).read(uuid);

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("12.50"), result.orElseThrow().balance());
        assertEquals(12345L, result.orElseThrow().version());
        verify(jedis).mget(balanceKey, versionKey);
        verify(jedis, never()).get(anyString());
    }

    @Test
    void incompletePairIsNotReturnedAsAReconciliationSnapshot() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000014");
        String prefix = "cmi:";
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(RedisKeys.cmiBalance(prefix, uuid), RedisKeys.cmiVersion(prefix, uuid)))
                .thenReturn(Arrays.asList("12.50", null));

        Optional<CMIRedisMirror.Snapshot> result = new CMIRedisMirror(redisManager, prefix).read(uuid);

        assertTrue(result.isEmpty());
    }

    @Test
    void compareAndWriteUsesAnAtomicVersionComparisonForBothMirrorKeys() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000015");
        String prefix = "cmi:";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        CMIRedisMirror.WriteResult result = new CMIRedisMirror(redisManager, prefix)
                .compareAndWrite(uuid, new BigDecimal("20.00"), 17_000_000_000_000_001L);

        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED, result);
        verify(jedis).eval(anyString(), eq(2), eq(balanceKey), eq(versionKey),
                eq("20.00"), eq("17000000000000001"));
        verify(jedis, never()).mset(anyString(), anyString(), anyString(), anyString());
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void compareAndWriteDistinguishesRejectedAndUnavailableMirrorStates() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000016");
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        CMIRedisMirror mirror = new CMIRedisMirror(redisManager, "cmi:");
        assertEquals(CMIRedisMirror.WriteResult.REJECTED,
                mirror.compareAndWrite(uuid, new BigDecimal("20.00"), 18L));

        when(redisManager.isDegraded()).thenReturn(true);
        assertEquals(CMIRedisMirror.WriteResult.UNAVAILABLE,
                mirror.compareAndWrite(uuid, new BigDecimal("20.00"), 19L));
    }

    @Test
    void staleOfflineCmiEventIsAppliedAsDeltaToTheCanonicalMirror() throws Exception {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000018");
        String prefix = "syncmoney:cmi";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(balanceKey, versionKey)).thenReturn(Arrays.asList("0.00", "100"));
        when(jedis.eval(anyString(), eq(3), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(1L, "7200.00", "200"));

        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CMIRedisMirrorTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn(prefix);
        when(config.cmi().isCMICrossServerSync()).thenReturn(true);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.getBalance(uuid)).thenReturn(11650.0);
        when(cmiApi.setBalance(uuid, 7200.0)).thenReturn(true);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, redisManager, syncManager, cmiApi);

        assertTrue(handler.trySyncAbsoluteBalance(uuid, new BigDecimal("11650.00"),
                new BigDecimal("7200.00"), true, "CMI"));

        verify(jedis).eval(anyString(), eq(3), eq(balanceKey), eq(versionKey), anyString(),
                eq("100"), eq("7200.00"), anyString(), anyString(), anyString(), anyString());
        verify(syncManager).publishCMIUpdate(eq(uuid), eq(new BigDecimal("7200.00")), eq(200L),
                eq("CMI_DEPOSIT"), eq(7200.0), eq("CMI"));

        Method applyJoinMirror = CMIEconomyHandler.class.getDeclaredMethod(
                "applyJoinMirror", UUID.class, BigDecimal.class, long.class);
        applyJoinMirror.setAccessible(true);
        applyJoinMirror.invoke(handler, uuid, new BigDecimal("7200.00"), 200L);
        verify(cmiApi).setBalance(uuid, 7200.0);
    }

    @Test
    void deltaCasRetriesAgainstTheLatestMirrorWithoutLosingConcurrentChanges() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000019");
        String prefix = "syncmoney:cmi";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(balanceKey, versionKey)).thenReturn(
                Arrays.asList("0.00", "100"), Arrays.asList("100.00", "101"));
        when(jedis.eval(anyString(), eq(3), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(0L), Arrays.asList(1L, "7300.00", "102"));

        CMIRedisMirror.ApplyResult result = new CMIRedisMirror(redisManager, prefix).applyDelta(
                uuid, new BigDecimal("11650.00"), new BigDecimal("7200.00"), "event-cas-1",
                new AtomicLong(100L)::incrementAndGet);

        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED, result.status());
        assertEquals(new BigDecimal("7300.00"), result.snapshot().balance());
        assertEquals(102L, result.snapshot().version());
        verify(jedis, times(2)).eval(anyString(), eq(3), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(jedis).eval(anyString(), eq(3), eq(balanceKey), eq(versionKey), anyString(),
                eq("101"), eq("7300.00"), eq("102"), anyString(), anyString(), anyString());
    }

    @Test
    void retryWithTheSameCmiEventIdReturnsItsReceiptInsteadOfAddingAgain() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000020");
        String prefix = "syncmoney:cmi";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(balanceKey, versionKey)).thenReturn(
                Arrays.asList("0.00", "100"), Arrays.asList("7200.00", "200"));
        when(jedis.eval(anyString(), eq(3), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(1L, "7200.00", "200"), Arrays.asList(2L, "7200.00", "200"));
        CMIRedisMirror mirror = new CMIRedisMirror(redisManager, prefix);

        CMIRedisMirror.ApplyResult first = mirror.applyDelta(uuid, new BigDecimal("11650.00"),
                new BigDecimal("7200.00"), "event-retry-1", () -> 200L);
        CMIRedisMirror.ApplyResult retry = mirror.applyDelta(uuid, new BigDecimal("11650.00"),
                new BigDecimal("7200.00"), "event-retry-1", () -> 201L);

        assertEquals(new BigDecimal("7200.00"), first.snapshot().balance());
        assertEquals(new BigDecimal("7200.00"), retry.snapshot().balance());
        assertEquals(200L, retry.snapshot().version());
        verify(jedis, times(2)).eval(anyString(), eq(3), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deltaDuplicateDetectionUsesTheOperationReceiptInsteadOfBalanceEquality() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000021");
        String prefix = "syncmoney:cmi";
        String balanceKey = RedisKeys.cmiBalance(prefix, uuid);
        String versionKey = RedisKeys.cmiVersion(prefix, uuid);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.isDegraded()).thenReturn(false);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.mget(balanceKey, versionKey)).thenReturn(Arrays.asList("11650.00", "100"));
        when(jedis.eval(anyString(), eq(3), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(1L, "18850.00", "101"));

        CMIRedisMirror.ApplyResult result = new CMIRedisMirror(redisManager, prefix).applyDelta(
                uuid, new BigDecimal("11650.00"), new BigDecimal("7200.00"), "event-distinct-1", () -> 101L);

        assertEquals(CMIRedisMirror.WriteResult.ACCEPTED, result.status());
        assertEquals(new BigDecimal("18850.00"), result.snapshot().balance());
        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(jedis).eval(script.capture(), eq(3), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
        String lua = script.getValue();
        assertTrue(lua.contains("local receipt = redis.call('GET', KEYS[3])"));
        assertFalse(lua.contains("canonical_current_balance == local_balance"),
                "an equal current balance is not proof that this event's delta was applied");
    }

    @Test
    void realRedisReplaysOnlyTheReceiptForTheSameCmiOperationFingerprint() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path dataDirectory = Files.createTempDirectory("syncmoney-cmi-receipt-redis-");
        Process redisServer;
        try {
            redisServer = new ProcessBuilder("redis-server",
                    "--bind", "127.0.0.1",
                    "--port", Integer.toString(port),
                    "--save", "",
                    "--appendonly", "no",
                    "--dir", dataDirectory.toString(),
                    "--loglevel", "warning")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException unavailable) {
            Files.deleteIfExists(dataDirectory);
            Assumptions.assumeTrue(false, "redis-server is required for the Redis-backed receipt regression");
            return;
        }

        try {
            awaitRedis(redisServer, port);
            RedisManager redisManager = mock(RedisManager.class);
            when(redisManager.isDegraded()).thenReturn(false);
            when(redisManager.getResource()).thenAnswer(ignored -> new Jedis("127.0.0.1", port));
            UUID uuid = UUID.randomUUID();
            CMIRedisMirror mirror = new CMIRedisMirror(redisManager, "syncmoney:cmi");
            assertEquals(CMIRedisMirror.WriteResult.ACCEPTED,
                    mirror.compareAndWrite(uuid, new BigDecimal("100.00"), 100L));

            CMIRedisMirror.ApplyResult first = mirror.applyDelta(uuid,
                    new BigDecimal("500.00"), new BigDecimal("25.00"), "redis-receipt-operation", () -> 101L);
            CMIRedisMirror.ApplyResult replay = mirror.applyDelta(uuid,
                    new BigDecimal("500.00"), new BigDecimal("25.00"), "redis-receipt-operation", () -> 102L);
            CMIRedisMirror.ApplyResult changedFingerprint = mirror.applyDelta(uuid,
                    new BigDecimal("500.00"), new BigDecimal("30.00"), "redis-receipt-operation", () -> 103L);

            assertEquals(CMIRedisMirror.WriteResult.ACCEPTED, first.status());
            assertEquals(new BigDecimal("125.00"), first.snapshot().balance());
            assertEquals(101L, first.snapshot().version());
            assertEquals(CMIRedisMirror.WriteResult.ACCEPTED, replay.status());
            assertEquals(new BigDecimal("125.00"), replay.snapshot().balance());
            assertEquals(101L, replay.snapshot().version());
            assertEquals(CMIRedisMirror.WriteResult.REJECTED, changedFingerprint.status());
            CMIRedisMirror.Snapshot persisted = mirror.read(uuid).orElseThrow();
            assertEquals(new BigDecimal("125.00"), persisted.balance());
            assertEquals(101L, persisted.version());
        } finally {
            redisServer.destroy();
            if (!redisServer.waitFor(3, TimeUnit.SECONDS)) {
                redisServer.destroyForcibly();
                redisServer.waitFor(3, TimeUnit.SECONDS);
            }
            try (var files = Files.list(dataDirectory)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // This temporary directory is test-owned; cleanup failure is surfaced below.
                    }
                });
            }
            Files.deleteIfExists(dataDirectory);
        }
    }

    private static void awaitRedis(Process redisServer, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!redisServer.isAlive()) {
                throw new AssertionError("redis-server exited before its readiness probe");
            }
            try (Jedis jedis = new Jedis("127.0.0.1", port)) {
                if ("PONG".equals(jedis.ping())) {
                    return;
                }
            } catch (RuntimeException ignored) {
                Thread.sleep(25L);
            }
        }
        throw new AssertionError("redis-server did not accept loopback connections within 10 seconds");
    }

    @Test
    void rejectedOutboundMirrorWriteDoesNotPublishOrReportCmiMutationAsAccepted() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000017");
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CMIRedisMirrorTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        when(config.cmi().isCMICrossServerSync()).thenReturn(true);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        when(jedis.get(anyString())).thenReturn("10.00");
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, redisManager, syncManager,
                mock(CMIApi.class));

        assertFalse(handler.trySyncAbsoluteBalance(uuid, new BigDecimal("20.00"),
                new BigDecimal("10.00"), true, "test"));
        assertEquals(new BigDecimal("-1"), handler.deposit(uuid, new BigDecimal("1.00")));
        assertEquals(new BigDecimal("-1"), handler.withdraw(uuid, new BigDecimal("1.00")));
        assertEquals(new BigDecimal("-1"), handler.setBalance(uuid, new BigDecimal("20.00")));

        verify(syncManager, never()).publishCMIUpdate(any(UUID.class), any(BigDecimal.class), anyLong(),
                anyString(), anyDouble(), anyString());
    }
}
