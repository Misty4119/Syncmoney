package noietime.syncmoney.vault.unlocked;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault2.economy.Economy;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VaultUnlockedRegistrarLifecycleTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void closeCancelsDelayedRegistrationAndCallbackCannotRestoreProvider() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask delayed = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(services.getRegistration(Economy.class)).thenReturn(null);
        when(scheduler.runDelayed(eq(plugin), any(Consumer.class), eq(20L))).thenReturn(delayed);

        VaultUnlockedRegistrar registrar = new VaultUnlockedRegistrar(plugin,
                mock(EconomyFacade.class), mock(NameResolver.class), mock(SyncmoneyConfig.class));
        assertTrue(registrar.setupEconomy());
        ArgumentCaptor<Consumer<ScheduledTask>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runDelayed(eq(plugin), callback.capture(), eq(20L));

        registrar.close();
        callback.getValue().accept(mock(ScheduledTask.class));

        verify(delayed).cancel();
        verify(services, times(1)).register(eq(Economy.class), any(Economy.class), eq(plugin),
                eq(ServicePriority.Highest));
        verify(services).unregister(eq(Economy.class), any(Object.class));
        assertFalse(registrar.setupEconomy());
    }
}
