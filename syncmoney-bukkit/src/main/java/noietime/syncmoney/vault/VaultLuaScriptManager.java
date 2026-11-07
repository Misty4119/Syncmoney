package noietime.syncmoney.vault;

import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [SYNC-VAULT-005] Manages Lua script loading and SHA caching for bank operations.
 * Handles atomic bank withdraw, deposit, and transfer scripts.
 */
public class VaultLuaScriptManager {

    private static final Logger log = LoggerFactory.getLogger(VaultLuaScriptManager.class);

    private volatile String bankWithdrawScriptSha;
    private volatile String bankDepositScriptSha;
    private volatile String bankTransferScriptSha;

    private final Plugin plugin;

    public VaultLuaScriptManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * [SYNC-VAULT-005] Load a Lua script from resources.
     */
    public String loadScript(String filename) {
        try (var is = plugin.getResource("lua/" + filename)) {
            if (is == null) {
                plugin.getLogger().severe("Lua script not found: " + filename);
                return null;
            }
            return new String(is.readAllBytes());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load Lua script " + filename + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets the bank withdraw script SHA.
     */
    public String getBankWithdrawScriptSha() {
        return bankWithdrawScriptSha;
    }

    /**
     * Gets the bank deposit script SHA.
     */
    public String getBankDepositScriptSha() {
        return bankDepositScriptSha;
    }

    /**
     * Gets the bank transfer script SHA.
     */
    public String getBankTransferScriptSha() {
        return bankTransferScriptSha;
    }

    /**
     * Sets the bank withdraw script SHA.
     */
    public void setBankWithdrawScriptSha(String sha) {
        this.bankWithdrawScriptSha = sha;
    }

    /**
     * Sets the bank deposit script SHA.
     */
    public void setBankDepositScriptSha(String sha) {
        this.bankDepositScriptSha = sha;
    }

    /**
     * Sets the bank transfer script SHA.
     */
    public void setBankTransferScriptSha(String sha) {
        this.bankTransferScriptSha = sha;
    }

    /**
     * Checks if bank scripts are loaded.
     */
    public boolean areScriptsLoaded() {
        return bankWithdrawScriptSha != null && bankDepositScriptSha != null && bankTransferScriptSha != null;
    }
}
