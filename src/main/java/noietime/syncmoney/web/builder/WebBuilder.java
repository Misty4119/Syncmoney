package noietime.syncmoney.web.builder;

import noietime.syncmoney.Syncmoney;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Builds web frontend using pnpm (preferred) or npm as fallback.
 */
public class WebBuilder {

    private final Syncmoney plugin;
    private final Path webDir;

    /** Preferred package managers in order. */
    private static final String[] PKG_MANAGERS = {"pnpm", "npm"};

    public WebBuilder(Syncmoney plugin, Path webDir) {
        this.plugin = plugin;
        this.webDir = webDir;
    }

    /**
     * Check if package.json exists (prerequisite for any build).
     */
    public boolean isBuildable() {
        return Files.exists(webDir.resolve("package.json"));
    }

    /**
     * Detect the first available package manager on this system.
     * Tries pnpm first, falls back to npm.
     */
    public String detectPackageManager() {
        for (String pm : PKG_MANAGERS) {
            try {
                Process p = new ProcessBuilder(pm, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    plugin.getLogger().fine("Detected package manager: " + pm);
                    return pm;
                }
            } catch (Exception ignored) {
            }
        }
        return "npm";
    }

    /**
     * Run package manager install.
     */
    public boolean installDependencies() throws IOException, InterruptedException {
        if (!isBuildable()) {
            plugin.getLogger().warning("package.json not found, skipping install");
            return false;
        }

        String pm = detectPackageManager();
        plugin.getLogger().fine("Running " + pm + " install...");
        return runCommand(pm, "install");
    }

    /**
     * Run package manager build.
     */
    public boolean build() throws IOException, InterruptedException {
        if (!isBuildable()) {
            plugin.getLogger().warning("package.json not found, skipping build");
            return false;
        }

        String pm = detectPackageManager();
        plugin.getLogger().fine("Running " + pm + " run build...");
        return runCommand(pm, "run", "build");
    }

    /**
     * Full build process: install + build.
     */
    public boolean buildAll() throws IOException, InterruptedException {
        plugin.getLogger().fine("Starting web frontend build process...");

        String pm = detectPackageManager();

        if (!runCommand(pm, "install")) {
            plugin.getLogger().severe(pm + " install failed");
            return false;
        }

        if (!runCommand(pm, "run", "build")) {
            plugin.getLogger().severe(pm + " run build failed");
            return false;
        }

        plugin.getLogger().fine("Web frontend build completed successfully");
        return true;
    }

    private boolean runCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(webDir.toFile())
                .redirectErrorStream(true);

        Process process = pb.start();

        Thread progressLogger = new Thread(() -> {
            try (BufferedReader reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    plugin.getLogger().fine("[" + command[0] + "] " + line);
                }
            } catch (IOException ignored) {
            }
        });
        progressLogger.setDaemon(true);
        progressLogger.start();

        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            plugin.getLogger().severe("Command timed out: " + String.join(" ", command));
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            plugin.getLogger().severe("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
            return false;
        }

        return true;
    }
}
