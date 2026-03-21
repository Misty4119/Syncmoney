package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * [SYNC-ECO-073] Economy Mode Router.
 * Determines the handling logic for economic operations based on configured mode.
 */
public class EconomyModeRouter {

    private final EconomyMode mode;
    private final Syncmoney plugin;
    private final SyncmoneyConfig config;

    private EconomyFacadeWrapper syncmoneyFacade;
    private LocalEconomyHandler localHandler;
    private CMIEconomyHandler cmiHandler;
    private CrossServerSyncManager syncManager;

    public EconomyModeRouter(Syncmoney plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.mode = config.getEconomyMode();
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

    /**
     * [SYNC-ECO-078] Handle deposit operation.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return switch (mode) {
            case LOCAL -> {
                if (localHandler != null) {
                    yield localHandler.deposit(uuid, null, amount, source != null ? source.name() : "LOCAL");
                }
                yield BigDecimal.ZERO;
            }
            case SYNC -> {
                if (syncmoneyFacade != null && syncManager != null) {
                    BigDecimal result = syncmoneyFacade.deposit(uuid, amount, source);
                    syncManager.publishAndNotify(uuid, result,
                        source.name(), amount.doubleValue(), "Syncmoney", null);
                    yield result;
                }
                yield BigDecimal.ZERO;
            }
            case CMI -> {
                if (cmiHandler != null) {
                    yield cmiHandler.deposit(uuid, amount);
                }
                yield BigDecimal.ZERO;
            }
            case LOCAL_REDIS -> {
                if (syncmoneyFacade != null && syncManager != null) {
                    BigDecimal result = syncmoneyFacade.deposit(uuid, amount, source);
                    syncManager.publishAndNotify(uuid, result,
                        source.name(), amount.doubleValue(), "Syncmoney", null);
                    yield result;
                }
                yield BigDecimal.ZERO;
            }
        };
    }

    /**
     * [SYNC-ECO-079] Handle withdrawal operation.
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount, EconomyEvent.EventSource source) {
        return switch (mode) {
            case LOCAL -> {
                if (localHandler != null) {
                    yield localHandler.withdraw(uuid, null, amount, source != null ? source.name() : "LOCAL");
                }
                yield BigDecimal.ZERO;
            }
            case SYNC -> {
                if (syncmoneyFacade != null && syncManager != null) {
                    BigDecimal result = syncmoneyFacade.withdraw(uuid, amount, source);
                    syncManager.publishAndNotify(uuid, result,
                        source.name(), -amount.doubleValue(), "Syncmoney", null);
                    yield result;
                }
                yield BigDecimal.ZERO;
            }
            case CMI -> {
                if (cmiHandler != null) {
                    yield cmiHandler.withdraw(uuid, amount);
                }
                yield BigDecimal.ZERO;
            }
            case LOCAL_REDIS -> {
                if (syncmoneyFacade != null && syncManager != null) {
                    BigDecimal result = syncmoneyFacade.withdraw(uuid, amount, source);
                    syncManager.publishAndNotify(uuid, result,
                        source.name(), -amount.doubleValue(), "Syncmoney", null);
                    yield result;
                }
                yield BigDecimal.ZERO;
            }
        };
    }

    /**
     * [SYNC-ECO-080] Handle balance query.
     */
    public BigDecimal getBalance(UUID uuid) {
        return switch (mode) {
            case LOCAL -> {
                if (localHandler != null) {
                    yield localHandler.getBalance(uuid);
                }
                yield BigDecimal.ZERO;
            }
            case SYNC, LOCAL_REDIS -> {
                if (syncmoneyFacade != null) {
                    yield syncmoneyFacade.getBalance(uuid);
                }
                yield BigDecimal.ZERO;
            }
            case CMI -> {
                if (cmiHandler != null) {
                    yield cmiHandler.getBalance(uuid);
                }
                yield BigDecimal.ZERO;
            }
        };
    }

    /**
     * [SYNC-ECO-081] Handle balance setting.
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance, EconomyEvent.EventSource source) {
        return switch (mode) {
            case LOCAL -> {
                if (localHandler != null) {
                    yield localHandler.setBalance(uuid, null, newBalance, source != null ? source.name() : "LOCAL");
                }
                yield BigDecimal.ZERO;
            }
            case SYNC -> {
                if (syncmoneyFacade != null && syncManager != null) {
                    BigDecimal result = syncmoneyFacade.setBalance(uuid, newBalance, source);
                    syncManager.publishAndNotify(uuid, result,
                        "SET_BALANCE", newBalance.doubleValue(), "Syncmoney", null);
                    yield result;
                }
                yield BigDecimal.ZERO;
            }
            case CMI -> {
                if (cmiHandler != null) {
                    yield cmiHandler.setBalance(uuid, newBalance);
                }
                yield BigDecimal.ZERO;
            }
            case LOCAL_REDIS -> {
                if (syncmoneyFacade != null && syncManager != null) {
                    BigDecimal result = syncmoneyFacade.setBalance(uuid, newBalance, source);
                    syncManager.publishAndNotify(uuid, result,
                        "SET_BALANCE", newBalance.doubleValue(), "Syncmoney", null);
                    yield result;
                }
                yield BigDecimal.ZERO;
            }
        };
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
}
