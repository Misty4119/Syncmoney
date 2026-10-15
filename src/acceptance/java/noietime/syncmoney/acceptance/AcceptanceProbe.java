package noietime.syncmoney.acceptance;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.breaker.EconomicCircuitBreaker;
import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyWriteQueue;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/** Explicit RCON-triggered tests against an isolated development server, never production. */
public final class AcceptanceProbe extends JavaPlugin implements Listener {
    private static final long ACCOUNT_LOAD_TIMEOUT_MILLIS = 10_000L;
    private static final UUID LEGACY_SQLITE_ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEGACY_SQLITE_ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final java.util.concurrent.ConcurrentMap<UUID, String> expectedTeleportWorlds =
            new ConcurrentHashMap<>();
    private volatile GuardPairRun activeGuardPairRun;

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
                if (scenario.equalsIgnoreCase("guard-pair-notice-ack")) {
                    if (sender instanceof org.bukkit.entity.Player player && args.length > 2) {
                        acknowledgeGuardPairNotice(player, args[2]);
                    } else {
                        failGuardPairRun("notice-ack", "notice acknowledgement requires its receiving player");
                    }
                    return true;
                }
                if (scenario.equalsIgnoreCase("guard")) {
                    if (sender instanceof org.bukkit.entity.Player player) {
                        runGuardScenario(player);
                    } else {
                        getLogger().warning("T21 guard scenario requires an Acceptance player");
                    }
                    return true;
                }
                if (scenario.equalsIgnoreCase("guard-pair")) {
                    if (sender instanceof org.bukkit.entity.Player player && args.length > 2) {
                        runGuardPairScenario(player, args[2], false);
                    } else {
                        getLogger().warning("T21 guard-pair scenario requires AcceptanceGuardA and its peer name");
                    }
                    return true;
                }
                if (scenario.equalsIgnoreCase("guard-pair-breaker")) {
                    if (sender instanceof org.bukkit.entity.Player player && args.length > 2) {
                        runGuardPairScenario(player, args[2], true);
                    } else {
                        getLogger().warning("T21 guard-pair-breaker scenario requires AcceptanceGuardA and its peer name");
                    }
                    return true;
                }
                if (scenario.equalsIgnoreCase("cross-owner")) {
                    if (sender instanceof org.bukkit.entity.Player player && args.length > 2) {
                        runCrossOwnerScenario(player, args[2]);
                    } else {
                        getLogger().warning("T21 cross-owner scenario requires an Acceptance player and its peer name");
                    }
                    return true;
                }
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
            if (args.length > 0 && args[0].equalsIgnoreCase("quiescence")) {
                getServer().getGlobalRegionScheduler().run(this, task -> runQuiescenceCheck());
                return true;
            }
            getServer().getAsyncScheduler().runNow(this, task -> runChecks(args));
            return true;
        });
    }

    private void runQuiescenceCheck() {
        try {
            Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
            require(plugin != null && plugin.isEnabled(), "plugin enabled");
            verifyPluginVersion(plugin);
            int onlinePlayers = getServer().getOnlinePlayers().size();
            require(onlinePlayers == 0, "quiesced upgrade requires zero online players; observed " + onlinePlayers);
            String loadedPlugins = java.util.Arrays.stream(getServer().getPluginManager().getPlugins())
                    .map(org.bukkit.plugin.Plugin::getName)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            getLogger().info("T21_QUIESCENCE_PASS online-players=0 loaded-plugins=" + loadedPlugins);
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "T21_QUIESCENCE_FAIL", e);
        }
    }

    @EventHandler
    public void onAcceptancePlayerChangedWorld(PlayerChangedWorldEvent event) {
        var player = event.getPlayer();
        if (player.getName().startsWith("Acceptance")) {
            getLogger().info("T15_WORLD_CHANGE uuid=" + player.getUniqueId() + " from="
                    + event.getFrom().getName() + " to=" + player.getWorld().getName());
            String expectedWorld = expectedTeleportWorlds.get(player.getUniqueId());
            recordTeleportReached(player, expectedWorld, "world-change-event");
        }
    }

    private void recordTeleportReached(org.bukkit.entity.Player player, String expectedWorld, String source) {
        if (expectedWorld != null && expectedWorld.equals(player.getWorld().getName())
                && expectedTeleportWorlds.remove(player.getUniqueId(), expectedWorld)) {
            getLogger().info("T15_TELEPORT_REACHED uuid=" + player.getUniqueId()
                    + " world=" + player.getWorld().getName() + " verifiedBy=" + source);
            player.sendMessage("T15_TELEPORT_REACHED world=" + player.getWorld().getName());
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
            getLogger().info("T15_PLAYER_QUIT uuid=" + player.getUniqueId()
                    + " world=" + player.getWorld().getName());
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
                            readEconomyWriteQueue(plugin), teleportDestination, teleportWorldName);
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

    private void runCrossOwnerScenario(org.bukkit.entity.Player anchor, String otherPlayerName) {
        if (!anchor.getName().equals("AcceptanceA") || !otherPlayerName.equals("AcceptanceB")) {
            getLogger().severe("T21_CROSS_OWNER_FAIL reason=unexpected-acceptance-player-pair");
            return;
        }
        getServer().getGlobalRegionScheduler().run(this, task -> {
            try {
                var other = getServer().getPlayerExact(otherPlayerName);
                require(other != null, "second Acceptance player is online");
                require(other != anchor, "cross-owner participants are different entities");
                World overworld = getServer().getWorlds().stream()
                        .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                        .findFirst().orElseThrow(() -> new IllegalStateException("Overworld unavailable"));

                var anchorLocation = captureRegionLocation(anchor, overworld, "AcceptanceA");
                var otherLocation = moveAndCaptureRegionLocation(other, overworld, "AcceptanceB",
                        new org.bukkit.Location(overworld, 16_384.5, 100, 16_384.5));
                anchorLocation.thenCombine(otherLocation, RegionPair::new)
                        .whenComplete((pair, failure) -> {
                            if (failure != null) {
                                reportCrossOwnerFailure("prepare", failure);
                                return;
                            }
                            verifyDistinctRegionOwners(anchor, other, pair);
                        });
            } catch (Throwable failure) {
                reportCrossOwnerFailure("start", failure);
            }
        });
    }

    private CompletableFuture<RegionLocation> captureRegionLocation(
            org.bukkit.entity.Player player, World expectedWorld, String playerName) {
        var result = new CompletableFuture<RegionLocation>();
        player.getScheduler().run(this, task -> {
            try {
                require(Bukkit.isOwnedByCurrentRegion(player), "entity scheduler owns " + playerName);
                require(player.getWorld().equals(expectedWorld), playerName + " is in the Overworld");
                result.complete(snapshot(player.getLocation()));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, () -> result.completeExceptionally(new IllegalStateException(
                "player retired before region location snapshot: " + playerName)));
        return result;
    }

    private CompletableFuture<RegionLocation> moveAndCaptureRegionLocation(
            org.bukkit.entity.Player player, World world, String playerName, org.bukkit.Location destination) {
        var result = new CompletableFuture<RegionLocation>();
        player.getScheduler().run(this, task -> {
            try {
                player.teleportAsync(destination, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((success, failure) -> {
                            if (failure != null) {
                                result.completeExceptionally(failure);
                            } else if (!Boolean.TRUE.equals(success)) {
                                result.completeExceptionally(new IllegalStateException(
                                        "teleportAsync returned false for " + playerName));
                            } else {
                                captureRegionLocation(player, world, playerName).whenComplete((location, captureFailure) -> {
                                    if (captureFailure != null) result.completeExceptionally(captureFailure);
                                    else result.complete(location);
                                });
                            }
                        });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, () -> result.completeExceptionally(new IllegalStateException(
                "player retired before remote-region teleport: " + playerName)));
        return result;
    }

    private RegionLocation snapshot(org.bukkit.Location location) {
        World world = location.getWorld();
        require(world != null, "region probe world is available");
        int blockX = location.getBlockX();
        int blockZ = location.getBlockZ();
        return new RegionLocation(world, world.getUID(), world.getName(), blockX, blockZ,
                blockX >> 4, blockZ >> 4);
    }

    private void verifyDistinctRegionOwners(
            org.bukkit.entity.Player anchor, org.bukkit.entity.Player other, RegionPair pair) {
        var anchorProof = observeRegionOwnership(
                anchor, "AcceptanceA", pair.anchor(), "AcceptanceB", pair.other());
        var otherProof = observeRegionOwnership(
                other, "AcceptanceB", pair.other(), "AcceptanceA", pair.anchor());
        CompletableFuture.allOf(anchorProof, otherProof).whenComplete((ignored, failure) -> {
            if (failure != null) {
                reportCrossOwnerFailure("owner-scheduler", failure);
                return;
            }
            OwnerObservation first = anchorProof.getNow(null);
            OwnerObservation second = otherProof.getNow(null);
            double distance = Math.hypot((double) first.blockX() - second.blockX(),
                    (double) first.blockZ() - second.blockZ());
            boolean pass = first.selfOwned() && second.selfOwned()
                    && !first.otherOwned() && !second.otherOwned()
                    && first.worldId().equals(second.worldId()) && distance >= 4096.0;
            if (!pass) {
                getLogger().severe("T21_CROSS_OWNER_FAIL reason=owner-or-separation-check");
                return;
            }
            getLogger().info("T21_CROSS_OWNER_PASS players=AcceptanceA,AcceptanceB distanceBlocks="
                    + Math.round(distance));
            sendCrossOwnerClientCompletion(anchor, "AcceptanceA");
            sendCrossOwnerClientCompletion(other, "AcceptanceB");
        });
    }

    private void sendCrossOwnerClientCompletion(org.bukkit.entity.Player player, String playerName) {
        try {
            player.getScheduler().run(this, task -> {
                if (!player.isOnline()) {
                    getLogger().severe("T21_CROSS_OWNER_FAIL reason=" + playerName + "-offline-before-result");
                    return;
                }
                player.sendMessage("T21_CROSS_OWNER_PASS player=" + playerName);
            }, () -> getLogger().severe(
                    "T21_CROSS_OWNER_FAIL reason=" + playerName + "-retired-before-result"));
        } catch (Throwable failure) {
            reportCrossOwnerFailure(playerName + "-completion", failure);
        }
    }

    private CompletableFuture<OwnerObservation> observeRegionOwnership(
            org.bukkit.entity.Player player, String playerName, RegionLocation expectedLocation,
            String otherPlayerName, RegionLocation otherLocation) {
        var result = new CompletableFuture<OwnerObservation>();
        player.getScheduler().run(this, task -> {
            try {
                var currentLocation = snapshot(player.getLocation());
                require(currentLocation.worldId().equals(expectedLocation.worldId())
                                && currentLocation.chunkX() == expectedLocation.chunkX()
                                && currentLocation.chunkZ() == expectedLocation.chunkZ(),
                        "player stayed in the expected region during owner check: " + playerName);
                boolean selfOwned = Bukkit.isOwnedByCurrentRegion(player);
                boolean otherOwned = Bukkit.isOwnedByCurrentRegion(otherLocation.world(),
                        otherLocation.chunkX(), otherLocation.chunkZ());
                getLogger().info("T21_REGION_OWNER player=" + playerName
                        + " world=" + currentLocation.worldName()
                        + " blockX=" + currentLocation.blockX() + " blockZ=" + currentLocation.blockZ()
                        + " chunkX=" + currentLocation.chunkX() + " chunkZ=" + currentLocation.chunkZ()
                        + " selfOwned=" + selfOwned
                        + " otherPlayer=" + otherPlayerName + " otherWorld=" + otherLocation.worldName()
                        + " otherBlockX=" + otherLocation.blockX() + " otherBlockZ=" + otherLocation.blockZ()
                        + " otherChunkX=" + otherLocation.chunkX() + " otherChunkZ=" + otherLocation.chunkZ()
                        + " otherOwned=" + otherOwned);
                result.complete(new OwnerObservation(currentLocation.worldId(), currentLocation.blockX(),
                        currentLocation.blockZ(), selfOwned, otherOwned));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, () -> result.completeExceptionally(new IllegalStateException(
                "player retired before owner check: " + playerName)));
        return result;
    }

    private void reportCrossOwnerFailure(String phase, Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, "T21_CROSS_OWNER_FAIL phase=" + phase, failure);
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
            UUID uuid = player.getUniqueId();
            int acceptedPending = readAcceptedWriteCount(context.writeQueue(), uuid);
            require(acceptedPending >= 2,
                    "two accepted writes remain incomplete at lifecycle action; observed=" + acceptedPending);
            getLogger().info("T15_PENDING_AT_BOUNDARY scenario=" + scenario + " uuid=" + uuid
                    + " pending=" + acceptedPending + " pending-state=accepted-not-completed");
            getLogger().info("T15_BOUNDARY_ACTION scenario=" + scenario
                    + " thread=" + Thread.currentThread().getName());
            switch (scenario.toLowerCase(java.util.Locale.ROOT)) {
                case "logout" -> {
                    getLogger().info("T15_LOGOUT_REQUEST uuid=" + uuid);
                    player.kickPlayer("T15 accepted-write logout scenario");
                }
                case "teleport" -> {
                    var destination = context.teleportDestination();
                    String sourceWorldName = player.getWorld().getName();
                    expectedTeleportWorlds.put(uuid, context.teleportWorldName());
                    var teleport = player.teleportAsync(destination,
                            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    teleport.whenComplete((success, failure) -> {
                        if (failure != null) {
                            getLogger().log(java.util.logging.Level.SEVERE,
                                    "T15_TELEPORT_FUTURE_FAILURE uuid=" + uuid, failure);
                        } else {
                            getLogger().info("T15_TELEPORT_FUTURE_RESULT uuid=" + uuid
                                    + " success=" + success);
                            if (Boolean.TRUE.equals(success)) {
                                player.getScheduler().run(this,
                                        entityTask -> recordTeleportReached(player,
                                                context.teleportWorldName(), "entity-scheduler-future"),
                                        () -> getLogger().severe("T15_TELEPORT_ENTITY_RETIRED uuid=" + uuid));
                            }
                        }
                    });
                    getLogger().info("T15_TELEPORT_REQUEST uuid=" + uuid
                            + " cause=PLUGIN destination=" + context.teleportWorldName() + ":0.5,80,0.5"
                            + " source=" + sourceWorldName
                            + " thread=" + Thread.currentThread().getName());
                }
                default -> throw new IllegalArgumentException(
                        "Expected lifecycle scenario logout or teleport");
            }
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "T15_LIFECYCLE_FAIL scenario=" + scenario, e);
        }
    }

    private void runGuardScenario(org.bukkit.entity.Player player) {
        if (!player.getName().equals("AcceptanceGuard")) {
            getLogger().severe("T21_GUARD_FAIL phase=player-name reason=unexpected-acceptance-player");
            return;
        }
        getServer().getGlobalRegionScheduler().run(this, globalTask -> {
            try {
                Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
                require(plugin != null && plugin.isEnabled(), "Syncmoney enabled for guard scenario");
                var breakerManager = plugin.getBreakerManager();
                require(breakerManager != null, "breaker manager initialized");
                PlayerTransactionGuard transactionGuard = breakerManager.getPlayerTransactionGuard();
                require(transactionGuard != null, "per-player guard initialized");
                var registration = getServer().getServicesManager().getRegistration(Economy.class);
                require(registration != null && registration.getPlugin() == plugin,
                        "Vault provider registered for guard scenario");
                Economy economy = registration.getProvider();
                player.getScheduler().run(this, ownerTask -> {
                    try {
                        require(Bukkit.isOwnedByCurrentRegion(player),
                                "guard scenario is running on AcceptanceGuard's entity owner");
                        UUID uuid = player.getUniqueId();
                        getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                            try {
                                BigDecimal balanceBefore = awaitAccountReady(plugin.getEconomyFacade(), uuid);
                                player.getScheduler().run(this,
                                        entityTask -> runGuardTransactions(
                                                plugin, transactionGuard, economy, player, uuid, balanceBefore),
                                        () -> getLogger().severe("T21_GUARD_FAIL phase=entity-retired reason=AcceptanceGuard"));
                            } catch (Throwable failure) {
                                reportGuardFailure("account-ready", failure);
                            }
                        });
                    } catch (Throwable failure) {
                        reportGuardFailure("owner-dispatch", failure);
                    }
                }, () -> getLogger().severe("T21_GUARD_FAIL phase=entity-retired reason=AcceptanceGuard"));
            } catch (Throwable failure) {
                reportGuardFailure("setup", failure);
            }
        });
    }

    private void runGuardTransactions(Syncmoney plugin, PlayerTransactionGuard transactionGuard, Economy economy,
            org.bukkit.entity.Player player, UUID uuid, BigDecimal balanceBefore) {
        runGuardTransactions(plugin, transactionGuard, economy, player, null, uuid, balanceBefore, null, null);
    }

    private void runGuardPairScenario(
            org.bukkit.entity.Player anchor, String otherPlayerName, boolean requireCircuitBreaker) {
        if (!anchor.getName().equals("AcceptanceGuardA") || !otherPlayerName.equals("AcceptanceGuardB")) {
            getLogger().severe("T21_GUARD_FAIL phase=player-pair reason=unexpected-acceptance-player-pair");
            return;
        }
        getServer().getGlobalRegionScheduler().run(this, globalTask -> {
            try {
                var other = getServer().getPlayerExact(otherPlayerName);
                require(other != null, "second guard Acceptance player is online");
                require(other != anchor, "guard participants are different entities");
                Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
                require(plugin != null && plugin.isEnabled(), "Syncmoney enabled for guard-pair scenario");
                var breakerManager = plugin.getBreakerManager();
                require(breakerManager != null, "breaker manager initialized for guard-pair scenario");
                PlayerTransactionGuard transactionGuard = breakerManager.getPlayerTransactionGuard();
                require(transactionGuard != null, "per-player guard initialized");
                EconomicCircuitBreaker circuitBreaker = breakerManager.getCircuitBreaker();
                if (requireCircuitBreaker) {
                    require(circuitBreaker != null, "global circuit breaker initialized");
                    require(!circuitBreaker.isLocked(), "global circuit breaker is unlocked before owner dispatch");
                }
                var registration = getServer().getServicesManager().getRegistration(Economy.class);
                require(registration != null && registration.getPlugin() == plugin,
                        "Vault provider registered for guard-pair scenario");
                Economy economy = registration.getProvider();
                World overworld = getServer().getWorlds().stream()
                        .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                        .findFirst().orElseThrow(() -> new IllegalStateException("Overworld unavailable"));
                GuardPairRun pairRun = new GuardPairRun(
                        plugin, transactionGuard, economy, anchor, other,
                        "AcceptanceGuardA", "AcceptanceGuardB",
                        requireCircuitBreaker ? circuitBreaker : null);
                moveAndCaptureRegionLocation(other, overworld, "AcceptanceGuardB",
                        new org.bukkit.Location(overworld, 16_384.5, 100, 16_384.5))
                        .whenComplete((location, moveFailure) -> {
                            if (moveFailure != null) {
                                reportGuardFailure("pair-separation", moveFailure);
                                return;
                            }
                            try {
                                if (activeGuardPairRun != null) {
                                    throw new IllegalStateException("another guard-pair run is awaiting a notice acknowledgement");
                                }
                                activeGuardPairRun = pairRun;
                                dispatchGuardParticipant(
                                        plugin, transactionGuard, economy, anchor, other, "AcceptanceGuardA",
                                        pairRun.circuitBreaker, pairRun).whenComplete((ignored, failure) -> {
                                    if (failure != null) reportGuardFailure("pair-transactions-A", failure);
                                });
                            } catch (Throwable failure) {
                                reportGuardFailure("pair-setup", failure);
                            }
                        });
            } catch (Throwable failure) {
                reportGuardFailure("pair-start", failure);
            }
        });
    }

    private void acknowledgeGuardPairNotice(org.bukkit.entity.Player sender, String recipientName) {
        GuardPairRun pairRun = activeGuardPairRun;
        int stage = pairRun == null ? 0 : pairRun.expectedNoticeAckStage;
        String expectedName = pairRun == null ? null : pairRun.expectedPlayerName(stage);
        UUID expectedUuid = pairRun == null ? null : pairRun.expectedPlayerUuid(stage);
        if (expectedName == null || expectedUuid == null || !sender.getName().equals(expectedName)
                || !recipientName.equals(expectedName) || !sender.getUniqueId().equals(expectedUuid)) {
            failGuardPairRun("notice-ack", "unexpected player or stage in rate-limit notice acknowledgement");
            return;
        }

        getLogger().info("T21_GUARD_NOTICE_ACK stage=" + stage + " player=" + expectedName
                + " uuid=" + expectedUuid + " recipient=" + sender.getName());
        if (stage == 1) {
            pairRun.expectedNoticeAckStage = 2;
            dispatchGuardParticipant(
                    pairRun.plugin, pairRun.transactionGuard, pairRun.economy,
                    pairRun.second, pairRun.first, pairRun.secondName, pairRun.circuitBreaker, pairRun)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) reportGuardFailure("pair-transactions-B", failure);
                    });
        } else {
            activeGuardPairRun = null;
            getLogger().info("T21_GUARD_PAIR_PASS players=AcceptanceGuardA,AcceptanceGuardB");
        }
    }

    private void failGuardPairRun(String phase, String reason) {
        activeGuardPairRun = null;
        getLogger().severe("T21_GUARD_FAIL phase=" + phase + " reason=" + reason);
    }

    private CompletableFuture<Void> dispatchGuardParticipant(
            Syncmoney plugin, PlayerTransactionGuard transactionGuard, Economy economy,
            org.bukkit.entity.Player player,
            org.bukkit.entity.Player peer, String expectedName, EconomicCircuitBreaker circuitBreaker,
            GuardPairRun pairRun) {
        var completed = new CompletableFuture<Void>();
        player.getScheduler().run(this, ownerTask -> {
            try {
                boolean selfOwned = Bukkit.isOwnedByCurrentRegion(player);
                boolean peerOwned = Bukkit.isOwnedByCurrentRegion(peer);
                require(player.getName().equals(expectedName), "guard owner callback is for " + expectedName);
                require(selfOwned && !peerOwned,
                        "guard callback is owned by " + expectedName + " and not by its peer");
                UUID uuid = player.getUniqueId();
                pairRun.rememberIdentity(expectedName, uuid);
                getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                    try {
                        BigDecimal balanceBefore = awaitAccountReady(plugin.getEconomyFacade(), uuid);
                        player.getScheduler().run(this,
                                entityTask -> runGuardTransactions(
                                        plugin, transactionGuard, economy, player, peer,
                                        uuid, balanceBefore, completed, circuitBreaker),
                                () -> completeGuardFailure(completed,
                                        "T21 guard player retired before transactions: " + expectedName));
                    } catch (Throwable failure) {
                        reportGuardFailure("account-ready-" + expectedName, failure);
                        completed.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                reportGuardFailure("owner-dispatch-" + expectedName, failure);
                completed.completeExceptionally(failure);
            }
        }, () -> completeGuardFailure(completed,
                "T21 guard player retired before owner callback: " + expectedName));
        return completed;
    }

    private void runGuardTransactions(Syncmoney plugin, PlayerTransactionGuard transactionGuard, Economy economy,
            org.bukkit.entity.Player player, org.bukkit.entity.Player peer,
            UUID uuid, BigDecimal balanceBefore, CompletableFuture<Void> completed) {
        runGuardTransactions(plugin, transactionGuard, economy, player, peer, uuid, balanceBefore, completed, null);
    }

    private void runGuardTransactions(Syncmoney plugin, PlayerTransactionGuard transactionGuard, Economy economy,
            org.bukkit.entity.Player player, org.bukkit.entity.Player peer,
            UUID uuid, BigDecimal balanceBefore, CompletableFuture<Void> completed,
            EconomicCircuitBreaker circuitBreaker) {
        try {
            boolean owner = Bukkit.isOwnedByCurrentRegion(player);
            require(owner, "guard transactions run on " + player.getName() + " current entity owner");
            boolean otherOwned = peer != null && Bukkit.isOwnedByCurrentRegion(peer);
            if (peer != null) {
                require(!otherOwned, "guard transactions run outside the peer entity owner");
                getLogger().info("T21_GUARD_OWNER player=" + player.getName() + " uuid=" + uuid
                        + " selfOwned=" + owner + " otherOwned=" + otherOwned + " scheduler=entity");
            } else {
                getLogger().info("T21_GUARD_OWNER player=" + player.getName() + " uuid=" + uuid
                        + " selfOwned=" + owner + " scheduler=entity");
            }
            require(transactionGuard != null,
                    "per-player guard remains active on the entity owner");
            if (circuitBreaker != null) {
                require(!circuitBreaker.isLocked(),
                        "global circuit breaker remains unlocked on " + player.getName() + " entity owner");
                getLogger().info("T21_GUARD_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=enabled player=" + player.getName());
            }
            require(balanceBefore.compareTo(BigDecimal.ZERO) == 0,
                    "fresh isolated " + player.getName() + " account starts at zero: " + balanceBefore);

            int accepted = 0;
            int rejected = 0;
            EconomyResponse rejectedResponse = null;
            StringBuilder outcomes = new StringBuilder(6);
            for (int index = 0; index < 6; index++) {
                EconomyResponse response = economy.depositPlayer(player, 1.00);
                if (response.type == EconomyResponse.ResponseType.SUCCESS) {
                    accepted++;
                    outcomes.append('S');
                } else if (response.type == EconomyResponse.ResponseType.FAILURE) {
                    rejected++;
                    rejectedResponse = response;
                    outcomes.append('F');
                } else {
                    outcomes.append('?');
                }
            }

            BigDecimal balanceAfter = BigDecimal.valueOf(economy.getBalance(player))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            require(accepted == 5, "per-second guard accepted exactly five transactions: " + accepted);
            require(rejected == 1 && rejectedResponse != null,
                    "per-second guard rejected exactly the sixth transaction: " + rejected);
            require(outcomes.toString().equals("SSSSSF"),
                    "per-second guard rejected the sixth transaction in order: " + outcomes);
            require(balanceAfter.compareTo(BigDecimal.valueOf(5)) == 0,
                    "rejected guard transaction did not change the balance: " + balanceAfter);
            getLogger().info("T21_GUARD_TRANSACTIONS player=" + player.getName() + " uuid=" + uuid
                    + " accepted=" + accepted + " rejected=" + rejected + " outcomes=" + outcomes
                    + " before=" + balanceBefore.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + " after=" + balanceAfter.toPlainString());
            if (completed != null) completed.complete(null);
        } catch (Throwable failure) {
            reportGuardFailure("transactions", failure);
            if (completed != null) completed.completeExceptionally(failure);
        }
    }

    private void completeGuardFailure(CompletableFuture<Void> completed, String message) {
        var failure = new IllegalStateException(message);
        getLogger().severe("T21_GUARD_FAIL " + message);
        completed.completeExceptionally(failure);
    }

    private void reportGuardFailure(String phase, Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, "T21_GUARD_FAIL phase=" + phase, failure);
    }

    private EconomyWriteQueue readEconomyWriteQueue(Syncmoney plugin) throws ReflectiveOperationException {
        // Keep queue inspection in this test-only fixture; do not widen the plugin API for acceptance metrics.
        var managerField = Syncmoney.class.getDeclaredField("economyServiceManager");
        managerField.setAccessible(true);
        Object manager = managerField.get(plugin);
        require(manager != null, "economy service manager available for queue observation");
        Method queueAccessor = manager.getClass().getMethod("getEconomyWriteQueue");
        Object queue = queueAccessor.invoke(manager);
        require(queue instanceof EconomyWriteQueue, "economy write queue available for pending observation");
        return (EconomyWriteQueue) queue;
    }

    private int readAcceptedWriteCount(EconomyWriteQueue queue, UUID uuid) throws ReflectiveOperationException {
        // Queue size drops on dequeue, so it omits in-flight writes. Accepted-write ownership is
        // retained until the writer completes the persistence/reconciliation callback.
        var countsField = EconomyWriteQueue.class.getDeclaredField("acceptedWriteCounts");
        countsField.setAccessible(true);
        Object counts = countsField.get(queue);
        require(counts instanceof java.util.concurrent.ConcurrentMap,
                "accepted-write ownership map available for lifecycle observation");
        Object count = ((java.util.concurrent.ConcurrentMap<?, ?>) counts).get(uuid);
        if (count == null) return 0;
        require(count instanceof java.util.concurrent.atomic.AtomicInteger,
                "accepted-write ownership count available for lifecycle observation");
        return ((java.util.concurrent.atomic.AtomicInteger) count).get();
    }

    private record RegionLocation(
            World world, UUID worldId, String worldName, int blockX, int blockZ, int chunkX, int chunkZ) {}

    private record RegionPair(RegionLocation anchor, RegionLocation other) {}

    private record OwnerObservation(UUID worldId, int blockX, int blockZ, boolean selfOwned, boolean otherOwned) {}

    private record LifecycleContext(
            Economy economy,
            EconomyWriteQueue writeQueue,
            org.bukkit.Location teleportDestination,
            String teleportWorldName) {
    }

    private static final class GuardPairRun {
        private final Syncmoney plugin;
        private final PlayerTransactionGuard transactionGuard;
        private final Economy economy;
        private final org.bukkit.entity.Player first;
        private final org.bukkit.entity.Player second;
        private final String firstName;
        private final String secondName;
        private final EconomicCircuitBreaker circuitBreaker;
        private volatile int expectedNoticeAckStage = 1;
        private volatile UUID firstUuid;
        private volatile UUID secondUuid;

        private GuardPairRun(
                Syncmoney plugin,
                PlayerTransactionGuard transactionGuard,
                Economy economy,
                org.bukkit.entity.Player first,
                org.bukkit.entity.Player second,
                String firstName,
                String secondName,
                EconomicCircuitBreaker circuitBreaker) {
            this.plugin = plugin;
            this.transactionGuard = transactionGuard;
            this.economy = economy;
            this.first = first;
            this.second = second;
            this.firstName = firstName;
            this.secondName = secondName;
            this.circuitBreaker = circuitBreaker;
        }

        private void rememberIdentity(String playerName, UUID uuid) {
            if (firstName.equals(playerName)) firstUuid = uuid;
            else if (secondName.equals(playerName)) secondUuid = uuid;
            else throw new IllegalArgumentException("Unexpected guard-pair identity: " + playerName);
        }

        private String expectedPlayerName(int stage) {
            return stage == 1 ? firstName : stage == 2 ? secondName : null;
        }

        private UUID expectedPlayerUuid(int stage) {
            return stage == 1 ? firstUuid : stage == 2 ? secondUuid : null;
        }
    }

    private void runVersionChecks(String command) {
        try {
            Syncmoney plugin = (Syncmoney) getServer().getPluginManager().getPlugin("Syncmoney");
            require(plugin != null && plugin.isEnabled(), "plugin enabled");
            verifyPluginVersion(plugin);
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
            verifyPluginVersion(plugin);
            var facade = plugin.getEconomyFacade();
            if (args.length > 0 && args[0].equalsIgnoreCase("m10")) {
                runM10ReconciliationProbe(plugin, args);
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("legacy-sqlite")) {
                runLegacySqliteCheck(plugin, facade, args);
                return;
            }
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
            boolean rollbackRead = args.length > 0 && args[0].equals("rollback-read");
            boolean sharedStateRead = args.length > 0 && args[0].equals("state-read");
            if (args.length > 0 && (args[0].equals("read") || rollbackRead || sharedStateRead)) {
                BigDecimal expectedA = args.length > 1 ? new BigDecimal(args[1]) : new BigDecimal("1000");
                BigDecimal expectedB = args.length > 2 ? new BigDecimal(args[2]) : new BigDecimal("520");
                double coldVaultA = vault.getBalance(offlineA);
                BigDecimal coldVaultUnlockedB = vaultUnlocked.balance(b);
                if (!rollbackRead && !sharedStateRead) {
                    require(Double.isNaN(coldVaultA), "cold Vault account A is unavailable");
                    require(coldVaultUnlockedB == null, "cold VaultUnlocked account B is unavailable");
                }
                awaitAccountReady(facade, a);
                awaitAccountReady(facade, b);
                BigDecimal persistedA = facade.getBalance(a);
                BigDecimal persistedB = facade.getBalance(b);
                BigDecimal warmVaultUnlockedB = vaultUnlocked.balance(b);
                require(persistedA.compareTo(expectedA) == 0, "persisted account A expected=" + expectedA);
                require(persistedB.compareTo(expectedB) == 0, "persisted account B expected=" + expectedB);
                require(expectedB.compareTo(warmVaultUnlockedB) == 0,
                        "VaultUnlocked account B after background load");
                if (rollbackRead || sharedStateRead) {
                    require(Double.compare(vault.getBalance(offlineA), expectedA.doubleValue()) == 0,
                            "warm Vault account A expected=" + expectedA);
                    if (rollbackRead) {
                        getLogger().info("T21_ROLLBACK_READ_PASS expected-a=" + expectedA
                                + " expected-b=" + expectedB + " actual-a=" + persistedA + " actual-b=" + persistedB);
                    } else {
                        getLogger().info("T21_READ_PASS expected-a=" + expectedA
                                + " expected-b=" + expectedB + " actual-a=" + persistedA + " actual-b=" + persistedB);
                    }
                    return;
                }
                getLogger().info("ACCEPTANCE READ cold-vault-a=" + coldVaultA
                        + " cold-vault-unlocked-b=" + coldVaultUnlockedB
                        + " a=" + persistedA + " b=" + persistedB
                        + " vault-unlocked-b=" + warmVaultUnlockedB);
                getLogger().info("T21_READ_PASS expected-a=" + expectedA + " expected-b=" + expectedB);
                return;
            }
            if (args.length > 0 && args[0].equals("write")) {
                require(args.length == 3, "usage: smaccept write <balance-a> <balance-b>");
                BigDecimal expectedA = new BigDecimal(args[1]);
                BigDecimal expectedB = new BigDecimal(args[2]);
                require(expectedA.signum() >= 0 && expectedB.signum() >= 0, "nonnegative fixture balances");
                facade.unlockPlayer(a);
                facade.unlockPlayer(b);
                awaitAccountReady(facade, a);
                awaitAccountReady(facade, b);
                VaultMutationReceipt receiptA = mutateThroughVault(vault, offlineA, a, expectedA, facade);
                VaultMutationReceipt receiptB = mutateThroughVault(vault, offlineB, b, expectedB, facade);
                require(facade.getBalance(a).compareTo(expectedA) == 0, "written account A");
                require(facade.getBalance(b).compareTo(expectedB) == 0, "written account B");
                getLogger().info("T21_WRITE_PASS expected-a=" + formatT21Amount(expectedA)
                        + " expected-b=" + formatT21Amount(expectedB)
                        + " receipt-a=" + receiptA.marker() + " receipt-b=" + receiptB.marker());
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
            require(facade.getBalanceForPlaceholder(a).compareTo(new BigDecimal("1000")) == 0, "cached placeholder");
            getLogger().info("ACCEPTANCE PASS platform=" + getServer().getName()
                    + " server=" + getServer().getVersion() + " mode=" + plugin.getSyncmoneyConfig().getEconomyMode()
                    + " java=" + Runtime.version() + " total=" + before);
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "ACCEPTANCE FAIL", e);
        }
    }

    /** Runtime-only M10 probe: mutate one disposable account and inspect memory without loading authority. */
    private void runM10ReconciliationProbe(Syncmoney plugin, String[] args) {
        try {
            require(args.length >= 3, "usage: smaccept m10 <snapshot|deposit|await> <player|uuid> [arguments]");
            String action = args[1].toLowerCase(java.util.Locale.ROOT);
            if (action.equals("ready")) {
                require(args.length == 4, "usage: smaccept m10 ready <player> <timeout-seconds>");
                String playerName = args[2];
                int timeoutSeconds = Integer.parseInt(args[3]);
                require(timeoutSeconds > 0 && timeoutSeconds <= 120, "bounded M10 player-load timeout");
                dispatchM10OnlinePlayer(playerName, "ready", uuid -> {
                    getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
                        try {
                            while (System.nanoTime() < deadline) {
                                var state = plugin.getEconomyFacade().getMemoryState(uuid);
                                if (state != null) {
                                    getLogger().info("T21_M10_READY_PASS name=" + playerName + " uuid=" + uuid
                                            + " balance=" + formatT21Amount(state.balance()) + " version=" + state.version());
                                    return;
                                }
                                Thread.sleep(100);
                            }
                            throw new IllegalStateException("player account load did not populate memory before timeout");
                        } catch (Throwable e) {
                            getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=ready", e);
                        }
                    });
                });
                return;
            }

            if (action.equals("snapshot")) {
                String playerName = args[2];
                String requestId = args.length > 3 ? args[3] : "unspecified";
                dispatchM10OnlinePlayer(playerName, "snapshot", uuid -> {
                    getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                        try {
                            var state = plugin.getEconomyFacade().getMemoryState(uuid);
                            require(state != null, "memory state exists for online player " + playerName);
                            getLogger().info("T21_M10_MEMORY request=" + requestId + " name=" + playerName + " uuid=" + uuid
                                    + " balance=" + formatT21Amount(state.balance()) + " version=" + state.version());
                        } catch (Throwable e) {
                            getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=snapshot", e);
                        }
                    });
                });
                return;
            }

            if (action.equals("warm")) {
                require(args.length == 4, "usage: smaccept m10 warm <uuid> <timeout-seconds>");
                UUID uuid = UUID.fromString(args[2]);
                int timeoutSeconds = Integer.parseInt(args[3]);
                require(timeoutSeconds > 0 && timeoutSeconds <= 120, "bounded M10 account-warm timeout");
                getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                    try {
                        BigDecimal balance = awaitAccountReady(plugin.getEconomyFacade(), uuid,
                                java.util.concurrent.TimeUnit.SECONDS.toMillis(timeoutSeconds));
                        var state = plugin.getEconomyFacade().getMemoryState(uuid);
                        require(state != null && state.balance().compareTo(balance) == 0,
                                "warmed M10 account state is available");
                        getLogger().info("T21_M10_WARM_PASS uuid=" + uuid + " balance=" + formatT21Amount(balance)
                                + " version=" + state.version());
                    } catch (Throwable e) {
                        getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=warm", e);
                    }
                });
                return;
            }

            if (action.equals("deposit")) {
                require(args.length == 5, "usage: smaccept m10 deposit <uuid> <amount> <request-id>");
                UUID uuid = UUID.fromString(args[2]);
                BigDecimal amount = new BigDecimal(args[3]);
                String requestId = args[4];
                require(requestId.matches("[0-9a-fA-F-]{8,64}"), "M10 deposit request ID format");
                require(amount.signum() > 0, "positive M10 deposit amount");
                getServer().getGlobalRegionScheduler().run(this, globalTask -> {
                    try {
                        var registration = getServer().getServicesManager().getRegistration(Economy.class);
                        require(registration != null && registration.getPlugin() == plugin, "Vault provider registration");
                        Economy vault = registration.getProvider();
                        OfflinePlayer offlinePlayer = getServer().getOfflinePlayer(uuid);
                        // Vault's public Economy contract is synchronous; keep this probe on
                        // Paper's global scheduler while the product write path stays memory-first.
                        runM10Deposit(plugin, vault, offlinePlayer, uuid, amount, requestId);
                    } catch (Throwable e) {
                        getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=deposit-dispatch", e);
                    }
                });
                return;
            }

            if (action.equals("await")) {
                require(args.length == 6, "usage: smaccept m10 await <player> <balance> <version> <timeout-seconds>");
                String playerName = args[2];
                BigDecimal expectedBalance = new BigDecimal(args[3]);
                long expectedVersion = Long.parseLong(args[4]);
                int timeoutSeconds = Integer.parseInt(args[5]);
                require(timeoutSeconds > 0 && timeoutSeconds <= 900, "bounded M10 await timeout");
                dispatchM10OnlinePlayer(playerName, "await", uuid -> {
                    getServer().getAsyncScheduler().runNow(this, asyncTask -> {
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
                        try {
                            while (System.nanoTime() < deadline) {
                                var state = plugin.getEconomyFacade().getMemoryState(uuid);
                                if (state != null && state.version() == expectedVersion
                                        && state.balance().compareTo(expectedBalance) == 0) {
                                    getLogger().info("T21_M10_RECONCILE_PASS name=" + playerName + " uuid=" + uuid
                                            + " balance=" + formatT21Amount(state.balance())
                                            + " version=" + state.version());
                                    return;
                                }
                                Thread.sleep(500);
                            }
                            var state = plugin.getEconomyFacade().getMemoryState(uuid);
                            getLogger().severe("T21_M10_FAIL action=await expected=" + formatT21Amount(expectedBalance)
                                    + "/v" + expectedVersion + " actual="
                                    + (state == null ? "missing" : formatT21Amount(state.balance()) + "/v" + state.version()));
                        } catch (Throwable e) {
                            getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=await", e);
                        }
                    });
                });
                return;
            }

            throw new IllegalArgumentException("Unknown M10 probe action: " + action);
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=dispatch", e);
        }
    }

    /** Resolve online-player identity on the entity owner before handing immutable data to async polling. */
    private void dispatchM10OnlinePlayer(String playerName, String action,
                                         java.util.function.Consumer<UUID> callback) {
        getServer().getGlobalRegionScheduler().run(this, globalTask -> {
            var player = getServer().getPlayerExact(playerName);
            if (player == null) {
                getLogger().severe("T21_M10_FAIL action=" + action
                        + " reason=acceptance-player-not-online name=" + playerName);
                return;
            }
            player.getScheduler().run(this, entityTask -> {
                if (!player.isOnline()) {
                    getLogger().severe("T21_M10_FAIL action=" + action
                            + " reason=acceptance-player-not-online name=" + playerName);
                    return;
                }
                try {
                    callback.accept(player.getUniqueId());
                } catch (Throwable e) {
                    getLogger().log(java.util.logging.Level.SEVERE,
                            "T21_M10_FAIL action=" + action + " reason=entity-callback", e);
                }
            }, () -> getLogger().severe("T21_M10_FAIL action=" + action
                    + " reason=entity-retired name=" + playerName));
        });
    }

    private void runM10Deposit(Syncmoney plugin, Economy vault, OfflinePlayer player, UUID uuid,
                               BigDecimal amount, String requestId) {
        try {
            var response = vault.depositPlayer(player, amount.doubleValue());
            require(response.transactionSuccess(), "Vault deposit accepted: " + response.errorMessage);
            BigDecimal actualBalance = BigDecimal.valueOf(response.balance);
            var state = plugin.getEconomyFacade().getMemoryState(uuid);
            require(state != null && state.balance().compareTo(actualBalance) == 0,
                    "accepted Vault deposit is reflected in local memory");
            getLogger().info("T21_M10_DEPOSIT_PASS request=" + requestId + " uuid=" + uuid + " amount=" + formatT21Amount(amount)
                    + " balance=" + formatT21Amount(actualBalance) + " version=" + state.version());
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "T21_M10_FAIL action=deposit", e);
        }
    }

    private VaultMutationReceipt mutateThroughVault(
            Economy vault,
            OfflinePlayer player,
            UUID uuid,
            BigDecimal target,
            noietime.syncmoney.economy.EconomyFacade facade) {
        BigDecimal before = facade.getBalance(uuid);
        BigDecimal delta = target.subtract(before);
        require(delta.signum() != 0, "Vault mutation must change the disposable account balance " + uuid);
        BigDecimal amount = delta.abs();
        String operation = delta.signum() > 0 ? "DEPOSIT" : "WITHDRAW";
        var response = delta.signum() > 0
                ? vault.depositPlayer(player, amount.doubleValue())
                : vault.withdrawPlayer(player, amount.doubleValue());
        require(response.transactionSuccess(), "Vault " + operation + " rejected: " + response.errorMessage);

        BigDecimal receiptAmount = BigDecimal.valueOf(response.amount);
        BigDecimal receiptBalance = BigDecimal.valueOf(response.balance);
        require(receiptAmount.compareTo(amount) == 0,
                "Vault " + operation + " receipt amount expected=" + amount + " actual=" + receiptAmount);
        require(receiptBalance.compareTo(target) == 0,
                "Vault " + operation + " receipt balance expected=" + target + " actual=" + receiptBalance);
        require(facade.getBalance(uuid).compareTo(target) == 0,
                "Vault " + operation + " did not update the accepted in-memory balance");
        return new VaultMutationReceipt(response.type.name(), operation, receiptAmount, receiptBalance);
    }

    private static String formatT21Amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private record VaultMutationReceipt(String type, String operation, BigDecimal amount, BigDecimal balance) {
        private String marker() {
            return type + ":" + operation + ":" + formatT21Amount(amount) + ":" + formatT21Amount(balance);
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException(check);
    }

    private BigDecimal awaitAccountReady(noietime.syncmoney.economy.EconomyFacade facade, UUID uuid)
            throws InterruptedException {
        return awaitAccountReady(facade, uuid, ACCOUNT_LOAD_TIMEOUT_MILLIS);
    }

    private BigDecimal awaitAccountReady(noietime.syncmoney.economy.EconomyFacade facade, UUID uuid,
                                         long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
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

    private void runLegacySqliteCheck(
            Syncmoney plugin,
            noietime.syncmoney.economy.EconomyFacade facade,
            String[] args) throws InterruptedException {
        String operation = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "read";
        plugin.getNameResolver().cacheName("fixture-a", LEGACY_SQLITE_ACCOUNT_A);
        plugin.getNameResolver().cacheName("fixture-b", LEGACY_SQLITE_ACCOUNT_B);

        if (operation.equals("write")) {
            require(args.length == 4, "usage: smaccept legacy-sqlite write <balance-a> <balance-b>");
            BigDecimal expectedA = new BigDecimal(args[2]);
            BigDecimal expectedB = new BigDecimal(args[3]);
            require(expectedA.signum() >= 0 && expectedB.signum() >= 0, "nonnegative legacy fixture balances");
            awaitAccountReady(facade, LEGACY_SQLITE_ACCOUNT_A);
            awaitAccountReady(facade, LEGACY_SQLITE_ACCOUNT_B);
            var source = EconomyEvent.EventSource.COMMAND_ADMIN;
            facade.setBalance(LEGACY_SQLITE_ACCOUNT_A, expectedA, source);
            facade.setBalance(LEGACY_SQLITE_ACCOUNT_B, expectedB, source);
            require(facade.getBalance(LEGACY_SQLITE_ACCOUNT_A).compareTo(expectedA) == 0,
                    "legacy fixture account A write");
            require(facade.getBalance(LEGACY_SQLITE_ACCOUNT_B).compareTo(expectedB) == 0,
                    "legacy fixture account B write");
            getLogger().info("T21_LEGACY_SQLITE_PASS operation=write expected-a=" + expectedA
                    + " expected-b=" + expectedB);
            return;
        }

        require(operation.equals("read"), "usage: smaccept legacy-sqlite [read [balance-a balance-b]|write balance-a balance-b]");
        BigDecimal expectedA = args.length > 2 ? new BigDecimal(args[2]) : new BigDecimal("100.00");
        BigDecimal expectedB = args.length > 3 ? new BigDecimal(args[3]) : new BigDecimal("0.00");
        awaitAccountReady(facade, LEGACY_SQLITE_ACCOUNT_A);
        awaitAccountReady(facade, LEGACY_SQLITE_ACCOUNT_B);
        BigDecimal observedA = facade.getBalance(LEGACY_SQLITE_ACCOUNT_A);
        BigDecimal observedB = facade.getBalance(LEGACY_SQLITE_ACCOUNT_B);
        require(observedA.compareTo(expectedA) == 0,
                "legacy SQLite account A expected=" + expectedA + " actual=" + observedA);
        require(observedB.compareTo(expectedB) == 0,
                "legacy SQLite account B expected=" + expectedB + " actual=" + observedB);
        getLogger().info("T21_LEGACY_SQLITE_PASS operation=read expected-a=" + expectedA
                + " expected-b=" + expectedB + " actual-a=" + observedA + " actual-b=" + observedB
                + " sqlite-path=" + plugin.getConfig().getString("economy.local.sqlite-path"));
    }

    private void verifyPluginVersion(Syncmoney plugin) {
        String expectedVersion = expectedVersion();
        String actualVersion = plugin.getDescription().getVersion();
        require(!actualVersion.isBlank(), "plugin version is present");
        if (expectedVersion != null) {
            require(actualVersion.equals(expectedVersion), "plugin version expected=" + expectedVersion
                    + " actual=" + actualVersion);
        }
        getLogger().info("T21_VERSION_PASS expected="
                + (expectedVersion == null ? actualVersion : expectedVersion) + " actual=" + actualVersion);
    }

    private static String expectedVersion() {
        String configured = System.getProperty("syncmoney.acceptance.version");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SYNCMONEY_EXPECTED_VERSION");
        }
        return configured == null || configured.isBlank() ? null : configured.trim();
    }
}
