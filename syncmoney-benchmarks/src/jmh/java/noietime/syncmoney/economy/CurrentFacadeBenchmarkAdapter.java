package noietime.syncmoney.economy;

import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter;
import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.mockito.Mockito.when;

/** Exercises the current public facade with a deterministic, asynchronous receipt adapter. */
public final class CurrentFacadeBenchmarkAdapter implements TransactionBenchmarkAdapter {

    private static final Logger LOGGER = Logger.getLogger("SyncmoneyBenchmark");

    private final EconomyWriteQueue queue = new EconomyWriteQueue(50_000, LOGGER);
    private final EconomyFacade facade;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong drained = new AtomicLong();
    private final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
    private final ConcurrentMap<UUID, Receipt> latestReceipts = new ConcurrentHashMap<>();
    private final Thread drainer;

    public CurrentFacadeBenchmarkAdapter() {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(LOGGER);
        FallbackEconomyWrapper fallback = Mockito.mock(FallbackEconomyWrapper.class);
        when(fallback.isDegraded()).thenReturn(false);
        facade = new EconomyFacade(plugin, Mockito.mock(SyncmoneyConfig.class), null, null, null, null,
                queue, fallback, null, Mockito.mock(OverflowLogInterface.class));
        drainer = new Thread(this::drain, "syncmoney-benchmark-receipt-adapter");
        drainer.setDaemon(true);
        drainer.start();
    }

    @Override
    public void seed(UUID account, BigDecimal balance, long version) {
        if (!facade.updateMemoryState(account, balance, version)) {
            throw new IllegalStateException("Current facade rejected benchmark seed for " + account);
        }
    }

    @Override
    public boolean updateMemoryState(UUID account, BigDecimal balance, long version) {
        return facade.updateMemoryState(account, balance, version);
    }

    @Override
    public BigDecimal balance(UUID account) {
        return facade.getBalance(account);
    }

    @Override
    public long version(UUID account) {
        EconomyFacade.EconomyState state = facade.getMemoryState(account);
        if (state == null) throw new IllegalStateException("Current facade lost benchmark account " + account);
        return state.version();
    }

    @Override
    public Receipt latestReceipt(UUID account) {
        return latestReceipts.get(account);
    }

    @Override
    public BigDecimal deposit(UUID account, BigDecimal amount) {
        return facade.deposit(account, amount, EconomyEvent.EventSource.TEST);
    }

    @Override
    public BigDecimal withdraw(UUID account, BigDecimal amount) {
        return facade.withdraw(account, amount, EconomyEvent.EventSource.TEST);
    }

    @Override
    public BigDecimal set(UUID account, BigDecimal balance) {
        return facade.setBalance(account, balance, EconomyEvent.EventSource.TEST);
    }

    @Override
    public BigDecimal transfer(UUID from, UUID to, BigDecimal amount) {
        return facade.atomicTransfer(from, to, amount, EconomyEvent.EventSource.TEST);
    }

    @Override
    public void awaitDrain(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while ((queue.size() != 0 || inFlight.get() != 0) && System.nanoTime() < deadline) {
            throwIfDrainFailed();
            Thread.sleep(1L);
        }
        throwIfDrainFailed();
        if (queue.size() != 0 || inFlight.get() != 0) {
            throw new IllegalStateException("Current benchmark receipt adapter did not drain within " + timeoutMillis + "ms");
        }
    }

    @Override
    public long drainedEvents() {
        return drained.get();
    }

    @Override
    public int queuedEvents() {
        return queue.size();
    }

    @Override
    public void throwIfDrainFailed() {
        Throwable failure = drainFailure.get();
        if (failure != null) throw new IllegalStateException("Current benchmark receipt adapter failed", failure);
    }

    private void drain() {
        while (running.get() || queue.size() != 0) {
            try {
                EconomyEvent event = queue.poll(50, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                inFlight.incrementAndGet();
                try {
                    if (event.type() == EconomyEvent.EventType.TRANSFER_IN
                            || event.type() == EconomyEvent.EventType.TRANSFER_OUT) {
                        EconomyEvent paired = queue.poll(1, TimeUnit.SECONDS);
                        if (paired == null) throw new IllegalStateException("Accepted transfer was missing its paired event");
                        inFlight.incrementAndGet();
                        recordReceipt(event);
                        recordReceipt(paired);
                        if (!queue.completePair(event, event.balanceAfter(), event.version(),
                                paired, paired.balanceAfter(), paired.version())) {
                            throw new IllegalStateException("Current queue rejected its accepted transfer receipt pair");
                        }
                        drained.addAndGet(2);
                        inFlight.decrementAndGet();
                    } else {
                        recordReceipt(event);
                        queue.complete(event, event.balanceAfter(), event.version());
                        drained.incrementAndGet();
                    }
                } finally {
                    inFlight.decrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                if (!running.get()) return;
            } catch (Throwable failure) {
                drainFailure.compareAndSet(null, failure);
                return;
            }
        }
    }

    private void recordReceipt(EconomyEvent event) {
        latestReceipts.compute(event.playerUuid(), (account, previous) -> {
            if (previous == null || event.version() > previous.version()) {
                return new Receipt(event.balanceAfter(), event.version());
            }
            if (event.version() == previous.version()
                    && event.balanceAfter().compareTo(previous.balance()) != 0) {
                throw new IllegalStateException("Current benchmark emitted conflicting balances at one account version");
            }
            return previous;
        });
    }

    @Override
    public void close() {
        try {
            awaitDrain(10_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting current benchmark receipt adapter", interrupted);
        }
        running.set(false);
        drainer.interrupt();
        try {
            drainer.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing current benchmark receipt adapter", interrupted);
        }
        facade.shutdown();
    }
}
