package noietime.syncmoney.command;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationCommandAsyncIoTest {

    @Test
    @SuppressWarnings("unchecked")
    void consoleAndBlockRepliesUseGlobalAndOwningRegionSchedulers(@TempDir Path tempDir) {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        BlockCommandSender blockSender = mock(BlockCommandSender.class);
        Block block = mock(Block.class);
        World world = mock(World.class);
        Location blockLocation = new Location(world, 12, 65, -4);
        List<Consumer<ScheduledTask>> globalActions = new ArrayList<>();
        List<Consumer<ScheduledTask>> regionActions = new ArrayList<>();
        AtomicReference<Location> scheduledBlockLocation = new AtomicReference<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCommandAsyncIoTest"));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(console.hasPermission("syncmoney.admin")).thenReturn(true);
        when(blockSender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(blockSender.getBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(blockLocation);

        doAnswer(invocation -> {
            globalActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(globalScheduler).run(eq(plugin), any(Consumer.class));
        doAnswer(invocation -> {
            scheduledBlockLocation.set(invocation.getArgument(1));
            regionActions.add(invocation.getArgument(2));
            return mock(ScheduledTask.class);
        }).when(regionScheduler).run(eq(plugin), any(Location.class), any(Consumer.class));

        MigrationCommand command = new MigrationCommand(
                plugin,
                config,
                mock(EconomyFacade.class),
                mock(DatabaseManager.class),
                mock(RedisManager.class),
                mock(NameResolver.class));

        command.onCommand(console, null, "syncmoney", new String[0]);
        command.onCommand(blockSender, null, "syncmoney", new String[0]);

        assertAll(
                () -> assertEquals(7, globalActions.size(), "console replies must be queued globally"),
                () -> assertEquals(7, regionActions.size(), "block replies must be queued on its region"),
                () -> assertEquals(blockLocation, scheduledBlockLocation.get()),
                () -> assertNotSame(blockLocation, scheduledBlockLocation.get(), "block location must be captured before async work"),
                () -> verify(console, never()).sendMessage(any(String.class)),
                () -> verify(blockSender, never()).sendMessage(any(String.class)));

        for (Consumer<ScheduledTask> action : List.copyOf(globalActions)) {
            action.accept(mock(ScheduledTask.class));
        }
        for (Consumer<ScheduledTask> action : List.copyOf(regionActions)) {
            action.accept(mock(ScheduledTask.class));
        }

        verify(console, times(7)).sendMessage(any(String.class));
        verify(blockSender, times(7)).sendMessage(any(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void localPreviewReadsSqliteOnAsyncSchedulerAndRepliesOnPlayerScheduler(@TempDir Path tempDir) throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        Player sender = mock(Player.class);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        EntityScheduler senderScheduler = mock(EntityScheduler.class);
        NameResolver nameResolver = mock(NameResolver.class);
        Logger logger = Logger.getLogger("MigrationCommandAsyncIoTest");
        Path databaseFile = tempDir.resolve("synthetic-local.sqlite");
        String jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        List<Consumer<ScheduledTask>> asyncActions = new ArrayList<>();
        List<Consumer<ScheduledTask>> senderActions = new ArrayList<>();
        AtomicReference<Thread> sqliteReadThread = new AtomicReference<>();

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_balances (uuid TEXT, name TEXT, balance REAL, version INTEGER)");
            statement.execute("INSERT INTO player_balances VALUES "
                    + "('00000000-0000-0000-0000-000000000001', 'Synthetic Player', 12.50, 1)");
        }

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(config.discordWebhook().isDiscordWebhookEnabled()).thenReturn(false);
        when(config.migration().isMigrationAutoBackup()).thenReturn(false);
        when(config.local().getLocalSQLitePath()).thenAnswer(invocation -> {
            sqliteReadThread.set(Thread.currentThread());
            return jdbcUrl;
        });
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        when(sender.hasPermission("syncmoney.admin")).thenReturn(true);
        when(sender.getScheduler()).thenReturn(senderScheduler);
        when(sender.isOnline()).thenReturn(true);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        doAnswer(invocation -> {
            asyncActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
        doAnswer(invocation -> {
            senderActions.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(senderScheduler).run(eq(plugin), any(Consumer.class), isNull());

        MigrationCommand command = new MigrationCommand(
                plugin,
                config,
                economyFacade,
                mock(DatabaseManager.class),
                mock(RedisManager.class),
                nameResolver);

        Thread commandThread = Thread.currentThread();
        assertTrue(command.onCommand(sender, null, "syncmoney", new String[]{"local-to-sync", "-preview"}));

        assertAll(
                () -> assertEquals(1, asyncActions.size(), "preview work must be submitted asynchronously"),
                () -> assertNull(sqliteReadThread.get(), "SQLite must not be read before the async task runs"),
                () -> verify(sender, never()).sendMessage(any(Component.class)));

        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        Thread asyncThread = new Thread(() -> {
            try {
                asyncActions.get(0).accept(mock(ScheduledTask.class));
            } catch (Throwable failure) {
                asyncFailure.set(failure);
            }
        }, "migration-command-test-async");
        asyncThread.start();
        asyncThread.join(5_000);

        assertTrue(!asyncThread.isAlive(), "async migration preview should finish promptly");
        assertNull(asyncFailure.get(), "async migration preview should complete without an exception");
        assertSame(asyncThread, sqliteReadThread.get(), "the synthetic SQLite read must run on the async worker");
        assertTrue(senderActions.size() >= 5, "preview replies must be queued for the player entity scheduler");
        verify(sender, never()).sendMessage(any(Component.class));

        for (Consumer<ScheduledTask> senderAction : List.copyOf(senderActions)) {
            senderAction.accept(mock(ScheduledTask.class));
        }

        verify(sender, times(5)).sendMessage(any(Component.class));
        assertTrue(sqliteReadThread.get() != commandThread);
    }
}
