package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayTransferMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
        if (SyncmoneyEventBus.isInitialized()) SyncmoneyEventBus.getInstance().clearAll();
    }

    private static SyncmoneyEventBus resetEventBus(Fixture fixture) {
        if (!SyncmoneyEventBus.isInitialized()) SyncmoneyEventBus.init(fixture.plugin);
        SyncmoneyEventBus eventBus = SyncmoneyEventBus.getInstance();
        eventBus.clearAll();
        return eventBus;
    }

    private static PostTransactionEvent transferEvent(Fixture fixture, boolean success,
            BigDecimal senderBalance, String error) {
        BigDecimal amount = new BigDecimal("10.00");
        return new PostTransactionEvent(fixture.senderUuid, "sender",
                AsyncPreTransactionEvent.TransactionType.WITHDRAW, amount,
                success ? senderBalance.add(amount) : senderBalance,
                senderBalance, EconomyEvent.EventSource.PLAYER_TRANSFER.name(),
                fixture.targetUuid, "target", "test-operation", success, error);
    }

    @Test
    void sharedPayDoesNotEnterRedisTransferWhileMigrationLockIsActive() {
        Fixture fixture = new Fixture(false);

        fixture.runWhileLocked();

        verify(fixture.nameResolver, never()).resolve("target");
        verify(fixture.transferLockManager, never()).acquireDualLock(fixture.senderUuid, fixture.targetUuid);
    }

    @Test
    void localPayDoesNotEnterFacadeTransferWhileMigrationLockIsActive() {
        Fixture fixture = new Fixture(true);

        fixture.runWhileLocked();

        verify(fixture.nameResolver, never()).resolve("target");
        verify(fixture.transferLockManager, never()).acquireDualLock(fixture.senderUuid, fixture.targetUuid);
        verify(fixture.economyFacade, never()).withdraw(any(), any(), any());
        verify(fixture.economyFacade, never()).deposit(any(), any(), any());
    }

    @Test
    void sharedPayUsesPairedFacadeAdmissionAndWaitsForTransferOutcome() throws Exception {
        Fixture fixture = new Fixture(false);
        SyncmoneyEventBus eventBus = resetEventBus(fixture);
        when(fixture.transferLockManager.acquireDualLock(fixture.senderUuid, fixture.targetUuid)).thenReturn(true);
        when(fixture.economyFacade.atomicTransfer(eq(fixture.senderUuid), eq(fixture.targetUuid),
                eq(new BigDecimal("10.00")), eq(EconomyEvent.EventSource.PLAYER_TRANSFER)))
                .thenReturn(new BigDecimal("90.00"));

        fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));

        verify(fixture.economyFacade).atomicTransfer(eq(fixture.senderUuid), eq(fixture.targetUuid),
                eq(new BigDecimal("10.00")), eq(EconomyEvent.EventSource.PLAYER_TRANSFER));
        verify(fixture.plugin, never()).getMessage("pay.success-sender");
        verify(fixture.plugin, never()).getMessage("pay.transfer-failed");
        verify(fixture.sender, never()).sendMessage(any(net.kyori.adventure.text.Component.class));

        eventBus.callEvent(transferEvent(fixture, true, new BigDecimal("90.00"), null));

        verify(fixture.plugin).getMessage("pay.success-sender");
        verify(fixture.sender).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void sharedPayCompletionUsesSenderNameCapturedBeforeAsyncCallback() throws Exception {
        Fixture fixture = new Fixture(false);
        SyncmoneyEventBus eventBus = resetEventBus(fixture);
        when(fixture.transferLockManager.acquireDualLock(fixture.senderUuid, fixture.targetUuid)).thenReturn(true);
        when(fixture.economyFacade.atomicTransfer(eq(fixture.senderUuid), eq(fixture.targetUuid),
                eq(new BigDecimal("10.00")), eq(EconomyEvent.EventSource.PLAYER_TRANSFER)))
                .thenReturn(new BigDecimal("90.00"));

        Thread commandThread = Thread.currentThread();
        AtomicReference<Thread> nameReadThread = new AtomicReference<>();
        doAnswer(invocation -> {
            nameReadThread.set(Thread.currentThread());
            return "sender";
        }).when(fixture.sender).getName();
        org.mockito.Mockito.clearInvocations(fixture.sender);

        fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));
        assertSame(commandThread, nameReadThread.get(), "the command path should capture the sender name");

        Thread completionWorker = new Thread(
                () -> eventBus.callEvent(transferEvent(fixture, true, new BigDecimal("90.00"), null)),
                "pay-completion-worker");
        completionWorker.start();
        completionWorker.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(completionWorker.isAlive(), "the transfer completion callback should finish");
        assertSame(commandThread, nameReadThread.get(), "async completion must reuse the captured sender name");
        verify(fixture.sender, times(1)).getName();
    }

    @Test
    void sharedPayReportsRejectionOnlyAfterAuthorityPairIsReconciled() {
        Fixture fixture = new Fixture(false);
        SyncmoneyEventBus eventBus = resetEventBus(fixture);
        when(fixture.transferLockManager.acquireDualLock(fixture.senderUuid, fixture.targetUuid)).thenReturn(true);
        when(fixture.economyFacade.atomicTransfer(eq(fixture.senderUuid), eq(fixture.targetUuid),
                eq(new BigDecimal("10.00")), eq(EconomyEvent.EventSource.PLAYER_TRANSFER)))
                .thenReturn(new BigDecimal("90.00"));
        when(fixture.plugin.getMessage("pay.insufficient-funds")).thenReturn("Insufficient {balance}/{amount}");

        fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));

        verify(fixture.plugin, never()).getMessage("pay.insufficient-funds");
        verify(fixture.plugin, never()).getMessage("pay.success-sender");

        eventBus.callEvent(transferEvent(fixture, false, new BigDecimal("5.00"), "REJECTED"));

        verify(fixture.plugin).getMessage("pay.insufficient-funds");
        verify(fixture.plugin, never()).getMessage("pay.success-sender");
        verify(fixture.sender).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void sharedPayUnknownOutcomeQuarantinesFurtherPayAttempts() {
        Fixture fixture = new Fixture(false);
        SyncmoneyEventBus eventBus = resetEventBus(fixture);
        when(fixture.transferLockManager.acquireDualLock(fixture.senderUuid, fixture.targetUuid)).thenReturn(true);
        when(fixture.economyFacade.atomicTransfer(eq(fixture.senderUuid), eq(fixture.targetUuid),
                eq(new BigDecimal("10.00")), eq(EconomyEvent.EventSource.PLAYER_TRANSFER)))
                .thenReturn(new BigDecimal("90.00"));

        fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));
        eventBus.callEvent(transferEvent(fixture, false, new BigDecimal("90.00"), "SENT_UNKNOWN"));
        fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));

        verify(fixture.economyFacade, times(1)).atomicTransfer(
                eq(fixture.senderUuid), eq(fixture.targetUuid), eq(new BigDecimal("10.00")),
                eq(EconomyEvent.EventSource.PLAYER_TRANSFER));
        verify(fixture.plugin, atLeastOnce()).getMessage("pay.transfer-failed");
        verify(fixture.sender, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void localPayUsesOnePairedFacadeMutationInsteadOfWithdrawThenDeposit() throws Exception {
        Fixture fixture = new Fixture(true);
        when(fixture.transferLockManager.acquireDualLock(fixture.senderUuid, fixture.targetUuid)).thenReturn(true);
        when(fixture.economyFacade.atomicTransfer(fixture.senderUuid, fixture.targetUuid,
                new BigDecimal("10.00"), EconomyEvent.EventSource.PLAYER_TRANSFER))
                .thenReturn(new BigDecimal("90.00"));

        fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));

        verify(fixture.economyFacade).atomicTransfer(fixture.senderUuid, fixture.targetUuid,
                new BigDecimal("10.00"), EconomyEvent.EventSource.PLAYER_TRANSFER);
        verify(fixture.economyFacade, never()).withdraw(any(), any(), any());
        verify(fixture.economyFacade, never()).deposit(any(), any(), any());
        verify(fixture.plugin, never()).getMessage("pay.transfer-failed");
    }

    @Test
    void sharedPayLeasePreventsMigrationFromLockingDuringTransfer() throws Exception {
        Fixture fixture = new Fixture(false);
        CountDownLatch targetResolutionStarted = new CountDownLatch(1);
        CountDownLatch resumeTargetResolution = new CountDownLatch(1);
        when(fixture.transferLockManager.acquireDualLock(fixture.senderUuid, fixture.targetUuid))
                .thenReturn(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            targetResolutionStarted.countDown();
            assertTrue(resumeTargetResolution.await(5, TimeUnit.SECONDS),
                    "test should release the target-resolution barrier");
            return Optional.of(fixture.targetUuid);
        }).when(fixture.nameResolver).resolve("target");

        MigrationLock.enable();
        AtomicReference<Throwable> commandFailure = new AtomicReference<>();
        Thread command = new Thread(() -> {
            try {
                fixture.executor.executeTransferAsync(fixture.sender, "target", new BigDecimal("10.00"));
            } catch (Throwable failure) {
                commandFailure.set(failure);
            }
        }, "pay-migration-lock-barrier");

        command.start();
        assertTrue(targetResolutionStarted.await(5, TimeUnit.SECONDS),
                "the pay operation should reach target resolution with its write lease held");
        boolean migrationLockAcquiredDuringTransfer;
        try {
            migrationLockAcquiredDuringTransfer = MigrationLock.lock();
        } finally {
            resumeTargetResolution.countDown();
        }
        command.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(command.isAlive(), "the pay operation should finish after the barrier is released");
        if (commandFailure.get() != null) {
            throw new AssertionError("pay operation failed unexpectedly", commandFailure.get());
        }

        if (migrationLockAcquiredDuringTransfer) {
            MigrationLock.unlock();
        }
        assertFalse(migrationLockAcquiredDuringTransfer,
                "migration must not acquire its lock while /pay is between admission and completion");
        assertTrue(MigrationLock.lock(), "migration may acquire its lock after /pay releases its lease");
    }

    private static final class Fixture {
        private final Syncmoney plugin = mock(Syncmoney.class);
        private final EconomyFacade economyFacade = mock(EconomyFacade.class);
        private final NameResolver nameResolver = mock(NameResolver.class);
        private final TransferLockManager transferLockManager = mock(TransferLockManager.class);
        private final UUID senderUuid = UUID.randomUUID();
        private final UUID targetUuid = UUID.randomUUID();
        private final Player sender = mock(Player.class);
        private final PayTransferExecutor executor;

        private Fixture(boolean localMode) {
            AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
            Server server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("PayTransferMigrationLockTest"));
            when(plugin.getMessage("pay.lock-failed")).thenReturn("Transfer unavailable");
            when(plugin.getMessage("pay.transfer-failed")).thenReturn("Transfer failed: {error}");
            when(plugin.getMessage("pay.insufficient-funds")).thenReturn("Insufficient funds");
            when(plugin.getMessage("pay.success-sender")).thenReturn("{player} {amount} {balance}");
            when(plugin.getMessage("pay.success-receiver")).thenReturn("{player} {amount}");
            when(sender.getUniqueId()).thenReturn(senderUuid);
            when(sender.getName()).thenReturn("sender");
            EntityScheduler senderScheduler = mock(EntityScheduler.class);
            when(sender.getScheduler()).thenReturn(senderScheduler);
            doAnswer(invocation -> {
                Consumer<ScheduledTask> action = invocation.getArgument(1);
                action.accept(mock(ScheduledTask.class));
                return mock(ScheduledTask.class);
            }).when(senderScheduler).run(eq(plugin), any(), any());
            GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
            when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
            doAnswer(invocation -> {
                Consumer<ScheduledTask> action = invocation.getArgument(1);
                action.accept(mock(ScheduledTask.class));
                return null;
            }).when(globalScheduler).run(eq(plugin), any());
            when(server.getPlayerExact("target")).thenReturn(null);
            when(nameResolver.resolve("target")).thenReturn(Optional.of(targetUuid));
            doAnswer(invocation -> {
                Consumer<ScheduledTask> action = invocation.getArgument(1);
                action.accept(mock(ScheduledTask.class));
                return null;
            }).when(asyncScheduler).runNow(eq(plugin), any());

            executor = new PayTransferExecutor(plugin, mock(SyncmoneyConfig.class), economyFacade,
                    nameResolver, transferLockManager, localMode);
        }

        private void runWhileLocked() {
            if (!SyncmoneyEventBus.isInitialized()) SyncmoneyEventBus.init(plugin);
            MigrationLock.enable();
            assertTrue(MigrationLock.lock());
            executor.executeTransferAsync(sender, "target", new BigDecimal("10.00"));
        }
    }
}
