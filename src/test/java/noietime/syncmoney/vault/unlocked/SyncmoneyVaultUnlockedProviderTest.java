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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncmoneyVaultUnlockedProviderTest {

    private EconomyFacade economyFacade;
    private NameResolver nameResolver;
    private SyncmoneyVaultUnlockedProvider provider;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        economyFacade = mock(EconomyFacade.class);
        nameResolver = mock(NameResolver.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
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
        when(economyFacade.getBalance(account)).thenReturn(new BigDecimal("10.00"));
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
        when(economyFacade.getBalance(account)).thenReturn(new BigDecimal("4.00"));
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
        when(economyFacade.getBalance(to)).thenReturn(new BigDecimal("8.00"));

        MultiEconomyResponse response = provider.transfer("Auction", from, to, new BigDecimal("3"));

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type());
        assertEquals(new BigDecimal("7.00"), response.balance(from).orElseThrow());
        assertEquals(new BigDecimal("8.00"), response.balance(to).orElseThrow());
    }

    @Test
    void reportsSingleCurrencyWithoutSharedAccounts() {
        assertFalse(provider.hasMultiCurrencySupport());
        assertFalse(provider.hasSharedAccountSupport());
        assertEquals("$", provider.getDefaultCurrency("Test"));
        assertEquals(2, provider.fractionalDigits("Test"));
    }
}
