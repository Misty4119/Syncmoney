package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void directAtomicTransferIsRejectedWhileMigrationLockIsActive() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferMigrationLockTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CacheManager cache = mock(CacheManager.class);
        EconomyFacade facade = new EconomyFacade(plugin, config, cache, mock(RedisManager.class),
                null, null, new EconomyWriteQueue(4), mock(FallbackEconomyWrapper.class), null,
                mock(OverflowLogInterface.class));
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        try {
            MigrationLock.enable();
            assertTrue(MigrationLock.lock());

            CacheManager.TransferResult result = facade.executeAtomicTransfer(
                    sender, receiver, new BigDecimal("10.00"));

            assertNull(result, "direct Redis atomic transfer must be rejected during migration");
            verify(cache, never()).atomicTransfer(sender, receiver, new BigDecimal("10.00"));
        } finally {
            facade.shutdown();
        }
    }
}
