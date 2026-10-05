package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EconomyModeRouterMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void localRouterRejectsMutationBeforeCallingSqliteHandlerWhenMigrationIsLocked() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getEconomyMode()).thenReturn(EconomyMode.LOCAL);
        LocalEconomyHandler localHandler = mock(LocalEconomyHandler.class);
        EconomyModeRouter router = new EconomyModeRouter(plugin, config);
        router.initialize(null, localHandler, null, null);

        MigrationLock.enable();
        MigrationLock.lock();
        UUID account = UUID.randomUUID();

        assertEquals(new BigDecimal("-1"), router.deposit(account, new BigDecimal("25.00"),
                EconomyEvent.EventSource.ADMIN_SET));
        assertEquals(new BigDecimal("-1"), router.setBalance(account, new BigDecimal("125.00"),
                EconomyEvent.EventSource.ADMIN_SET));
        verify(localHandler, never()).deposit(account, null, new BigDecimal("25.00"), "ADMIN_SET");
        verify(localHandler, never()).setBalance(account, null, new BigDecimal("125.00"), "ADMIN_SET");
    }
}
