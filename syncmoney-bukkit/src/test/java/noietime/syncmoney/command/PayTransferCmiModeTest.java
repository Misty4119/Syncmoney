package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayTransferCmiModeTest {

    private static final String DISABLED_MESSAGE =
            "Command unavailable: CMI is the economy authority and this operation cannot safely update CMI balances.";

    @Test
    void preservesLegacyPublicPayCommandConstructorSignature() throws Exception {
        assertNotNull(PayCommand.class.getConstructor(
                Syncmoney.class,
                SyncmoneyConfig.class,
                EconomyFacade.class,
                noietime.syncmoney.storage.CacheManager.class,
                noietime.syncmoney.storage.RedisManager.class,
                NameResolver.class,
                FallbackEconomyWrapper.class,
                TransferLockManager.class,
                CooldownManager.class,
                noietime.syncmoney.storage.db.DbWriteQueue.class,
                noietime.syncmoney.economy.EconomyWriteQueue.class,
                noietime.syncmoney.sync.PubsubSubscriber.class,
                noietime.syncmoney.baltop.BaltopManager.class,
                double.class,
                double.class,
                boolean.class,
                boolean.class));
    }

    @Test
    void successNotificationUsesCapturedSenderNameWithoutReadingPlayerOffOwner() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        Player sender = mock(Player.class);
        EntityScheduler senderScheduler = mock(EntityScheduler.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);

        when(plugin.getMessage("pay.success-sender")).thenReturn("Paid {player} {amount} {balance}");
        when(plugin.getMessage("pay.success-receiver")).thenReturn("Received from {player} {amount}");
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getPlayerExact("target")).thenReturn(null);
        when(sender.getScheduler()).thenReturn(senderScheduler);
        doThrow(new IllegalStateException("Player#getName must not run off-owner"))
                .when(sender).getName();

        PayTransferExecutor executor = new PayTransferExecutor(plugin, config, economyFacade,
                mock(NameResolver.class), mock(TransferLockManager.class), false);

        executor.notifyTransferSuccess(sender, "sender", "target", new BigDecimal("10.00"), 90.0);

        verify(sender, never()).getName();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cmiModeRejectsTransferBeforeRedisAndDeliversReplyOnSenderScheduler() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        NameResolver nameResolver = mock(NameResolver.class);
        TransferLockManager lockManager = mock(TransferLockManager.class);
        Player sender = mock(Player.class);
        EntityScheduler senderScheduler = mock(EntityScheduler.class);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        UUID senderUuid = UUID.randomUUID();
        AtomicReference<Consumer<ScheduledTask>> scheduledReply = new AtomicReference<>();

        when(config.isCMIMode()).thenReturn(true);
        when(plugin.getMessage("general.cmi-authority-command-disabled")).thenReturn(DISABLED_MESSAGE);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(sender.getUniqueId()).thenReturn(senderUuid);
        when(sender.getScheduler()).thenReturn(senderScheduler);
        doAnswer(invocation -> {
            scheduledReply.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(senderScheduler).run(eq(plugin), any(Consumer.class), isNull());

        PayTransferExecutor executor = new PayTransferExecutor(plugin, config, economyFacade,
                nameResolver, lockManager, false);

        executor.executeTransferAsync(sender, "target", new BigDecimal("10.00"));

        assertNotNull(scheduledReply.get(), "CMI rejection reply should be scheduled on the sender owner");
        verify(sender, never()).sendMessage(any(Component.class));
        verifyNoInteractions(economyFacade, nameResolver, lockManager, asyncScheduler);

        scheduledReply.get().accept(mock(ScheduledTask.class));

        org.mockito.ArgumentCaptor<Component> reply = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(reply.capture());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(reply.getValue()).contains(DISABLED_MESSAGE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void payCommandRejectsCmiModeBeforeValidationOrConfirmation() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        FallbackEconomyWrapper fallbackWrapper = mock(FallbackEconomyWrapper.class);
        CooldownManager cooldownManager = mock(CooldownManager.class);
        NameResolver nameResolver = mock(NameResolver.class);
        TransferLockManager lockManager = mock(TransferLockManager.class);
        Player sender = mock(Player.class);
        EntityScheduler senderScheduler = mock(EntityScheduler.class);
        AtomicReference<Consumer<ScheduledTask>> scheduledReply = new AtomicReference<>();

        when(config.isCMIMode()).thenReturn(true);
        when(config.pay().getPayConfirmThreshold()).thenReturn(1_000.0);
        when(plugin.getMessage("general.cmi-authority-command-disabled")).thenReturn(DISABLED_MESSAGE);
        when(sender.hasPermission("syncmoney.pay")).thenReturn(true);
        when(sender.getScheduler()).thenReturn(senderScheduler);
        doAnswer(invocation -> {
            scheduledReply.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(senderScheduler).run(eq(plugin), any(Consumer.class), isNull());

        PayCommand command = new PayCommand(plugin, config, economyFacade,
                nameResolver, fallbackWrapper, lockManager, cooldownManager,
                1.0, 10_000.0, false, false);

        assertTrue(command.onCommand(sender, mock(Command.class), "pay", new String[0]));

        assertNotNull(scheduledReply.get(), "the CMI denial should be sent on the sender entity scheduler");
        verify(sender, never()).sendMessage(any(Component.class));
        verifyNoInteractions(economyFacade, fallbackWrapper, cooldownManager, nameResolver, lockManager);
        scheduledReply.get().accept(mock(ScheduledTask.class));

        org.mockito.ArgumentCaptor<Component> reply = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(reply.capture());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(reply.getValue()).contains(DISABLED_MESSAGE));
    }

    @Test
    void payTabCompletionUsesOnlyReadyMemoryBalance() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        FallbackEconomyWrapper fallbackWrapper = mock(FallbackEconomyWrapper.class);
        CooldownManager cooldownManager = mock(CooldownManager.class);
        NameResolver nameResolver = mock(NameResolver.class);
        TransferLockManager lockManager = mock(TransferLockManager.class);
        Player sender = mock(Player.class);
        UUID senderUuid = UUID.randomUUID();

        when(config.pay().getPayConfirmThreshold()).thenReturn(1_000.0);
        when(sender.getUniqueId()).thenReturn(senderUuid);
        when(economyFacade.getBalanceForPlaceholder(senderUuid)).thenReturn(null);

        PayCommand command = new PayCommand(plugin, config, economyFacade,
                nameResolver, fallbackWrapper, lockManager, cooldownManager,
                1.0, 10_000.0, false, false);

        List<String> suggestions = command.onTabComplete(sender, mock(Command.class), "pay",
                new String[] { "target", "" });

        verify(economyFacade).getBalanceForPlaceholder(senderUuid);
        verify(economyFacade, never()).getBalance(senderUuid);
        assertFalse(suggestions.isEmpty());
    }
}
