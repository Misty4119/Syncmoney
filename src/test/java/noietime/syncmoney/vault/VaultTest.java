package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>{@code syncManager} and {@code config} are passed as {@code null} to mirror how
 * {@link SyncmoneyVaultProvider} constructs the handler in production, which keeps the
 * cross-server publish path disabled and the units isolated.
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
        when(economyFacade.executeAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class)))
                .thenReturn(CacheManager.TransferResult.insufficientFunds());
        when(economyFacade.getBalance(fromUuid)).thenReturn(currentBalance);

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
        when(economyFacade.executeAtomicTransfer(eq(fromUuid), eq(toUuid), any(BigDecimal.class)))
                .thenReturn(null);

        EconomyResponse response = transferHandler.withdrawPlayer(fromPlayer, 100.0, toUuid);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Atomic transfer failed - try again", response.errorMessage);
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
        when(economyFacade.deposit(recipientUuid, amount, EconomyEvent.EventSource.VAULT_DEPOSIT))
                .thenReturn(new BigDecimal("100.00"));

        EconomyResponse response = providerCore.depositPlayer(recipient, amount.doubleValue());

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        verify(economyFacade).deposit(recipientUuid, amount, EconomyEvent.EventSource.VAULT_DEPOSIT);
        verify(economyFacade, never()).pluginDeposit(any(UUID.class), any(BigDecimal.class), any(String.class));
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
