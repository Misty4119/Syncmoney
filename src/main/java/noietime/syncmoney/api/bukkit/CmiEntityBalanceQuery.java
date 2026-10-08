package noietime.syncmoney.api.bukkit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.economy.CMIEconomyHandler;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Routes a cold CMI balance read through the online player's entity owner. */
final class CmiEntityBalanceQuery {

    private final Plugin plugin;
    private final CMIEconomyHandler cmiHandler;
    private final BooleanSupplier providerActive;

    CmiEntityBalanceQuery(Plugin plugin, CMIEconomyHandler cmiHandler) {
        this(plugin, cmiHandler, () -> true);
    }

    CmiEntityBalanceQuery(Plugin plugin, CMIEconomyHandler cmiHandler, BooleanSupplier providerActive) {
        this.plugin = plugin;
        this.cmiHandler = cmiHandler;
        this.providerActive = providerActive;
    }

    CompletionStage<Optional<BigDecimal>> load(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Account UUID must not be null"));
        }

        CompletableFuture<Optional<BigDecimal>> result = new CompletableFuture<>();
        AtomicBoolean lookupStarted = new AtomicBoolean();
        try {
            Server server = plugin.getServer();
            server.getGlobalRegionScheduler().run(plugin, task -> {
                if (!lookupStarted.compareAndSet(false, true) || !isActive(result)) {
                    return;
                }
                try {
                    Player player = server.getPlayer(uuid);
                    if (player == null) {
                        throw unknownBalance(uuid, "player is not online on this server");
                    }

                    ScheduledTask scheduled = player.getScheduler().run(plugin, entityTask -> {
                        if (!isActive(result)) {
                            return;
                        }
                        try {
                            Optional<BigDecimal> balance = cmiHandler.getExistingBalanceForApi(uuid);
                            if (balance == null || balance.isEmpty()) {
                                throw unknownBalance(uuid, "CMI has no loaded user for the online player");
                            }
                            result.complete(balance);
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    }, () -> result.completeExceptionally(
                            unknownBalance(uuid, "player entity retired before the CMI read")));
                    if (scheduled == null) {
                        result.completeExceptionally(unknownBalance(uuid, "player entity scheduler is retired"));
                    }
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result.minimalCompletionStage();
    }

    private boolean isActive(CompletableFuture<?> result) {
        if (result.isDone()) {
            return false;
        }
        if (!providerActive.getAsBoolean()) {
            result.completeExceptionally(new IllegalStateException("Syncmoney API provider is shutting down"));
            return false;
        }
        return true;
    }

    private static IllegalStateException unknownBalance(UUID uuid, String reason) {
        return new IllegalStateException("CMI balance is unknown for " + uuid + ": " + reason);
    }
}
