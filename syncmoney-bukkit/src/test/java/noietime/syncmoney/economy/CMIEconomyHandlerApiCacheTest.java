package noietime.syncmoney.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.sync.CMIApi;
import org.junit.jupiter.api.Test;

class CMIEconomyHandlerApiCacheTest {

    @Test
    void inboundSynchronizationBookkeepingDoesNotClaimAConfirmedAuthoritySnapshot() {
        CMIApi cmiApi = mock(CMIApi.class);
        CMIEconomyHandler handler = newHandler(cmiApi);
        UUID uuid = UUID.randomUUID();
        BigDecimal mirrorOnlyBalance = new BigDecimal("93.00");

        handler.notifyInboundApplied(uuid, mirrorOnlyBalance, 12L);

        assertTrue(handler.getCachedBalanceForApi(uuid).isEmpty(),
                "synchronization bookkeeping may reflect a mirror value not applied to local CMI");
    }

    @Test
    void existingOwnerReadPopulatesTheApiAuthoritySnapshot() {
        CMIApi cmiApi = mock(CMIApi.class);
        CMIEconomyHandler handler = newHandler(cmiApi);
        UUID uuid = UUID.randomUUID();
        BigDecimal authorityBalance = new BigDecimal("27.50");
        when(cmiApi.getExistingBalance(uuid)).thenReturn(Optional.of(authorityBalance));

        assertEquals(Optional.of(authorityBalance), handler.getExistingBalanceForApi(uuid));
        assertEquals(Optional.of(authorityBalance), handler.getCachedBalanceForApi(uuid));
    }

    @Test
    void inboundVersionWatermarkEvictsLeastRecentlyUsedOnlyWhenCapacityIsExceeded() throws Exception {
        CMIEconomyHandler handler = newHandler(mock(CMIApi.class));
        List<UUID> accounts = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            UUID account = UUID.randomUUID();
            accounts.add(account);
            handler.notifyInboundApplied(account, BigDecimal.ZERO, (long) i + 1L);
        }

        Field field = CMIEconomyHandler.class.getDeclaredField("lastAppliedVersion");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Long> watermark = (Map<UUID, Long>) field.get(handler);
        synchronized (watermark) {
            assertEquals(1L, watermark.get(accounts.get(0)));
        }
        UUID newest = UUID.randomUUID();
        handler.notifyInboundApplied(newest, BigDecimal.ZERO, 10_001L);

        assertEquals(10_000, watermark.size());
        assertTrue(watermark.containsKey(accounts.get(0)), "a recently used watermark should stay resident");
        assertFalse(watermark.containsKey(accounts.get(1)), "the least recently used watermark should be evicted under capacity pressure");
        assertTrue(watermark.containsKey(newest));
    }

    private static CMIEconomyHandler newHandler(CMIApi cmiApi) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyHandlerApiCacheTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        return new CMIEconomyHandler(plugin, config, null, null, cmiApi);
    }
}
