package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * [SYNC-ECO-073] Economy Mode Router.
 * Determines the handling logic for economic operations based on configured mode.
 *
 * <p>The per-mode dispatch is implemented as a {@code Map<EconomyMode, EconomyStrategy>}
 * built once during {@link #initialize}. {@code SYNC} and {@code LOCAL_REDIS} share the
 * same strategy instance because their behaviour is identical (Syncmoney facade write +
 * cross-server publish), which removes the previously duplicated switch branches.
 */
public class EconomyModeRouter {

    private final EconomyMode mode;
    private final Syncmoney plugin;
    private final SyncmoneyConfig config;

    private EconomyFacadeWrapper syncmoneyFacade;
    private LocalEconomyHandler localHandler;
    private CMIEconomyHandler cmiHandler;
    private CrossServerSyncManager syncManager;

    private final Map<EconomyMode, EconomyStrategy> strategies = new EnumMap<>(EconomyMode.class);

    public EconomyModeRouter(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.mode = config.getEconomyMode();

        EconomyStrategy sync = new SyncStrategy();
        strategies.put(EconomyMode.LOCAL, new LocalStrategy());
        strategies.put(EconomyMode.SYNC, sync);
        strategies.put(EconomyMode.LOCAL_REDIS, sync);
        strategies.put(EconomyMode.CMI, new CmiStrategy());
    }

    /**
     * [SYNC-ECO-074] Initialize handlers.
     */
    public void initialize(EconomyFacadeWrapper syncmoneyFacade,
                         LocalEconomyHandler localHandler,
                         CMIEconomyHandler cmiHandler,
                         CrossServerSyncManager syncManager) {
        this.syncmoneyFacade = syncmoneyFacade;
        this.localHandler = localHandler;
        this.cmiHandler = cmiHandler;
        this.syncManager = syncManager;
    }

    /**
     * [SYNC-ECO-075] Set CMI handler after initialization.
     * Used when CMI mode is detected at runtime.
     */
    public void setCmiHandler(CMIEconomyHandler cmiHandler) {
        this.cmiHandler = cmiHandler;
    }

    /**
     * [SYNC-ECO-076] Get CMI handler.
     */
    public CMIEconomyHandler getCmiHandler() {
        return cmiHandler;
    }

    /**
     * [SYNC-ECO-077] Get current economy mode.
     */
    public EconomyMode getMode() {
        return mode;
    }

    private EconomyStrategy strategy() {
        return strategies.get(mode);
    }

    /**
     * [SYNC-ECO-078] Handle deposit operation.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return strategy().deposit(uuid, amount, source);
    }

    /**
     * [SYNC-ECO-079] Handle withdrawal operation.
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return strategy().withdraw(uuid, amount, source);
    }

    /**
     * [SYNC-ECO-080] Handle balance query.
     */
    public BigDecimal getBalance(UUID uuid) {
        return strategy().getBalance(uuid);
    }

    /**
     * [SYNC-ECO-081] Handle balance setting.
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        return strategy().setBalance(uuid, newBalance, source);
    }

    /**
     * [SYNC-ECO-082] EconomyFacade wrapper interface.
     * Provides unified interface for economy operations.
     */
    public interface EconomyFacadeWrapper {
        BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source);
        BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source);
        BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source);
        BigDecimal getBalance(UUID uuid);
    }

    private interface EconomyStrategy {
        BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source);
        BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source);
        BigDecimal getBalance(UUID uuid);
        BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source);
    }

    private final class LocalStrategy implements EconomyStrategy {
        @Override
        public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
            if (localHandler != null) {
                return localHandler.deposit(uuid, null, amount, source != null ? source.name() : "LOCAL");
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
            if (localHandler != null) {
                return localHandler.withdraw(uuid, null, amount, source != null ? source.name() : "LOCAL");
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getBalance(UUID uuid) {
            if (localHandler != null) {
                return localHandler.getBalance(uuid);
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
            if (localHandler != null) {
                return localHandler.setBalance(uuid, null, newBalance, source != null ? source.name() : "LOCAL");
            }
            return BigDecimal.ZERO;
        }
    }

    private final class SyncStrategy implements EconomyStrategy {
        @Override
        public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
            if (syncmoneyFacade != null && syncManager != null) {
                BigDecimal result = syncmoneyFacade.deposit(uuid, amount, source);
                syncManager.publishAndNotify(uuid, result,
                    source.name(), amount.doubleValue(), "Syncmoney", null);
                return result;
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
            if (syncmoneyFacade != null && syncManager != null) {
                BigDecimal result = syncmoneyFacade.withdraw(uuid, amount, source);
                syncManager.publishAndNotify(uuid, result,
                    source.name(), -amount.doubleValue(), "Syncmoney", null);
                return result;
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getBalance(UUID uuid) {
            if (syncmoneyFacade != null) {
                return syncmoneyFacade.getBalance(uuid);
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
            if (syncmoneyFacade != null && syncManager != null) {
                BigDecimal result = syncmoneyFacade.setBalance(uuid, newBalance, source);
                syncManager.publishAndNotify(uuid, result,
                    "SET_BALANCE", newBalance.doubleValue(), "Syncmoney", null);
                return result;
            }
            return BigDecimal.ZERO;
        }
    }

    private final class CmiStrategy implements EconomyStrategy {
        @Override
        public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
            if (cmiHandler != null) {
                return cmiHandler.deposit(uuid, amount);
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
            if (cmiHandler != null) {
                return cmiHandler.withdraw(uuid, amount);
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getBalance(UUID uuid) {
            if (cmiHandler != null) {
                return cmiHandler.getBalance(uuid);
            }
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
            if (cmiHandler != null) {
                return cmiHandler.setBalance(uuid, newBalance);
            }
            return BigDecimal.ZERO;
        }
    }
}
