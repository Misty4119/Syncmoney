package noietime.syncmoney.migration;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * CMI Economy Module Disabler.
 * Used to automatically disable CMI economy-related commands after migration completion.
 */
public final class CMIEconomyDisabler {

    private final JavaPlugin plugin;


    private static final List<String> ECONOMY_COMMANDS = Arrays.asList(
            "balance", "baltop", "money", "pay"
    );

    public CMIEconomyDisabler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Disables all CMI economy commands.
     *
     * @return true if successful
     */
    public boolean disableEconomyCommands() {
        File aliasFile = getAliasFile();

        if (!aliasFile.exists()) {
            plugin.getLogger().warning("CMI Alias.yml not found, skipping command disable");
            return false;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(aliasFile);

            boolean modified = false;
            for (String command : ECONOMY_COMMANDS) {
                String path = "Alias." + command + ".Enabled";
                if (config.contains(path)) {
                    Boolean current = config.getBoolean(path, true);
                    if (current) {
                        config.set(path, false);
                        modified = true;
                        plugin.getLogger().info("Disabled CMI command: /" + command);
                    }
                }
            }

            if (modified) {
                config.save(aliasFile);
                plugin.getLogger().info("CMI Alias.yml updated, commands disabled");
                return true;
            } else {
                plugin.getLogger().info("No CMI economy commands needed to be disabled");
                return true;
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to update CMI Alias.yml: " + e.getMessage());
            return false;
        }
    }

    /**
     * Disables CMI economy module (Economy.Enabled in Config.yml).
     *
     * @return true if successful
     */
    public boolean disableEconomyModule() {
        File configFile = getConfigFile();

        if (!configFile.exists()) {
            plugin.getLogger().warning("CMI Config.yml not found, skipping economy disable");
            return false;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

            String path = "Economy.Enabled";
            if (config.contains(path)) {
                Boolean current = config.getBoolean(path, true);
                if (current) {
                    config.set(path, false);
                    config.save(configFile);
                    plugin.getLogger().info("CMI Economy module disabled");
                    return true;
                } else {
                    plugin.getLogger().info("CMI Economy is already disabled");
                    return true;
                }
            } else {
                plugin.getLogger().warning("CMI Economy config path not found");
                return false;
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to update CMI Config.yml: " + e.getMessage());
            return false;
        }
    }

    /**
     * Executes CMI reload.
     */
    public void reloadCMI() {
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "cmi reload");
        plugin.getLogger().fine("CMI reloaded");
    }

    /**
     * Gets Alias.yml file path.
     */
    private File getAliasFile() {
        File cmiFolder = new File(plugin.getDataFolder().getParent(), "CMI");
        return new File(cmiFolder, "Settings/Alias.yml");
    }

    /**
     * Gets CMI Config.yml file path.
     */
    private File getConfigFile() {
        File cmiFolder = new File(plugin.getDataFolder().getParent(), "CMI");
        return new File(cmiFolder, "Config.yml");
    }
}
