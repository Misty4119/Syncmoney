package noietime.syncmoney.vault;

import net.milkbowl.vault.economy.EconomyResponse;
import noietime.syncmoney.economy.EconomyFacade;

import java.util.UUID;

/**
 * [SYNC-VAULT-019] Vault-internal helper that centralizes the repeated
 * "is this account locked?" guard used throughout the Vault provider handlers.
 *
 * <p>Keeps the lock-check behavior byte-for-byte identical (delegates to
 * {@link EconomyFacade#isPlayerLocked(UUID)}) while removing duplication.
 *
 * <p>Scope: intentionally limited to the Vault package; other modules must not be
 * forced to depend on it.
 */
final class LockingHelper {

    private LockingHelper() {
    }

    static boolean isLocked(EconomyFacade economyFacade, UUID uuid) {
        return economyFacade.isPlayerLocked(uuid);
    }

    static EconomyResponse requireNotLocked(EconomyFacade economyFacade, UUID uuid, String lockMessage) {
        if (economyFacade.isPlayerLocked(uuid)) {
            return new EconomyResponse(0, 0.0, EconomyResponse.ResponseType.FAILURE, lockMessage);
        }
        return null;
    }
}
