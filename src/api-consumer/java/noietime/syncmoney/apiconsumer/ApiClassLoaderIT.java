package noietime.syncmoney.apiconsumer;

import noietime.syncmoney.api.SyncmoneyApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper runtime fixture compiled with the SDK as compileOnly. */
public final class ApiClassLoaderIT extends JavaPlugin {

    @Override
    public void onEnable() {
        try {
            RegisteredServiceProvider<SyncmoneyApi> registration =
                    getServer().getServicesManager().getRegistration(SyncmoneyApi.class);
            require(registration != null, "Syncmoney API service registration exists");
            require("Syncmoney".equals(registration.getPlugin().getName()), "Syncmoney owns the API service");

            SyncmoneyApi provider = registration.getProvider();
            require(provider == getServer().getServicesManager().load(SyncmoneyApi.class),
                    "typed service lookup returns the registered provider");

            String apiName = SyncmoneyApi.class.getName();
            Class<?> providerPluginView = registration.getPlugin().getClass().getClassLoader().loadClass(apiName);
            Class<?> providerClassView = provider.getClass().getClassLoader().loadClass(apiName);
            require(providerPluginView == SyncmoneyApi.class, "consumer and provider plugin share API class identity");
            require(providerClassView == SyncmoneyApi.class, "provider implementation uses the registered API class");
            require(provider.economy() != null, "read-only economy service is available");

            getLogger().info("API CLASSLOADER PASS provider=" + registration.getPlugin().getName());
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "API CLASSLOADER FAIL", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) {
            throw new IllegalStateException(check);
        }
    }
}
