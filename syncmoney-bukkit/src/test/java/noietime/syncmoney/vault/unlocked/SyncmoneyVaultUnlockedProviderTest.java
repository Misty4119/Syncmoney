package noietime.syncmoney.vault.unlocked;

import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import noietime.syncmoney.config.DisplayConfig;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncmoneyVaultUnlockedProviderTest {

    private EconomyFacade economyFacade;
    private NameResolver nameResolver;
    private SyncmoneyConfig config;
    private SyncmoneyVaultUnlockedProvider provider;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        economyFacade = mock(EconomyFacade.class);
        nameResolver = mock(NameResolver.class);
        config = mock(SyncmoneyConfig.class);
        DisplayConfig display = mock(DisplayConfig.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("VaultUnlockedProviderTest"));
        when(config.display()).thenReturn(display);
        when(display.getCurrencyName()).thenReturn("$");
        when(display.getDecimalPlaces()).thenReturn(2);

        provider = new SyncmoneyVaultUnlockedProvider(plugin, economyFacade, nameResolver, config);
        provider.setEnabled(true);
    }

    @Test
    void deposit_usesBigDecimalPluginPathAndPreservesAttribution() {
        UUID account = UUID.randomUUID();
        when(economyFacade.getBalanceForPlaceholder(account)).thenReturn(new BigDecimal("10.00"));
        when(economyFacade.isPlayerLocked(account)).thenReturn(false);
        when(economyFacade.pluginDeposit(account, new BigDecimal("2.35"), "Shop"))
                .thenReturn(new BigDecimal("12.35"));

        EconomyResponse response = provider.deposit("Shop", account, new BigDecimal("2.345"));

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        assertEquals(new BigDecimal("2.35"), response.amount);
        assertEquals(new BigDecimal("12.35"), response.balance);
        verify(economyFacade).pluginDeposit(account, new BigDecimal("2.35"), "Shop");
    }

    @Test
    void withdraw_rejectsInsufficientFundsBeforeMutation() {
        UUID account = UUID.randomUUID();
        when(economyFacade.getBalanceForPlaceholder(account)).thenReturn(new BigDecimal("4.00"));
        when(economyFacade.isPlayerLocked(account)).thenReturn(false);

        EconomyResponse response = provider.withdraw("Shop", account, new BigDecimal("5.00"));

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Insufficient funds", response.errorMessage);
    }

    @Test
    void transfer_delegatesToSyncmoneyAtomicTransfer() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(economyFacade.atomicTransfer(
                eq(from), eq(to), eq(new BigDecimal("3.00")),
                eq(EconomyEvent.EventSource.PLUGIN_WITHDRAW)))
                .thenReturn(new BigDecimal("7.00"));
        when(economyFacade.getBalanceForPlaceholder(from)).thenReturn(new BigDecimal("10.00"));
        when(economyFacade.getBalanceForPlaceholder(to)).thenReturn(new BigDecimal("8.00"));

        MultiEconomyResponse response = provider.transfer("Auction", from, to, new BigDecimal("3"));

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type());
        assertEquals(new BigDecimal("7.00"), response.balance(from).orElseThrow());
        assertEquals(new BigDecimal("8.00"), response.balance(to).orElseThrow());
    }

    @Test
    void transferRejectsCmiModeBeforeReadingOrMutatingAccounts() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(config.isCMIMode()).thenReturn(true);

        MultiEconomyResponse response = provider.transfer("Auction", from, to, new BigDecimal("3"));

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type());
        assertTrue(response.errorMessage().toLowerCase().contains("cmi"));
        verify(economyFacade, never()).getBalanceForPlaceholder(any(UUID.class));
        verify(economyFacade, never()).atomicTransfer(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                any(EconomyEvent.EventSource.class));
    }

    @Test
    void retainedProviderReferenceRejectsWritesAfterItIsDisabled() {
        UUID account = UUID.randomUUID();
        provider.setEnabled(false);

        EconomyResponse response = provider.deposit("Shop", account, BigDecimal.ONE);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        verify(economyFacade, never()).getBalanceForPlaceholder(account);
        verify(economyFacade, never()).pluginDeposit(any(), any(), any());
    }

    @Test
    void coldAccountReturnsNullAndRejectsMutationsWithoutReadingStorageSynchronously() {
        UUID account = UUID.randomUUID();
        when(economyFacade.getBalanceForPlaceholder(account)).thenReturn(null);

        assertNull(provider.getBalance("Shop", account),
                "VaultUnlocked BigDecimal has no unavailable number, so null must remain explicit");
        assertFalse(provider.has("Shop", account, BigDecimal.ONE),
                "an unavailable balance must fail closed");

        EconomyResponse response = provider.deposit("Shop", account, BigDecimal.ONE);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("not ready"));
        assertNull(response.balance, "a rejected cold operation must not report an invented zero balance");
        verify(economyFacade, never()).getBalance(account);
        verify(economyFacade, never()).pluginDeposit(eq(account), eq(BigDecimal.ONE), eq("Shop"));
    }

    @Test
    void withdrawRejectsColdSnapshotEvenIfAsyncLoadCompletesDuringTheSameCall() {
        UUID account = UUID.randomUUID();
        when(economyFacade.getBalanceForPlaceholder(account))
                .thenReturn(null, new BigDecimal("10.00"));
        when(economyFacade.isPlayerLocked(account)).thenReturn(false);
        when(economyFacade.pluginWithdraw(eq(account), any(BigDecimal.class), eq("Shop")))
                .thenReturn(new BigDecimal("9.00"));

        EconomyResponse response = provider.withdraw("Shop", account, BigDecimal.ONE);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("not ready"));
        verify(economyFacade, times(1)).getBalanceForPlaceholder(account);
        verify(economyFacade, never()).pluginWithdraw(eq(account), eq(BigDecimal.ONE), eq("Shop"));
    }

    @Test
    void depositRejectsColdSnapshotEvenIfAsyncLoadCompletesDuringTheSameCall() {
        UUID account = UUID.randomUUID();
        when(economyFacade.getBalanceForPlaceholder(account))
                .thenReturn(null, new BigDecimal("10.00"));
        when(economyFacade.isPlayerLocked(account)).thenReturn(false);
        when(economyFacade.pluginDeposit(eq(account), any(BigDecimal.class), eq("Shop")))
                .thenReturn(new BigDecimal("11.00"));

        EconomyResponse response = provider.deposit("Shop", account, BigDecimal.ONE);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.toLowerCase().contains("not ready"));
        verify(economyFacade, times(1)).getBalanceForPlaceholder(account);
        verify(economyFacade, never()).pluginDeposit(eq(account), eq(BigDecimal.ONE), eq("Shop"));
    }

    @Test
    void reportsSingleCurrencyWithoutSharedAccounts() {
        assertFalse(provider.hasMultiCurrencySupport());
        assertFalse(provider.hasSharedAccountSupport());
        assertEquals("$", provider.getDefaultCurrency("Test"));
        assertEquals(2, provider.fractionalDigits("Test"));
    }
}
