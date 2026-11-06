package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.economy.OverflowLogInterface;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests guarding the behavior-critical contracts of the refactored Vault
 * provider handlers: standard Vault operations must remain independent, while
 * explicit transfers retain their atomic failure semantics.
 *
 * <p>{@code syncManager} and {@code config} are passed as {@code null} in the fixture so the
 * provider core's runtime dependency injection remains explicit in tests for cross-server paths.
 */
class VaultTest {

    @Mock
    private Plugin plugin;
    @Mock
    private EconomyFacade economyFacade;
    @Mock
    private VaultPluginDetector pluginDetector;
    @Mock
    private VaultPlayerHandler playerHandler;
    @Mock
    private NameResolver nameResolver;
    @Mock
    private VaultBankHandler bankHandler;
    @Mock
    private VaultLuaScriptManager luaScriptManager;

    private VaultTransferHandler transferHandler;
    private VaultProviderCore providerCore;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VaultTest"));
        transferHandler = new VaultTransferHandler(
                plugin, economyFacade, null, pluginDetector, null, playerHandler, nameResolver);
        providerCore = new VaultProviderCore(plugin, economyFacade, playerHandler, transferHandler,
                bankHandler, pluginDetector, luaScriptManager, nameResolver);
        providerCore.setEnabled(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // =========================================================================
    // Atomic transfer error code: InsufficientFunds must map to a FAILURE
    // response carrying "Insufficient funds" and the current balance.
    // =========================================================================

    @Test
    void atomicTransfer_insufficientFunds_returnsFailureWithInsufficientFundsCode() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        double amount = 100.0;
        BigDecimal currentBalance = new BigDecimal("5.00");

        OfflinePlayer fromPlayer = mock(OfflinePlayer.class);
        when(fromPlayer.getUniqueId()).thenReturn(fromUuid);

        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(BigDecimal.valueOf(-1));
        when(economyFacade.getBalanceForPlaceholder(fromUuid)).thenReturn(currentBalance);

        EconomyResponse response = transferHandler.withdrawPlayer(fromPlayer, amount, toUuid);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Insufficient funds", response.errorMessage);
        assertEquals(0.0, response.amount, 0.0001);
        assertEquals(currentBalance.doubleValue(), response.balance, 0.0001);

        // Insufficient funds must not perform a non-atomic withdraw fallback.
        verify(economyFacade, never())
                .withdraw(any(UUID.class), any(BigDecimal.class), any(EconomyEvent.EventSource.class));
    }

    @Test
    void atomicTransfer_nullResult_returnsRetryFailure() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();

        OfflinePlayer fromPlayer = mock(OfflinePlayer.class);
        when(fromPlayer.getUniqueId()).thenReturn(fromUuid);

        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(null);
        when(economyFacade.getBalanceForPlaceholder(fromUuid)).thenReturn(new BigDecimal("5.00"));

        EconomyResponse response = transferHandler.withdrawPlayer(fromPlayer, 100.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Transfer was not accepted; one or both accounts are unavailable", response.errorMessage);
    }

    @Test
    void disabledProviderReferenceRejectsBankMutation() {
        providerCore.setEnabled(false);

        EconomyResponse response = providerCore.bankDeposit("test-bank", 5.0);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        verify(bankHandler, never()).bankDeposit("test-bank", 5.0);
    }

