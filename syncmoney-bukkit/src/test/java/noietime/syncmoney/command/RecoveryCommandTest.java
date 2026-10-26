package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEventConsumer;
import noietime.syncmoney.economy.EventConsumerManager;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryCommandTest {

    @Test
    void unauthorizedSenderCannotReadOrReconcileQueuedRecoveryRecords() {
        Syncmoney plugin = mock(Syncmoney.class);
        EventConsumerManager manager = mock(EventConsumerManager.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(false);

        new RecoveryCommand(plugin, manager).onCommand(sender, mock(Command.class), "syncmoney",
                new String[] { "list" });

        verify(sender).sendMessage("You do not have permission to inspect or reconcile queued recovery records.");
        verify(manager, never()).getEconomyEventConsumer();
    }

    @Test
    void listReportsBlockedSpoolInsteadOfClaimingThereAreNoRecords() {
        Syncmoney plugin = mock(Syncmoney.class);
        EventConsumerManager manager = mock(EventConsumerManager.class);
        EconomyEventConsumer consumer = mock(EconomyEventConsumer.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(manager.getEconomyEventConsumer()).thenReturn(consumer);
        when(consumer.isRecoveryBlocked()).thenReturn(true);

        new RecoveryCommand(plugin, manager).onCommand(sender, mock(Command.class), "syncmoney",
                new String[] { "list" });

        verify(sender).sendMessage("Queued recovery spool is blocked; preserve it and inspect the server log.");
        verify(sender, never()).sendMessage("No pending queued recovery records.");
    }

    @Test
    void listShowsRestartRestoredAmbiguousQueuedTransfers() {
        Syncmoney plugin = mock(Syncmoney.class);
        EventConsumerManager manager = mock(EventConsumerManager.class);
        EconomyEventConsumer consumer = mock(EconomyEventConsumer.class);
        CommandSender sender = mock(CommandSender.class);
        UUID account = UUID.randomUUID();
        String id = "ambiguous-pay-1";
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(manager.getEconomyEventConsumer()).thenReturn(consumer);
        when(consumer.recoveryViews()).thenReturn(java.util.List.of(new EconomyEventConsumer.RecoveryView(
                id, account, UUID.randomUUID(), "AMBIGUOUS", 0, 123L)));

        new RecoveryCommand(plugin, manager).onCommand(sender, mock(Command.class), "syncmoney",
                new String[] { "list" });

        verify(sender).sendMessage("Pending queued recovery records: 1");
        verify(sender).sendMessage(org.mockito.ArgumentMatchers.contains(id + " account=" + account));
        verify(sender).sendMessage(org.mockito.ArgumentMatchers.contains("state=AMBIGUOUS"));
    }

    @Test
    void inspectAndReconcileDoNotTreatBlockedSpoolAsAnEmptyRecoverySet() {
        Syncmoney plugin = mock(Syncmoney.class);
        EventConsumerManager manager = mock(EventConsumerManager.class);
        EconomyEventConsumer consumer = mock(EconomyEventConsumer.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(manager.getEconomyEventConsumer()).thenReturn(consumer);
        when(consumer.isRecoveryBlocked()).thenReturn(true);

        RecoveryCommand command = new RecoveryCommand(plugin, manager);
        command.onCommand(sender, mock(Command.class), "syncmoney", new String[] { "inspect", "missing-id" });
        command.onCommand(sender, mock(Command.class), "syncmoney", new String[] { "reconcile", "missing-id" });

        verify(sender).sendMessage("Recovery state is incomplete because the queued recovery spool is blocked; "
                + "preserve it and inspect the server log.");
        verify(sender).sendMessage("Recovery state is incomplete because the queued recovery spool is blocked; "
                + "no reconciliation was started.");
        verify(sender, never()).sendMessage("No pending queued recovery record matches that ID.");
        verify(plugin, never()).getServer();
    }

    @Test
    void authorizedReconcileChecksExactQueuedReceiptOnAsyncSchedulerAndReportsOutcome() {
        Syncmoney plugin = mock(Syncmoney.class);
        EventConsumerManager manager = mock(EventConsumerManager.class);
        EconomyEventConsumer consumer = mock(EconomyEventConsumer.class);
        CommandSender sender = mock(CommandSender.class);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        String operationId = "queued-reconcile-1";
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(manager.getEconomyEventConsumer()).thenReturn(consumer);
        when(consumer.inspectRecovery(operationId)).thenReturn(new EconomyEventConsumer.RecoveryView(
                operationId, UUID.randomUUID(), UUID.randomUUID(), "AMBIGUOUS", 0, 123L));
        when(consumer.reconcileRecovery(operationId))
                .thenReturn("Exact Redis receipt reconciled; the local recovery checkpoint was acknowledged.");
        doAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any());
        doAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(mock(ScheduledTask.class));
            return null;
        }).when(globalScheduler).run(eq(plugin), any());

        new RecoveryCommand(plugin, manager).onCommand(sender, mock(Command.class), "syncmoney",
                new String[] { "reconcile", operationId });

        verify(consumer).reconcileRecovery(operationId);
        verify(sender).sendMessage("Checking the exact Redis operation receipt for queued transfer " + operationId + ".");
        verify(sender).sendMessage("Exact Redis receipt reconciled; the local recovery checkpoint was acknowledged.");
    }
}
