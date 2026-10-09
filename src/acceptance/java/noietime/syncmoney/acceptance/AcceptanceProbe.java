package noietime.syncmoney.acceptance;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/** Explicit RCON-triggered tests against an isolated development server, never production. */
public final class AcceptanceProbe extends JavaPlugin implements Listener {
    private static final long ACCOUNT_LOAD_TIMEOUT_MILLIS = 10_000L;
    private final java.util.concurrent.ConcurrentMap<UUID, String> expectedTeleportWorlds =
            new ConcurrentHashMap<>();

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("smaccept").setExecutor((sender, command, label, args) -> {
            boolean console = sender instanceof org.bukkit.command.ConsoleCommandSender
                    || sender instanceof org.bukkit.command.RemoteConsoleCommandSender;
            // Velocity's backend handoff can race the bootstrap plugin's OP grant. The
            // disposable acceptance accounts are the explicit authorization boundary.
            boolean networkBot = sender instanceof org.bukkit.entity.Player player
                    && player.getName().startsWith("Acceptance");
            if (!console && !networkBot) return true;
            if (args.length > 0 && args[0].equalsIgnoreCase("lifecycle")) {
                if (args.length < 2) {
                    getLogger().warning("Usage: smaccept lifecycle <logout|teleport> [AcceptancePlayer]");
                    return true;
                }
                String scenario = args[1];
                if (sender instanceof org.bukkit.entity.Player player) {
                    runLifecycleScenario(player, scenario);
                } else if (args.length > 2) {
                    String playerName = args[2];
                    getServer().getGlobalRegionScheduler().run(this, task -> {
                        var player = getServer().getPlayerExact(playerName);
                        if (player == null || !player.getName().startsWith("Acceptance")) {
                            getLogger().warning("T15 lifecycle target must be an online Acceptance player: "
                                    + playerName);
                            return;
                        }
                        player.getScheduler().run(this,
                                entityTask -> runLifecycleScenario(player, scenario),
                                () -> getLogger().warning("T15 lifecycle player retired before scenario: "
                                        + playerName));
                    });
                } else {
                    getLogger().warning("Console lifecycle command requires an online Acceptance player name");
                }
                return true;
            }
            if (args.length > 0 && args[0].startsWith("version")) {
                getServer().getGlobalRegionScheduler().run(this, task -> runVersionChecks(args[0]));
                return true;
            }
            getServer().getAsyncScheduler().runNow(this, task -> runChecks(args));
            return true;
        });
    }

    @EventHandler
    public void onAcceptancePlayerChangedWorld(PlayerChangedWorldEvent event) {
        var player = event.getPlayer();
        if (player.getName().startsWith("Acceptance")) {
            getLogger().info("T15_WORLD_CHANGE uuid=" + player.getUniqueId() + " from="
                    + event.getFrom().getName() + " to=" + player.getWorld().getName());
            String expectedWorld = expectedTeleportWorlds.get(player.getUniqueId());
            if (expectedWorld != null && expectedWorld.equals(player.getWorld().getName())
                    && expectedTeleportWorlds.remove(player.getUniqueId(), expectedWorld)) {
                getLogger().info("T15_TELEPORT_REACHED uuid=" + player.getUniqueId()
                        + " world=" + player.getWorld().getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onAcceptancePlayerTeleport(PlayerTeleportEvent event) {
        var player = event.getPlayer();
        String expectedWorld = expectedTeleportWorlds.get(player.getUniqueId());
        if (player.getName().startsWith("Acceptance") && expectedWorld != null
                && event.getTo() != null && event.getTo().getWorld() != null
                && expectedWorld.equals(event.getTo().getWorld().getName())) {
            getLogger().info("T15_TELEPORT_EVENT uuid=" + player.getUniqueId()
                    + " cancelled=" + event.isCancelled() + " cause=" + event.getCause());
            if (event.isCancelled()) {
                getLogger().info("T15_TELEPORT_DEFERRED uuid=" + player.getUniqueId()
                        + " destination=" + expectedWorld);
            }
        }
    }

    @EventHandler
    public void onAcceptancePlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        if (player.getName().startsWith("Acceptance")) {
            getLogger().info("T15_PLAYER_QUIT uuid=" + player.getUniqueId());
        }
    }

    private void runLifecycleScenario(org.bukkit.entity.Player player, String scenario) {
        try {
            UUID uuid = player.getUniqueId();
            getServer().getGlobalRegionScheduler().run(this, task -> {
                try {
                    Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
                    require(plugin != null && plugin.isEnabled(), "plugin enabled");
                    var registration = getServer().getServicesManager().getRegistration(Economy.class);
                    require(registration != null && registration.getPlugin() == plugin,
                            "Vault provider registration");
                    org.bukkit.Location teleportDestination = null;
                    String teleportWorldName = null;
                    if (scenario.equalsIgnoreCase("teleport")) {
                        var nether = getServer().getWorlds().stream()
                                .filter(world -> world.getEnvironment() == org.bukkit.World.Environment.NETHER)
                                .findFirst().orElseThrow(() -> new IllegalStateException("Nether world unavailable"));
                        teleportDestination = new org.bukkit.Location(nether, 0.5, 80, 0.5);
                        teleportWorldName = nether.getName();
                    }
                    LifecycleContext context = new LifecycleContext(registration.getProvider(),
                            teleportDestination, teleportWorldName);
                    getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                        try {
                            BigDecimal balance = awaitAccountReady(plugin.getEconomyFacade(), uuid);
                            getLogger().info("T15_ACCOUNT_READY scenario=" + scenario + " uuid=" + uuid
                                    + " balance=" + balance);
                            player.getScheduler().run(this,
                                    entityTask -> runReadyLifecycleScenario(player, scenario, context),
                                    () -> getLogger().severe("T15_LIFECYCLE_FAIL scenario=" + scenario
                                            + " player retired before the entity task"));
                        } catch (Throwable e) {
                            getLogger().log(java.util.logging.Level.SEVERE,
                                    "T15_LIFECYCLE_FAIL scenario=" + scenario, e);
                        }
                    });
                } catch (Throwable e) {
                    getLogger().log(java.util.logging.Level.SEVERE, "T15_LIFECYCLE_FAIL scenario=" + scenario, e);
                }
            });
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "T15_LIFECYCLE_FAIL scenario=" + scenario, e);
        }
    }

    private void runReadyLifecycleScenario(
            org.bukkit.entity.Player player, String scenario, LifecycleContext context) {
        try {
            if (scenario.equalsIgnoreCase("warm")) {
                getLogger().info("T15_WARM_PASS uuid=" + player.getUniqueId());
                return;
            }
            if (scenario.equalsIgnoreCase("teleport")) {
                require(player.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL,
                        "teleport fixture requires a fresh Acceptance player spawned in the Overworld");
            }
            for (int index = 1; index <= 2; index++) {
                var response = context.economy().depositPlayer(player, 1.00);
                require(response.transactionSuccess(), "Vault deposit accepted: " + response.errorMessage);
                getLogger().info("T15_ACCEPTED_WRITE scenario=" + scenario + " index=" + index
                        + " uuid=" + player.getUniqueId() + " amount=1.00 response=" + response.type);
            }

            switch (scenario.toLowerCase(java.util.Locale.ROOT)) {
                case "logout" -> {
                    getLogger().info("T15_LOGOUT_REQUEST uuid=" + player.getUniqueId());
                    player.kickPlayer("T15 accepted-write logout scenario");
                }
                case "teleport" -> {
                    var destination = context.teleportDestination();
                    expectedTeleportWorlds.put(player.getUniqueId(), context.teleportWorldName());
                    player.teleportAsync(destination, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    getLogger().info("T15_TELEPORT_REQUEST uuid=" + player.getUniqueId()
                            + " cause=PLUGIN destination=" + context.teleportWorldName() + ":0.5,80,0.5");
                }
                default -> throw new IllegalArgumentException("Expected lifecycle scenario logout or teleport");
            }
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "T15_LIFECYCLE_FAIL scenario=" + scenario, e);
        }
    }

    private record LifecycleContext(
            Economy economy, org.bukkit.Location teleportDestination, String teleportWorldName) {
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
            var registration = getServer().getServicesManager().getRegistration(Economy.class);
            require(registration != null && registration.getPlugin() == plugin, "Vault provider registration");
            Economy vault = registration.getProvider();
            require(vault.isEnabled(), "Vault provider enabled");
            VaultUnlockedAccess vaultUnlocked = vaultUnlockedAccess(plugin);
            OfflinePlayer offlineA = getServer().getOfflinePlayer(a);
            OfflinePlayer offlineB = getServer().getOfflinePlayer(b);
            if (args.length > 0 && args[0].equals("read")) {
                double coldVaultA = vault.getBalance(offlineA);
                BigDecimal coldVaultUnlockedB = vaultUnlocked.balance(b);
                require(Double.isNaN(coldVaultA), "cold Vault account A is unavailable");
                require(coldVaultUnlockedB == null, "cold VaultUnlocked account B is unavailable");
                awaitAccountReady(facade, a);
                awaitAccountReady(facade, b);
                BigDecimal persistedA = facade.getBalance(a);
                BigDecimal persistedB = facade.getBalance(b);
                BigDecimal warmVaultUnlockedB = vaultUnlocked.balance(b);
                require(persistedA.compareTo(new BigDecimal("1000")) == 0, "persisted account A");
                require(persistedB.compareTo(new BigDecimal("520")) == 0, "persisted account B");
                require(new BigDecimal("520.00").compareTo(warmVaultUnlockedB) == 0,
                        "VaultUnlocked account B after background load");
                getLogger().info("ACCEPTANCE READ cold-vault-a=" + coldVaultA
                        + " cold-vault-unlocked-b=" + coldVaultUnlockedB
                        + " a=" + persistedA + " b=" + persistedB
                        + " vault-unlocked-b=" + warmVaultUnlockedB);
                return;
            }
            // Seed only these disposable accounts through the existing administrative path.
            facade.unlockPlayer(a);
            facade.unlockPlayer(b);
            awaitAccountReady(facade, a);
            awaitAccountReady(facade, b);
            var source = EconomyEvent.EventSource.COMMAND_ADMIN;
            facade.setBalance(a, new BigDecimal("1000.00"), source);
            facade.setBalance(b, new BigDecimal("500.00"), source);
            require(vault.depositPlayer(offlineA, 25.50).transactionSuccess(), "Vault deposit");
            require(vault.withdrawPlayer(offlineA, 5.50).transactionSuccess(), "Vault withdrawal");
            require(vault.getBalance(offlineA) == 1020.00, "Vault warm balance after deposit/withdrawal");
            require(facade.getBalance(a).compareTo(new BigDecimal("1020")) == 0, "deposit/withdraw");
            facade.atomicTransfer(a, b, new BigDecimal("20"), source);
            require(facade.getBalance(a).compareTo(new BigDecimal("1000")) == 0, "transfer sender");
            require(facade.getBalance(b).compareTo(new BigDecimal("520")) == 0, "transfer receiver");
            var before = facade.getBalance(a).add(facade.getBalance(b));
            require(facade.atomicTransfer(a, b, new BigDecimal("1001"), source).signum() < 0,
                    "insufficient funds rejected");
            require(facade.getBalance(a).add(facade.getBalance(b)).compareTo(before) == 0, "failed transfer conservation");
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

    private BigDecimal awaitAccountReady(noietime.syncmoney.economy.EconomyFacade facade, UUID uuid)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(ACCOUNT_LOAD_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            BigDecimal balance = facade.getBalanceForPlaceholder(uuid);
            if (balance != null) return balance;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("Timed out waiting for account readiness: " + uuid);
    }

    private VaultUnlockedAccess vaultUnlockedAccess(Syncmoney plugin) throws ReflectiveOperationException {
        Class<?> economyApi = Class.forName("net.milkbowl.vault2.economy.Economy", true,
                plugin.getClass().getClassLoader());
        var registration = getServer().getServicesManager().getRegistration(economyApi);
        require(registration != null && registration.getPlugin() == plugin,
                "VaultUnlocked provider registration");
        return new VaultUnlockedAccess(registration.getProvider(),
                economyApi.getMethod("getBalance", String.class, UUID.class));
    }

    private record VaultUnlockedAccess(Object provider, Method getBalanceMethod) {
        BigDecimal balance(UUID account) throws ReflectiveOperationException {
            return (BigDecimal) getBalanceMethod.invoke(provider, "SyncmoneyAcceptanceProbe", account);
        }
    }

    private static String expectedVersion() {
        String configured = System.getProperty("syncmoney.acceptance.version");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SYNCMONEY_EXPECTED_VERSION");
        }
        return configured == null || configured.isBlank() ? null : configured.trim();
    }
}
