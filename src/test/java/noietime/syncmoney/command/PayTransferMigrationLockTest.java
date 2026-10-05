package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.PubsubSubscriber;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayTransferMigrationLockTest {

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void sharedPayDoesNotEnterRedisTransferWhileMigrationLockIsActive() {
        Fixture fixture = new Fixture(false);

        fixture.runWhileLocked();

        verify(fixture.nameResolver, never()).resolve("target");
        verify(fixture.transferLockManager, never()).acquireDualLock(fixture.senderUuid, fixture.targetUuid);
        verify(fixture.redisManager, never()).getResource();
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
        private final RedisManager redisManager = mock(RedisManager.class);
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
            when(sender.getUniqueId()).thenReturn(senderUuid);
            when(sender.getName()).thenReturn("sender");
            when(nameResolver.resolve("target")).thenReturn(Optional.of(targetUuid));
            doAnswer(invocation -> {
                Consumer<ScheduledTask> action = invocation.getArgument(1);
                action.accept(mock(ScheduledTask.class));
                return null;
            }).when(asyncScheduler).runNow(eq(plugin), any());

            executor = new PayTransferExecutor(plugin, mock(SyncmoneyConfig.class), economyFacade,
                    mock(CacheManager.class), redisManager, nameResolver, transferLockManager,
                    mock(DbWriteQueue.class), mock(EconomyWriteQueue.class),
                    mock(PubsubSubscriber.class), mock(BaltopManager.class),
                    mock(PayLuaScriptManager.class), localMode);
        }

        private void runWhileLocked() {
            MigrationLock.enable();
            assertTrue(MigrationLock.lock());
            executor.executeTransferAsync(sender, "target", new BigDecimal("10.00"));
        }
    }
}
