package noietime.syncmoney.migration;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LocalToSyncMigrationOverwriteTest {
    @TempDir
    Path temp;

    @AfterEach
    void resetMigrationLock() {
        MigrationLock.unlock();
        MigrationLock.disable();
    }

    @Test
    void defaultMigrationPreservesAnExistingSharedRow() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);

        fixture.task().start(false, false);

        verify(fixture.database()).insertPlayerIfAbsentForMigration(
                eq(uuid), eq("local-player"), any(), eq(3L), eq("test-server"));
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                eq(uuid), any(), any(), anyLong(), any());
        verify(fixture.jedis()).mget(RedisKeys.balance(uuid), RedisKeys.version(uuid));
        verify(fixture.jedis(), never()).eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void explicitForceMigrationReplacesAnExistingSharedRow() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);
        when(fixture.database().tryReplacePlayerForMigration(any(), any(), any(), anyLong(), any()))
                .thenReturn(true);

        fixture.task().start(true, false);

        verify(fixture.database()).tryReplacePlayerForMigration(
                eq(uuid), eq("local-player"), argThat(balance ->
                        balance != null && balance.compareTo(new java.math.BigDecimal("12.50")) == 0),
                eq(3L), eq("test-server"));
        verify(fixture.jedis()).mset(
                RedisKeys.balance(uuid), "12.50", RedisKeys.version(uuid), "3");
        verify(fixture.jedis(), never()).eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nonForceMigrationPreservesAnExistingRedisAccountWhenSqlRowIsAbsent() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);
        when(fixture.jedis().mget(RedisKeys.balance(uuid), RedisKeys.version(uuid)))
                .thenReturn(Arrays.asList("200.00", "200"));

        fixture.task().start(false, false);

        verify(fixture.database(), never()).insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString());
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString());
        verify(fixture.jedis(), never()).eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
        verify(fixture.jedis(), never()).mset(anyString(), anyString(), anyString(), anyString());
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.economyFacade(), never()).forceUpdateMemoryState(any(UUID.class), any(), anyLong());
        verify(fixture.economyFacade(), never()).updateMemoryState(any(UUID.class), any(BigDecimal.class), anyLong());
    }

    @Test
    void nonForceMigrationFailsClosedOnPartialRedisAccount() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);
        when(fixture.jedis().mget(RedisKeys.balance(uuid), RedisKeys.version(uuid)))
                .thenReturn(Arrays.asList("200.00", null));

        fixture.task().start(false, false);

        verify(fixture.database(), never()).insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString());
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString());
        verify(fixture.jedis(), never()).eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.economyFacade(), never()).forceUpdateMemoryState(any(UUID.class), any(), anyLong());
        verify(fixture.economyFacade(), never()).updateMemoryState(any(UUID.class), any(BigDecimal.class), anyLong());
        verify(fixture.checkpoint()).recordFailure(eq(uuid.toString()), contains("incomplete destination account"));
        verify(fixture.checkpoint()).fail();
        verify(fixture.checkpoint(), never()).complete();
    }

    @Test
    void nonForceRedisPopulationUsesAtomicAbsentOnlyWrite() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);
        when(fixture.database().insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString())).thenReturn(true);
        when(fixture.jedis().eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        fixture.task().start(false, false);

        verify(fixture.jedis()).eval(anyString(), eq(2),
                eq(RedisKeys.balance(uuid)), eq(RedisKeys.version(uuid)), eq("12.50"), eq("3"));
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).mset(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nonForceRedisRaceDoesNotOverwriteAnAccountThatAppearsAfterPreflight() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);
        when(fixture.database().insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString())).thenReturn(true);
        when(fixture.jedis().eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        fixture.task().start(false, false);

        verify(fixture.database()).insertPlayerIfAbsentForMigration(
                eq(uuid), eq("local-player"), any(), eq(3L), eq("test-server"));
        verify(fixture.jedis()).eval(anyString(), eq(2),
                eq(RedisKeys.balance(uuid)), eq(RedisKeys.version(uuid)), eq("12.50"), eq("3"));
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).mset(anyString(), anyString(), anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.economyFacade(), never()).forceUpdateMemoryState(any(UUID.class), any(), anyLong());
        verify(fixture.economyFacade(), never()).updateMemoryState(any(UUID.class), any(BigDecimal.class), anyLong());
        verify(fixture.checkpoint()).recordFailure(eq(uuid.toString()), contains("appeared during non-force migration"));
    }

    @Test
    void failedNonForceInsertDoesNotPopulateRedis() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid);
        MigrationFixture fixture = fixture(source);
        when(fixture.database().insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new SQLException("shared balance exceeds DECIMAL(20,2)"));

        fixture.task().start(false, false);

        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
    }

    @Test
    void legacySubCentBalanceUsesNormalizedValueForPreviewAndMigrationTargets() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid, 1.234);
        MigrationFixture fixture = fixture(source);

        LocalToSyncMigrationTask.PreviewResult preview = fixture.task().preview();
        assertEquals(new BigDecimal("1.23"), preview.totalBalance());
        assertEquals(new BigDecimal("1.23"), preview.samplePlayers().get(0).balance());

        when(fixture.database().insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString()))
                .thenReturn(true);
        when(fixture.jedis().eval(anyString(), eq(2), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        fixture.task().start(false, false);

        verify(fixture.database()).insertPlayerIfAbsentForMigration(
                eq(uuid), eq("local-player"),
                argThat(balance -> balance != null && balance.compareTo(new BigDecimal("1.23")) == 0),
                eq(3L), eq("test-server"));
        verify(fixture.jedis()).eval(anyString(), eq(2),
                eq(RedisKeys.balance(uuid)), eq(RedisKeys.version(uuid)), eq("1.23"), eq("3"));
        verify(fixture.jedis()).zadd(anyString(), eq(1.23D), eq(uuid.toString()));
        verify(fixture.economyFacade()).forceUpdateMemoryState(
                eq(uuid), argThat(balance -> balance != null && balance.compareTo(new BigDecimal("1.23")) == 0),
                eq(3L));
    }

    @Test
    void legacyNegativeBalanceIsRejectedBeforeSharedWrites() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid, -1.25);
        MigrationFixture fixture = fixture(source);

        assertNull(fixture.task().preview());
        fixture.task().start(false, false);

        verify(fixture.database(), never()).insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.economyFacade(), never()).forceUpdateMemoryState(any(UUID.class), any(), anyLong());
    }

    @Test
    void legacyNegativeSubCentBalanceIsRejectedBeforeSharedWrites() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = createLocalSource(uuid, -0.004);
        MigrationFixture fixture = fixture(source);

        LocalToSyncMigrationTask.PreviewResult preview = fixture.task().preview();
        when(fixture.database().insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString()))
                .thenReturn(true);

        fixture.task().start(false, false);

        assertNull(preview);
        verify(fixture.database(), never()).insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.redis(), never()).getResource();
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.economyFacade(), never()).forceUpdateMemoryState(any(UUID.class), any(), anyLong());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT balance FROM player_balances WHERE uuid = '" + uuid + "'")) {
            assertEquals(true, result.next());
            assertEquals(-0.004, result.getDouble("balance"), 0.000001);
        }
    }
    @Test
    void malformedUuidInMixedSnapshotFailsBeforeAnySharedWrite() throws Exception {
        UUID validUuid = UUID.randomUUID();
        Path source = createLocalSourceWithMalformedUuid(validUuid);
        MigrationFixture fixture = fixture(source);

        fixture.task().start(false, false);

        verify(fixture.database(), never()).insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.database(), never()).getConnection();
        verify(fixture.redis(), never()).getResource();
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.checkpoint()).recordFailure(
                eq("LOCAL row 2"), eq("Invalid UUID in LOCAL source row 2: not-a-uuid"));
        verify(fixture.checkpoint()).fail();
        verify(fixture.checkpoint(), never()).complete();
    }

    @Test
    void abbreviatedUuidInMixedSnapshotFailsBeforeAnySharedWriteAndPreservesLocalSource() throws Exception {
        UUID validUuid = UUID.randomUUID();
        String abbreviatedUuid = "1-1-1-1-1";
        Path source = createLocalSourceWithMalformedUuid(validUuid, abbreviatedUuid);
        MigrationFixture fixture = fixture(source);

        fixture.task().start(false, false);

        verify(fixture.database(), never()).insertPlayerIfAbsentForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.database(), never()).tryReplacePlayerForMigration(
                any(UUID.class), anyString(), any(BigDecimal.class), anyLong(), anyString());
        verify(fixture.database(), never()).getConnection();
        verify(fixture.redis(), never()).getResource();
        verify(fixture.jedis(), never()).set(anyString(), anyString());
        verify(fixture.jedis(), never()).zadd(anyString(), anyDouble(), anyString());
        verify(fixture.economyFacade(), never()).forceUpdateMemoryState(any(UUID.class), any(), anyLong());
        verify(fixture.checkpoint()).recordFailure(
                eq("LOCAL row 2"), eq("Invalid UUID in LOCAL source row 2: " + abbreviatedUuid));
        verify(fixture.checkpoint()).fail();
        verify(fixture.checkpoint(), never()).complete();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                PreparedStatement query = connection.prepareStatement(
                        "SELECT balance FROM player_balances WHERE uuid = ?")) {
            query.setString(1, abbreviatedUuid);
            try (var result = query.executeQuery()) {
                assertEquals(true, result.next());
                assertEquals(99.00, result.getDouble("balance"), 0.000001);
            }
        }
    }
    private Path createLocalSource(UUID uuid) throws Exception {
        return createLocalSource(uuid, 12.50);
    }

    private Path createLocalSource(UUID uuid, double balance) throws Exception {
        Path source = temp.resolve("local.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, name TEXT, balance REAL, version INTEGER)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_balances VALUES (?, 'local-player', ?, 3)")) {
                insert.setString(1, uuid.toString());
                insert.setDouble(2, balance);
                insert.executeUpdate();
            }
        }
        return source;
    }

    private Path createLocalSourceWithMalformedUuid(UUID validUuid) throws Exception {
        return createLocalSourceWithMalformedUuid(validUuid, "not-a-uuid");
    }

    private Path createLocalSourceWithMalformedUuid(UUID validUuid, String malformedUuid) throws Exception {
        Path source = temp.resolve("local-malformed-uuid.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, name TEXT, balance REAL, version INTEGER)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_balances VALUES (?, ?, ?, ?)")) {
                insert.setString(1, validUuid.toString());
                insert.setString(2, "valid-player");
                insert.setDouble(3, 12.50);
                insert.setLong(4, 3L);
                insert.executeUpdate();
                insert.setString(1, malformedUuid);
                insert.setString(2, "invalid-player");
                insert.setDouble(3, 99.00);
                insert.setLong(4, 4L);
                insert.executeUpdate();
            }
        }
        return source;
    }
    private MigrationFixture fixture(Path source) {
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LocalToSyncMigrationOverwriteTest"));
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class));
            return null;
        }).when(scheduler).runNow(eq(plugin), any());

        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.local().getLocalSQLitePath()).thenReturn("jdbc:sqlite:" + source);
        when(config.migration().isMigrationPopulateRedis()).thenReturn(false);
        when(config.getServerName()).thenReturn("test-server");
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isConnected()).thenReturn(true);
        RedisManager redis = mock(RedisManager.class);
        when(redis.isConnected()).thenReturn(true);
        Jedis jedis = mock(Jedis.class);
        when(redis.getResource()).thenReturn(jedis);
        when(jedis.mget(anyString(), anyString())).thenReturn(Arrays.asList(null, null));
        when(config.migration().isMigrationPopulateRedis()).thenReturn(true);
        EconomyFacade economyFacade = mock(EconomyFacade.class);
        when(economyFacade.isLocalRecoveryReady()).thenReturn(true);
        MigrationCheckpoint checkpoint = mock(MigrationCheckpoint.class);
        LocalToSyncMigrationTask task = new LocalToSyncMigrationTask(plugin, config,
                economyFacade, database, redis, checkpoint, null);
        return new MigrationFixture(task, database, redis, jedis, economyFacade, checkpoint);
    }

    private record MigrationFixture(LocalToSyncMigrationTask task, DatabaseManager database,
            RedisManager redis, Jedis jedis, EconomyFacade economyFacade,
            MigrationCheckpoint checkpoint) { }
}
