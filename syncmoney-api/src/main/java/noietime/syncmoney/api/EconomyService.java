package noietime.syncmoney.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Read-only account balance queries.
 *
 * <p>Balances use {@link java.math.BigDecimal} and preserve the normalization of
 * the active economy mode (currently a two-decimal currency scale with HALF_UP
 * normalization). These reads are local snapshots; they do not promise global
 * linearizability, durable persistence, or that a balance remains current after
 * the snapshot is returned. Query work may complete on an asynchronous worker;
 * in CMI mode a cold read is routed through the online player's entity scheduler.
 */
public interface EconomyService {

    /**
     * Returns a balance already available in Syncmoney's local memory.
     *
     * <p>This method performs no storage or network I/O and never waits for an
     * in-memory account transition. If a mutation currently owns the account
     * lease, this lookup returns empty and the caller may use {@link #loadBalance(UUID)}
     * for an asynchronous query. An empty result means that no confirmed local
     * snapshot is ready; it does not mean a zero balance or a confirmed absent account.
     *
     * @param accountId non-null account UUID
     * @return the current local snapshot, or empty when none is ready
     * @throws IllegalArgumentException if {@code accountId} is null
     * @throws IllegalStateException if the provider has shut down
     */
    Optional<BalanceSnapshot> cachedBalance(UUID accountId);

    /**
     * Loads a local balance snapshot without blocking the caller.
     *
     * <p>A confirmed absent account completes with {@link Optional#empty()} and
     * is not created or persisted as zero. An unavailable authority or storage
     * backend, an unknown CMI account state, or provider shutdown completes the
     * returned stage exceptionally. In CMI mode a cold query requires an online
     * entity owner; this method does not create or load a CMI user to perform a
     * lookup.
     *
     * <p>The returned stage does not guarantee an entity-owner completion thread.
     * Callers that interact with players must schedule that work on the player's
     * entity scheduler.
     *
     * <p>A null UUID is reported through exceptional completion rather than a
     * synchronous exception.
     *
     * @param accountId account UUID; null completes the returned stage exceptionally
     * @return a stage with a balance snapshot, or empty for a confirmed absent account
     */
    CompletionStage<Optional<BalanceSnapshot>> loadBalance(UUID accountId);
}
