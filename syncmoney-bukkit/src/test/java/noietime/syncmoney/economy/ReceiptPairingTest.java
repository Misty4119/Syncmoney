package noietime.syncmoney.economy;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.ShadowSyncConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent.EventSource;
import noietime.syncmoney.economy.EconomyEvent.EventType;
import noietime.syncmoney.protocol.RedisOperationFingerprint;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.PubSubMessage;
import noietime.syncmoney.sync.PubsubSubscriber;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptPairingTest {
    @Test
    void cachedSnapshotReturnsOnePairWithoutFallingBackToRedis() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ReceiptPairingTest"));
        RedisManager redisManager = mock(RedisManager.class);
        CacheManager cacheManager = new CacheManager(plugin, redisManager, false);
        UUID account = UUID.randomUUID();
        cacheManager.updateMemoryCache(account, new BigDecimal("90.00"), 7L);

        Optional<CacheManager.CachedBalance> snapshot = cacheManager.getCachedBalanceSnapshot(account);

        assertTrue(snapshot.isPresent());
        assertEquals(new BigDecimal("90.00"), snapshot.orElseThrow().balance());
        assertEquals(7L, snapshot.orElseThrow().version());
        verify(redisManager, never()).getResource();
    }

    @Test
    void confirmedPubSubMessagePreservesExactDecimalAndPluginAttribution() {
        UUID accountId = UUID.randomUUID();
        BigDecimal exactBalance = new BigDecimal("9007199254740993.01");
        EconomyEvent event = new EconomyEvent(accountId, new BigDecimal("0.01"), exactBalance, 42L,
                EventType.DEPOSIT, EventSource.PLUGIN_DEPOSIT, "plugin-deposit", 1234L, "ChestShop");
        ConfirmedAccount account = new ConfirmedAccount(accountId, exactBalance, 42L);

        PubSubMessage message = EconomyEventConsumer.createPubSubMessage(event, account, "server-a");
        PubSubMessage decoded = PubSubMessage.fromJson(message.toJson());

        assertEquals(exactBalance, decoded.getBalanceAsBigDecimal());
        assertEquals("ChestShop", decoded.getSourcePlugin());
        assertEquals(42L, decoded.getVersion());
    }

    @Test
    void directTransferPublisherKeepsTheAtomicMutationVersionWhenCacheAdvances() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ReceiptPairingTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("server-a");

        UUID account = UUID.randomUUID();
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getVersion(account)).thenReturn(8L);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);

        PubsubSubscriber publisher = new PubsubSubscriber(plugin, config, cacheManager,
                null, null, redisManager, true);
        Field running = PubsubSubscriber.class.getDeclaredField("running");
        running.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) running.get(publisher)).set(true);

        publisher.publishTransferEvent(account, new BigDecimal("90.00"), 7L,
                "TRANSFER_OUT", 10.0, "Sender");

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedis).publish(eq("syncmoney:balance:update"), payload.capture());
        PubSubMessage message = PubSubMessage.fromJson(payload.getValue());
        assertEquals(new BigDecimal("90.00"), message.getBalanceAsBigDecimal());
        assertEquals(7L, message.getVersion(),
                "the transfer's v7 balance must not be relabeled with a later cached v8");
        verify(cacheManager, never()).getVersion(account);
    }

    @Test
    void legacyTransferPublisherRequiresOneMatchingCachedBalanceVersionPair() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ReceiptPairingTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("server-a");

        UUID matchingAccount = UUID.randomUUID();
        UUID mismatchedAccount = UUID.randomUUID();
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCachedBalanceSnapshot(matchingAccount)).thenReturn(Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("90.00"), 7L)));
        when(cacheManager.getCachedBalanceSnapshot(mismatchedAccount)).thenReturn(Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("100.00"), 8L)));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);

        PubsubSubscriber publisher = new PubsubSubscriber(plugin, config, cacheManager,
                null, null, redisManager, true);
        Field running = PubsubSubscriber.class.getDeclaredField("running");
        running.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) running.get(publisher)).set(true);

        publisher.publishTransferEvent(matchingAccount, new BigDecimal("90.00"),
                "TRANSFER_OUT", 10.0, "Sender");
        publisher.publishBalanceChange(matchingAccount, new BigDecimal("90.0"));
        publisher.publishTransferEvent(mismatchedAccount, new BigDecimal("90.00"),
                "TRANSFER_OUT", 10.0, "Sender");

        org.mockito.ArgumentCaptor<String> payloads = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jedis, org.mockito.Mockito.times(2))
                .publish(eq("syncmoney:balance:update"), payloads.capture());
        List<PubSubMessage> messages = payloads.getAllValues().stream().map(PubSubMessage::fromJson).toList();
        assertEquals(7L, messages.get(0).getVersion());
        assertEquals("TRANSFER_OUT", messages.get(0).getEventType());
        assertEquals(7L, messages.get(1).getVersion());
        assertEquals(null, messages.get(1).getEventType());
        verify(cacheManager, never()).getVersion(matchingAccount);
        verify(cacheManager, never()).getVersion(mismatchedAccount);
    }

    @Test
    void publishAndPersistenceUseTheRedisReceiptAfterTheCacheAdvances() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ReceiptPairingTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        ShadowSyncConfig shadowConfig = mock(ShadowSyncConfig.class);
        when(config.shadowSync()).thenReturn(shadowConfig);
        when(config.getServerName()).thenReturn("server-a");
        when(config.isPubsubEnabled()).thenReturn(true);
        when(config.isDbEnabled()).thenReturn(true);
        when(shadowConfig.isShadowSyncEnabled()).thenReturn(false);

        UUID account = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(account, new BigDecimal("10.00"), new BigDecimal("100.00"), 7L,
                EventType.DEPOSIT, EventSource.TEST, "receipt-v7", System.currentTimeMillis());
        EconomyWriteQueue queue = new EconomyWriteQueue(1);
        EconomyWriteQueue.Reservation reservation = queue.tryReserve(account);
        assertTrue(reservation.commit(event));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicAddBalanceReceipt(
                eq(account), eq(new BigDecimal("10.00")), eq("receipt-v7")))
                .thenReturn(new ConfirmedAccount(account, new BigDecimal("90.00"), 7L));
        when(cacheManager.getVersion(account)).thenReturn(8L);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.publish(anyString(), anyString())).thenReturn(1L);

        DbWriteQueue dbWriteQueue = mock(DbWriteQueue.class);
        CountDownLatch queued = new CountDownLatch(1);
        AtomicReference<DbWriteQueue.DbWriteTask> capturedTask = new AtomicReference<>();
        when(dbWriteQueue.offerAndTrack(any(DbWriteQueue.DbWriteTask.class))).thenAnswer(invocation -> {
            capturedTask.set(invocation.getArgument(0));
            queued.countDown();
            return CompletableFuture.completedFuture(true);
        });
        NameResolver nameResolver = mock(NameResolver.class);
        when(nameResolver.getNameCachedOnly(account)).thenReturn("PlayerA");

        EconomyEventConsumer consumer = new EconomyEventConsumer(plugin, config, queue, cacheManager,
                redisManager, dbWriteQueue, null, null, nameResolver, null, null, null, null);
        consumer.start();
        try {
            assertTrue(queued.await(3, TimeUnit.SECONDS), "the confirmed snapshot should reach the SQL queue");
        } finally {
            consumer.shutdown();
        }

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedis).publish(eq("syncmoney:balance:update"), payload.capture());
        PubSubMessage message = PubSubMessage.fromJson(payload.getValue());
        assertEquals(new BigDecimal("90.00"), message.getBalanceAsBigDecimal());
        assertEquals(7L, message.getVersion(),
                "Pub/Sub must carry the exact balance/version pair returned by Redis");
        assertTrue(capturedTask.get() != null);
        assertEquals("receipt-v7", capturedTask.get().operationId());
        assertEquals(account, capturedTask.get().account().playerUuid());
        assertEquals(new BigDecimal("90.00"), capturedTask.get().account().balance());
        assertTrue(capturedTask.get().version() == 7L,
                "the SQL queue must retain the same confirmed version as the published balance");
        verify(cacheManager, never()).getVersion(account);
        assertFalse(queue.hasAcceptedWrite(account), "successful completion releases the accepted write");
    }

    @Test
    void delayedRedisReceiptDoesNotRegressTheLocalCache() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ReceiptPairingTest"));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        UUID account = UUID.randomUUID();
        String fingerprint = RedisOperationFingerprint.add(account, new BigDecimal("10.00"));
        when(jedis.evalsha(anyString(), eq(3), any(String[].class)))
                .thenReturn("v1|" + fingerprint + "|APPLIED|-|1|90.00|7");

        CacheManager cacheManager = new CacheManager(plugin, redisManager, false);
        Field addScript = CacheManager.class.getDeclaredField("atomicAddBalanceSha");
        addScript.setAccessible(true);
        addScript.set(cacheManager, "test-script-sha");
        cacheManager.updateMemoryCache(account, new BigDecimal("100.00"), 8L);

        ConfirmedAccount receipt = cacheManager.atomicAddBalanceReceipt(account, new BigDecimal("10.00"));

        assertEquals(new BigDecimal("90.00"), receipt.balance());
        assertEquals(7L, receipt.version(), "receipt must preserve the pair returned by the same Lua call");
        assertEquals(new BigDecimal("100.00"), cacheManager.getBalance(account),
                "a delayed v7 response must not overwrite the locally observed v8 cache entry");
        assertEquals(8L, cacheManager.getVersion(account));
        verify(jedis).evalsha(anyString(), eq(3), any(String[].class));
    }
}
