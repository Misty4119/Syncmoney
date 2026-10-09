package noietime.syncmoney.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

    private static CMIEconomyHandler newHandler(CMIApi cmiApi) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CMIEconomyHandlerApiCacheTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        return new CMIEconomyHandler(plugin, config, null, null, cmiApi);
    }
}
