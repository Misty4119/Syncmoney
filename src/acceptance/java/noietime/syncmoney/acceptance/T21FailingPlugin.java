package noietime.syncmoney.acceptance;

import org.bukkit.plugin.java.JavaPlugin;

/** Disposable plugin entrypoint used to exercise PlugDev pre-session startup cleanup. */
public final class T21FailingPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        throw new IllegalStateException("Intentional T21 failed-start cleanup probe");
    }
}
