package noietime.syncmoney.economy;

import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverflowLogReadTest {
    @TempDir
    Path temp;

    @Test
    void fileLegacyReadIsRepeatableAndLeavesOriginalBytesUntouched() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Path file = temp.resolve("overflow.log");
        String line = "1728290000000|2ecbc4d9-82f4-4cbe-a73b-b702669ec865|SET_BALANCE|ADMIN_SET|5.00|legacy-set-1";
        Files.writeString(file, line + System.lineSeparator());
        byte[] before = Files.readAllBytes(file);
        OverflowLog overflowLog = new OverflowLog(plugin, temp);

        assertEquals(List.of(line), overflowLog.readWithoutClear());
        assertEquals(List.of(line), overflowLog.readWithoutClear());
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void redisLegacyReadDoesNotDeleteOrPruneEntries() throws Exception {
        Plugin plugin = mock(Plugin.class);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        UUID uuid = UUID.fromString("2ecbc4d9-82f4-4cbe-a73b-b702669ec865");
        String json = "{\"timestamp\":1728290000000,\"playerUuid\":\"" + uuid
                + "\",\"eventType\":\"SET_BALANCE\",\"eventSource\":\"ADMIN_SET\","
                + "\"amount\":\"5.00\",\"requestId\":\"legacy-set-1\","
                + "\"balanceAfter\":\"25.00\",\"version\":4}";
        when(redisManager.getResource()).thenReturn(jedis);
        when(jedis.zrange("syncmoney:overflow:events", 0, -1)).thenReturn(List.of("legacy-set-1:" + uuid + "|" + json));
        RedisOverflowLog overflowLog = new RedisOverflowLog(plugin, redisManager);

        List<String> lines = overflowLog.readWithoutClear();

        assertEquals(List.of("1728290000000|" + uuid + "|SET_BALANCE|ADMIN_SET|5.00|legacy-set-1"), lines);
        verify(jedis, never()).del(anyString());
        verify(jedis, never()).zremrangeByScore(anyString(), anyString(), anyString());
        verify(jedis, never()).zremrangeByRank(anyString(), anyLong(), anyLong());
        assertFalse(lines.isEmpty());
    }

    @Test
    void newLegacyRedisOverflowWriteDoesNotDeleteOlderEntries() {
        Plugin plugin = mock(Plugin.class);
        RedisManager redisManager = mock(RedisManager.class);
        Jedis jedis = mock(Jedis.class);
        when(redisManager.getResource()).thenReturn(jedis);
        RedisOverflowLog overflowLog = new RedisOverflowLog(plugin, redisManager);
        UUID uuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(uuid, BigDecimal.ONE, BigDecimal.TEN, 1L,
                EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventSource.TEST, "new-event", 1728290000000L);

        overflowLog.log(event);

        verify(jedis, never()).zremrangeByScore(anyString(), anyString(), anyString());
        verify(jedis, never()).zremrangeByRank(anyString(), anyLong(), anyLong());
    }
}
