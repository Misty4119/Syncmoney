package noietime.syncmoney.api.bukkit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import noietime.syncmoney.api.BalanceSnapshot;
import noietime.syncmoney.api.EconomyService;
import noietime.syncmoney.api.SyncmoneyApi;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.EconomyFacade;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Owns Syncmoney's public read-only API service registration. */
public final class BukkitApiProvider implements SyncmoneyApi, AutoCloseable {

    private final Plugin plugin;
    private final EconomyFacade economyFacade;
    private final CMIEconomyHandler cmiHandler;
    private final CmiEntityBalanceQuery cmiQuery;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<Optional<BalanceSnapshot>>> outstanding = ConcurrentHashMap.newKeySet();
    private final EconomyService economy = new ReadOnlyEconomyService();

    public BukkitApiProvider(Plugin plugin, EconomyFacade economyFacade, CMIEconomyHandler cmiHandler) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.cmiHandler = cmiHandler;
        this.cmiQuery = cmiHandler == null ? null : new CmiEntityBalanceQuery(plugin, cmiHandler, () -> !closed.get());
    }

    /** Registers this provider after plugin initialization has completed. */
    public void register() {
        if (closed.get()) {
            throw new IllegalStateException("Syncmoney API provider is closed");
        }
        if (registered.compareAndSet(false, true)) {
            try {
                servicesManager().register(SyncmoneyApi.class, this, plugin, ServicePriority.Normal);
            } catch (RuntimeException failure) {
                registered.set(false);
                throw failure;
            }
        }
    }

    @Override
    public EconomyService economy() {
        return economy;
    }

    /** Unregisters the service and fails all pending queries before shutdown quiesces producers. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (registered.compareAndSet(true, false)) {
                servicesManager().unregister(SyncmoneyApi.class, this);
            }
        } catch (Throwable failure) {
            if (plugin.getLogger() != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to unregister Syncmoney API service", failure);
            }
        } finally {
            IllegalStateException shutdown = new IllegalStateException("Syncmoney API provider is shutting down");
            outstanding.forEach(future -> future.completeExceptionally(shutdown));
            outstanding.clear();
        }
    }

    private ServicesManager servicesManager() {
        return plugin.getServer().getServicesManager();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Syncmoney API provider is shutting down");
        }
    }

    private CompletionStage<Optional<BalanceSnapshot>> loadBalance(UUID accountId) {
        if (accountId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Account UUID must not be null"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Syncmoney API provider is shutting down"));
        }

        CompletableFuture<Optional<BalanceSnapshot>> response = new CompletableFuture<>();
        outstanding.add(response);
        response.whenComplete((ignored, failure) -> outstanding.remove(response));
        if (closed.get()) {
            response.completeExceptionally(new IllegalStateException("Syncmoney API provider is shutting down"));
            return response.minimalCompletionStage();
        }

        try {
            CompletionStage<Optional<BigDecimal>> read;
            if (cmiHandler == null) {
                read = economyFacade.loadBalanceForApi(accountId);
            } else {
                Optional<BigDecimal> cached = cmiHandler.getCachedBalanceForApi(accountId);
                read = cached.isPresent()
                        ? CompletableFuture.completedFuture(cached)
                        : cmiQuery.load(accountId);
            }
            read.whenComplete((balance, failure) -> {
                if (closed.get()) {
                    response.completeExceptionally(new IllegalStateException("Syncmoney API provider is shutting down"));
                } else if (failure != null) {
                    response.completeExceptionally(failure);
                } else if (balance == null) {
                    response.completeExceptionally(new IllegalStateException("Economy query returned no result"));
                } else {
                    response.complete(balance.map(value -> new BalanceSnapshot(accountId, value)));
                }
            });
        } catch (Throwable failure) {
            response.completeExceptionally(failure);
        }
        return response.minimalCompletionStage();
    }

    private final class ReadOnlyEconomyService implements EconomyService {

        @Override
        public Optional<BalanceSnapshot> cachedBalance(UUID accountId) {
            if (accountId == null) {
                throw new IllegalArgumentException("Account UUID must not be null");
            }
            ensureOpen();
            Optional<BigDecimal> balance = cmiHandler == null
                    ? economyFacade.getCachedBalanceForApi(accountId)
                    : cmiHandler.getCachedBalanceForApi(accountId);
            return balance.map(value -> new BalanceSnapshot(accountId, value));
        }

        @Override
        public CompletionStage<Optional<BalanceSnapshot>> loadBalance(UUID accountId) {
            return BukkitApiProvider.this.loadBalance(accountId);
        }
    }
}
