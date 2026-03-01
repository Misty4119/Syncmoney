package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reload command handler.
 * Handles /syncmoney reload command.
 *
 * Usage:
 * /syncmoney reload - Reload all configurations
 * /syncmoney reload config - Reload config.yml
 * /syncmoney reload messages - Reload messages.yml
 */
public final class ReloadCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;

    public ReloadCommand(Syncmoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.reload")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            reloadAll(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload", "all" -> reloadAll(sender);
            case "config" -> reloadConfig(sender);
            case "messages" -> reloadMessages(sender);
            case "permissions" -> reloadPermissions(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    /**
     * Reloads all configurations.
     */
    private void reloadAll(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.reloading-all"));

        boolean configOk = reloadConfig(sender, false);
        boolean messagesOk = reloadMessages(sender, false);

        if (configOk && messagesOk) {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.success-all"));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.fail-all"));
        }
    }

    /**
     * Reloads config.yml.
     */
    private void reloadConfig(CommandSender sender) {
        if (reloadConfig(sender, true)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.success-config"));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.fail-config"));
        }
    }

    private boolean reloadConfig(CommandSender sender, boolean notify) {
        try {
            plugin.reloadConfig();

            plugin.reloadSyncmoneyConfig();

            plugin.reloadPermissionService();

            plugin.reloadEconomyFacade();

            return true;
        } catch (Exception e) {
            if (notify) {
                MessageHelper.sendMessage(sender, plugin.getMessage("reload.fail-config-error")
                        .replace("{error}", e.getMessage()));
            }
            plugin.getLogger().severe("Failed to reload config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reloads messages.yml.
     */
    private void reloadMessages(CommandSender sender) {
        if (reloadMessages(sender, true)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.success-messages"));
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.fail-messages"));
        }
    }

    private boolean reloadMessages(CommandSender sender, boolean notify) {
        try {
            boolean success = plugin.reloadMessagesConfig();

            if (!success) {
                throw new Exception("Failed to reload messages.yml");
            }

            return true;
        } catch (Exception e) {
            if (notify) {
                MessageHelper.sendMessage(sender, plugin.getMessage("reload.fail-messages-error")
                        .replace("{error}", e.getMessage()));
            }
            plugin.getLogger().severe("Failed to reload messages: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reloads permission configuration.
     */
    private void reloadPermissions(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.reloading-permissions"));

        try {
            plugin.reloadPermissionService();

            MessageHelper.sendMessage(sender, plugin.getMessage("reload.success-permissions"));
        } catch (Exception e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("reload.fail-permissions")
                    .replace("{error}", e.getMessage()));
            plugin.getLogger().severe("Failed to reload permissions: " + e.getMessage());
        }
    }

    /**
     * Sends usage instructions.
     */
    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.usage.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.usage.all"));
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.usage.config"));
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.usage.messages"));
        MessageHelper.sendMessage(sender, plugin.getMessage("reload.usage.permissions"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin.reload")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("all", "config", "messages", "permissions")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
