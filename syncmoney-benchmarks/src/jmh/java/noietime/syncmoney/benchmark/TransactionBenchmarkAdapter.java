package noietime.syncmoney.benchmark;

import java.math.BigDecimal;
import java.util.UUID;

/** Test-time facade adapter; it reports acceptance separately from async receipt work. */
public interface TransactionBenchmarkAdapter extends AutoCloseable {

    void seed(UUID account, BigDecimal balance, long version);

    boolean updateMemoryState(UUID account, BigDecimal balance, long version);

    BigDecimal balance(UUID account);

    long version(UUID account);

    Receipt latestReceipt(UUID account);

    BigDecimal deposit(UUID account, BigDecimal amount);

    BigDecimal withdraw(UUID account, BigDecimal amount);

    BigDecimal set(UUID account, BigDecimal balance);

    BigDecimal transfer(UUID from, UUID to, BigDecimal amount);

    void awaitDrain(long timeoutMillis) throws InterruptedException;

    long drainedEvents();

    int queuedEvents();

    void throwIfDrainFailed();

    @Override
    void close();

    record Receipt(BigDecimal balance, long version) { }
}
