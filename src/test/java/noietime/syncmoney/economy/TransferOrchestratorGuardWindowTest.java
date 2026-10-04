package noietime.syncmoney.economy;

import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.breaker.NotificationService;
import noietime.syncmoney.config.PlayerProtectionConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferOrchestratorGuardWindowTest {

    @Test
    void receiverWithdrawalCanRunAfterDepositBeforeTransferGuardLock() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TransferOrchestratorGuardWindowTest"));
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        PlayerProtectionConfig protectionConfig = mock(PlayerProtectionConfig.class);
        when(config.playerProtection()).thenReturn(protectionConfig);
        when(protectionConfig.isPlayerProtectionEnabled()).thenReturn(true);
        when(protectionConfig.isPlayerProtectionLockReceiver()).thenReturn(true);
        when(protectionConfig.getPlayerProtectionReceiverLockThreshold()).thenReturn(5L);
        when(protectionConfig.getPlayerProtectionMaxAmountPerMinute()).thenReturn(0L);
        when(protectionConfig.getPlayerProtectionWarningThreshold()).thenReturn(100);
        when(protectionConfig.getPlayerProtectionBalanceChangeThreshold()).thenReturn(1000.0);
        when(protectionConfig.isPlayerProtectionGlobalLockEnabled()).thenReturn(false);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getBalance(sender)).thenReturn(new BigDecimal("100.00"));
        when(cacheManager.getBalance(receiver)).thenReturn(BigDecimal.ZERO);
        when(cacheManager.getVersion(sender)).thenReturn(7L);
        when(cacheManager.getVersion(receiver)).thenReturn(3L);
        RedisManager redisManager = mock(RedisManager.class);
        PlayerTransactionGuard guard = spy(new PlayerTransactionGuard(
                plugin, config, mock(NotificationService.class), redisManager));

        CountDownLatch receiverDepositCommitted = new CountDownLatch(1);
        CountDownLatch allowGuardToLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            receiverDepositCommitted.countDown();
            if (!allowGuardToLock.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("receiver guard barrier timed out");
            }
            return invocation.callRealMethod();
        }).when(guard).checkAndLockReceiverForTransfer(receiver, amount);

        EconomyWriteQueue queue = new EconomyWriteQueue(20, Logger.getLogger("TransferOrchestratorGuardWindowTest"));
        EconomyFacade facade = new EconomyFacade(plugin, config, cacheManager, redisManager,
                null, null, queue, new FallbackEconomyWrapper(plugin, redisManager), guard,
                mock(OverflowLogInterface.class));
        AtomicReference<BigDecimal> transferResult = new AtomicReference<>();
        AtomicReference<Throwable> transferFailure = new AtomicReference<>();
        Thread transferThread = new Thread(() -> {
            try {
                transferResult.set(facade.atomicTransfer(sender, receiver, amount, EconomyEvent.EventSource.TEST));
            } catch (Throwable failure) {
                transferFailure.set(failure);
            }
        }, "transfer-guard-window");

        try {
            transferThread.start();
            boolean reachedGuardWindow = receiverDepositCommitted.await(5, TimeUnit.SECONDS);
            assertTrue(reachedGuardWindow,
                    "the transfer should pause after receiver deposit and before its guard lock call; result="
                            + transferResult.get() + ", failure=" + transferFailure.get());

            BigDecimal concurrentWithdrawal = facade.withdraw(
                    receiver, new BigDecimal("5.00"), EconomyEvent.EventSource.TEST);
            assertEquals(0, concurrentWithdrawal.compareTo(new BigDecimal("5.00")),
                    "the receiver withdrawal is accepted before the deferred transfer lock is applied");

            allowGuardToLock.countDown();
            transferThread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(transferThread.isAlive(), "the transfer should finish after the guard barrier is released");
            if (transferFailure.get() != null) {
                throw new AssertionError("transfer failed unexpectedly", transferFailure.get());
            }

            BigDecimal postLockWithdrawal = facade.withdraw(
                    receiver, BigDecimal.ONE, EconomyEvent.EventSource.TEST);
            assertTrue(guard.isTransferLocked(receiver), "the configured receiver lock applies after the barrier");
            assertEquals(BigDecimal.valueOf(-1), postLockWithdrawal,
                    "the same guard rejects a withdrawal after the receiver lock is installed");

            List<EconomyEvent> queued = new ArrayList<>();
            EconomyEvent event;
            while ((event = queue.poll(0, TimeUnit.MILLISECONDS)) != null) {
                queued.add(event);
            }
            BigDecimal queuedDelta = queued.stream().map(EconomyEvent::delta)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertAll(
                    () -> assertEquals(0, transferResult.get().compareTo(new BigDecimal("90.00"))),
                    () -> assertEquals(0, facade.getBalance(sender).compareTo(new BigDecimal("90.00"))),
                    () -> assertEquals(0, facade.getBalance(receiver).compareTo(new BigDecimal("5.00"))),
                    () -> assertEquals(3, queued.size(), "the fixture should include both transfer legs and the withdrawal"),
                    () -> assertEquals(0, queuedDelta.compareTo(new BigDecimal("-5.00")),
                            "the interleaving does not show funds created or destroyed beyond the explicit withdrawal")
            );
        } finally {
            allowGuardToLock.countDown();
            transferThread.join(TimeUnit.SECONDS.toMillis(5));
            guard.shutdown();
            facade.shutdown();
        }
    }
}
