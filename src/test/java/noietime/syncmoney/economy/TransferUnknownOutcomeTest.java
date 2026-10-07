package noietime.syncmoney.economy;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisMutationException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferUnknownOutcomeTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void unknownRedisOutcomeMakesBothAccountsUnavailableAndPreventsBlindFollowUpTransfer() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferUnknownOutcomeTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.atomicTransfer(sender, receiver, amount)).thenThrow(
                new RedisMutationException(RedisMutationException.ExecutionState.SENT_UNKNOWN,
                        "response dropped after Redis may have applied transfer", null));
        MemoryStateManager state = new MemoryStateManager(plugin);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.getCircuitBreaker()).thenReturn(null);
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, cacheManager, state, null);

        assertNull(orchestrator.executeAtomicTransfer(sender, receiver, amount));
        CacheManager.TransferResult followUp = orchestrator.pluginAtomicTransfer(sender, receiver,
                amount, "test-plugin");

        assertAll(
                () -> assertNull(followUp, "a second direct path must not retry the ambiguous delta"),
                () -> assertTrue(state.isUnavailable(sender)),
                () -> assertTrue(state.isUnavailable(receiver)),
                () -> verify(cacheManager).atomicTransfer(sender, receiver, amount),
                () -> verify(cacheManager, never()).readTransferSnapshotStrict(any(), any())
        );
    }

    @Test
    void pluginAtomicTransferRespectsMigrationLockBeforeCallingRedis() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferUnknownOutcomeTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CacheManager cacheManager = mock(CacheManager.class);
        MemoryStateManager state = new MemoryStateManager(plugin);
        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.getCircuitBreaker()).thenReturn(null);
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, cacheManager, state, null);

        MigrationLock.enable();
        org.junit.jupiter.api.Assertions.assertTrue(MigrationLock.lock());
        CacheManager.TransferResult result = orchestrator.pluginAtomicTransfer(sender, receiver,
                new BigDecimal("10.00"), "test-plugin");

        assertNull(result);
        verify(cacheManager, never()).atomicTransfer(eq(sender), eq(receiver), any(BigDecimal.class));
    }

    @Test
    void pluginAtomicTransferDoesNotOvertakeAcceptedLocalQueueWrites() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferUnknownOutcomeTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CacheManager cacheManager = mock(CacheManager.class);
        EconomyWriteQueue queue = new EconomyWriteQueue(4);
        MemoryStateManager state = new MemoryStateManager(plugin, queue);
        state.put(sender, new EconomyFacade.EconomyState(
                new BigDecimal("20.00"), 8L, System.currentTimeMillis()));
        state.put(receiver, new EconomyFacade.EconomyState(
                new BigDecimal("80.00"), 4L, System.currentTimeMillis()));
        EconomyEvent queued = new EconomyEvent(sender, new BigDecimal("-80.00"),
                new BigDecimal("20.00"), 8L, EconomyEvent.EventType.WITHDRAW,
                EconomyEvent.EventSource.TEST, "accepted-before-direct-transfer", System.currentTimeMillis());
        org.junit.jupiter.api.Assertions.assertTrue(queue.offer(queued));

        EconomyFacade facade = mock(EconomyFacade.class);
        when(facade.ensureAccountsLoaded(sender, receiver)).thenReturn(true);
        when(facade.isPlayerLocked(any(UUID.class))).thenReturn(false);
        when(facade.getCircuitBreaker()).thenReturn(null);
        TransferOrchestrator orchestrator = new TransferOrchestrator(
                facade, plugin, config, cacheManager, state, null);

        CacheManager.TransferResult result = orchestrator.pluginAtomicTransfer(sender, receiver,
                new BigDecimal("30.00"), "test-plugin");

        assertAll(
                () -> assertNull(result),
                () -> assertTrue(queue.hasPending(sender)),
                () -> verify(cacheManager, never()).atomicTransfer(eq(sender), eq(receiver), any(BigDecimal.class))
        );
    }
}
