package noietime.syncmoney.api.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import noietime.syncmoney.api.BalanceSnapshot;
import noietime.syncmoney.api.EconomyService;
import noietime.syncmoney.api.SyncmoneyApi;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.economy.EconomyFacade;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;

class BukkitApiProviderTest {

    @Test
    void registersTheReadApiAsAServiceAndUnregistersOnClose() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        BukkitApiProvider provider = new BukkitApiProvider(plugin, mock(EconomyFacade.class), null);

        provider.register();
        assertEquals(provider, (SyncmoneyApi) provider);
        verify(services).register(SyncmoneyApi.class, provider, plugin, ServicePriority.Normal);

        provider.close();
        verify(services).unregister(SyncmoneyApi.class, provider);
        assertThrows(IllegalStateException.class, () -> provider.economy().cachedBalance(UUID.randomUUID()));
    }

    @Test
    void mapsCachedAndAsynchronousFacadeBalancesToImmutableSnapshots() throws Exception {
        EconomyFacade facade = mock(EconomyFacade.class);
        Plugin plugin = mock(Plugin.class);
        UUID uuid = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("12.3400");
        when(facade.getCachedBalanceForApi(uuid)).thenReturn(Optional.of(balance));
        when(facade.loadBalanceForApi(uuid)).thenReturn(
                CompletableFuture.completedFuture(Optional.of(balance)));
        BukkitApiProvider provider = new BukkitApiProvider(plugin, facade, null);

        EconomyService economy = provider.economy();
        BalanceSnapshot cached = economy.cachedBalance(uuid).orElseThrow();
        BalanceSnapshot loaded = economy.loadBalance(uuid).toCompletableFuture().get(2, TimeUnit.SECONDS)
                .orElseThrow();

        assertEquals(uuid, cached.accountId());
        assertEquals(balance, cached.balance());
        assertEquals(cached, loaded);
        verify(facade).getCachedBalanceForApi(uuid);
        verify(facade).loadBalanceForApi(uuid);
        provider.close();
    }

    @Test
    void cachedCmiAuthoritySnapshotDoesNotFallBackToTheSharedFacade() {
        EconomyFacade facade = mock(EconomyFacade.class);
        CMIEconomyHandler cmi = mock(CMIEconomyHandler.class);
        Plugin plugin = mock(Plugin.class);
        UUID uuid = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("44.20");
        when(cmi.getCachedBalanceForApi(uuid)).thenReturn(Optional.of(balance));
        BukkitApiProvider provider = new BukkitApiProvider(plugin, facade, cmi);

        assertEquals(Optional.of(new BalanceSnapshot(uuid, balance)),
                provider.economy().cachedBalance(uuid));

        verify(cmi).getCachedBalanceForApi(uuid);
        verify(facade, never()).getCachedBalanceForApi(uuid);
        provider.close();
    }

    @Test
    void nullUuidCompletesExceptionallyAndCloseFailsOutstandingQueries() throws Exception {
        EconomyFacade facade = mock(EconomyFacade.class);
        Plugin plugin = mock(Plugin.class);
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Optional<BigDecimal>> pending = new CompletableFuture<>();
        when(facade.loadBalanceForApi(uuid)).thenReturn(pending);
        BukkitApiProvider provider = new BukkitApiProvider(plugin, facade, null);

        ExecutionException invalid = assertThrows(ExecutionException.class,
                () -> provider.economy().loadBalance(null).toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(invalid.getCause() instanceof IllegalArgumentException);

        CompletableFuture<Optional<BalanceSnapshot>> result =
                provider.economy().loadBalance(uuid).toCompletableFuture();
        provider.close();
        ExecutionException closed = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(closed.getCause() instanceof IllegalStateException);

        pending.complete(Optional.of(new BigDecimal("90.00")));
        assertTrue(result.isCompletedExceptionally(), "late backend completion must not replace shutdown failure");
    }

    @Test
    void closeStillFailsOutstandingQueriesWhenServiceUnregisterThrows() throws Exception {
        EconomyFacade facade = mock(EconomyFacade.class);
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Optional<BigDecimal>> pending = new CompletableFuture<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        when(facade.loadBalanceForApi(uuid)).thenReturn(pending);
        BukkitApiProvider provider = new BukkitApiProvider(plugin, facade, null);
        provider.register();
        doThrow(new IllegalStateException("service registry failure"))
                .when(services).unregister(SyncmoneyApi.class, provider);

        CompletableFuture<Optional<BalanceSnapshot>> result =
                provider.economy().loadBalance(uuid).toCompletableFuture();

        assertDoesNotThrow(provider::close);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        pending.complete(Optional.of(new BigDecimal("1.00")));
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    @SuppressWarnings("unchecked")
    void closeBeforeCmiEntityCallbackPreventsLateAuthorityAccess() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        Player player = mock(Player.class);
        EntityScheduler entity = mock(EntityScheduler.class);
        CMIEconomyHandler cmi = mock(CMIEconomyHandler.class);
        EconomyFacade facade = mock(EconomyFacade.class);
        UUID uuid = UUID.randomUUID();
        AtomicReference<java.util.function.Consumer<ScheduledTask>> entityAction = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(server.getPlayer(uuid)).thenReturn(player);
        when(player.getScheduler()).thenReturn(entity);
        when(global.run(org.mockito.ArgumentMatchers.eq(plugin), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<ScheduledTask> task = invocation.getArgument(1);
                    task.accept(mock(ScheduledTask.class));
                    return mock(ScheduledTask.class);
                });
        when(entity.run(org.mockito.ArgumentMatchers.eq(plugin), any(java.util.function.Consumer.class),
                any(Runnable.class))).thenAnswer(invocation -> {
                    entityAction.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });
        when(cmi.getCachedBalanceForApi(uuid)).thenReturn(Optional.empty());
        BukkitApiProvider provider = new BukkitApiProvider(plugin, facade, cmi);

        CompletableFuture<Optional<BalanceSnapshot>> result =
                provider.economy().loadBalance(uuid).toCompletableFuture();
        provider.close();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        entityAction.get().accept(mock(ScheduledTask.class));
        verify(cmi, never()).getExistingBalanceForApi(uuid);
    }
}
