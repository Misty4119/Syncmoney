package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /syncmoney command unified router.
 * Dispatches subcommands to corresponding handlers.
 *
 * Supports two-level subcommand structure:
 * - /syncmoney admin give <player> <amount>
 * - /syncmoney breaker status
 */
public final class SyncmoneyCommandRouter implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;
    private final Map<String, CommandExecutor> subCommands = new LinkedHashMap<>();
    private final Map<String, TabCompleter> subTabCompleters = new LinkedHashMap<>();
    private CommandExecutor defaultHandler;

    /**
     * Constructor.
     * @param plugin Syncmoney plugin instance, used to retrieve messages
     */
    public SyncmoneyCommandRouter(Syncmoney plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a subcommand.
     * @param subCommand Subcommand name (lowercase)
     * @param executor Executor
     */
    public void register(String subCommand, CommandExecutor executor) {
        subCommands.put(subCommand.toLowerCase(), executor);
        if (executor instanceof TabCompleter tc) {
            subTabCompleters.put(subCommand.toLowerCase(), tc);
        }
    }

    /**
     * Registers multiple subcommands mapping to the same executor.
     */
    public void register(List<String> subCommandNames, CommandExecutor executor) {
        for (String name : subCommandNames) {
            register(name, executor);
        }
    }

    /**
     * Sets default handler (invoked when no subcommand or unknown subcommand).
     */
    public void setDefaultHandler(CommandExecutor handler) {
        this.defaultHandler = handler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (defaultHandler != null) {
                return defaultHandler.onCommand(sender, command, label, args);
            }
            sendCommandHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        CommandExecutor executor = subCommands.get(sub);
        if (executor != null) {
            String[] subArgs = new String[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                subArgs[i - 1] = args[i];
            }
            return executor.onCommand(sender, command, label, subArgs);
        }

        if (defaultHandler != null) {
            return defaultHandler.onCommand(sender, command, label, args);
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("router.unknown-command")
                .replace("{subcommand}", sub));
        sendCommandHelp(sender, label);
        return true;
    }

    /**
     * Sends command help message.
     */
    private void sendCommandHelp(CommandSender sender, String label) {
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.admin"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.breaker"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.audit"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.econstats"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.monitor"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.shadow"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.reload"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.migrate"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.web"));
        MessageHelper.sendMessage(sender, plugin.getMessage("router.help.test"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return subCommands.keySet().stream()
                    .filter(key -> hasSubcommandPermission(sender, key))
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (args.length == 1) {
            return subCommands.keySet().stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .filter(key -> hasSubcommandPermission(sender, key))
                    .sorted()
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();
        TabCompleter tc = subTabCompleters.get(sub);
        if (tc != null) {
            String[] subArgs = new String[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                subArgs[i - 1] = args[i];
            }
            return tc.onTabComplete(sender, command, alias, subArgs);
        }

        return Collections.emptyList();
    }

    /**
     * [CMD-M04 FIX] Check if sender has permission for subcommand.
     */
    private boolean hasSubcommandPermission(CommandSender sender, String subcommand) {
        return switch (subcommand) {
            case "admin" -> sender.hasPermission("syncmoney.admin");
            case "breaker", "monitor", "econstats", "audit" -> sender.hasPermission("syncmoney.admin");
            case "migrate", "shadow", "web" -> sender.hasPermission("syncmoney.admin");
            case "test" -> sender.hasPermission("syncmoney.admin.test");
            case "reload" -> sender.hasPermission("syncmoney.admin");
            case "sync-balance" -> sender.hasPermission("syncmoney.admin");
            default -> true;
        };
    }
}
