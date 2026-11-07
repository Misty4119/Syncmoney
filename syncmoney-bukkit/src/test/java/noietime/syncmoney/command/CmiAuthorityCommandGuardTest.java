package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyMode;
import noietime.syncmoney.economy.EconomyModeRouter;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.sync.CMIApi;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class CmiAuthorityCommandGuardTest {

    private static final String DISABLED_MESSAGE = "CMI is the economy authority";
    private static final String AUTHORITY_UNAVAILABLE_MESSAGE = "Economy authority unavailable";

    @Test
    void adminBalanceMutationsFailClosedInCmiModeBeforeAnyBalanceOperation() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        CommandSender sender = mock(CommandSender.class);

        when(config.adminPermission().getAdminConfirmThreshold()).thenReturn(1_000.0);
        when(modeRouter.getMode()).thenReturn(EconomyMode.CMI);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(plugin.getMessage("general.cmi-authority-command-disabled")).thenReturn(DISABLED_MESSAGE);

        AdminCommand command = new AdminCommand(
                plugin, config, economyFacade, mock(RedisManager.class), null,
                null, null, null, modeRouter);
        List<String[]> requests = List.of(
                new String[]{"set", "Target", "10"},
                new String[]{"give", "Target", "10"},
                new String[]{"take", "Target", "10"},
                new String[]{"reset", "Target"},
                new String[]{"confirm"});

        for (String[] request : requests) {
            assertTrue(command.onCommand(sender, null, "admin", request));
        }

        verify(sender, times(requests.size())).sendMessage(contains(DISABLED_MESSAGE));
        verifyNoInteractions(economyFacade);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminBalanceViewUsesCmiAuthorityWithoutAllowingMutations() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        CMIApi cmiApi = mock(CMIApi.class);
        Player sender = mock(Player.class);
        Player target = mock(Player.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        EntityScheduler targetScheduler = mock(EntityScheduler.class);
        EntityScheduler senderScheduler = mock(EntityScheduler.class);
        noietime.syncmoney.uuid.NameResolver nameResolver = mock(noietime.syncmoney.uuid.NameResolver.class);
        UUID targetUuid = UUID.randomUUID();
        List<Consumer<ScheduledTask>> globalActions = new ArrayList<>();
        AtomicReference<Consumer<ScheduledTask>> targetAction = new AtomicReference<>();
        AtomicReference<Consumer<ScheduledTask>> senderAction = new AtomicReference<>();

        when(config.adminPermission().getAdminConfirmThreshold()).thenReturn(1_000.0);
        when(modeRouter.getMode()).thenReturn(EconomyMode.CMI);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CmiAuthorityCommandGuardTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(targetUuid)).thenReturn(target);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getScheduler()).thenReturn(targetScheduler);
        when(sender.getScheduler()).thenReturn(senderScheduler);
        when(cmiApi.isAvailable()).thenReturn(true);
        when(cmiApi.getExistingBalance(targetUuid)).thenReturn(Optional.of(new BigDecimal("42.50")));
        CMIEconomyHandler cmiHandler = new CMIEconomyHandler(
                plugin, config, null, null, cmiApi);
        when(modeRouter.getCmiHandler()).thenReturn(cmiHandler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(sender.hasPermission("syncmoney.money.others")).thenReturn(true);
        when(plugin.getMessage("money.player-not-found")).thenReturn("Player {player} was not found");
        when(plugin.getMessage("money.others")).thenReturn("Balance for {player}: {balance}");

        doAnswer(invocation -> {
            globalActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any(Consumer.class));
        doAnswer(invocation -> {
            targetAction.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(targetScheduler).run(eq(plugin), any(Consumer.class), any(Runnable.class));
        doAnswer(invocation -> {
            senderAction.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(senderScheduler).run(eq(plugin), any(Consumer.class), isNull());

        AdminCommand command = new AdminCommand(
                plugin, config, economyFacade, mock(RedisManager.class), null,
                nameResolver, null, null, modeRouter);

        assertTrue(command.onCommand(sender, null, "admin", new String[]{"view", "Target"}));

        assertTrue(globalActions.size() == 1, "exact online-player lookup should be queued globally");
        verify(nameResolver, never()).resolve("Target");
        verify(server, never()).getPlayerExact("Target");
        verify(cmiApi, never()).getExistingBalance(targetUuid);
        verify(sender, never()).sendMessage(any(Component.class));

        globalActions.get(0).accept(mock(ScheduledTask.class));
        verify(server).getPlayerExact("Target");
        assertTrue(globalActions.size() == 2, "CMI query should queue the loaded-player owner lookup");
        verify(cmiApi, never()).getExistingBalance(targetUuid);

        globalActions.get(1).accept(mock(ScheduledTask.class));
        assertNotNull(targetAction.get(), "CMI read should be dispatched to the target entity owner");
        verify(globalScheduler, times(2)).run(eq(plugin), any(Consumer.class));
        verify(targetScheduler).run(eq(plugin), any(Consumer.class), any(Runnable.class));
        verify(cmiApi, never()).getExistingBalance(targetUuid);
        verify(sender, never()).sendMessage(any(Component.class));

        targetAction.get().accept(mock(ScheduledTask.class));
        verify(cmiApi).getExistingBalance(targetUuid);
        verify(cmiApi, never()).getBalance(targetUuid);
        verify(modeRouter, never()).getBalance(targetUuid);
        assertNotNull(senderAction.get(), "Delayed player replies should be dispatched to the sender owner");
        verify(senderScheduler).run(eq(plugin), any(Consumer.class), isNull());
        verify(sender, never()).sendMessage(any(Component.class));

        senderAction.get().accept(mock(ScheduledTask.class));
        org.mockito.ArgumentCaptor<Component> reply = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(reply.capture());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(reply.getValue()).contains("42.50"));
        verify(sender, never()).sendMessage(contains(DISABLED_MESSAGE));
        verifyNoInteractions(economyFacade);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncCmiBalanceViewDeliversConsoleReplyOnGlobalRegionScheduler() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        CMIApi cmiApi = mock(CMIApi.class);
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        Player target = mock(Player.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        EntityScheduler targetScheduler = mock(EntityScheduler.class);
        noietime.syncmoney.uuid.NameResolver nameResolver = mock(noietime.syncmoney.uuid.NameResolver.class);
        UUID targetUuid = UUID.randomUUID();
        List<Consumer<ScheduledTask>> globalActions = new ArrayList<>();
        AtomicReference<Consumer<ScheduledTask>> targetAction = new AtomicReference<>();

        when(config.adminPermission().getAdminConfirmThreshold()).thenReturn(1_000.0);
        when(modeRouter.getMode()).thenReturn(EconomyMode.CMI);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CmiAuthorityCommandGuardTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(targetUuid)).thenReturn(target);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getScheduler()).thenReturn(targetScheduler);
        when(cmiApi.isAvailable()).thenReturn(true);
        when(cmiApi.getExistingBalance(targetUuid)).thenReturn(Optional.of(new BigDecimal("42.50")));
        CMIEconomyHandler cmiHandler = new CMIEconomyHandler(
                plugin, config, null, null, cmiApi);
        when(modeRouter.getCmiHandler()).thenReturn(cmiHandler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(sender.hasPermission("syncmoney.money.others")).thenReturn(true);
        when(plugin.getMessage("money.player-not-found")).thenReturn("Player {player} was not found");
        when(plugin.getMessage("money.others")).thenReturn("Balance for {player}: {balance}");

        doAnswer(invocation -> {
            globalActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any(Consumer.class));
        doAnswer(invocation -> {
            targetAction.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(targetScheduler).run(eq(plugin), any(Consumer.class), any(Runnable.class));

        AdminCommand command = new AdminCommand(
                plugin, config, economyFacade, mock(RedisManager.class), null,
                nameResolver, null, null, modeRouter);

        assertTrue(command.onCommand(sender, null, "admin", new String[]{"view", "Target"}));

        assertTrue(globalActions.size() == 1, "exact online-player lookup should use the global scheduler");
        verify(nameResolver, never()).resolve("Target");
        verify(sender, never()).sendMessage(contains("42.50"));
        globalActions.get(0).accept(mock(ScheduledTask.class));
        assertTrue(globalActions.size() == 2, "CMI query should queue its loaded-player lookup globally");
        globalActions.get(1).accept(mock(ScheduledTask.class));
        assertNotNull(targetAction.get(), "CMI read should be dispatched to the target entity owner");
        targetAction.get().accept(mock(ScheduledTask.class));

        assertTrue(globalActions.size() == 3, "console reply should be queued on the global scheduler");
        verify(sender, never()).sendMessage(contains("42.50"));
        globalActions.get(2).accept(mock(ScheduledTask.class));

        verify(globalScheduler, times(3)).run(eq(plugin), any(Consumer.class));
        verify(cmiApi).getExistingBalance(targetUuid);
        verify(cmiApi, never()).getBalance(targetUuid);
        verify(sender).sendMessage(contains("42.50"));
        verifyNoInteractions(economyFacade);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncCmiBalanceViewDeliversBlockSenderReplyOnCapturedRegion() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        CMIApi cmiApi = mock(CMIApi.class);
        BlockCommandSender sender = mock(BlockCommandSender.class);
        Block commandBlock = mock(Block.class);
        Location commandBlockLocation = new Location(mock(World.class), 10, 64, -5);
        Location capturedBlockLocation = commandBlockLocation.clone();
        Player target = mock(Player.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        EntityScheduler targetScheduler = mock(EntityScheduler.class);
        noietime.syncmoney.uuid.NameResolver nameResolver = mock(noietime.syncmoney.uuid.NameResolver.class);
        UUID targetUuid = UUID.randomUUID();
        List<Consumer<ScheduledTask>> globalActions = new ArrayList<>();
        AtomicReference<Consumer<ScheduledTask>> targetAction = new AtomicReference<>();
        AtomicReference<Consumer<ScheduledTask>> replyAction = new AtomicReference<>();

        when(config.adminPermission().getAdminConfirmThreshold()).thenReturn(1_000.0);
        when(modeRouter.getMode()).thenReturn(EconomyMode.CMI);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CmiAuthorityCommandGuardTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(targetUuid)).thenReturn(target);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getScheduler()).thenReturn(targetScheduler);
        when(sender.getBlock()).thenReturn(commandBlock);
        when(commandBlock.getLocation()).thenReturn(commandBlockLocation);
        when(cmiApi.isAvailable()).thenReturn(true);
        when(cmiApi.getExistingBalance(targetUuid)).thenReturn(Optional.of(new BigDecimal("42.50")));
        CMIEconomyHandler cmiHandler = new CMIEconomyHandler(
                plugin, config, null, null, cmiApi);
        when(modeRouter.getCmiHandler()).thenReturn(cmiHandler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(sender.hasPermission("syncmoney.money.others")).thenReturn(true);
        when(plugin.getMessage("money.player-not-found")).thenReturn("Player {player} was not found");
        when(plugin.getMessage("money.others")).thenReturn("Balance for {player}: {balance}");

        doAnswer(invocation -> {
            globalActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any(Consumer.class));
        doAnswer(invocation -> {
            targetAction.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(targetScheduler).run(eq(plugin), any(Consumer.class), any(Runnable.class));
        doAnswer(invocation -> {
            replyAction.set(invocation.getArgument(2));
            return mock(ScheduledTask.class);
        }).when(regionScheduler).run(eq(plugin), any(Location.class), any(Consumer.class));

        AdminCommand command = new AdminCommand(
                plugin, config, economyFacade, mock(RedisManager.class), null,
                nameResolver, null, null, modeRouter);

        assertTrue(command.onCommand(sender, null, "admin", new String[]{"view", "Target"}));

        assertTrue(globalActions.size() == 1, "exact online-player lookup should use the global scheduler");
        verify(nameResolver, never()).resolve("Target");
        verify(commandBlock).getLocation();
        verify(sender, never()).sendMessage(contains("42.50"));

        commandBlockLocation.setX(99);
        globalActions.get(0).accept(mock(ScheduledTask.class));
        assertTrue(globalActions.size() == 2, "CMI query should queue its loaded-player lookup globally");
        globalActions.get(1).accept(mock(ScheduledTask.class));
        assertNotNull(targetAction.get(), "CMI read should be dispatched to the target entity owner");
        targetAction.get().accept(mock(ScheduledTask.class));

        assertNotNull(replyAction.get(), "block sender reply should be dispatched to the captured region");
        verify(regionScheduler).run(eq(plugin), eq(capturedBlockLocation), any(Consumer.class));
        verify(sender, never()).sendMessage(contains("42.50"));
        replyAction.get().accept(mock(ScheduledTask.class));

        verify(cmiApi).getExistingBalance(targetUuid);
        verify(cmiApi, never()).getBalance(targetUuid);
        verify(sender).sendMessage(contains("42.50"));
        verifyNoInteractions(economyFacade);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cmiAdminBalanceViewReportsUnavailableWhenCmiHasNoLoadedUserWithoutCreatingAccount() {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        CMIApi cmiApi = mock(CMIApi.class);
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        Player target = mock(Player.class);
        EntityScheduler targetScheduler = mock(EntityScheduler.class);
        noietime.syncmoney.uuid.NameResolver nameResolver = mock(noietime.syncmoney.uuid.NameResolver.class);
        UUID targetUuid = UUID.randomUUID();
        List<Consumer<ScheduledTask>> globalActions = new ArrayList<>();

        when(config.adminPermission().getAdminConfirmThreshold()).thenReturn(1_000.0);
        when(modeRouter.getMode()).thenReturn(EconomyMode.CMI);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CmiAuthorityCommandGuardTest"));
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(targetUuid)).thenReturn(target);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getScheduler()).thenReturn(targetScheduler);
        when(cmiApi.isAvailable()).thenReturn(true);
        when(cmiApi.getExistingBalance(targetUuid)).thenReturn(Optional.empty());
        CMIEconomyHandler cmiHandler = new CMIEconomyHandler(
                plugin, config, null, null, cmiApi);
        when(modeRouter.getCmiHandler()).thenReturn(cmiHandler);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(sender.hasPermission("syncmoney.money.others")).thenReturn(true);
        when(plugin.getMessage("money.player-not-found")).thenReturn("Player {player} was not found");
        when(plugin.getMessage("general.economy-authority-unavailable"))
                .thenReturn(AUTHORITY_UNAVAILABLE_MESSAGE);
        doAnswer(invocation -> {
            globalActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any(Consumer.class));
        when(targetScheduler.run(eq(plugin), any(Consumer.class), any(Runnable.class))).thenAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });

        AdminCommand command = new AdminCommand(
                plugin, config, economyFacade, mock(RedisManager.class), null,
                nameResolver, null, null, modeRouter);

        assertTrue(command.onCommand(sender, null, "admin", new String[]{"view", "Target"}));

        assertTrue(globalActions.size() == 1, "exact online-player lookup should be queued on the global scheduler");
        verify(nameResolver, never()).resolve("Target");
        verify(sender, never()).sendMessage(contains(AUTHORITY_UNAVAILABLE_MESSAGE));
        verify(sender, never()).sendMessage(contains("Player Target was not found"));
        globalActions.get(0).accept(mock(ScheduledTask.class));

        assertTrue(globalActions.size() == 2, "CMI query should queue its loaded-player lookup globally");
        verify(cmiApi, never()).getExistingBalance(targetUuid);
        globalActions.get(1).accept(mock(ScheduledTask.class));
        verify(cmiApi).getExistingBalance(targetUuid);
        verify(cmiApi, never()).getBalance(targetUuid);
        verify(modeRouter, never()).getBalance(targetUuid);
        assertTrue(globalActions.size() == 3, "unavailable reply should be queued on the global scheduler");
        verify(sender, never()).sendMessage(contains(AUTHORITY_UNAVAILABLE_MESSAGE));
        verify(sender, never()).sendMessage(contains("Player Target was not found"));
        globalActions.get(2).accept(mock(ScheduledTask.class));
        verify(globalScheduler, times(3)).run(eq(plugin), any(Consumer.class));
        verify(sender).sendMessage(contains(AUTHORITY_UNAVAILABLE_MESSAGE));
        verify(sender, never()).sendMessage(contains("Player Target was not found"));
        verifyNoInteractions(economyFacade);
    }

    @Test
    void syncBalanceCommandFailsClosedInCmiModeBeforeResolvingOrWritingBalances() {
        Syncmoney plugin = mock(Syncmoney.class);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        EconomyModeRouter modeRouter = mock(EconomyModeRouter.class);
        CacheManager cacheManager = mock(CacheManager.class);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        CommandSender sender = mock(CommandSender.class);

        when(modeRouter.getMode()).thenReturn(EconomyMode.CMI);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(plugin.getMessage("general.cmi-authority-command-disabled")).thenReturn(DISABLED_MESSAGE);

        SyncBalanceCommand command = new SyncBalanceCommand(
                plugin, economyFacade, cacheManager, databaseManager, modeRouter);

        assertTrue(command.onCommand(sender, null, "sync-balance", new String[]{"Target"}));

        verify(sender).sendMessage(contains(DISABLED_MESSAGE));
        verifyNoInteractions(economyFacade, cacheManager, databaseManager);
    }
}
