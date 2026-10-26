package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.economy.EconomyEventConsumer;
import noietime.syncmoney.economy.EventConsumerManager;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/** Admin-only inspection and exact-receipt reconciliation for queued economy writes. */
public final class RecoveryCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "syncmoney.admin.recovery";

    private final Syncmoney plugin;
    private final EventConsumerManager eventConsumerManager;

    public RecoveryCommand(Syncmoney plugin, EventConsumerManager eventConsumerManager) {
        this.plugin = plugin;
        this.eventConsumerManager = eventConsumerManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION) && !sender.hasPermission("syncmoney.admin")) {
            send(sender, "You do not have permission to inspect or reconcile queued recovery records.");
            return true;
        }
        if (args.length == 0) {
            send(sender, "Usage: /syncmoney recovery <list|inspect <id>|reconcile <id>>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "list" -> list(sender);
            case "inspect" -> inspect(sender, args);
            case "reconcile" -> reconcile(sender, args);
            default -> {
                send(sender, "Usage: /syncmoney recovery <list|inspect <id>|reconcile <id>>");
                yield true;
            }
        };
    }

    private boolean list(CommandSender sender) {
        EconomyEventConsumer consumer = currentConsumer();
        if (consumer == null) {
            send(sender, "Queued recovery is unavailable; no recovery consumer is initialized.");
            return true;
        }
        List<EconomyEventConsumer.RecoveryView> recoveries = consumer.recoveryViews();
        if (consumer.isRecoveryBlocked()) {
            send(sender, "Queued recovery spool is blocked; preserve it and inspect the server log.");
        }
        if (recoveries.isEmpty()) {
            if (!consumer.isRecoveryBlocked()) send(sender, "No pending queued recovery records.");
            return true;
        }
        send(sender, "Pending queued recovery records: " + recoveries.size());
        recoveries.stream().limit(50).forEach(recovery -> send(sender,
                recovery.operationId() + " account=" + recovery.playerUuid()
                        + (recovery.partnerUuid() == null ? "" : " partner=" + recovery.partnerUuid())
                        + " state=" + recovery.stage()));
        if (recoveries.size() > 50) {
            send(sender, "Showing first 50; use inspect with an operation ID for details.");
        }
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "Usage: /syncmoney recovery inspect <id>");
            return true;
        }
        String id = args[1];
        EconomyEventConsumer consumer = currentConsumer();
        EconomyEventConsumer.RecoveryView queued = consumer == null ? null : consumer.inspectRecovery(id);
        if (queued == null && consumer != null && consumer.isRecoveryBlocked()) {
            send(sender, "Recovery state is incomplete because the queued recovery spool is blocked; preserve it and inspect the server log.");
        } else if (queued == null) {
            send(sender, "No pending queued recovery record matches that ID.");
        } else {
            send(sender, "queued " + queued.operationId() + " account=" + queued.playerUuid()
                    + " partner=" + queued.partnerUuid() + " state=" + queued.stage()
                    + " retries=" + queued.retryCount() + " since=" + queued.firstFailureTime());
        }
        return true;
    }

    private boolean reconcile(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "Usage: /syncmoney recovery reconcile <id>");
            return true;
        }
        String id = args[1];
        EconomyEventConsumer consumer = currentConsumer();
        if (consumer == null || consumer.inspectRecovery(id) == null) {
            if (consumer != null && consumer.isRecoveryBlocked()) {
                send(sender, "Recovery state is incomplete because the queued recovery spool is blocked; no reconciliation was started.");
            } else {
                send(sender, "No pending queued recovery record matches that ID.");
            }
            return true;
        }
        MessageHelper.SenderContext replyContext = MessageHelper.captureSender(sender);
        plugin.getServer().getAsyncScheduler().runNow(plugin, task ->
                replyContext.sendMessage(plugin, consumer.reconcileRecovery(id)));
        send(sender, "Checking the exact Redis operation receipt for queued transfer " + id + ".");
        return true;
    }

    private EconomyEventConsumer currentConsumer() {
        return eventConsumerManager == null ? null : eventConsumerManager.getEconomyEventConsumer();
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) && !sender.hasPermission("syncmoney.admin")) return List.of();
        if (args.length == 1) return List.of("list", "inspect", "reconcile").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && (args[0].equalsIgnoreCase("inspect")
                || args[0].equalsIgnoreCase("reconcile"))) {
            EconomyEventConsumer consumer = currentConsumer();
            if (consumer == null) return List.of();
            return consumer.recoveryViews().stream()
                    .map(EconomyEventConsumer.RecoveryView::operationId)
                    .filter(id -> id.startsWith(args[1])).limit(50).toList();
        }
        return List.of();
    }
}
