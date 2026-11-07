package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.sync.CMIApi;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CMIEconomyHandlerObservationTest {

    @Test
    void balanceObservationsAreBoundedAndEvictTheLeastRecentlyUsedOnlyAtCapacity()
            throws ReflectiveOperationException {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyHandlerObservationTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.cmi().getCMIRedisPrefix()).thenReturn("syncmoney:cmi");
        CMIApi cmiApi = mock(CMIApi.class);
        when(cmiApi.isAvailable()).thenReturn(false);
        CMIEconomyHandler handler = new CMIEconomyHandler(plugin, config, null, null, cmiApi);

        UUID oldest = null;
        UUID secondOldest = null;
        for (int i = 0; i < 10_000; i++) {
            UUID uuid = UUID.randomUUID();
            if (i == 0) oldest = uuid;
            if (i == 1) secondOldest = uuid;
            handler.recordApiAuthorityBalance(uuid, BigDecimal.valueOf(i));
        }

        assertTrue(handler.getCachedBalanceForApi(oldest).isPresent(),
                "observations remain available until the cache reaches capacity");
        handler.recordApiAuthorityBalance(oldest, new BigDecimal("42.00"));
        UUID newest = UUID.randomUUID();
        handler.recordApiAuthorityBalance(newest, new BigDecimal("43.00"));

        Field field = CMIEconomyHandler.class.getDeclaredField("balanceObservations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        LinkedHashMap<UUID, ?> observations = (LinkedHashMap<UUID, ?>) field.get(handler);
        assertEquals(10_000, observations.size());
        assertTrue(observations.containsKey(oldest), "a refreshed observation must not be evicted");
        assertFalse(observations.containsKey(secondOldest), "the least recently observed account is evicted");
        assertTrue(observations.containsKey(newest));
    }
}