    @Test
    void directVaultTransferUsesPairedMemoryAdmissionWithoutSynchronousRedisReceipt() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer fromPlayer = mock(OfflinePlayer.class);
        when(fromPlayer.getUniqueId()).thenReturn(fromUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(new BigDecimal("90.00"));

        EconomyResponse response = transferHandler.withdrawPlayer(fromPlayer, 10.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        assertEquals(10.0, response.amount, 0.0001);
        assertEquals(90.0, response.balance, 0.0001);
        verify(economyFacade, never()).executeAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class));
    }

    @Test
    void directVaultSelfTransferIsRejectedBeforeAdmission() {
        UUID account = UUID.randomUUID();
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(account);

        EconomyResponse response = transferHandler.withdrawPlayer(player, 10.0, account);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Cannot transfer to the same account", response.errorMessage);
        verify(economyFacade, never()).atomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                any(EconomyEvent.EventSource.class));
        verify(economyFacade, never()).executeAtomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class));
    }

    @Test
    void directVaultTransferReceiptUpdatesBothMemoryAccountsAndQueuesOneSqlOperation() throws Exception {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        CacheManager.TransferResult result = new CacheManager.TransferResult(
                new BigDecimal("90.00"), new BigDecimal("60.00"), 8L, 12L);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isDbEnabled()).thenReturn(true);
        when(config.getServerName()).thenReturn("server-a");
        when(nameResolver.getNameCachedOnly(fromUuid)).thenReturn("sender");
        when(nameResolver.getNameCachedOnly(toUuid)).thenReturn("receiver");
        DbWriteQueue dbWriteQueue = new DbWriteQueue(5);
        DbWriteQueue.Reservation reservation = dbWriteQueue.tryReserve();
        assertNotNull(reservation);

        TransferReceiptSupport.accept(plugin, economyFacade, dbWriteQueue, config, nameResolver,
                reservation, fromUuid, toUuid, result);

        verify(economyFacade).updateMemoryState(fromUuid, new BigDecimal("90.00"), 8L);
        verify(economyFacade).updateMemoryState(toUuid, new BigDecimal("60.00"), 12L);
        assertEquals(1, dbWriteQueue.size());
        DbWriteQueue.DbWriteTask task = dbWriteQueue.poll();
        assertNotNull(task);
        assertEquals(2, task.accountCount());
        assertEquals(new ConfirmedAccount(fromUuid, new BigDecimal("90.00"), 8L),
                task.accounts().get(0).account());
        assertEquals(new ConfirmedAccount(toUuid, new BigDecimal("60.00"), 12L),
                task.accounts().get(1).account());
        assertEquals("sender", task.accounts().get(0).playerName());
        assertEquals("receiver", task.accounts().get(1).playerName());
    }

    @Test
    void pluginTransfer_coldReceiverReportsNotReadyAndPreservesKnownSenderBalance() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(to.getUniqueId()).thenReturn(toUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.isPlayerLocked(toUuid)).thenReturn(false);
        when(economyFacade.pluginAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class), eq("test-plugin")))
                .thenReturn(null);
        when(economyFacade.getBalanceForPlaceholder(fromUuid)).thenReturn(new BigDecimal("125.00"));
        when(economyFacade.getBalanceForPlaceholder(toUuid)).thenReturn(null);

        EconomyResponse response = providerCore.pluginTransfer(from, to, 10.0, "test-plugin");

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Account is not ready; retry after it finishes loading", response.errorMessage);
        assertEquals(125.0, response.balance, 0.0001,
                "the known sender balance remains useful even while the receiver is cold");
    }

    @Test
    void pluginTransfer_unknownOutcomeDoesNotTellCallerToRetryWhileAccountsAreUnresolved() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(to.getUniqueId()).thenReturn(toUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.isPlayerLocked(toUuid)).thenReturn(false);
        when(economyFacade.pluginAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class), eq("test-plugin")))
                .thenReturn(null);
        when(economyFacade.getBalanceForPlaceholder(fromUuid)).thenReturn(null);
        when(economyFacade.getBalanceForPlaceholder(toUuid)).thenReturn(null);

        EconomyResponse response = providerCore.pluginTransfer(from, to, 10.0, "test-plugin");

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("not confirmed"));
        assertTrue(response.errorMessage.toLowerCase().contains("do not retry"));
    }

    @Test
    void cmiVaultTransferRejectsPairedMutationBeforeReadingBalances() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(true);
        transferHandler.setConfig(config);

        EconomyResponse response = transferHandler.withdrawPlayer(from, 10.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("cmi"));
        verify(economyFacade, never()).isPlayerLocked(any(UUID.class));
        verify(economyFacade, never()).getBalanceForPlaceholder(any(UUID.class));
        verify(economyFacade, never()).atomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                any(EconomyEvent.EventSource.class));
        verify(economyFacade, never()).executeAtomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class));
    }

    @Test
    void cmiVaultZeroAmountExplicitTransferIsStillRejected() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(true);
        transferHandler.setConfig(config);

        EconomyResponse response = transferHandler.withdrawPlayer(from, 0.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("cmi"));
        verifyNoEconomyCallsForCmiTransfer();
    }

    @Test
    void cmiVaultPluginTransferRejectsBeforeReadingOrMutatingAccounts() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(to.getUniqueId()).thenReturn(toUuid);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(true);
        providerCore.setConfig(config);

        EconomyResponse response = providerCore.pluginTransfer(from, to, 10.0, "test-plugin");

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("cmi"));
        verifyNoEconomyCallsForCmiTransfer();
    }

    private void verifyNoEconomyCallsForCmiTransfer() {
        verify(economyFacade, never()).isPlayerLocked(any(UUID.class));
        verify(economyFacade, never()).getBalanceForPlaceholder(any(UUID.class));
        verify(economyFacade, never()).atomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                any(EconomyEvent.EventSource.class));
        verify(economyFacade, never()).executeAtomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class));
        verify(economyFacade, never()).pluginAtomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                any(String.class));
    }

    @Test
    void directVaultTransferPublishesLocalNotificationsAfterPairedAdmission() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(new BigDecimal("90.00"));
        when(economyFacade.getBalanceForPlaceholder(toUuid)).thenReturn(new BigDecimal("40.00"));
        when(pluginDetector.detectCallingPlugin()).thenReturn("example-plugin");
        when(nameResolver.getName(fromUuid)).thenReturn("Sender");

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isSyncMode()).thenReturn(true);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        VaultTransferHandler syncTransferHandler = new VaultTransferHandler(
                plugin, economyFacade, syncManager, pluginDetector, config, playerHandler, nameResolver);

        EconomyResponse response = syncTransferHandler.withdrawPlayer(from, 10.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        verify(syncManager).notifyLocalBalanceChange(fromUuid, "VAULT_WITHDRAW", -10.0, "example-plugin");
        verify(syncManager).notifyLocalBalanceChange(toUuid, "VAULT_DEPOSIT", 10.0, "example-plugin");
        verify(economyFacade, never()).executeAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class));
    }

    @Test
    void pairedVaultTransferUsesMemoryAdmissionForSharedDatabaseMode() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.getBalanceForPlaceholder(fromUuid)).thenReturn(new BigDecimal("100.00"));
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(new BigDecimal("90.00"));

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isDbEnabled()).thenReturn(true);
        VaultTransferHandler syncTransferHandler = new VaultTransferHandler(
                plugin, economyFacade, null, pluginDetector, config, playerHandler, nameResolver);

        EconomyResponse response = syncTransferHandler.withdrawPlayer(from, 10.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type,
                "shared-backend transfers use memory-first paired admission and asynchronous persistence");
        verify(economyFacade).atomicTransfer(fromUuid, toUuid, new BigDecimal("10.00"),
                EconomyEvent.EventSource.VAULT_WITHDRAW);
        verify(economyFacade, never()).executeAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class));
    }

    @Test
    void fullSqlAdmissionRejectsPluginAtomicTransferBeforeRedisMutation() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(to.getUniqueId()).thenReturn(toUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.isPlayerLocked(toUuid)).thenReturn(false);
        when(economyFacade.getBalanceForPlaceholder(fromUuid)).thenReturn(new BigDecimal("100.00"));

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isDbEnabled()).thenReturn(true);
        providerCore.setConfig(config);
        DbWriteQueue dbWriteQueue = new DbWriteQueue(5);
        providerCore.setDbWriteQueue(dbWriteQueue);
        for (int i = 0; i < 4; i++) {
            UUID account = UUID.randomUUID();
            assertTrue(dbWriteQueue.offer(new DbWriteQueue.DbWriteTask("plugin-filler-" + i,
                    new ConfirmedAccount(account, BigDecimal.ONE, 1L), "filler", "server", Instant.now())));
        }

        EconomyResponse response = providerCore.pluginTransfer(from, to, 10.0, "test-plugin");

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("SQL write queue is full; retry shortly", response.errorMessage);
        verify(economyFacade, never()).pluginAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq("test-plugin"));
        assertEquals(4, dbWriteQueue.size(), "failed admission must leave previously queued SQL work intact");
    }

    @Test
    void providerCoreInjectsSyncDependenciesIntoPairedVaultTransferHandler() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(new BigDecimal("90.00"));
        when(economyFacade.getBalanceForPlaceholder(toUuid)).thenReturn(new BigDecimal("40.00"));
        when(pluginDetector.detectCallingPlugin()).thenReturn("example-plugin");
        when(nameResolver.getName(fromUuid)).thenReturn("Sender");

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isSyncMode()).thenReturn(true);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        providerCore.setConfig(config);
        providerCore.setSyncManager(syncManager);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);

        EconomyResponse response = providerCore.withdrawPlayer(from, 10.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        verify(syncManager).notifyLocalBalanceChange(fromUuid, "VAULT_WITHDRAW", -10.0, "example-plugin");
        verify(syncManager).notifyLocalBalanceChange(toUuid, "VAULT_DEPOSIT", 10.0, "example-plugin");
        verify(economyFacade, never()).executeAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class));
    }

    @Test
    void directVaultProviderAtomicTransferPublishesBothAuthorityVersions() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(to.getUniqueId()).thenReturn(toUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.isPlayerLocked(toUuid)).thenReturn(false);
        when(economyFacade.pluginAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class), eq("example-plugin")))
                .thenReturn(new CacheManager.TransferResult(new BigDecimal("90.00"),
                        new BigDecimal("40.00"), 7L, 12L));
        when(nameResolver.getName(fromUuid)).thenReturn("Sender");

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isSyncMode()).thenReturn(true);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        providerCore.setConfig(config);
        providerCore.setSyncManager(syncManager);

        EconomyResponse response = providerCore.pluginTransfer(from, to, 10.0, "example-plugin");

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        verify(syncManager).publishAndNotify(fromUuid, new BigDecimal("90.00"), 7L,
                "PLUGIN_WITHDRAW", -10.0, "example-plugin", "Sender");
        verify(syncManager).publishAndNotify(toUuid, new BigDecimal("40.00"), 12L,
                "PLUGIN_DEPOSIT", 10.0, "example-plugin", "Sender");
    }

    @Test
    void confirmedPluginTransferRemainsSuccessfulWhenCrossServerNotificationThrows() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        OfflinePlayer to = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(to.getUniqueId()).thenReturn(toUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.isPlayerLocked(toUuid)).thenReturn(false);
        when(economyFacade.pluginAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class), eq("test-plugin")))
                .thenReturn(new CacheManager.TransferResult(new BigDecimal("90.00"),
                        new BigDecimal("10.00"), 8L, 4L));

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isSyncMode()).thenReturn(true);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("plugin is shutting down"))
                .when(syncManager).publishAndNotify(any(UUID.class), any(BigDecimal.class),
                        org.mockito.ArgumentMatchers.anyLong(), any(String.class),
                        org.mockito.ArgumentMatchers.anyDouble(), any(String.class), any(String.class));
        providerCore.setConfig(config);
        providerCore.setSyncManager(syncManager);

        EconomyResponse response = providerCore.pluginTransfer(from, to, 10.0, "test-plugin");

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type,
                "post-accept notification failure cannot turn a confirmed transfer into caller-visible failure");
        assertEquals(90.0, response.balance, 0.0001);
    }

    @Test
    void acceptedMemoryVaultTransferRemainsSuccessfulWhenCrossServerNotificationThrows() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();
        OfflinePlayer from = mock(OfflinePlayer.class);
        when(from.getUniqueId()).thenReturn(fromUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(false);
        when(economyFacade.atomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class),
                eq(EconomyEvent.EventSource.VAULT_WITHDRAW))).thenReturn(new BigDecimal("90.00"));
        when(economyFacade.getBalanceForPlaceholder(toUuid)).thenReturn(new BigDecimal("40.00"));

        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isSyncMode()).thenReturn(true);
        CrossServerSyncManager syncManager = mock(CrossServerSyncManager.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("plugin is shutting down"))
                .when(syncManager).notifyLocalBalanceChange(fromUuid, "VAULT_WITHDRAW", -10.0, null);
        providerCore.setConfig(config);
        providerCore.setSyncManager(syncManager);

        EconomyResponse response = providerCore.withdrawPlayer(from, 10.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type,
                "post-accept notifications must not report an accepted pair as a failed transfer");
        assertEquals(90.0, response.balance, 0.0001);
    }

    @Test
    void withdrawPlayer_lockedAccount_returnsFailureBeforeAnyTransfer() {
        UUID fromUuid = UUID.randomUUID();
        UUID toUuid = UUID.randomUUID();

        OfflinePlayer fromPlayer = mock(OfflinePlayer.class);
        when(fromPlayer.getUniqueId()).thenReturn(fromUuid);
        when(economyFacade.isPlayerLocked(fromUuid)).thenReturn(true);

        EconomyResponse response = transferHandler.withdrawPlayer(fromPlayer, 100.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Account is locked due to suspicious activity", response.errorMessage);
        verify(economyFacade, never())
                .executeAtomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class));
    }

    // =========================================================================
    // LockingHelper: centralized lock guard must keep the original semantics.
    // =========================================================================

    @Test
    void lockingHelper_requireNotLocked_returnsFailureWhenLocked() {
        UUID uuid = UUID.randomUUID();
        when(economyFacade.isPlayerLocked(uuid)).thenReturn(true);

        EconomyResponse response = LockingHelper.requireNotLocked(economyFacade, uuid, "locked-message");

        assertNotNull(response);
        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("locked-message", response.errorMessage);
        assertEquals(0.0, response.balance, 0.0001);
    }

    @Test
    void lockingHelper_requireNotLocked_returnsNullWhenNotLocked() {
        UUID uuid = UUID.randomUUID();
        when(economyFacade.isPlayerLocked(uuid)).thenReturn(false);

        assertNull(LockingHelper.requireNotLocked(economyFacade, uuid, "locked-message"));
    }

    @Test
    void standardVaultDeposit_isIndependentFromSameAmountWithdrawal() {
        UUID recipientUuid = UUID.randomUUID();
        OfflinePlayer recipient = mock(OfflinePlayer.class);
        BigDecimal amount = new BigDecimal("47.50");

        when(recipient.getUniqueId()).thenReturn(recipientUuid);
        when(economyFacade.isPlayerLocked(recipientUuid)).thenReturn(false);
        when(economyFacade.deposit(recipientUuid, amount, EconomyEvent.EventSource.VAULT_DEPOSIT,
                (String) null))
                .thenReturn(new BigDecimal("100.00"));

        EconomyResponse response = providerCore.depositPlayer(recipient, amount.doubleValue());

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        verify(economyFacade).deposit(recipientUuid, amount, EconomyEvent.EventSource.VAULT_DEPOSIT, null);
        verify(economyFacade, never()).pluginDeposit(any(UUID.class), any(BigDecimal.class), any(String.class));
    }

    @Test
    void rejectedColdVaultDepositDoesNotReadBalanceAgainForItsFailureResponse() {
        UUID account = UUID.randomUUID();
        OfflinePlayer recipient = mock(OfflinePlayer.class);
        when(recipient.getUniqueId()).thenReturn(account);
        when(economyFacade.isPlayerLocked(account)).thenReturn(false);
        when(economyFacade.deposit(account, new BigDecimal("10.00"), EconomyEvent.EventSource.VAULT_DEPOSIT,
                (String) null))
                .thenReturn(new BigDecimal("-1"));
        when(economyFacade.getBalanceForPlaceholder(account)).thenReturn(null);

        EconomyResponse response = providerCore.depositPlayer(recipient, 10.0);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(Double.isNaN(response.balance), "unknown balance must not be encoded as zero");
        verify(economyFacade, never()).getBalance(account);
    }

    @Test
    void coldVaultReadReturnsUnavailableAndSchedulesAConfirmedSnapshotLoad() throws Exception {
        UUID account = UUID.randomUUID();
        BigDecimal existingBalance = new BigDecimal("735.25");
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(account);

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getBalanceSnapshotStrict(account)).thenReturn(Optional.of(
                new CacheManager.CachedBalance(existingBalance, 9L, System.currentTimeMillis())));
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        OverflowLogInterface overflowLog = mock(OverflowLogInterface.class);
        EconomyFacade coldFacade = new EconomyFacade(plugin, config, cacheManager, redisManager,
                mock(DatabaseManager.class), null, new EconomyWriteQueue(10),
                new FallbackEconomyWrapper(plugin, redisManager), null, overflowLog);
        VaultPlayerHandler coldHandler = new VaultPlayerHandler(coldFacade, nameResolver, plugin);

        try {
            assertTrue(Double.isNaN(coldHandler.getBalance(offline)),
                    "a cold Vault double cannot represent not-ready as a valid monetary value");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!coldFacade.hasInMemory(account) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(existingBalance, coldFacade.getBalance(account),
                    "the bounded background load should publish the confirmed cached snapshot");
            verify(cacheManager, never()).getBalance(account);
            verify(cacheManager).getBalanceSnapshotStrict(account);
        } finally {
            coldFacade.shutdown();
        }
    }

    @Test
    void coldVaultNameReadReturnsUnavailableAndSchedulesNameResolution() {
        String playerName = "ColdStoredPlayer";
        UUID account = UUID.randomUUID();
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayerIfCached(playerName)).thenReturn(null);
        when(nameResolver.resolveUUIDForPlaceholder(playerName)).thenReturn(account);
        when(economyFacade.isPlayerLocked(account)).thenReturn(false);
        when(economyFacade.getBalanceForPlaceholder(account)).thenReturn(null);
        VaultPlayerHandler coldHandler = new VaultPlayerHandler(economyFacade, nameResolver, plugin);

        assertTrue(Double.isNaN(coldHandler.getBalance(playerName)),
                "an unresolved or cold name-based balance must not be reported as a confirmed zero");
        verify(nameResolver).resolveUUIDForPlaceholder(playerName);
        verify(economyFacade).getBalanceForPlaceholder(account);
        verify(nameResolver, never()).resolve(playerName);
    }

    @Test
    void lockedColdVaultNameReadStillWarmsAccountInsteadOfReturningFalseZero() {
        String playerName = "ColdLockedPlayer";
        UUID account = UUID.randomUUID();
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(account);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayerIfCached(playerName)).thenReturn(offline);
        when(economyFacade.isPlayerLocked(account)).thenReturn(true);
        when(economyFacade.getBalanceForPlaceholder(account)).thenReturn(null);
        VaultPlayerHandler coldHandler = new VaultPlayerHandler(economyFacade, nameResolver, plugin);

        assertTrue(Double.isNaN(coldHandler.getBalance(playerName)),
                "an unavailable locked account must not be presented as a zero balance");
        verify(economyFacade).getBalanceForPlaceholder(account);
    }

    @Test
    void coldVaultReadReturnsNaNWhileSqlSnapshotIsBlockedOffCallerAndMutationIsRejected() throws Exception {
        UUID account = UUID.randomUUID();
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(account);

        CacheManager cacheManager = mock(CacheManager.class);
        RedisManager redisManager = mock(RedisManager.class);
        when(cacheManager.getBalanceSnapshotStrict(account)).thenReturn(Optional.empty());
        when(redisManager.isDegraded()).thenReturn(false);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isDbEnabled()).thenReturn(true);
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        CountDownLatch sqlReadEntered = new CountDownLatch(1);
        CountDownLatch releaseSqlRead = new CountDownLatch(1);
        AtomicReference<Thread> sqlReadThread = new AtomicReference<>();
        when(databaseManager.getPlayerStrict(account)).thenAnswer(invocation -> {
            sqlReadThread.set(Thread.currentThread());
            sqlReadEntered.countDown();
            assertTrue(releaseSqlRead.await(10, TimeUnit.SECONDS));
            return Optional.of(new DatabaseManager.PlayerRecord(
                    account, "offline", new BigDecimal("735.25"), 9L, "test-server", Instant.now()));
        });
        OverflowLogInterface overflowLog = mock(OverflowLogInterface.class);
        EconomyWriteQueue writeQueue = new EconomyWriteQueue(10);
        EconomyFacade coldFacade = new EconomyFacade(plugin, config, cacheManager, redisManager,
                databaseManager, null, writeQueue,
                new FallbackEconomyWrapper(plugin, redisManager), null, overflowLog);
        VaultPlayerHandler coldHandler = new VaultPlayerHandler(coldFacade, nameResolver, plugin);

        try {
            double coldBalance = coldHandler.getBalance(offline);
            assertTrue(sqlReadEntered.await(2, TimeUnit.SECONDS), "cold read should schedule SQL on the account loader");
            BigDecimal rejectedMutation = coldFacade.deposit(account, BigDecimal.TEN, EconomyEvent.EventSource.VAULT_DEPOSIT);
            assertAll(
                    () -> assertTrue(Double.isNaN(coldBalance), "unknown is not a zero balance"),
                    () -> assertFalse(Thread.currentThread() == sqlReadThread.get(),
                            "SQL must run away from the Vault caller"),
                    () -> assertEquals(new BigDecimal("-1"), rejectedMutation,
                            "a cold mutation is rejected instead of using an unknown balance"),
                    () -> assertFalse(coldFacade.hasInMemory(account), "the blocked load must not create memory state"),
                    () -> assertEquals(0, writeQueue.size(), "a cold rejected write must not be queued"),
                    () -> verify(cacheManager, never()).getBalance(account)
            );
        } finally {
            releaseSqlRead.countDown();
            coldFacade.shutdown();
        }
    }

    @Test
    void coldVaultReadReturnsImmediatelyWhileCacheSnapshotIsBlockedOnLoader() throws Exception {
        UUID account = UUID.randomUUID();
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(account);

        CacheManager cacheManager = mock(CacheManager.class);
        CountDownLatch cacheReadEntered = new CountDownLatch(1);
        CountDownLatch releaseCacheRead = new CountDownLatch(1);
        AtomicReference<Thread> cacheReadThread = new AtomicReference<>();
        when(cacheManager.getBalanceSnapshotStrict(account)).thenAnswer(invocation -> {
            cacheReadThread.set(Thread.currentThread());
            cacheReadEntered.countDown();
            releaseCacheRead.await();
            return Optional.of(new CacheManager.CachedBalance(new BigDecimal("735.25"), 7L,
                    System.currentTimeMillis()));
        });

        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.isDegraded()).thenReturn(false);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        EconomyFacade coldFacade = new EconomyFacade(plugin, config, cacheManager, redisManager,
                mock(DatabaseManager.class), null, new EconomyWriteQueue(10),
                mock(FallbackEconomyWrapper.class), null, mock(OverflowLogInterface.class));
        VaultPlayerHandler coldHandler = new VaultPlayerHandler(coldFacade, nameResolver, plugin);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> vaultCallerThread = new AtomicReference<>();

        try {
            var result = caller.submit(() -> {
                vaultCallerThread.set(Thread.currentThread());
                return coldHandler.getBalance(offline);
            });
            assertTrue(Double.isNaN(result.get(2, TimeUnit.SECONDS)),
                    "the cold Vault call should return the unavailable sentinel without waiting");
            assertTrue(cacheReadEntered.await(2, TimeUnit.SECONDS),
                    "the cache snapshot should load asynchronously");
            assertFalse(vaultCallerThread.get() == cacheReadThread.get(),
                    "the cache adapter must run away from the Vault caller thread");
            assertFalse(coldFacade.hasInMemory(account), "the blocked snapshot is not READY");

            releaseCacheRead.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!coldFacade.hasInMemory(account) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(new BigDecimal("735.25"), coldFacade.getBalance(account));
        } finally {
            releaseCacheRead.countDown();
            caller.shutdownNow();
            coldFacade.shutdown();
        }
    }

    // =========================================================================
    // CrossServerNotifier null-safety: offline / null player must be a no-op.
    // =========================================================================

    @Test
    void crossServerNotifier_nullPlayer_isNoOp() {
        assertDoesNotThrow(() -> CrossServerNotifier.notifyBalanceChange(
                plugin, (OfflinePlayer) null, "vault.deposited", BigDecimal.ONE, BigDecimal.TEN));
    }
}
