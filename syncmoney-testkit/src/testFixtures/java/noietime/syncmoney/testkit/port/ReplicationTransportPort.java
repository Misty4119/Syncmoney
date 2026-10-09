package noietime.syncmoney.testkit.port;

import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Test-only absolute-snapshot transport contract used by fake and disposable-backend conformance
 * tests. The operation ID correlates a receipt only; it does not promise operation deduplication
 * or make an ambiguous authority mutation safe to replay.
 */
public interface ReplicationTransportPort {

    ReplicationReceipt replicate(String operationId, List<ConfirmedAccount> accounts);

    Optional<ConfirmedAccount> find(UUID accountId);

    void deliver(ReplicationReceipt receipt, Consumer<ConfirmedAccount> receiver);
}
