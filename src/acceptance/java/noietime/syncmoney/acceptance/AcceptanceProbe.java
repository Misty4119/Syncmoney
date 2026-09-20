package noietime.syncmoney.acceptance;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;
import java.math.BigDecimal;
import java.util.UUID;

/** Explicit RCON-triggered tests against an isolated development server, never production. */
public final class AcceptanceProbe extends JavaPlugin {
    @Override public void onEnable() {
        getCommand("smaccept").setExecutor((sender, command, label, args) -> {
            boolean console = sender instanceof org.bukkit.command.ConsoleCommandSender
                    || sender instanceof org.bukkit.command.RemoteConsoleCommandSender;
            // Velocity's backend handoff can race the bootstrap plugin's OP grant. The
            // disposable acceptance accounts are the explicit authorization boundary.
            boolean networkBot = sender instanceof org.bukkit.entity.Player player
                    && player.getName().startsWith("Acceptance");
            if (!console && !networkBot) return true;
            if (args.length > 0 && args[0].startsWith("version")) {
                getServer().getGlobalRegionScheduler().run(this, task -> runVersionChecks(args[0]));
                return true;
            }
            getServer().getAsyncScheduler().runNow(this, task -> runChecks(args));
            return true;
        });
    }

    private void runVersionChecks(String command) {
        try {
            Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
            require(plugin != null && plugin.isEnabled(), "plugin enabled");
            String suffix = switch (command.toLowerCase(java.util.Locale.ROOT)) {
                case "version" -> "";
                case "version-full" -> " full";
                case "version-save" -> " save";
                default -> throw new IllegalArgumentException("unknown version acceptance command: " + command);
            };
            require(getServer().dispatchCommand(getServer().getConsoleSender(), "syncmoney version" + suffix),
                    "version command dispatched");
            getLogger().info("ACCEPTANCE VERSION PASS command=" + command);
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "ACCEPTANCE VERSION FAIL", e);
        }
    }

    private void runChecks(String[] args) {
        try {
            Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
            require(plugin != null && plugin.isEnabled(), "plugin enabled");
            var facade = plugin.getEconomyFacade();
            UUID a = UUID.nameUUIDFromBytes("OfflinePlayer:AcceptanceA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            UUID b = UUID.nameUUIDFromBytes("OfflinePlayer:AcceptanceB".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            plugin.getNameResolver().cacheName("AcceptanceA", a);
            plugin.getNameResolver().cacheName("AcceptanceB", b);
            if (args.length > 0 && args[0].equals("read")) {
                getLogger().info("ACCEPTANCE READ a=" + facade.getBalance(a) + " b=" + facade.getBalance(b));
                return;
            }
            // Seed only these disposable accounts through the existing administrative path.
            facade.unlockPlayer(a);
            facade.unlockPlayer(b);
            var source = EconomyEvent.EventSource.COMMAND_ADMIN;
            facade.setBalance(a, new BigDecimal("1000.00"), source);
            facade.setBalance(b, new BigDecimal("500.00"), source);
            facade.deposit(a, new BigDecimal("25.50"), source);
            facade.withdraw(a, new BigDecimal("5.50"), source);
            require(facade.getBalance(a).compareTo(new BigDecimal("1020")) == 0, "deposit/withdraw");
            facade.atomicTransfer(a, b, new BigDecimal("20"), source);
            require(facade.getBalance(a).compareTo(new BigDecimal("1000")) == 0, "transfer sender");
            require(facade.getBalance(b).compareTo(new BigDecimal("520")) == 0, "transfer receiver");
            var before = facade.getBalance(a).add(facade.getBalance(b));
            require(facade.atomicTransfer(a, b, new BigDecimal("1001"), source).signum() < 0,
                    "insufficient funds rejected");
            require(facade.getBalance(a).add(facade.getBalance(b)).compareTo(before) == 0, "failed transfer conservation");
            var registration = getServer().getServicesManager().getRegistration(Economy.class);
            require(registration != null && registration.getPlugin() == plugin, "Vault provider registration");
            Economy vault = registration.getProvider();
            require(vault.isEnabled(), "Vault provider enabled");
            String expectedVersion = expectedVersion();
            String actualVersion = plugin.getDescription().getVersion();
            require(!actualVersion.isBlank(), "plugin version is present");
            if (expectedVersion != null) {
                require(actualVersion.equals(expectedVersion), "plugin version expected=" + expectedVersion
                        + " actual=" + actualVersion);
            }
            require(facade.getBalanceForPlaceholder(a).compareTo(new BigDecimal("1000")) == 0, "cached placeholder");
            getLogger().info("ACCEPTANCE PASS platform=" + getServer().getName()
                    + " server=" + getServer().getVersion() + " mode=" + plugin.getSyncmoneyConfig().getEconomyMode()
                    + " java=" + Runtime.version() + " total=" + before);
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "ACCEPTANCE FAIL", e);
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException(check);
    }

    private static String expectedVersion() {
        String configured = System.getProperty("syncmoney.acceptance.version");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SYNCMONEY_EXPECTED_VERSION");
        }
        return configured == null || configured.isBlank() ? null : configured.trim();
    }
}
