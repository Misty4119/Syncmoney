package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.economy.EconomyModeRouter;
import noietime.syncmoney.permission.AdminPermissionService;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCommandColdBalanceTest {

    @Test
    void giveDoesNotSynchronouslyLoadBalanceBeforeColdDepositIsRejected() {
        Fixture fixture = fixture();
        when(fixture.economyFacade.getBalance(fixture.targetUuid)).thenReturn(new BigDecimal("25.00"));
        when(fixture.economyFacade.deposit(fixture.targetUuid, new BigDecimal("10.00"),
                EconomyEvent.EventSource.ADMIN_GIVE)).thenReturn(BigDecimal.valueOf(-1));
        when(fixture.plugin.getMessage("general.error")).thenReturn("Transaction failed");

        assertTrue(fixture.command.onCommand(fixture.sender, null, "admin",
                new String[]{"give", "Target", "10"}));

        verify(fixture.economyFacade, never()).getBalance(fixture.targetUuid);
        verify(fixture.economyFacade).deposit(fixture.targetUuid, new BigDecimal("10.00"),
                EconomyEvent.EventSource.ADMIN_GIVE);
    }

    @Test
    void takeFailureDoesNotSynchronouslyReloadBalanceForItsReply() {
        Fixture fixture = fixture();
        when(fixture.economyFacade.withdraw(fixture.targetUuid, new BigDecimal("10.00"),
                EconomyEvent.EventSource.ADMIN_TAKE)).thenReturn(BigDecimal.valueOf(-1));
        when(fixture.economyFacade.getBalance(fixture.targetUuid)).thenReturn(new BigDecimal("25.00"));
        when(fixture.plugin.getMessage("admin.insufficient-funds")).thenReturn("Current balance: {balance}");

        assertTrue(fixture.command.onCommand(fixture.sender, null, "admin",
                new String[]{"take", "Target", "10"}));

        verify(fixture.economyFacade, never()).getBalance(fixture.targetUuid);
        verify(fixture.economyFacade).withdraw(fixture.targetUuid, new BigDecimal("10.00"),
                EconomyEvent.EventSource.ADMIN_TAKE);
        org.mockito.ArgumentCaptor<String> reply = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.sender).sendMessage(reply.capture());
        Assertions.assertTrue(reply.getValue().contains("N/A"),
                "a cold account reply must not invent a balance or trigger a storage read");
    }

    private Fixture fixture() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        AdminPermissionService permissionService = mock(AdminPermissionService.class);
        NameResolver nameResolver = mock(NameResolver.class);
        CommandSender sender = mock(CommandSender.class);
        UUID targetUuid = UUID.randomUUID();

        when(config.adminPermission().getAdminConfirmThreshold()).thenReturn(1_000.0);
        when(modeRouter.getMode()).thenReturn(EconomyMode.SYNC);
        when(plugin.getPermissionService()).thenReturn(permissionService);
        when(permissionService.canExecute(sender, "give")).thenReturn(true);
        when(permissionService.canExecute(sender, "take")).thenReturn(true);
        when(permissionService.checkDailyLimit(eq(sender), eq("give"), anyDouble())).thenReturn(true);
        when(permissionService.checkDailyLimit(eq(sender), eq("take"), anyDouble())).thenReturn(true);
        when(nameResolver.resolve("Target")).thenReturn(Optional.of(targetUuid));
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);

        AdminCommand command = new AdminCommand(plugin, config, economyFacade, mock(RedisManager.class),
                null, nameResolver, null, null, modeRouter);
        return new Fixture(command, plugin, economyFacade, sender, targetUuid);
    }

    private record Fixture(AdminCommand command, Syncmoney plugin, EconomyFacade economyFacade,
            CommandSender sender, UUID targetUuid) {}
}
