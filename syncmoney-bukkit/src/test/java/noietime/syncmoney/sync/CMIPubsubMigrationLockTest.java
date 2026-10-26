package noietime.syncmoney.sync;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.RedisManager;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class CMIPubsubMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void ignoresInboundCmiWriteBeforeChangingRedisMirrorWhileMigrationIsLocked() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIPubsubMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.getServerName()).thenReturn("local");
        RedisManager redis = mock(RedisManager.class);
        when(redis.isDegraded()).thenReturn(false);
        when(redis.getResource()).thenReturn(mock(Jedis.class));
        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(false);
        CMIEconomyHandler cmiHandler = mock(CMIEconomyHandler.class);
        CMIPubsubHandler handler = new CMIPubsubHandler(plugin, config, redis, cmiApi, cmiHandler);
        UUID account = UUID.randomUUID();
        PubSubMessage message = new PubSubMessage(account.toString(), 150.0, 2L,
                "remote", "message-2", 2L);

        MigrationLock.enable();
        assertTrue(MigrationLock.lock());

        handler.process(message);

        verify(redis, never()).getResource();
        verify(cmiHandler, never()).notifyInboundApplied(any(), any(), any());
    }

    @Test
    void keepsMigrationAdmissionClosedUntilScheduledCmiMutationFinishes() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIPubsubMigrationLockTest"));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.getServerName()).thenReturn("local");
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.isDegraded()).thenReturn(false);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(true);
        UUID account = UUID.randomUUID();
        when(cmiApi.getBalance(account)).thenReturn(100.0);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(server.getPlayer(account)).thenReturn(player);
        AtomicReference<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> scheduledWork =
                new AtomicReference<>();
        doAnswer(invocation -> {
            scheduledWork.set(invocation.getArgument(1));
            return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        }).when(entityScheduler).run(eq(plugin), any(), any());
        CMIEconomyHandler cmiHandler = mock(CMIEconomyHandler.class);
        CMIPubsubHandler handler = new CMIPubsubHandler(plugin, config, redis, cmiApi, cmiHandler);

        MigrationLock.enable();
        handler.process(new PubSubMessage(account.toString(), 150.0, 2L,
                "remote", "message-2", 2L));

        assertNotNull(scheduledWork.get(), "online CMI mutation is dispatched to the entity owner");
        assertFalse(MigrationLock.lock(), "migration cannot start while the accepted inbound CMI write is pending");
        scheduledWork.get().accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));

        verify(cmiApi).setBalance(account, 150.0);
        assertTrue(MigrationLock.lock(), "migration can acquire its lock after the CMI mutation completes");
    }
}
