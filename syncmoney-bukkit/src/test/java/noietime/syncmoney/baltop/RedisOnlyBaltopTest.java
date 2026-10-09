package noietime.syncmoney.baltop;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RedisOnlyBaltopTest {
    @Test void redisOnlyModeKeepsLeaderboardEnabled() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.database().getDatabaseType()).thenReturn("mysql");
        RedisManager redis = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.zcard("syncmoney:baltop")).thenReturn(2L);
        BaltopManager manager = new BaltopManager(plugin, config, redis, mock(NameResolver.class), null);
        assertEquals(2, manager.getTotalPlayers());
        verify(jedis).close();
    }
}
