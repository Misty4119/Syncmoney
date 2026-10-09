package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.CrossServerConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.PubSubMessage;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossServerSyncManagerReceiptTest {
    @Test
    void localNotificationHookDoesNotPublishBeforeTheConsumerReceipt() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CrossServerSyncManagerReceiptTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("server-a");
        CrossServerConfig crossServer = mock(CrossServerConfig.class);
        when(config.crossServer()).thenReturn(crossServer);
        when(crossServer.isCrossServerNotificationsEnabled()).thenReturn(false);

        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        CrossServerSyncManager syncManager = new CrossServerSyncManager(
                plugin, config, redisManager, null, cacheManager);

        syncManager.notifyLocalBalanceChange(UUID.randomUUID(), "DEPOSIT", 10.0, "ExamplePlugin");

        verify(cacheManager, never()).getVersion(org.mockito.ArgumentMatchers.any());
        verify(redisManager, never()).getResource();
    }

    @Test
    void legacyPublisherSkipsBalanceWhenItCannotMatchAnAtomicCachedPair() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CrossServerSyncManagerReceiptTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("server-a");
        CrossServerConfig crossServer = mock(CrossServerConfig.class);
        when(config.crossServer()).thenReturn(crossServer);
        when(crossServer.isCrossServerNotificationsEnabled()).thenReturn(false);

        UUID account = UUID.randomUUID();
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCachedBalanceSnapshot(account)).thenReturn(Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("100.00"), 8L)));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CrossServerSyncManager syncManager = new CrossServerSyncManager(
                plugin, config, redisManager, null, cacheManager);

        syncManager.publishAndNotify(account, new BigDecimal("90.00"),
                "VAULT_WITHDRAW", -10.0, "ExamplePlugin", null);

        verify(jedis, never()).publish(anyString(), anyString());
        verify(cacheManager, never()).getVersion(account);
    }

    @Test
    void legacyPublisherUsesTheVersionFromTheMatchingCachedBalancePair() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CrossServerSyncManagerReceiptTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("server-a");
        CrossServerConfig crossServer = mock(CrossServerConfig.class);
        when(config.crossServer()).thenReturn(crossServer);
        when(crossServer.isCrossServerNotificationsEnabled()).thenReturn(false);

        UUID account = UUID.randomUUID();
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCachedBalanceSnapshot(account)).thenReturn(Optional.of(
                new CacheManager.CachedBalance(new BigDecimal("90.00"), 7L)));
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CrossServerSyncManager syncManager = new CrossServerSyncManager(
                plugin, config, redisManager, null, cacheManager);

        syncManager.publishAndNotify(account, new BigDecimal("90.0"),
                "VAULT_WITHDRAW", -10.0, "ExamplePlugin", "Sender");

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedis).publish(org.mockito.ArgumentMatchers.eq(
                noietime.syncmoney.sync.PubSubChannels.BALANCE_UPDATE), payload.capture());
        PubSubMessage message = PubSubMessage.fromJson(payload.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("90.00"), message.getBalanceAsBigDecimal());
        org.junit.jupiter.api.Assertions.assertEquals(7L, message.getVersion());
        org.junit.jupiter.api.Assertions.assertEquals("ExamplePlugin", message.getSourcePlugin());
        org.junit.jupiter.api.Assertions.assertEquals("Sender", message.getSourcePlayerName());
        verify(cacheManager, never()).getVersion(account);
    }

    @Test
    void explicitPublisherKeepsTheAtomicMutationVersionAndAttribution() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CrossServerSyncManagerReceiptTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("server-a");
        CrossServerConfig crossServer = mock(CrossServerConfig.class);
        when(config.crossServer()).thenReturn(crossServer);
        when(crossServer.isCrossServerNotificationsEnabled()).thenReturn(false);

        UUID account = UUID.randomUUID();
        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        CrossServerSyncManager syncManager = new CrossServerSyncManager(
                plugin, config, redisManager, null, cacheManager);

        syncManager.publishAndNotify(account, new BigDecimal("90.00"), 7L,
                "VAULT_WITHDRAW", -10.0, "ExamplePlugin", "Sender");

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedis).publish(org.mockito.ArgumentMatchers.eq(
                noietime.syncmoney.sync.PubSubChannels.BALANCE_UPDATE), payload.capture());
        PubSubMessage message = PubSubMessage.fromJson(payload.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("90.00"), message.getBalanceAsBigDecimal());
        org.junit.jupiter.api.Assertions.assertEquals(7L, message.getVersion());
        org.junit.jupiter.api.Assertions.assertEquals("ExamplePlugin", message.getSourcePlugin());
        org.junit.jupiter.api.Assertions.assertEquals("Sender", message.getSourcePlayerName());
        verify(cacheManager, never()).getCachedBalanceSnapshot(account);
        verify(cacheManager, never()).getVersion(account);
    }
}
