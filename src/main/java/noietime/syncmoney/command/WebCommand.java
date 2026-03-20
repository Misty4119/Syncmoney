package noietime.syncmoney.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.util.Constants;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.web.builder.WebBuilder;
import noietime.syncmoney.web.builder.WebDownloader;
import noietime.syncmoney.web.builder.WebVersionChecker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /syncmoney web command handler.
 * Manages web frontend download, build, reload, and status.
 *
 * Directory layout:
 *   plugins/Syncmoney/syncmoney-web/              — source root (package.json, src/, …)
 *   plugins/Syncmoney/syncmoney-web/dist/         — build output (served by WebAdminServer)
 *   plugins/Syncmoney/syncmoney-web/syncmoney-web.tar.gz — downloaded archive (deleted after extraction)
 */
public class WebCommand implements CommandExecutor, TabCompleter {

    private final Syncmoney plugin;

    public WebCommand(Syncmoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            MessageHelper.sendMessage(sender, plugin.getMessage("general.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "download" -> handleDownload(sender, args);
            case "build"    -> handleBuild(sender);
            case "reload"   -> handleReload(sender);
            case "open"     -> handleOpen(sender);
            case "status"   -> handleStatus(sender);
            case "check"    -> handleCheck(sender);
            case "version"  -> handleCheck(sender);
            default         -> sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syncmoney.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterStarts(List.of("download", "build", "reload", "open", "status", "check", "version"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("download")) {
            return filterStarts(List.of("latest"), args[1]);
        }
        return Collections.emptyList();
    }





    /**
     * /syncmoney web download [version|latest]
     * Downloads tar.gz → extracts → builds → reloads, all asynchronously.
     */
    private void handleDownload(CommandSender sender, String[] args) {
        String version = (args.length > 1) ? args[1] : null;

        Path webDir = plugin.getDataFolder().toPath().resolve("syncmoney-web");
        Path tarGzPath = webDir.resolve("syncmoney-web.tar.gz");
        String repo = plugin.getSyncmoneyConfig().getConfig()
                .getString("web-admin.web.github-repo", Constants.DEFAULT_GITHUB_REPO);

        MessageHelper.sendMessage(sender, plugin.getMessage("web.download.starting"));

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                WebDownloader downloader = new WebDownloader(plugin, repo);

                boolean downloaded;
                if (version != null && !version.isEmpty() && !version.equalsIgnoreCase("latest")) {
                    downloaded = downloader.downloadVersion(version, tarGzPath, webDir);
                } else {
                    downloaded = downloader.downloadLatest(tarGzPath, webDir);
                }

                if (!downloaded) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("web.download.failed"));
                    return;
                }

                MessageHelper.sendMessage(sender, plugin.getMessage("web.download.success"));


                WebBuilder builder = new WebBuilder(plugin, webDir);
                if (!builder.isBuildable()) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("web.build.not-found"));
                    return;
                }

                MessageHelper.sendMessage(sender, plugin.getMessage("web.build.starting"));
                boolean built = builder.buildAll();

