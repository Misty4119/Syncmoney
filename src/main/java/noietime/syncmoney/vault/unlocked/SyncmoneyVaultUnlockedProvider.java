package noietime.syncmoney.vault.unlocked;

import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.NumericUtil;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * [SYNC-VAULT2-001] VaultUnlocked 2.x adapter for Syncmoney.
 *
 * <p>Syncmoney currently exposes one global currency and one global balance per
 * UUID. Vault2 world and currency overloads therefore delegate to that same
 * balance, as permitted by the Vault2 contract for providers without
 * multi-currency support. Shared accounts are explicitly unsupported.
 *
 * <p>[ThreadSafe] Transactions delegate to EconomyFacade's optimistic in-memory
 * update path and asynchronous persistence queue.
 */
public final class SyncmoneyVaultUnlockedProvider implements Economy {

    private static final String PROVIDER_NAME = "Syncmoney";
    private static final String FALLBACK_CURRENCY = "$";
    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;
    private final SyncmoneyConfig config;

    private volatile boolean enabled;

    public SyncmoneyVaultUnlockedProvider(Plugin plugin, EconomyFacade economyFacade,
            NameResolver nameResolver, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.nameResolver = nameResolver;
        this.config = config;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return config != null ? config.display().getDecimalPlaces() : 2;
    }

