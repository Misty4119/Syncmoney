package noietime.syncmoney.api;

/**
 * Entry point for Syncmoney's supported read-only economy API.
 *
 * <p>Consumers should obtain this service from Bukkit's {@code ServicesManager}.
 * The SDK has no Bukkit dependency so consumers can compile against it without
 * packaging server implementation classes.
 */
public interface SyncmoneyApi {

    /**
     * Returns the read-only economy query service.
     *
     * @return the economy query service
     */
    EconomyService economy();
}