                if (!built) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("web.build.failed"));
                    return;
                }

                MessageHelper.sendMessage(sender, plugin.getMessage("web.build.success"));


                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    if (plugin.getWebAdminServer() != null) {
                        try {
                            plugin.getWebAdminServer().reloadWebRoot();
                            MessageHelper.sendMessage(sender, plugin.getMessage("web.reload.success"));
                        } catch (Exception e) {
                            MessageHelper.sendMessage(sender, plugin.getMessage("web.reload.failed")
                                    .replace("{error}", e.getMessage()));
                        }
                    }
                });

            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("web.download.error")
                        .replace("{error}", e.getMessage()));
                plugin.getLogger().severe("Web download error: " + e.getMessage());
            }
        });
    }

    /**
     * /syncmoney web build
     * Builds the frontend asynchronously and auto-reloads on success.
     */
    private void handleBuild(CommandSender sender) {
        Path webDir = plugin.getDataFolder().toPath().resolve("syncmoney-web");
        WebBuilder builder = new WebBuilder(plugin, webDir);

        if (!builder.isBuildable()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.build.not-found"));
            return;
        }

        MessageHelper.sendMessage(sender, plugin.getMessage("web.build.starting"));

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                boolean success = builder.buildAll();

                if (!success) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("web.build.failed"));
                    return;
                }

                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    if (plugin.getWebAdminServer() != null) {
                        try {
                            plugin.getWebAdminServer().reloadWebRoot();
                        } catch (Exception e) {
                            plugin.getLogger().warning("Web reload after build failed: " + e.getMessage());
                        }
                    }
                    MessageHelper.sendMessage(sender, plugin.getMessage("web.build.success"));
                });

            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("web.build.error")
                        .replace("{error}", e.getMessage()));
                plugin.getLogger().severe("Web build error: " + e.getMessage());
            }
        });
    }

    /**
     * /syncmoney web reload — re-read the latest web frontend files.
     */
    private void handleReload(CommandSender sender) {
        if (plugin.getWebAdminServer() == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.reload.not-running"));
            return;
        }

        try {
            plugin.getWebAdminServer().reloadWebRoot();
            MessageHelper.sendMessage(sender, plugin.getMessage("web.reload.success"));
        } catch (Exception e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.reload.failed")
                    .replace("{error}", e.getMessage()));
            plugin.getLogger().severe("Web reload error: " + e.getMessage());
        }
    }

    /**
     * /syncmoney web open — open the dashboard in browser.
     */
    private void handleOpen(CommandSender sender) {
        if (plugin.getWebAdminServer() == null || !plugin.getWebAdminServer().isRunning()) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.open.not-running"));
            return;
        }

        String host = plugin.getSyncmoneyConfig().getConfig()
                .getString("web-admin.server.host", "localhost");
        int port = plugin.getSyncmoneyConfig().getConfig()
                .getInt("web-admin.server.port", 8080);

        String url = "http://" + host + ":" + port;


        boolean isHeadless = isHeadlessEnvironment();

        if (isHeadless) {

            MessageHelper.sendMessage(sender, plugin.getMessage("web.open.manual")
                    .replace("{url}", url));
            return;
        }

        try {
            Desktop.getDesktop().browse(new URI(url));
            MessageHelper.sendMessage(sender, plugin.getMessage("web.open.success")
                    .replace("{url}", url));
        } catch (Exception e) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.open.failed")
                    .replace("{error}", e.getMessage()));
            MessageHelper.sendMessage(sender, plugin.getMessage("web.open.manual")
                    .replace("{url}", url));
        }
    }

    /**
     * Detect if running in headless environment (no GUI available).
     */
    private boolean isHeadlessEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String display = System.getenv("DISPLAY");


        if (os.contains("linux") && (display == null || display.isEmpty())) {
            return true;
        }


        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }

        return false;
    }

    /**
     * /syncmoney web status — show detailed frontend status.
     */
    private void handleStatus(CommandSender sender) {
        Path webDir = plugin.getDataFolder().toPath().resolve("syncmoney-web");
        Path tarGzPath = webDir.resolve("syncmoney-web.tar.gz");
        Path packageJson = webDir.resolve("package.json");
        Path distIndex = webDir.resolve("dist").resolve("index.html");

        boolean tarExists     = Files.exists(tarGzPath);
        boolean sourceExists  = Files.exists(packageJson);
        boolean buildExists   = Files.exists(distIndex);

        String yes = plugin.getMessage("web.status.status-yes");
        String no  = plugin.getMessage("web.status.status-no");

        MessageHelper.sendMessage(sender, plugin.getMessage("web.status.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.status.directory")
                .replace("{path}", webDir.toString()));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.status.tarball")
                .replace("{path}", tarGzPath.getFileName().toString())
                .replace("{status}", tarExists ? yes : no));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.status.installed")
                .replace("{status}", sourceExists ? yes : no));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.status.build-ready")
                .replace("{status}", buildExists ? yes : no));

        boolean serverRunning = plugin.getWebAdminServer() != null && plugin.getWebAdminServer().isRunning();
        MessageHelper.sendMessage(sender, plugin.getMessage("web.status.server-running")
                .replace("{status}", serverRunning ? yes : no));

        if (serverRunning) {
            String host = plugin.getSyncmoneyConfig().getConfig()
                    .getString("web-admin.server.host", "localhost");
            int port = plugin.getSyncmoneyConfig().getConfig()
                    .getInt("web-admin.server.port", 8080);
            String url = "http://" + host + ":" + port;
            MessageHelper.sendMessage(sender, plugin.getMessage("web.status.web-address")
                    .replace("{url}", url));
        }
    }

    /**
     * /syncmoney web check — compare local vs latest GitHub release version.
     * Shows a clickable download suggestion when an update is available.
     */
    private void handleCheck(CommandSender sender) {
        String repo = plugin.getSyncmoneyConfig().getConfig()
                .getString("web-admin.web.github-repo", Constants.DEFAULT_GITHUB_REPO);

        WebVersionChecker checker = new WebVersionChecker(plugin, repo);
        String latest = checker.checkLatestVersion();

        if (latest == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.version.check-failed"));
            return;
        }

        String bundled = plugin.getWebAdminConfig().getBundledVersion();
        String local   = getLocalVersionFromDisk();

        MessageHelper.sendMessage(sender, plugin.getMessage("web.version.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.version.bundled").replace("{version}", bundled));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.version.current").replace("{version}", local));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.version.latest").replace("{version}", latest));

        if (checker.hasNewerVersion(latest, local)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.version.update-available"));


            Component clickMsg = Component.text()
                    .append(Component.text(
                            plugin.getMessage("web.check.update-available-clickable")
                                    .replace("{latest}", latest)))
                    .append(Component.text("/syncmoney web download", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand("/syncmoney web download"))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to fill the download command", NamedTextColor.GREEN))))
                    .build();
            sender.sendMessage(clickMsg);
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("web.version.up-to-date"));
        }
    }





    /**
     * Read version from dist/version.json.
     * Falls back to bundled version if the file is absent or unreadable.
     */
    private String getLocalVersionFromDisk() {
        Path versionFile = plugin.getDataFolder().toPath()
                .resolve("syncmoney-web").resolve("dist").resolve("version.json");

        if (Files.exists(versionFile)) {
            try {
                String content = Files.readString(versionFile);
                int start = content.indexOf("\"version\":\"");
                if (start != -1) {
                    start += 11;
                    int end = content.indexOf("\"", start);
                    if (end != -1) {
                        return content.substring(start, end);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return plugin.getWebAdminConfig().getBundledVersion();
    }

    private void sendUsage(CommandSender sender) {
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.header"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.download"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.build"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.reload"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.open"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.status"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.check"));
        MessageHelper.sendMessage(sender, plugin.getMessage("web.usage.version"));
    }

    private List<String> filterStarts(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