    @Override
    public String format(BigDecimal amount) {
        return format(PROVIDER_NAME, amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return FormatUtil.formatCurrency(NumericUtil.normalize(amount)) + " " + currencyName();
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return format(PROVIDER_NAME, amount, currency);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return format(pluginName, amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return currency != null && currencyName().equalsIgnoreCase(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return currencyName();
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return currencyName();
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return currencyName();
    }

    @Override
    public Collection<String> currencies() {
        return List.of(currencyName());
    }

    @Override
    public boolean createAccount(UUID accountID, String name) {
        return createPlayerAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        return player && createPlayerAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return createPlayerAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return player && createPlayerAccount(accountID, name);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return nameResolver != null ? nameResolver.getCachedUuidNameMap() : Map.of();
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        if (accountID == null || nameResolver == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(nameResolver.getNameCachedOnly(accountID));
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        return accountID != null && !economyFacade.isPlayerLocked(accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        return hasAccount(accountID);
    }

    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return renameAccount(PROVIDER_NAME, accountID, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID accountID, String name) {
        if (accountID == null || name == null || name.isBlank() || nameResolver == null) {
            return false;
        }
        nameResolver.cacheName(name, accountID);
        return true;
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID accountID) {
        return false;
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return hasAccount(accountID) && hasCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID,
            String currency, String world) {
        return accountSupportsCurrency(pluginName, accountID, currency);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        if (accountID == null) {
            return BigDecimal.ZERO;
        }
        return NumericUtil.normalize(economyFacade.getBalance(accountID));
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return getBalance(pluginName, accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return getBalance(pluginName, accountID);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        return NumericUtil.isNonNegative(amount)
                && getBalance(pluginName, accountID).compareTo(NumericUtil.normalize(amount)) >= 0;
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return has(pluginName, accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName,
            String currency, BigDecimal amount) {
        return has(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, BigDecimal amount) {
        BigDecimal balance = getBalance(pluginName, accountID);
        if (!validTransaction(accountID, amount)) {
            return failure(balance, "Invalid account or amount");
        }
        if (economyFacade.isPlayerLocked(accountID)) {
            return failure(balance, "Account is locked");
        }
        if (balance.compareTo(NumericUtil.normalize(amount)) < 0) {
            return failure(balance, "Insufficient funds");
        }
        return success(NumericUtil.normalize(amount), balance);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID,
            String worldName, BigDecimal amount) {
        return canWithdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID,
            String worldName, String currency, BigDecimal amount) {
        return canWithdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        BigDecimal balance = getBalance(pluginName, accountID);
        EconomyResponse allowed = canWithdraw(pluginName, accountID, amount);
        if (!allowed.transactionSuccess()) {
            return allowed;
        }
        BigDecimal normalized = NumericUtil.normalize(amount);
        if (normalized.signum() == 0) {
            return success(BigDecimal.ZERO, balance);
        }

        BigDecimal newBalance = economyFacade.pluginWithdraw(
                accountID, normalized, sourcePlugin(pluginName));
        if (newBalance.signum() < 0) {
            return failure(getBalance(pluginName, accountID), "Withdrawal rejected");
        }
        return success(normalized, newBalance);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID,
            String worldName, BigDecimal amount) {
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID,
            String worldName, String currency, BigDecimal amount) {
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, BigDecimal amount) {
        BigDecimal balance = getBalance(pluginName, accountID);
        if (!validTransaction(accountID, amount)) {
            return failure(balance, "Invalid account or amount");
        }
        if (economyFacade.isPlayerLocked(accountID)) {
            return failure(balance, "Account is locked");
        }
        return success(NumericUtil.normalize(amount), balance);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID,
            String worldName, BigDecimal amount) {
        return canDeposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID,
            String worldName, String currency, BigDecimal amount) {
        return canDeposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        BigDecimal balance = getBalance(pluginName, accountID);
        EconomyResponse allowed = canDeposit(pluginName, accountID, amount);
        if (!allowed.transactionSuccess()) {
            return allowed;
        }
        BigDecimal normalized = NumericUtil.normalize(amount);
        if (normalized.signum() == 0) {
            return success(BigDecimal.ZERO, balance);
        }

        BigDecimal newBalance = economyFacade.pluginDeposit(
                accountID, normalized, sourcePlugin(pluginName));
        if (newBalance.signum() < 0) {
            return failure(getBalance(pluginName, accountID), "Deposit rejected");
        }
        return success(normalized, newBalance);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID,
            String worldName, BigDecimal amount) {
        return deposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID,
            String worldName, String currency, BigDecimal amount) {
        return deposit(pluginName, accountID, amount);
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) {
        if (!validTransaction(from, amount) || to == null || from.equals(to)) {
            return transferFailure(amount, "Invalid transfer");
        }

        BigDecimal normalized = NumericUtil.normalize(amount);
        if (normalized.signum() == 0) {
            MultiEconomyResponse response = transferSuccess(normalized);
            response.addBalance(from, getBalance(pluginName, from));
            response.addBalance(to, getBalance(pluginName, to));
            return response;
        }

        BigDecimal fromBalance = economyFacade.atomicTransfer(
                from, to, normalized, EconomyEvent.EventSource.PLUGIN_WITHDRAW);
        if (fromBalance.signum() < 0) {
            return transferFailure(normalized, "Transfer rejected");
        }

        MultiEconomyResponse response = transferSuccess(normalized);
        response.addBalance(from, NumericUtil.normalize(fromBalance));
        response.addBalance(to, getBalance(pluginName, to));
        plugin.getLogger().fine("VaultUnlocked transfer completed by " + sourcePlugin(pluginName)
                + ": " + from + " -> " + to + " : " + normalized);
        return response;
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to,
            String worldName, BigDecimal amount) {
        return transfer(pluginName, from, to, amount);
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to,
            String worldName, String currency, BigDecimal amount) {
        return transfer(pluginName, from, to, amount);
    }

    @Override
    public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
        return false;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid,
            AccountPermission... initialPermissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID accountID,
            UUID uuid, AccountPermission permission) {
        return false;
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID accountID,
            UUID uuid, AccountPermission permission, boolean value) {
        return false;
    }

    private boolean createPlayerAccount(UUID accountID, String name) {
        if (accountID == null || name == null || name.isBlank()) {
            return false;
        }
        if (nameResolver != null) {
            nameResolver.cacheName(name, accountID);
        }
        economyFacade.getBalance(accountID);
        return true;
    }

    private boolean validTransaction(UUID accountID, BigDecimal amount) {
        return accountID != null && NumericUtil.isNonNegative(amount);
    }

    private String currencyName() {
        if (config == null) {
            return FALLBACK_CURRENCY;
        }
        String configured = config.display().getCurrencyName();
        return configured == null || configured.isBlank() ? FALLBACK_CURRENCY : configured;
    }

    private String sourcePlugin(String pluginName) {
        return pluginName == null || pluginName.isBlank() ? "VaultUnlocked" : pluginName;
    }

    private EconomyResponse success(BigDecimal amount, BigDecimal balance) {
        return new EconomyResponse(
                NumericUtil.normalize(amount),
                NumericUtil.normalize(balance),
                EconomyResponse.ResponseType.SUCCESS,
                "");
    }

    private EconomyResponse failure(BigDecimal balance, String message) {
        return new EconomyResponse(
                BigDecimal.ZERO,
                NumericUtil.normalize(balance),
                EconomyResponse.ResponseType.FAILURE,
                message);
    }

    private MultiEconomyResponse transferSuccess(BigDecimal amount) {
        return new MultiEconomyResponse(
                NumericUtil.normalize(amount),
                EconomyResponse.ResponseType.SUCCESS,
                "");
    }

    private MultiEconomyResponse transferFailure(BigDecimal amount, String message) {
        return new MultiEconomyResponse(
                NumericUtil.normalize(amount),
                EconomyResponse.ResponseType.FAILURE,
                message);
    }
}
